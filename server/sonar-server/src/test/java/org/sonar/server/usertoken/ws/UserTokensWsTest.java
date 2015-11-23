/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.usertoken.ws;

import org.junit.Before;
import org.junit.Test;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.server.user.UserSession;
import org.sonar.server.usertoken.TokenGenerator;
import org.sonar.server.ws.WsTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class UserTokensWsTest {
  static final String CONTROLLER_KEY = "api/user_tokens";

  WsTester ws;

  @Before
  public void setUp() {
    UserSession userSession = mock(UserSession.class);
    DbClient dbClient = mock(DbClient.class);
    System2 system = mock(System2.class);
    TokenGenerator tokenGenerator = mock(TokenGenerator.class);

    ws = new WsTester(new UserTokensWs(
      new GenerateAction(userSession, dbClient, system, tokenGenerator)));
  }

  @Test
  public void generate_action() {
    WebService.Action action = ws.action(CONTROLLER_KEY, "generate");

    assertThat(action).isNotNull();
    assertThat(action.since()).isEqualTo("5.3");
    assertThat(action.responseExampleAsString()).isNotEmpty();
    assertThat(action.isPost()).isTrue();
    assertThat(action.param("login").isRequired()).isTrue();
    assertThat(action.param("name").isRequired()).isTrue();
  }
}