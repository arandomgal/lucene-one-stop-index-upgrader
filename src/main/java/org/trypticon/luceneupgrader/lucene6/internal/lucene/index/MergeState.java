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
package org.trypticon.luceneupgrader.lucene6.internal.lucene.index;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.trypticon.luceneupgrader.lucene6.internal.lucene.codecs.DocValuesProducer;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.codecs.FieldsProducer;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.codecs.NormsProducer;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.codecs.PointsReader;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.codecs.StoredFieldsReader;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.codecs.TermVectorsReader;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.search.Sort;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.util.Bits;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.util.InfoStream;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.util.packed.PackedInts;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.util.packed.PackedLongValues;


public class MergeState {

  public final DocMap[] docMaps;

  // Only used by IW when it must remap deletes that arrived against the merging segments while a merge was running:
  final DocMap[] leafDocMaps;

  public final SegmentInfo segmentInfo;

  public FieldInfos mergeFieldInfos;

  public final StoredFieldsReader[] storedFieldsReaders;

  public final TermVectorsReader[] termVectorsReaders;

  public final NormsProducer[] normsProducers;

  public final DocValuesProducer[] docValuesProducers;

  public final FieldInfos[] fieldInfos;

  public final Bits[] liveDocs;

  public final FieldsProducer[] fieldsProducers;

  public final PointsReader[] pointsReaders;

  public final int[] maxDocs;

  public final InfoStream infoStream;

  public boolean needsIndexSort;

  MergeState(List<CodecReader> originalReaders, SegmentInfo segmentInfo, InfoStream infoStream) throws IOException {

    this.infoStream = infoStream;

    final Sort indexSort = segmentInfo.getIndexSort();
    int numReaders = originalReaders.size();
    leafDocMaps = new DocMap[numReaders];
    List<CodecReader> readers = maybeSortReaders(originalReaders, segmentInfo);

    maxDocs = new int[numReaders];
    fieldsProducers = new FieldsProducer[numReaders];
    normsProducers = new NormsProducer[numReaders];
    storedFieldsReaders = new StoredFieldsReader[numReaders];
    termVectorsReaders = new TermVectorsReader[numReaders];
    docValuesProducers = new DocValuesProducer[numReaders];
    pointsReaders = new PointsReader[numReaders];
    fieldInfos = new FieldInfos[numReaders];
    liveDocs = new Bits[numReaders];

    int numDocs = 0;
    for(int i=0;i<numReaders;i++) {
      final CodecReader reader = readers.get(i);

      maxDocs[i] = reader.maxDoc();
      liveDocs[i] = reader.getLiveDocs();
      fieldInfos[i] = reader.getFieldInfos();

      normsProducers[i] = reader.getNormsReader();
      if (normsProducers[i] != null) {
        normsProducers[i] = normsProducers[i].getMergeInstance();
      }
      
      docValuesProducers[i] = reader.getDocValuesReader();
      if (docValuesProducers[i] != null) {
        docValuesProducers[i] = docValuesProducers[i].getMergeInstance();
      }
      
      storedFieldsReaders[i] = reader.getFieldsReader();
      if (storedFieldsReaders[i] != null) {
        storedFieldsReaders[i] = storedFieldsReaders[i].getMergeInstance();
      }
      
      termVectorsReaders[i] = reader.getTermVectorsReader();
      if (termVectorsReaders[i] != null) {
        termVectorsReaders[i] = termVectorsReaders[i].getMergeInstance();
      }
      
      fieldsProducers[i] = reader.getPostingsReader().getMergeInstance();
      pointsReaders[i] = reader.getPointsReader();
      if (pointsReaders[i] != null) {
        pointsReaders[i] = pointsReaders[i].getMergeInstance();
      }
      numDocs += reader.numDocs();
    }

    segmentInfo.setMaxDoc(numDocs);

    this.segmentInfo = segmentInfo;
    this.docMaps = buildDocMaps(readers, indexSort);
  }

  // Remap docIDs around deletions
  private DocMap[] buildDeletionDocMaps(List<CodecReader> readers) {

    int totalDocs = 0;
    int numReaders = readers.size();
    DocMap[] docMaps = new DocMap[numReaders];

    for (int i = 0; i < numReaders; i++) {
      LeafReader reader = readers.get(i);
      Bits liveDocs = reader.getLiveDocs();

      final PackedLongValues delDocMap;
      if (liveDocs != null) {
        delDocMap = removeDeletes(reader.maxDoc(), liveDocs);
      } else {
        delDocMap = null;
      }

      final int docBase = totalDocs;
      docMaps[i] = new DocMap() {
        @Override
        public int get(int docID) {
          if (liveDocs == null) {
            return docBase + docID;
          } else if (liveDocs.get(docID)) {
            return docBase + (int) delDocMap.get(docID);
          } else {
            return -1;
          }
        }
      };
      totalDocs += reader.numDocs();
    }

    return docMaps;
  }

  private DocMap[] buildDocMaps(List<CodecReader> readers, Sort indexSort) throws IOException {

    if (indexSort == null) {
      // no index sort ... we only must map around deletions, and rebase to the merged segment's docID space
      return buildDeletionDocMaps(readers);
    } else {
      // do a merge sort of the incoming leaves:
      long t0 = System.nanoTime();
      DocMap[] result = MultiSorter.sort(indexSort, readers);
      if (result == null) {
        // already sorted so we can switch back to map around deletions
        return buildDeletionDocMaps(readers);
      } else {
        needsIndexSort = true;
      }
      long t1 = System.nanoTime();
      if (infoStream.isEnabled("SM")) {
        infoStream.message("SM", String.format(Locale.ROOT, "%.2f msec to build merge sorted DocMaps", (t1-t0)/1000000.0));
      }
      return result;
    }
  }

  private List<CodecReader> maybeSortReaders(List<CodecReader> originalReaders, SegmentInfo segmentInfo) throws IOException {

    // Default to identity:
    for(int i=0;i<originalReaders.size();i++) {
      leafDocMaps[i] = new DocMap() {
          @Override
          public int get(int docID) {
            return docID;
          }
        };
    }

    Sort indexSort = segmentInfo.getIndexSort();
    if (indexSort == null) {
      return originalReaders;
    }

    final Sorter sorter = new Sorter(indexSort);
    List<CodecReader> readers = new ArrayList<>(originalReaders.size());

    for (CodecReader leaf : originalReaders) {
      Sort segmentSort = leaf.getIndexSort();

      if (segmentSort == null) {
        // This segment was written by flush, so documents are not yet sorted, so we sort them now:
        long t0 = System.nanoTime();
        Sorter.DocMap sortDocMap = sorter.sort(leaf);
        long t1 = System.nanoTime();
        double msec = (t1-t0)/1000000.0;
        
        if (sortDocMap != null) {
          if (infoStream.isEnabled("SM")) {
            infoStream.message("SM", String.format(Locale.ROOT, "segment %s is not sorted; wrapping for sort %s now (%.2f msec to sort)", leaf, indexSort, msec));
          }
          needsIndexSort = true;
          leaf = SlowCodecReaderWrapper.wrap(SortingLeafReader.wrap(new MergeReaderWrapper(leaf), sortDocMap));
          leafDocMaps[readers.size()] = new DocMap() {
              @Override
              public int get(int docID) {
                return sortDocMap.oldToNew(docID);
              }
            };
        } else {
          if (infoStream.isEnabled("SM")) {
            infoStream.message("SM", String.format(Locale.ROOT, "segment %s is not sorted, but is already accidentally in sort %s order (%.2f msec to sort)", leaf, indexSort, msec));
          }
        }

      } else {
        if (segmentSort.equals(indexSort) == false) {
          throw new IllegalArgumentException("index sort mismatch: merged segment has sort=" + indexSort + " but to-be-merged segment has sort=" + segmentSort);
        }
        if (infoStream.isEnabled("SM")) {
          infoStream.message("SM", "segment " + leaf + " already sorted");
        }
      }

      readers.add(leaf);
    }

    return readers;
  }

  public static abstract class DocMap {
    public DocMap() {
    }

    public abstract int get(int docID);
  }

  static PackedLongValues removeDeletes(final int maxDoc, final Bits liveDocs) {
    final PackedLongValues.Builder docMapBuilder = PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
    int del = 0;
    for (int i = 0; i < maxDoc; ++i) {
      docMapBuilder.add(i - del);
      if (liveDocs.get(i) == false) {
        ++del;
      }
    }
    return docMapBuilder.build();
  }
}