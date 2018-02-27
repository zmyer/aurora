/**
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
package org.apache.aurora.scheduler.storage.log;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import org.apache.aurora.codec.ThriftBinaryCodec;
import org.apache.aurora.codec.ThriftBinaryCodec.CodingException;
import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Data;
import org.apache.aurora.common.testing.easymock.EasyMockTest;
import org.apache.aurora.gen.Attribute;
import org.apache.aurora.gen.HostAttributes;
import org.apache.aurora.gen.storage.DeduplicatedSnapshot;
import org.apache.aurora.gen.storage.Frame;
import org.apache.aurora.gen.storage.FrameChunk;
import org.apache.aurora.gen.storage.FrameHeader;
import org.apache.aurora.gen.storage.LogEntry;
import org.apache.aurora.gen.storage.Op;
import org.apache.aurora.gen.storage.RemoveJob;
import org.apache.aurora.gen.storage.SaveFrameworkId;
import org.apache.aurora.gen.storage.Snapshot;
import org.apache.aurora.gen.storage.Transaction;
import org.apache.aurora.gen.storage.storageConstants;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.log.Log.Entry;
import org.apache.aurora.scheduler.log.Log.Position;
import org.apache.aurora.scheduler.log.Log.Stream;
import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.scheduler.storage.log.SnapshotDeduplicator.SnapshotDeduplicatorImpl;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

public class LogManagerTest extends EasyMockTest {

  private static final Amount<Integer, Data> NO_FRAMES_EVER_SIZE =
      Amount.of(Integer.MAX_VALUE, Data.GB);

  private Stream stream;
  private Position position1;
  private Position position2;

  @Before
  public void setUp() {
    stream = createMock(Stream.class);
    position1 = createMock(Position.class);
    position2 = createMock(Position.class);
  }

  private StreamManager createNoMessagesStreamManager() {
    return createStreamManager(NO_FRAMES_EVER_SIZE);
  }

  private StreamManager createStreamManager(final Amount<Integer, Data> maxEntrySize) {
    return new StreamManagerImpl(
        stream,
        new EntrySerializer.EntrySerializerImpl(maxEntrySize, Hashing.md5()),
        Hashing.md5(),
        new SnapshotDeduplicatorImpl());
  }

  @Test
  public void testStreamManagerReadFromUnknownNone() throws CodingException {
    expect(stream.readAll()).andReturn(Collections.emptyIterator());

    control.replay();

    assertEquals(
        ImmutableList.of(),
        ImmutableList.copyOf(createNoMessagesStreamManager().readFromBeginning()));
  }

  @Test
  public void testStreamManagerReadFromUnknownSome() throws CodingException {
    LogEntry transaction1 = createLogEntry(
        Op.removeJob(new RemoveJob(JobKeys.from("role", "env", "job").newBuilder())));
    Entry entry1 = createMock(Entry.class);
    expect(entry1.contents()).andReturn(encode(transaction1));
    expect(stream.readAll()).andReturn(Iterators.singletonIterator(entry1));

    control.replay();

    assertEquals(
        ImmutableList.of(transaction1),
        ImmutableList.copyOf(createNoMessagesStreamManager().readFromBeginning()));
  }

  @Test
  public void testStreamManagerTruncateBefore() {
    stream.truncateBefore(position2);

    control.replay();

    createNoMessagesStreamManager().truncateBefore(position2);
  }

  @Test
  public void testTransactionEmpty() throws CodingException {
    control.replay();

    createNoMessagesStreamManager().commit(ImmutableList.of());
  }

  private static class LogEntryMatcher implements IArgumentMatcher {
    private final LogEntry expected;

    LogEntryMatcher(LogEntry expected) {
      this.expected = expected;
    }

    @Override
    public boolean matches(Object argument) {
      if (!(argument instanceof byte[])) {
        return false;
      }

      try {
        return expected.equals(ThriftBinaryCodec.decode(LogEntry.class, (byte[]) argument));
      } catch (CodingException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void appendTo(StringBuffer buffer) {
      buffer.append(expected.toString());
    }
  }

  private static byte[] entryEq(LogEntry expected) {
    EasyMock.reportMatcher(new LogEntryMatcher(expected));
    return new byte[] {};
  }

  @Test
  public void testTransactionSnapshot() throws CodingException {
    Snapshot snapshot = createSnapshot();
    DeduplicatedSnapshot deduplicated = new SnapshotDeduplicatorImpl().deduplicate(snapshot);
    expectAppend(position1, Entries.deflate(LogEntry.deduplicatedSnapshot(deduplicated)));
    stream.truncateBefore(position1);

    control.replay();

    createNoMessagesStreamManager().snapshot(snapshot);
  }

  @Test
  public void testTransactionOps() throws CodingException {
    Op saveFrameworkId = Op.saveFrameworkId(new SaveFrameworkId("jake"));
    Op deleteJob = Op.removeJob(new RemoveJob(JobKeys.from("role", "env", "name").newBuilder()));
    expectTransaction(position1, saveFrameworkId, deleteJob);

    StreamManager streamManager = createNoMessagesStreamManager();
    control.replay();

    streamManager.commit(ImmutableList.of(saveFrameworkId, deleteJob));
  }

  static class Message {
    private final Amount<Integer, Data> chunkSize;
    private final LogEntry header;
    private final ImmutableList<LogEntry> chunks;

    Message(Amount<Integer, Data> chunkSize, Frame header, Iterable<Frame> chunks) {
      this.chunkSize = chunkSize;
      this.header = LogEntry.frame(header);
      this.chunks = ImmutableList.copyOf(Iterables.transform(chunks,
          LogEntry::frame));
    }
  }

  static Message frame(LogEntry logEntry) throws Exception {
    byte[] entry = encode(logEntry);

    double chunkBytes = entry.length / 2.0;
    Amount<Integer, Data> chunkSize = Amount.of((int) Math.floor(chunkBytes), Data.BYTES);
    int chunkLength = chunkSize.getValue();
    int chunkCount = (int) Math.ceil(entry.length / (double) chunkSize.getValue());

    Frame header = Frame.header(new FrameHeader(chunkCount,
        ByteBuffer.wrap(MessageDigest.getInstance("MD5").digest(entry))));

    List<Frame> chunks = Lists.newArrayList();
    for (int i = 0; i < chunkCount; i++) {
      int offset = i * chunkLength;
      ByteBuffer data =
          ByteBuffer.wrap(entry, offset, Math.min(chunkLength, entry.length - offset));
      chunks.add(Frame.chunk(new FrameChunk(data)));
    }

    return new Message(chunkSize, header, chunks);
  }

  @Test
  public void testTransactionFrames() throws Exception {
    Op saveFrameworkId = Op.saveFrameworkId(new SaveFrameworkId("jake"));

    Message message = frame(createLogEntry(saveFrameworkId));
    expectFrames(position1, message);

    StreamManager streamManager = createStreamManager(message.chunkSize);
    control.replay();

    streamManager.commit(ImmutableList.of(saveFrameworkId));
  }

  @Test
  public void testStreamManagerReadFrames() throws Exception {
    LogEntry transaction1 = createLogEntry(
        Op.removeJob(new RemoveJob(JobKeys.from("r1", "env", "name").newBuilder())));
    LogEntry transaction2 = createLogEntry(
        Op.removeJob(new RemoveJob(JobKeys.from("r2", "env", "name").newBuilder())));

    Message message = frame(transaction1);

    List<Entry> entries = Lists.newArrayList();

    // Should be read and skipped.
    Entry orphanChunkEntry = createMock(Entry.class);
    expect(orphanChunkEntry.contents()).andReturn(encode(message.chunks.get(0)));
    entries.add(orphanChunkEntry);

    // Should be read and skipped.
    Entry headerEntry = createMock(Entry.class);
    expect(headerEntry.contents()).andReturn(encode(message.header));
    entries.add(headerEntry);

    // We start a valid message, these frames should be read as 1 entry.
    expect(headerEntry.contents()).andReturn(encode(message.header));
    entries.add(headerEntry);
    for (LogEntry chunk : message.chunks) {
      Entry chunkEntry = createMock(Entry.class);
      expect(chunkEntry.contents()).andReturn(encode(chunk));
      entries.add(chunkEntry);
    }

    // Should be read and skipped.
    expect(orphanChunkEntry.contents()).andReturn(encode(message.chunks.get(0)));
    entries.add(orphanChunkEntry);

    // Should be read and skipped.
    expect(headerEntry.contents()).andReturn(encode(message.header));
    entries.add(headerEntry);

    // Should be read as 1 entry.
    Entry standardEntry = createMock(Entry.class);
    expect(standardEntry.contents()).andReturn(encode(transaction2));
    entries.add(standardEntry);

    expect(stream.readAll()).andReturn(entries.iterator());

    StreamManager streamManager = createStreamManager(message.chunkSize);
    control.replay();

    assertEquals(
        ImmutableList.of(transaction1, transaction2),
        ImmutableList.copyOf(streamManager.readFromBeginning()));
  }

  @Test
  public void testWriteAndReadDeflatedEntry() throws Exception {
    Snapshot snapshot = createSnapshot();
    LogEntry snapshotLogEntry = LogEntry.snapshot(snapshot);
    LogEntry deflatedSnapshotEntry = Entries.deflate(
        LogEntry.deduplicatedSnapshot(new SnapshotDeduplicatorImpl().deduplicate(snapshot)));

    Entry snapshotEntry = createMock(Entry.class);
    expect(stream.append(entryEq(deflatedSnapshotEntry))).andReturn(position1);
    stream.truncateBefore(position1);

    expect(snapshotEntry.contents()).andReturn(encode(deflatedSnapshotEntry));

    expect(stream.readAll()).andReturn(ImmutableList.of(snapshotEntry).iterator());

    control.replay();

    HashFunction md5 = Hashing.md5();
    StreamManagerImpl streamManager = new StreamManagerImpl(
        stream,
        new EntrySerializer.EntrySerializerImpl(NO_FRAMES_EVER_SIZE, md5),
        md5,
        new SnapshotDeduplicatorImpl());
    streamManager.snapshot(snapshot);
    assertEquals(
        ImmutableList.of(snapshotLogEntry),
        ImmutableList.copyOf(streamManager.readFromBeginning()));
  }

  private Snapshot createSnapshot() {
    return new Snapshot()
        .setTimestamp(1L)
        .setHostAttributes(ImmutableSet.of(new HostAttributes("host",
            ImmutableSet.of(new Attribute("hostname", ImmutableSet.of("abc"))))))
        .setTasks(ImmutableSet.of(TaskTestUtil.makeTask("task_id", TaskTestUtil.JOB).newBuilder()));
  }

  private void expectFrames(Position position, Message message) throws CodingException {
    expect(stream.append(entryEq(message.header))).andReturn(position);
    for (LogEntry chunk : message.chunks) {
      // Only return a valid position for the header.
      expect(stream.append(entryEq(chunk))).andReturn(null);
    }
  }

  private void expectTransaction(Position position, Op... ops) throws CodingException {
    expectAppend(position, createLogEntry(ops));
  }

  private LogEntry createLogEntry(Op... ops) {
    return LogEntry.transaction(
        new Transaction(ImmutableList.copyOf(ops), storageConstants.CURRENT_SCHEMA_VERSION));
  }

  private void expectAppend(Position position, LogEntry logEntry) throws CodingException {
    expect(stream.append(entryEq(logEntry))).andReturn(position);
  }

  private static byte[] encode(LogEntry logEntry) throws CodingException {
    return ThriftBinaryCodec.encode(logEntry);
  }
}
