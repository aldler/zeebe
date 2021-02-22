/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.journal.file.record;

import io.zeebe.journal.JournalRecord;
import java.nio.ByteBuffer;

public interface JournalRecordBufferWriter {

  /**
   * Writes a {@link JournalRecord} to the buffer at it's current position ({@code
   * buffer.position()})
   *
   * @param record to write
   * @param buffer to which the record will be written
   */
  JournalRecord write(JournalRecord record, ByteBuffer buffer);

  /**
   * Write an invalid record so that a reader does not read a record at buffer.position(). The
   * position of the buffer does not advance.
   *
   * @param buffer
   */
  void invalidate(ByteBuffer buffer);
}
