/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.storage.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.cluster.MemberId;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.RaftMember.Type;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.partition.impl.RaftNamespaces;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.utils.serializer.Serializer;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MetaStoreTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
  private MetaStore metaStore;
  private RaftStorage storage;

  @Before
  public void setup() throws IOException {
    storage = RaftStorage.builder().withDirectory(temporaryFolder.newFolder("store")).build();
    metaStore = new MetaStore(storage, Serializer.using(RaftNamespaces.RAFT_STORAGE));
  }

  @Test
  public void shouldStoreAndLoadConfiguration() throws IOException {
    // given
    final Configuration config = getConfiguration();
    metaStore.storeConfiguration(config);

    // when
    final Configuration readConfig = metaStore.loadConfiguration();

    // then
    assertThat(readConfig.index()).isEqualTo(config.index());
    assertThat(readConfig.term()).isEqualTo(config.term());
    assertThat(readConfig.time()).isEqualTo(config.time());
    assertThat(readConfig.members())
        .containsExactlyInAnyOrder(config.members().toArray(new RaftMember[0]));
  }

  private Configuration getConfiguration() {
    return new Configuration(
        1,
        2,
        1234L,
        new ArrayList<>(
            Set.of(
                new DefaultRaftMember(MemberId.from("0"), Type.ACTIVE, Instant.ofEpochMilli(12345)),
                new DefaultRaftMember(
                    MemberId.from("2"), Type.PASSIVE, Instant.ofEpochMilli(12346)))));
  }

  @Test
  public void shouldStoreAndLoadTerm() throws IOException {
    // when
    metaStore.storeTerm(2L);

    // then
    assertThat(metaStore.loadTerm()).isEqualTo(2L);
  }

  @Test
  public void shouldStoreAndLoadVote() throws IOException {
    // when
    metaStore.storeVote(new MemberId("id"));

    // then
    assertThat(metaStore.loadVote().id()).isEqualTo("id");
  }

  @Test
  public void shouldLoadExistingConfiguration() throws IOException {
    // given
    final Configuration config = getConfiguration();
    metaStore.storeConfiguration(config);

    // when
    metaStore.close();
    metaStore = new MetaStore(storage, Serializer.using(RaftNamespaces.RAFT_STORAGE));

    // then
    final Configuration readConfig = metaStore.loadConfiguration();

    assertThat(readConfig.index()).isEqualTo(config.index());
    assertThat(readConfig.term()).isEqualTo(config.term());
    assertThat(readConfig.time()).isEqualTo(config.time());
    assertThat(readConfig.members())
        .containsExactlyInAnyOrder(config.members().toArray(new RaftMember[0]));
  }

  @Test
  public void shouldLoadExistingTerm() throws IOException {
    // given
    metaStore.storeTerm(2L);

    // when
    metaStore.close();
    metaStore = new MetaStore(storage, Serializer.using(RaftNamespaces.RAFT_STORAGE));

    // then
    assertThat(metaStore.loadTerm()).isEqualTo(2L);
  }

  @Test
  public void shouldLoadExistingVote() throws IOException {
    // given
    metaStore.storeVote(new MemberId("id"));

    // when
    metaStore.close();
    metaStore = new MetaStore(storage, Serializer.using(RaftNamespaces.RAFT_STORAGE));

    // then
    assertThat(metaStore.loadVote().id()).isEqualTo("id");
  }

  @Test
  public void shouldLoadEmptyMeta() {
    // when -then
    assertThat(metaStore.loadVote()).isNull();

    // when - then
    assertThat(metaStore.loadTerm()).isEqualTo(0);
  }

  @Test
  public void shouldLoadEmptyVoteWhenTermExists() {
    // given
    metaStore.storeTerm(1);

    // when - then
    assertThat(metaStore.loadVote()).isNull();
  }

  @Test
  public void shouldLoadEmptyConfig() {
    // when -then
    assertThat(metaStore.loadConfiguration()).isNull();
  }
}
