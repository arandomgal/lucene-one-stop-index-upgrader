/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.trypticon.luceneupgrader.lucene7.internal.lucene.index;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.trypticon.luceneupgrader.lucene7.internal.lucene.store.IOContext;
import org.trypticon.luceneupgrader.lucene7.internal.lucene.util.Accountable;
import org.trypticon.luceneupgrader.lucene7.internal.lucene.util.BytesRef;
import org.trypticon.luceneupgrader.lucene7.internal.lucene.util.IOUtils;
import org.trypticon.luceneupgrader.lucene7.internal.lucene.util.InfoStream;


final class BufferedUpdatesStream implements Accountable {

  private final Set<FrozenBufferedUpdates> updates = new HashSet<>();

  // Starts at 1 so that SegmentInfos that have never had
  // deletes applied (whose bufferedDelGen defaults to 0)
  // will be correct:
  private long nextGen = 1;
  private final FinishedSegments finishedSegments;
  private final InfoStream infoStream;
  private final AtomicLong bytesUsed = new AtomicLong();
  private final AtomicInteger numTerms = new AtomicInteger();

  BufferedUpdatesStream(InfoStream infoStream) {
    this.infoStream = infoStream;
    this.finishedSegments = new FinishedSegments(infoStream);
  }

  // Appends a new packet of buffered deletes to the stream,
  // setting its generation:
  synchronized long push(FrozenBufferedUpdates packet) {
    /*
     * The insert operation must be atomic. If we let threads increment the gen
     * and push the packet afterwards we risk that packets are out of order.
     * With DWPT this is possible if two or more flushes are racing for pushing
     * updates. If the pushed packets get our of order would loose documents
     * since deletes are applied to the wrong segments.
     */
    packet.setDelGen(nextGen++);
    assert packet.any();
    assert checkDeleteStats();

    updates.add(packet);
    numTerms.addAndGet(packet.numTermDeletes);
    bytesUsed.addAndGet(packet.bytesUsed);
    if (infoStream.isEnabled("BD")) {
      infoStream.message("BD", String.format(Locale.ROOT, "push new packet (%s), packetCount=%d, bytesUsed=%.3f MB", packet, updates.size(), bytesUsed.get()/1024./1024.));
    }
    assert checkDeleteStats();

    return packet.delGen();
  }

  synchronized int getPendingUpdatesCount() {
    return updates.size();
  }

  synchronized void clear() {
    updates.clear();
    nextGen = 1;
    finishedSegments.clear();
    numTerms.set(0);
    bytesUsed.set(0);
  }

  boolean any() {
    return bytesUsed.get() != 0;
  }

  int numTerms() {
    return numTerms.get();
  }

  @Override
  public long ramBytesUsed() {
    return bytesUsed.get();
  }

  static class ApplyDeletesResult {
    
    // True if any actual deletes took place:
    final boolean anyDeletes;

    // If non-null, contains segments that are 100% deleted
    final List<SegmentCommitInfo> allDeleted;

    ApplyDeletesResult(boolean anyDeletes, List<SegmentCommitInfo> allDeleted) {
      this.anyDeletes = anyDeletes;
      this.allDeleted = allDeleted;
    }
  }

  void waitApplyAll(IndexWriter writer) throws IOException {
    assert Thread.holdsLock(writer) == false;
    Set<FrozenBufferedUpdates> waitFor;
    synchronized (this) {
      waitFor = new HashSet<>(updates);
    }

    waitApply(waitFor, writer);
  }

  boolean stillRunning(long delGen) {
    return finishedSegments.stillRunning(delGen);
  }

  void finishedSegment(long delGen) {
    finishedSegments.finishedSegment(delGen);
  }
  

  synchronized void finished(FrozenBufferedUpdates packet) {
    // TODO: would be a bit more memory efficient to track this per-segment, so when each segment writes it writes all packets finished for
    // it, rather than only recording here, across all segments.  But, more complex code, and more CPU, and maybe not so much impact in
    // practice?
    assert packet.applied.getCount() == 1: "packet=" + packet;

    packet.applied.countDown();

    updates.remove(packet);
    numTerms.addAndGet(-packet.numTermDeletes);
    assert numTerms.get() >= 0: "numTerms=" + numTerms + " packet=" + packet;
    
    bytesUsed.addAndGet(-packet.bytesUsed);

    finishedSegment(packet.delGen());
  }

  long getCompletedDelGen() {
    return finishedSegments.getCompletedDelGen();
  }   

  void waitApplyForMerge(List<SegmentCommitInfo> mergeInfos, IndexWriter writer) throws IOException {
    long maxDelGen = Long.MIN_VALUE;
    for (SegmentCommitInfo info : mergeInfos) {
      maxDelGen = Math.max(maxDelGen, info.getBufferedDeletesGen());
    }

    Set<FrozenBufferedUpdates> waitFor = new HashSet<>();
    synchronized (this) {
      for (FrozenBufferedUpdates packet : updates) {
        if (packet.delGen() <= maxDelGen) {
          // We must wait for this packet before finishing the merge because its
          // deletes apply to a subset of the segments being merged:
          waitFor.add(packet);
        }
      }
    }

    if (infoStream.isEnabled("BD")) {
      infoStream.message("BD", "waitApplyForMerge: " + waitFor.size() + " packets, " + mergeInfos.size() + " merging segments");
    }
    
    waitApply(waitFor, writer);
  }

  private void waitApply(Set<FrozenBufferedUpdates> waitFor, IndexWriter writer) throws IOException {

    long startNS = System.nanoTime();

    int packetCount = waitFor.size();

    if (waitFor.isEmpty()) {
      if (infoStream.isEnabled("BD")) {
        infoStream.message("BD", "waitApply: no deletes to apply");
      }
      return;
    }

    if (infoStream.isEnabled("BD")) {
      infoStream.message("BD", "waitApply: " + waitFor.size() + " packets: " + waitFor);
    }

    ArrayList<FrozenBufferedUpdates> pendingPackets = new ArrayList<>();
    long totalDelCount = 0;
    for (FrozenBufferedUpdates packet : waitFor) {
      // Frozen packets are now resolved, concurrently, by the indexing threads that
      // create them, by adding a DocumentsWriter.ResolveUpdatesEvent to the events queue,
      // but if we get here and the packet is not yet resolved, we resolve it now ourselves:
      if (packet.tryApply(writer) == false) {
        // if somebody else is currently applying it - move on to the next one and force apply below
        pendingPackets.add(packet);
      }
      totalDelCount += packet.totalDelCount;
    }
    for (FrozenBufferedUpdates packet : pendingPackets) {
      // now block on all the packets that were concurrently applied to ensure they are due before we continue.
      packet.forceApply(writer);
    }

    if (infoStream.isEnabled("BD")) {
      infoStream.message("BD",
                         String.format(Locale.ROOT, "waitApply: done %d packets; totalDelCount=%d; totBytesUsed=%d; took %.2f msec",
                                       packetCount,
                                       totalDelCount,
                                       bytesUsed.get(),
                                       (System.nanoTime() - startNS) / 1000000.));
    }
  }

  synchronized long getNextGen() {
    return nextGen++;
  }

  static final class SegmentState implements Closeable {
    final long delGen;
    final ReadersAndUpdates rld;
    final SegmentReader reader;
    final int startDelCount;
    private final IOUtils.IOConsumer<ReadersAndUpdates> onClose;

    TermsEnum termsEnum;
    PostingsEnum postingsEnum;
    BytesRef term;

    SegmentState(ReadersAndUpdates rld, IOUtils.IOConsumer<ReadersAndUpdates> onClose, SegmentCommitInfo info) throws IOException {
      this.rld = rld;
      reader = rld.getReader(IOContext.READ);
      startDelCount = rld.getDelCount();
      delGen = info.getBufferedDeletesGen();
      this.onClose = onClose;
    }

    @Override
    public String toString() {
      return "SegmentState(" + rld.info + ")";
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(() -> rld.release(reader), () -> onClose.accept(rld));
    }
  }

  // only for assert
  private boolean checkDeleteStats() {
    int numTerms2 = 0;
    long bytesUsed2 = 0;
    for(FrozenBufferedUpdates packet : updates) {
      numTerms2 += packet.numTermDeletes;
      bytesUsed2 += packet.bytesUsed;
    }
    assert numTerms2 == numTerms.get(): "numTerms2=" + numTerms2 + " vs " + numTerms.get();
    assert bytesUsed2 == bytesUsed.get(): "bytesUsed2=" + bytesUsed2 + " vs " + bytesUsed;
    return true;
  }

  private static class FinishedSegments {

    private long completedDelGen;

    private final Set<Long> finishedDelGens = new HashSet<>();

    private final InfoStream infoStream;

    FinishedSegments(InfoStream infoStream) {
      this.infoStream = infoStream;
    }

    synchronized void clear() {
      finishedDelGens.clear();
      completedDelGen = 0;
    }

    synchronized boolean stillRunning(long delGen) {
      return delGen > completedDelGen && finishedDelGens.contains(delGen) == false;
    }

    synchronized long getCompletedDelGen() {
      return completedDelGen;
    }

    synchronized void finishedSegment(long delGen) {
      finishedDelGens.add(delGen);
      while (true) {
        if (finishedDelGens.contains(completedDelGen + 1)) {
          finishedDelGens.remove(completedDelGen + 1);
          completedDelGen++;
        } else {
          break;
        }
      }

      if (infoStream.isEnabled("BD")) {
        infoStream.message("BD", "finished packet delGen=" + delGen + " now completedDelGen=" + completedDelGen);
      }
    }
  }

}
