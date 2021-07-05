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
package org.trypticon.luceneupgrader.lucene3.internal.lucene.index;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

import org.trypticon.luceneupgrader.lucene3.internal.lucene.store.BufferedIndexInput;
import org.trypticon.luceneupgrader.lucene3.internal.lucene.store.Directory;
import org.trypticon.luceneupgrader.lucene3.internal.lucene.store.IndexInput;
import org.trypticon.luceneupgrader.lucene3.internal.lucene.util.ArrayUtil;
import org.trypticon.luceneupgrader.lucene3.internal.lucene.util.IOUtils;

class TermVectorsReader implements Cloneable, Closeable {

  // NOTE: if you make a new format, it must be larger than
  // the current format
  static final int FORMAT_VERSION = 2;

  // Changes to speed up bulk merging of term vectors:
  static final int FORMAT_VERSION2 = 3;

  // Changed strings to UTF8 with length-in-bytes not length-in-chars
  static final int FORMAT_UTF8_LENGTH_IN_BYTES = 4;

  // NOTE: always change this if you switch to a new format!
  static final int FORMAT_CURRENT = FORMAT_UTF8_LENGTH_IN_BYTES;

  //The size in bytes that the FORMAT_VERSION will take up at the beginning of each file 
  static final int FORMAT_SIZE = 4;

  static final byte STORE_POSITIONS_WITH_TERMVECTOR = 0x1;
  static final byte STORE_OFFSET_WITH_TERMVECTOR = 0x2;
  
  private FieldInfos fieldInfos;

  private IndexInput tvx;
  private IndexInput tvd;
  private IndexInput tvf;
  private int size;
  private int numTotalDocs;

  // The docID offset where our docs begin in the index
  // file.  This will be 0 if we have our own private file.
  private int docStoreOffset;
  
  private final int format;

  TermVectorsReader(Directory d, String segment, FieldInfos fieldInfos)
    throws CorruptIndexException, IOException {
    this(d, segment, fieldInfos, BufferedIndexInput.BUFFER_SIZE);
  }

  TermVectorsReader(Directory d, String segment, FieldInfos fieldInfos, int readBufferSize)
    throws CorruptIndexException, IOException {
    this(d, segment, fieldInfos, readBufferSize, -1, 0);
  }
    
  TermVectorsReader(Directory d, String segment, FieldInfos fieldInfos, int readBufferSize, int docStoreOffset, int size)
    throws CorruptIndexException, IOException {
    boolean success = false;

    try {
      String idxName = IndexFileNames.segmentFileName(segment, IndexFileNames.VECTORS_INDEX_EXTENSION);
      tvx = d.openInput(idxName, readBufferSize);
      format = checkValidFormat(idxName, tvx);
      String fn = IndexFileNames.segmentFileName(segment, IndexFileNames.VECTORS_DOCUMENTS_EXTENSION);
      tvd = d.openInput(fn, readBufferSize);
      final int tvdFormat = checkValidFormat(fn, tvd);
      fn = IndexFileNames.segmentFileName(segment, IndexFileNames.VECTORS_FIELDS_EXTENSION);
      tvf = d.openInput(fn, readBufferSize);
      final int tvfFormat = checkValidFormat(fn, tvf);

      assert format == tvdFormat;
      assert format == tvfFormat;

      if (format >= FORMAT_VERSION2) {
        numTotalDocs = (int) (tvx.length() >> 4);
      } else {
        assert (tvx.length()-FORMAT_SIZE) % 8 == 0;
        numTotalDocs = (int) (tvx.length() >> 3);
      }

      if (-1 == docStoreOffset) {
        this.docStoreOffset = 0;
        this.size = numTotalDocs;
        assert size == 0 || numTotalDocs == size;
      } else {
        this.docStoreOffset = docStoreOffset;
        this.size = size;
        // Verify the file is long enough to hold all of our
        // docs
        assert numTotalDocs >= size + docStoreOffset: "numTotalDocs=" + numTotalDocs + " size=" + size + " docStoreOffset=" + docStoreOffset;
      }

      this.fieldInfos = fieldInfos;
      success = true;
    } finally {
      // With lock-less commits, it's entirely possible (and
      // fine) to hit a FileNotFound exception above. In
      // this case, we want to explicitly close any subset
      // of things that were opened so that we don't have to
      // wait for a GC to do so.
      if (!success) {
        close();
      }
    }
  }

  // Used for bulk copy when merging
  IndexInput getTvdStream() {
    return tvd;
  }

  // Used for bulk copy when merging
  IndexInput getTvfStream() {
    return tvf;
  }

  final private void seekTvx(final int docNum) throws IOException {
    if (format < FORMAT_VERSION2)
      tvx.seek((docNum + docStoreOffset) * 8L + FORMAT_SIZE);
    else
      tvx.seek((docNum + docStoreOffset) * 16L + FORMAT_SIZE);
  }

  boolean canReadRawDocs() {
    return format >= FORMAT_UTF8_LENGTH_IN_BYTES;
  }


  final void rawDocs(int[] tvdLengths, int[] tvfLengths, int startDocID, int numDocs) throws IOException {

    if (tvx == null) {
      Arrays.fill(tvdLengths, 0);
      Arrays.fill(tvfLengths, 0);
      return;
    }

    // SegmentMerger calls canReadRawDocs() first and should
    // not call us if that returns false.
    if (format < FORMAT_VERSION2)
      throw new IllegalStateException("cannot read raw docs with older term vector formats");

    seekTvx(startDocID);

    long tvdPosition = tvx.readLong();
    tvd.seek(tvdPosition);

    long tvfPosition = tvx.readLong();
    tvf.seek(tvfPosition);

    long lastTvdPosition = tvdPosition;
    long lastTvfPosition = tvfPosition;

    int count = 0;
    while (count < numDocs) {
      final int docID = docStoreOffset + startDocID + count + 1;
      assert docID <= numTotalDocs;
      if (docID < numTotalDocs)  {
        tvdPosition = tvx.readLong();
        tvfPosition = tvx.readLong();
      } else {
        tvdPosition = tvd.length();
        tvfPosition = tvf.length();
        assert count == numDocs-1;
      }
      tvdLengths[count] = (int) (tvdPosition-lastTvdPosition);
      tvfLengths[count] = (int) (tvfPosition-lastTvfPosition);
      count++;
      lastTvdPosition = tvdPosition;
      lastTvfPosition = tvfPosition;
    }
  }

  private int checkValidFormat(String fn, IndexInput in) throws CorruptIndexException, IOException
  {
    int format = in.readInt();
    if (format > FORMAT_CURRENT) {
      throw new IndexFormatTooNewException(in, format, 1, FORMAT_CURRENT);
    }
    return format;
  }

  public void close() throws IOException {
    IOUtils.close(tvx, tvd, tvf);
  }

  int size() {
    return size;
  }

  public void get(int docNum, String field, TermVectorMapper mapper) throws IOException {
    if (tvx != null) {
      int fieldNumber = fieldInfos.fieldNumber(field);
      //We need to account for the FORMAT_SIZE at when seeking in the tvx
      //We don't need to do this in other seeks because we already have the
      // file pointer
      //that was written in another file
      seekTvx(docNum);
      //System.out.println("TVX Pointer: " + tvx.getFilePointer());
      long tvdPosition = tvx.readLong();

      tvd.seek(tvdPosition);
      int fieldCount = tvd.readVInt();
      //System.out.println("Num Fields: " + fieldCount);
      // There are only a few fields per document. We opt for a full scan
      // rather then requiring that they be ordered. We need to read through
      // all of the fields anyway to get to the tvf pointers.
      int number = 0;
      int found = -1;
      for (int i = 0; i < fieldCount; i++) {
        if (format >= FORMAT_VERSION)
          number = tvd.readVInt();
        else
          number += tvd.readVInt();

        if (number == fieldNumber)
          found = i;
      }

      // This field, although valid in the segment, was not found in this
      // document
      if (found != -1) {
        // Compute position in the tvf file
        long position;
        if (format >= FORMAT_VERSION2)
          position = tvx.readLong();
        else
          position = tvd.readVLong();
        for (int i = 1; i <= found; i++)
          position += tvd.readVLong();

        mapper.setDocumentNumber(docNum);
        readTermVector(field, position, mapper);
      } else {
        //System.out.println("Fieldable not found");
      }
    } else {
      //System.out.println("No tvx file");
    }
  }



  TermFreqVector get(int docNum, String field) throws IOException {
    // Check if no term vectors are available for this segment at all
    ParallelArrayTermVectorMapper mapper = new ParallelArrayTermVectorMapper();
    get(docNum, field, mapper);

    return mapper.materializeVector();
  }

  // Reads the String[] fields; you have to pre-seek tvd to
  // the right point
  final private String[] readFields(int fieldCount) throws IOException {
    int number = 0;
    String[] fields = new String[fieldCount];

    for (int i = 0; i < fieldCount; i++) {
      if (format >= FORMAT_VERSION)
        number = tvd.readVInt();
      else
        number += tvd.readVInt();

      fields[i] = fieldInfos.fieldName(number);
    }

    return fields;
  }

  // Reads the long[] offsets into TVF; you have to pre-seek
  // tvx/tvd to the right point
  final private long[] readTvfPointers(int fieldCount) throws IOException {
    // Compute position in the tvf file
    long position;
    if (format >= FORMAT_VERSION2)
      position = tvx.readLong();
    else
      position = tvd.readVLong();

    long[] tvfPointers = new long[fieldCount];
    tvfPointers[0] = position;

    for (int i = 1; i < fieldCount; i++) {
      position += tvd.readVLong();
      tvfPointers[i] = position;
    }

    return tvfPointers;
  }

  TermFreqVector[] get(int docNum) throws IOException {
    TermFreqVector[] result = null;
    if (tvx != null) {
      //We need to offset by
      seekTvx(docNum);
      long tvdPosition = tvx.readLong();

      tvd.seek(tvdPosition);
      int fieldCount = tvd.readVInt();

      // No fields are vectorized for this document
      if (fieldCount != 0) {
        final String[] fields = readFields(fieldCount);
        final long[] tvfPointers = readTvfPointers(fieldCount);
        result = readTermVectors(docNum, fields, tvfPointers);
      }
    } else {
      //System.out.println("No tvx file");
    }
    return result;
  }

  public void get(int docNumber, TermVectorMapper mapper) throws IOException {
    // Check if no term vectors are available for this segment at all
    if (tvx != null) {
      //We need to offset by

      seekTvx(docNumber);
      long tvdPosition = tvx.readLong();

      tvd.seek(tvdPosition);
      int fieldCount = tvd.readVInt();

      // No fields are vectorized for this document
      if (fieldCount != 0) {
        final String[] fields = readFields(fieldCount);
        final long[] tvfPointers = readTvfPointers(fieldCount);
        mapper.setDocumentNumber(docNumber);
        readTermVectors(fields, tvfPointers, mapper);
      }
    } else {
      //System.out.println("No tvx file");
    }
  }


  private SegmentTermVector[] readTermVectors(int docNum, String fields[], long tvfPointers[])
          throws IOException {
    SegmentTermVector res[] = new SegmentTermVector[fields.length];
    for (int i = 0; i < fields.length; i++) {
      ParallelArrayTermVectorMapper mapper = new ParallelArrayTermVectorMapper();
      mapper.setDocumentNumber(docNum);
      readTermVector(fields[i], tvfPointers[i], mapper);
      res[i] = (SegmentTermVector) mapper.materializeVector();
    }
    return res;
  }

  private void readTermVectors(String fields[], long tvfPointers[], TermVectorMapper mapper)
          throws IOException {
    for (int i = 0; i < fields.length; i++) {
      readTermVector(fields[i], tvfPointers[i], mapper);
    }
  }


  private void readTermVector(String field, long tvfPointer, TermVectorMapper mapper)
          throws IOException {

    // Now read the data from specified position
    //We don't need to offset by the FORMAT here since the pointer already includes the offset
    tvf.seek(tvfPointer);

    int numTerms = tvf.readVInt();
    //System.out.println("Num Terms: " + numTerms);
    // If no terms - return a constant empty termvector. However, this should never occur!
    if (numTerms == 0) 
      return;
    
    boolean storePositions;
    boolean storeOffsets;
    
    if (format >= FORMAT_VERSION){
      byte bits = tvf.readByte();
      storePositions = (bits & STORE_POSITIONS_WITH_TERMVECTOR) != 0;
      storeOffsets = (bits & STORE_OFFSET_WITH_TERMVECTOR) != 0;
    }
    else{
      tvf.readVInt();
      storePositions = false;
      storeOffsets = false;
    }
    mapper.setExpectations(field, numTerms, storeOffsets, storePositions);
    int start = 0;
    int deltaLength = 0;
    int totalLength = 0;
    byte[] byteBuffer;
    char[] charBuffer;
    final boolean preUTF8 = format < FORMAT_UTF8_LENGTH_IN_BYTES;

    // init the buffers
    if (preUTF8) {
      charBuffer = new char[10];
      byteBuffer = null;
    } else {
      charBuffer = null;
      byteBuffer = new byte[20];
    }

    for (int i = 0; i < numTerms; i++) {
      start = tvf.readVInt();
      deltaLength = tvf.readVInt();
      totalLength = start + deltaLength;

      final String term;
      
      if (preUTF8) {
        // Term stored as java chars
        if (charBuffer.length < totalLength) {
          charBuffer = ArrayUtil.grow(charBuffer, totalLength);
        }
        tvf.readChars(charBuffer, start, deltaLength);
        term = new String(charBuffer, 0, totalLength);
      } else {
        // Term stored as utf8 bytes
        if (byteBuffer.length < totalLength) {
          byteBuffer = ArrayUtil.grow(byteBuffer, totalLength);
        }
        tvf.readBytes(byteBuffer, start, deltaLength);
        term = new String(byteBuffer, 0, totalLength, "UTF-8");
      }
      int freq = tvf.readVInt();
      int [] positions = null;
      if (storePositions) { //read in the positions
        //does the mapper even care about positions?
        if (mapper.isIgnoringPositions() == false) {
          positions = new int[freq];
          int prevPosition = 0;
          for (int j = 0; j < freq; j++)
          {
            positions[j] = prevPosition + tvf.readVInt();
            prevPosition = positions[j];
          }
        } else {
          //we need to skip over the positions.  Since these are VInts, I don't believe there is anyway to know for sure how far to skip
          //
          for (int j = 0; j < freq; j++)
          {
            tvf.readVInt();
          }
        }
      }
      TermVectorOffsetInfo[] offsets = null;
      if (storeOffsets) {
        //does the mapper even care about offsets?
        if (mapper.isIgnoringOffsets() == false) {
          offsets = new TermVectorOffsetInfo[freq];
          int prevOffset = 0;
          for (int j = 0; j < freq; j++) {
            int startOffset = prevOffset + tvf.readVInt();
            int endOffset = startOffset + tvf.readVInt();
            offsets[j] = new TermVectorOffsetInfo(startOffset, endOffset);
            prevOffset = endOffset;
          }
        } else {
          for (int j = 0; j < freq; j++){
            tvf.readVInt();
            tvf.readVInt();
          }
        }
      }
      mapper.map(term, freq, offsets, positions);
    }
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    
    final TermVectorsReader clone = (TermVectorsReader) super.clone();

    // These are null when a TermVectorsReader was created
    // on a segment that did not have term vectors saved
    if (tvx != null && tvd != null && tvf != null) {
      clone.tvx = (IndexInput) tvx.clone();
      clone.tvd = (IndexInput) tvd.clone();
      clone.tvf = (IndexInput) tvf.clone();
    }
    
    return clone;
  }
}


class ParallelArrayTermVectorMapper extends TermVectorMapper
{

  private String[] terms;
  private int[] termFreqs;
  private int positions[][];
  private TermVectorOffsetInfo offsets[][];
  private int currentPosition;
  private boolean storingOffsets;
  private boolean storingPositions;
  private String field;

  @Override
  public void setExpectations(String field, int numTerms, boolean storeOffsets, boolean storePositions) {
    this.field = field;
    terms = new String[numTerms];
    termFreqs = new int[numTerms];
    this.storingOffsets = storeOffsets;
    this.storingPositions = storePositions;
    if(storePositions)
      this.positions = new int[numTerms][];
    if(storeOffsets)
      this.offsets = new TermVectorOffsetInfo[numTerms][];
  }

  @Override
  public void map(String term, int frequency, TermVectorOffsetInfo[] offsets, int[] positions) {
    terms[currentPosition] = term;
    termFreqs[currentPosition] = frequency;
    if (storingOffsets)
    {
      this.offsets[currentPosition] = offsets;
    }
    if (storingPositions)
    {
      this.positions[currentPosition] = positions; 
    }
    currentPosition++;
  }

  public TermFreqVector materializeVector() {
    SegmentTermVector tv = null;
    if (field != null && terms != null) {
      if (storingPositions || storingOffsets) {
        tv = new SegmentTermPositionVector(field, terms, termFreqs, positions, offsets);
      } else {
        tv = new SegmentTermVector(field, terms, termFreqs);
      }
    }
    return tv;
  }
}
