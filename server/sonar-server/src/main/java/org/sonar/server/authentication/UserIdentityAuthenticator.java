/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.authentication;

import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.sonar.api.server.authentication.IdentityProvider;
import org.sonar.api.server.authentication.UserIdentity;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserGroupDto;
import org.sonar.server.authentication.event.AuthenticationEvent;
import org.sonar.server.authentication.event.AuthenticationException;
import org.sonar.server.organization.DefaultOrganization;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.user.ExternalIdentity;
import org.sonar.server.user.NewUser;
import org.sonar.server.user.UpdateUser;
import org.sonar.server.user.UserUpdater;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static org.sonar.core.util.stream.MoreCollectors.uniqueIndex;

public class UserIdentityAuthenticator {

  private static final Logger LOGGER = Loggers.get(UserIdentityAuthenticator.class);

  private final DbClient dbClient;
  private final UserUpdater userUpdater;
  private final DefaultOrganizationProvider defaultOrganizationProvider;

  public UserIdentityAuthenticator(DbClient dbClient, UserUpdater userUpdater, DefaultOrganizationProvider defaultOrganizationProvider) {
    this.dbClient = dbClient;
    this.userUpdater = userUpdater;
    this.defaultOrganizationProvider = defaultOrganizationProvider;
  }

  public UserDto authenticate(UserIdentity user, IdentityProvider provider, AuthenticationEvent.Source source) {
    return register(user, provider, source);
  }

  private UserDto register(UserIdentity user, IdentityProvider provider, AuthenticationEvent.Source source) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      String userLogin = user.getLogin();
      UserDto userDto = dbClient.userDao().selectByLogin(dbSession, userLogin);
      if (userDto != null && userDto.isActive()) {
        registerExistingUser(dbSession, userDto, user, provider);
        return userDto;
      }
      return registerNewUser(dbSession, user, provider, source);
    }
  }

  private UserDto registerNewUser(DbSession dbSession, UserIdentity user, IdentityProvider provider, AuthenticationEvent.Source source) {
    if (!provider.allowsUsersToSignUp()) {
      throw AuthenticationException.newBuilder()
        .setSource(source)
        .setLogin(user.getLogin())
        .setMessage(format("User signup disabled for provider '%s'", provider.getKey()))
        .setPublicMessage(format("'%s' users are not allowed to sign up", provider.getKey()))
        .build();
    }

    String email = user.getEmail();
    if (email != null && dbClient.userDao().doesEmailExist(dbSession, email)) {
      throw AuthenticationException.newBuilder()
        .setSource(source)
        .setLogin(user.getLogin())
        .setMessage(format("Email '%s' is already used", email))
        .setPublicMessage(format(
          "You can't sign up because email '%s' is already used by an existing user. This means that you probably already registered with another account.",
          email))
        .build();
    }

    String userLogin = user.getLogin();
    userUpdater.create(dbSession, NewUser.builder()
      .setLogin(userLogin)
      .setEmail(user.getEmail())
      .setName(user.getName())
      .setExternalIdentity(new ExternalIdentity(provider.getKey(), user.getProviderLogin()))
      .build());
    UserDto newUser = dbClient.userDao().selectOrFailByLogin(dbSession, userLogin);
    syncGroups(dbSession, user, newUser);
    return newUser;
  }

  private void registerExistingUser(DbSession dbSession, UserDto userDto, UserIdentity user, IdentityProvider provider) {
    userUpdater.update(dbSession, UpdateUser.create(userDto.getLogin())
      .setEmail(user.getEmail())
      .setName(user.getName())
      .setExternalIdentity(new ExternalIdentity(provider.getKey(), user.getProviderLogin()))
      .setPassword(null));
    syncGroups(dbSession, user, userDto);
  }

  private void syncGroups(DbSession dbSession, UserIdentity userIdentity, UserDto userDto) {
    if (userIdentity.shouldSyncGroups()) {
      String userLogin = userIdentity.getLogin();
      Set<String> userGroups = new HashSet<>(dbClient.groupMembershipDao().selectGroupsByLogins(dbSession, singletonList(userLogin)).get(userLogin));
      Set<String> identityGroups = userIdentity.getGroups();
      LOGGER.debug("List of groups returned by the identity provider '{}'", identityGroups);

      Collection<String> groupsToAdd = Sets.difference(identityGroups, userGroups);
      Collection<String> groupsToRemove = Sets.difference(userGroups, identityGroups);
      Collection<String> allGroups = new ArrayList<>(groupsToAdd);
      allGroups.addAll(groupsToRemove);
      DefaultOrganization defaultOrganization = defaultOrganizationProvider.get();
      Map<String, GroupDto> groupsByName = dbClient.groupDao().selectByNames(dbSession, defaultOrganization.getUuid(), allGroups)
        .stream()
        .collect(uniqueIndex(GroupDto::getName));

      addGroups(dbSession, userDto, groupsToAdd, groupsByName);
      removeGroups(dbSession, userDto, groupsToRemove, groupsByName);

      dbSession.commit();
    }
  }

  private void addGroups(DbSession dbSession, UserDto userDto, Collection<String> groupsToAdd, Map<String, GroupDto> groupsByName) {
    groupsToAdd.stream().map(groupsByName::get).filter(Objects::nonNull).forEach(
      groupDto -> {
        LOGGER.debug("Adding group '{}' to user '{}'", groupDto.getName(), userDto.getLogin());
        dbClient.userGroupDao().insert(dbSession, new UserGroupDto().setGroupId(groupDto.getId()).setUserId(userDto.getId()));
      });
  }

  private void removeGroups(DbSession dbSession, UserDto userDto, Collection<String> groupsToRemove, Map<String, GroupDto> groupsByName) {
    groupsToRemove.stream().map(groupsByName::get).filter(Objects::nonNull).forEach(
      groupDto -> {
        LOGGER.debug("Removing group '{}' from user '{}'", groupDto.getName(), userDto.getLogin());
        dbClient.userGroupDao().delete(dbSession, groupDto.getId(), userDto.getId());
      });
  }
}
