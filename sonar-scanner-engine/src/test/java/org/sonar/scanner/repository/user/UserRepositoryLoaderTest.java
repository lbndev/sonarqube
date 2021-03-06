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
package org.sonar.scanner.repository.user;

import org.assertj.core.util.Lists;
import org.sonar.scanner.bootstrap.ScannerWsClient;
import org.sonar.scanner.protocol.input.ScannerInput;
import org.sonar.scanner.protocol.input.ScannerInput.User;
import org.sonar.scanner.repository.user.UserRepositoryLoader;
import org.sonar.scanner.WsTestUtil;
import org.junit.Before;
import com.google.common.collect.ImmutableMap;
import org.junit.rules.ExpectedException;
import org.junit.Rule;
import org.mockito.Mockito;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

public class UserRepositoryLoaderTest {
  @Rule
  public final ExpectedException exception = ExpectedException.none();

  private ScannerWsClient wsClient;
  private UserRepositoryLoader userRepo;

  @Before
  public void setUp() {
    wsClient = mock(ScannerWsClient.class);
    userRepo = new UserRepositoryLoader(wsClient);
  }

  @Test
  public void testLoadEmptyList() {
    assertThat(userRepo.load(Lists.<String>emptyList())).isEmpty();
  }

  @Test
  public void testLoad() throws IOException {
    Map<String, String> userMap = ImmutableMap.of("fmallet", "Freddy Mallet", "sbrandhof", "Simon");
    InputStream is = createUsersMock(userMap);
    WsTestUtil.mockStream(wsClient, "/batch/users?logins=fmallet,sbrandhof", is);
    assertThat(userRepo.load(Arrays.asList("fmallet", "sbrandhof"))).extracting("login", "name").containsOnly(tuple("fmallet", "Freddy Mallet"), tuple("sbrandhof", "Simon"));
  }

  @Test
  public void testLoadListWithSingleUser() throws IOException {
    Map<String, String> userMap = ImmutableMap.of("fmallet", "Freddy Mallet");
    InputStream is = createUsersMock(userMap);
    WsTestUtil.mockStream(wsClient, "/batch/users?logins=fmallet", is);
    assertThat(userRepo.load(Arrays.asList("fmallet"))).extracting("login", "name").containsOnly(tuple("fmallet", "Freddy Mallet"));
  }

  @Test
  public void testMapUsers() throws IOException {
    Map<String, String> userMap = ImmutableMap.of("fmallet", "Freddy Mallet");
    InputStream is = createUsersMock(userMap);
    WsTestUtil.mockStream(wsClient, "/batch/users?logins=fmallet,sbrandhof", is);
    Map<String, User> map = userRepo.map(Arrays.asList("fmallet", "sbrandhof"));

    // one user doesn't exist
    assertThat(map).hasSize(1);
    assertThat(map.values().iterator().next().getLogin()).isEqualTo("fmallet");
  }

  @Test
  public void testLoadSingleUser() throws IOException {
    InputStream is = createUsersMock(ImmutableMap.of("fmallet", "Freddy Mallet"));
    WsTestUtil.mockStream(wsClient, "/batch/users?logins=fmallet", is);
    assertThat(userRepo.load("fmallet").getName()).isEqualTo("Freddy Mallet");
  }

  private InputStream createUsersMock(Map<String, String> users) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    for (Map.Entry<String, String> user : users.entrySet()) {
      ScannerInput.User.Builder builder = ScannerInput.User.newBuilder();
      builder.setLogin(user.getKey()).setName(user.getValue()).build().writeDelimitedTo(out);
    }
    return new ByteArrayInputStream(out.toByteArray());
  }

  @Test
  public void testInputStreamError() throws IOException {
    InputStream is = mock(InputStream.class);
    Mockito.doThrow(IOException.class).when(is).read();
    WsTestUtil.mockStream(wsClient, "/batch/users?logins=fmallet,sbrandhof", is);

    exception.expect(IllegalStateException.class);
    exception.expectMessage("Unable to get user details from server");

    userRepo.load(Arrays.asList("fmallet", "sbrandhof"));
  }
}
