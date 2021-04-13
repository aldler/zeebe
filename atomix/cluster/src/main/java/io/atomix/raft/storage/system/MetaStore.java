/*
 * Copyright 2015-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.raft.storage.system;

import static com.google.common.base.MoreObjects.toStringHelper;

import io.atomix.cluster.MemberId;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.serializer.MetaStoreSerializer;
import io.atomix.storage.StorageException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.IoUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages persistence of server configurations.
 *
 * <p>The server metastore is responsible for persisting server configurations according to the
 * configured {@link RaftStorage#storageLevel() storage level}. Each server persists their current
 * {@link #loadTerm() term} and last {@link #loadVote() vote} as is dictated by the Raft consensus
 * algorithm. Additionally, the metastore is responsible for storing the last know server {@link
 * Configuration}, including cluster membership.
 */
public class MetaStore implements AutoCloseable {

  private static final byte VERSION = 1;
  private static final int VERSION_LENGTH = Byte.BYTES;

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final FileChannel metaFileChannel;
  private final ByteBuffer metaBuffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
  private final FileChannel configurationChannel;
  private final File metaFile;
  private final File confFile;
  private final MetaStoreSerializer serializer = new MetaStoreSerializer();

  public MetaStore(final RaftStorage storage) throws IOException {
    if (!(storage.directory().isDirectory() || storage.directory().mkdirs())) {
      throw new IllegalArgumentException(
          String.format("Can't create storage directory [%s].", storage.directory()));
    }

    // Note that for raft safety, irrespective of the storage level, <term, vote> metadata is always
    // persisted on disk.
    metaFile = new File(storage.directory(), String.format("%s.meta", storage.prefix()));
    if (!metaFile.exists()) {
      metaFileChannel = IoUtil.createEmptyFile(metaFile, 32, true);
    } else {
      metaFileChannel =
          FileChannel.open(metaFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    confFile = new File(storage.directory(), String.format("%s.conf", storage.prefix()));

    if (!confFile.exists()) {
      configurationChannel = IoUtil.createEmptyFile(confFile, 32, true);
    } else {
      configurationChannel =
          FileChannel.open(confFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    // Read existing meta info and rewrite with the current version
    initializeMetaBuffer();
  }

  private void initializeMetaBuffer() {
    final var term = loadTerm();
    final long index = loadLastAppendedIndex();
    final var voted = loadVote();
    metaBuffer.put(0, VERSION);
    storeTerm(term);
    storeLastAppendedIndex(index);
    storeVote(voted);
  }

  /**
   * Stores the current server term.
   *
   * @param term The current server term.
   */
  public synchronized void storeTerm(final long term) {
    log.trace("Store term {}", term);
    final MutableDirectBuffer directBuffer = new UnsafeBuffer(metaBuffer);
    serializer.writeTerm(term, directBuffer, VERSION_LENGTH);
    try {
      metaFileChannel.write(metaBuffer, 0);
      metaBuffer.position(0);
      metaFileChannel.force(true);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Loads the stored server term.
   *
   * @return The stored server term.
   */
  public synchronized long loadTerm() {
    try {
      metaFileChannel.read(metaBuffer, 0);
      metaBuffer.position(0);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
    return serializer.readTerm(new UnsafeBuffer(metaBuffer), VERSION_LENGTH);
  }

  /**
   * Stores the last voted server.
   *
   * @param vote The server vote.
   */
  public synchronized void storeVote(final MemberId vote) {
    log.trace("Store vote {}", vote);
    try {
      final String id = vote == null ? null : vote.id();
      serializer.writeVotedFor(id, new UnsafeBuffer(metaBuffer), VERSION_LENGTH);
      metaFileChannel.write(metaBuffer, 0);
      metaFileChannel.force(true);
      metaBuffer.position(0);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Loads the last vote for the server.
   *
   * @return The last vote for the server.
   */
  public synchronized MemberId loadVote() {
    try {
      metaFileChannel.read(metaBuffer, 0);
      metaBuffer.position(0);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
    final String id = serializer.readVotedFor(new UnsafeBuffer(metaBuffer), VERSION_LENGTH);
    return id.isEmpty() ? null : MemberId.from(id);
  }

  public synchronized long loadLastAppendedIndex() {
    try {
      metaFileChannel.read(metaBuffer, 0);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
    return serializer.readLastAppendedIndex(new UnsafeBuffer(metaBuffer), VERSION_LENGTH);
  }

  public synchronized void storeLastAppendedIndex(final long index) {
    log.trace("Store last appended index {}", index);

    try {
      serializer.writeLastAppendedIndex(index, new UnsafeBuffer(metaBuffer), VERSION_LENGTH);
      metaFileChannel.write(metaBuffer, 0);
      metaFileChannel.force(true);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Stores the current cluster configuration.
   *
   * @param configuration The current cluster configuration.
   */
  public synchronized void storeConfiguration(final Configuration configuration) {
    log.trace("Store configuration {}", configuration);
    final ExpandableArrayBuffer serializedBuffer = new ExpandableArrayBuffer();
    serializedBuffer.putByte(0, VERSION);
    final var serializedLength =
        serializer.writeConfiguration(configuration, serializedBuffer, VERSION_LENGTH);

    final ByteBuffer buffer = ByteBuffer.allocate(VERSION_LENGTH + serializedLength);
    serializedBuffer.getBytes(0, buffer, 0, VERSION_LENGTH + serializedLength);
    try {
      configurationChannel.write(buffer, 0);
      configurationChannel.force(true);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Loads the current cluster configuration.
   *
   * @return The current cluster configuration.
   */
  public synchronized Configuration loadConfiguration() {
    try {
      configurationChannel.position(0);
      final ByteBuffer buffer = ByteBuffer.allocate((int) confFile.length());
      configurationChannel.read(buffer);
      buffer.position(0);
      return serializer.readConfiguration(new UnsafeBuffer(buffer), VERSION_LENGTH);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public synchronized void close() {
    try {
      metaFileChannel.close();
      configurationChannel.close();
    } catch (final IOException e) {
      log.warn("Failed to close metastore", e);
    }
  }

  @Override
  public String toString() {
    return toStringHelper(this).toString();
  }
}
