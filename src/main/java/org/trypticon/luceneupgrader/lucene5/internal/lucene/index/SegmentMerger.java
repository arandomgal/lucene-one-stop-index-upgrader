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
package org.trypticon.luceneupgrader.lucene5.internal.lucene.index;


import java.io.IOException;
import java.util.List;

import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.Codec;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.DocValuesConsumer;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.FieldsConsumer;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.NormsConsumer;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.StoredFieldsWriter;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.TermVectorsWriter;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.store.Directory;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.store.IOContext;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.util.InfoStream;

final class SegmentMerger {
  private final Directory directory;

  private final Codec codec;
  
  private final IOContext context;
  
  final MergeState mergeState;
  private final FieldInfos.Builder fieldInfosBuilder;

  // note, just like in codec apis Directory 'dir' is NOT the same as segmentInfo.dir!!
  SegmentMerger(List<CodecReader> readers, SegmentInfo segmentInfo, InfoStream infoStream, Directory dir,
                FieldInfos.FieldNumbers fieldNumbers, IOContext context) throws IOException {
    if (context.context != IOContext.Context.MERGE) {
      throw new IllegalArgumentException("IOContext.context should be MERGE; got: " + context.context);
    }
    mergeState = new MergeState(readers, segmentInfo, infoStream);
    directory = dir;
    this.codec = segmentInfo.getCodec();
    this.context = context;
    this.fieldInfosBuilder = new FieldInfos.Builder(fieldNumbers);
  }
  
  boolean shouldMerge() {
    return mergeState.segmentInfo.maxDoc() > 0;
  }

  MergeState merge() throws IOException {
    if (!shouldMerge()) {
      throw new IllegalStateException("Merge would result in 0 document segment");
    }
    mergeFieldInfos();
    long t0 = 0;
    if (mergeState.infoStream.isEnabled("SM")) {
      t0 = System.nanoTime();
    }
    int numMerged = mergeFields();
    if (mergeState.infoStream.isEnabled("SM")) {
      long t1 = System.nanoTime();
      mergeState.infoStream.message("SM", ((t1-t0)/1000000) + " msec to merge stored fields [" + numMerged + " docs]");
    }
    assert numMerged == mergeState.segmentInfo.maxDoc(): "numMerged=" + numMerged + " vs mergeState.segmentInfo.maxDoc()=" + mergeState.segmentInfo.maxDoc();

    final SegmentWriteState segmentWriteState = new SegmentWriteState(mergeState.infoStream, directory, mergeState.segmentInfo,
                                                                      mergeState.mergeFieldInfos, null, context);
    if (mergeState.infoStream.isEnabled("SM")) {
      t0 = System.nanoTime();
    }
    mergeTerms(segmentWriteState);
    if (mergeState.infoStream.isEnabled("SM")) {
      long t1 = System.nanoTime();
      mergeState.infoStream.message("SM", ((t1-t0)/1000000) + " msec to merge postings [" + numMerged + " docs]");
    }

    if (mergeState.infoStream.isEnabled("SM")) {
      t0 = System.nanoTime();
    }
    if (mergeState.mergeFieldInfos.hasDocValues()) {
      mergeDocValues(segmentWriteState);
    }
    if (mergeState.infoStream.isEnabled("SM")) {
      long t1 = System.nanoTime();
      mergeState.infoStream.message("SM", ((t1-t0)/1000000) + " msec to merge doc values [" + numMerged + " docs]");
    }
    
    if (mergeState.mergeFieldInfos.hasNorms()) {
      if (mergeState.infoStream.isEnabled("SM")) {
        t0 = System.nanoTime();
      }
      mergeNorms(segmentWriteState);
      if (mergeState.infoStream.isEnabled("SM")) {
        long t1 = System.nanoTime();
        mergeState.infoStream.message("SM", ((t1-t0)/1000000) + " msec to merge norms [" + numMerged + " docs]");
      }
    }

    if (mergeState.mergeFieldInfos.hasVectors()) {
      if (mergeState.infoStream.isEnabled("SM")) {
        t0 = System.nanoTime();
      }
      numMerged = mergeVectors();
      if (mergeState.infoStream.isEnabled("SM")) {
        long t1 = System.nanoTime();
        mergeState.infoStream.message("SM", ((t1-t0)/1000000) + " msec to merge vectors [" + numMerged + " docs]");
      }
      assert numMerged == mergeState.segmentInfo.maxDoc();
    }
    
    // write the merged infos
    if (mergeState.infoStream.isEnabled("SM")) {
      t0 = System.nanoTime();
    }
    codec.fieldInfosFormat().write(directory, mergeState.segmentInfo, "", mergeState.mergeFieldInfos, context);
    if (mergeState.infoStream.isEnabled("SM")) {
      long t1 = System.nanoTime();
      mergeState.infoStream.message("SM", ((t1-t0)/1000000) + " msec to write field infos [" + numMerged + " docs]");
    }

    return mergeState;
  }

  private void mergeDocValues(SegmentWriteState segmentWriteState) throws IOException {
    try (DocValuesConsumer consumer = codec.docValuesFormat().fieldsConsumer(segmentWriteState)) {
      consumer.merge(mergeState);
    }
  }

  private void mergeNorms(SegmentWriteState segmentWriteState) throws IOException {
    try (NormsConsumer consumer = codec.normsFormat().normsConsumer(segmentWriteState)) {
      consumer.merge(mergeState);
    }
  }
  
  public void mergeFieldInfos() throws IOException {
    for (FieldInfos readerFieldInfos : mergeState.fieldInfos) {
      for (FieldInfo fi : readerFieldInfos) {
        fieldInfosBuilder.add(fi);
      }
    }
    mergeState.mergeFieldInfos = fieldInfosBuilder.finish();
  }

  private int mergeFields() throws IOException {
    try (StoredFieldsWriter fieldsWriter = codec.storedFieldsFormat().fieldsWriter(directory, mergeState.segmentInfo, context)) {
      return fieldsWriter.merge(mergeState);
    }
  }

  private int mergeVectors() throws IOException {
    try (TermVectorsWriter termVectorsWriter = codec.termVectorsFormat().vectorsWriter(directory, mergeState.segmentInfo, context)) {
      return termVectorsWriter.merge(mergeState);
    }
  }

  private void mergeTerms(SegmentWriteState segmentWriteState) throws IOException {
    try (FieldsConsumer consumer = codec.postingsFormat().fieldsConsumer(segmentWriteState)) {
      consumer.merge(mergeState);
    }
  }
}
