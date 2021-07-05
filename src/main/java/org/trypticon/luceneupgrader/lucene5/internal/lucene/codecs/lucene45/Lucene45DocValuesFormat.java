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
package org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.lucene45;

import java.io.IOException;

import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.DocValuesConsumer;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.DocValuesProducer;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.codecs.DocValuesFormat;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.index.SegmentReadState;
import org.trypticon.luceneupgrader.lucene5.internal.lucene.index.SegmentWriteState;

@Deprecated
public class Lucene45DocValuesFormat extends DocValuesFormat {

  public Lucene45DocValuesFormat() {
    super("Lucene45");
  }

  @Override
  public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    // really we should be read-only, but to allow posting of dv updates against old segments...
    return new Lucene45DocValuesConsumer(state, DATA_CODEC, DATA_EXTENSION, META_CODEC, META_EXTENSION);
  }

  @Override
  public final DocValuesProducer fieldsProducer(SegmentReadState state) throws IOException {
    return new Lucene45DocValuesProducer(state, DATA_CODEC, DATA_EXTENSION, META_CODEC, META_EXTENSION);
  }
  
  static final String DATA_CODEC = "Lucene45DocValuesData";
  static final String DATA_EXTENSION = "dvd";
  static final String META_CODEC = "Lucene45ValuesMetadata";
  static final String META_EXTENSION = "dvm";
  static final int VERSION_START = 0;
  static final int VERSION_SORTED_SET_SINGLE_VALUE_OPTIMIZED = 1;
  static final int VERSION_CHECKSUM = 2;
  static final int VERSION_CURRENT = VERSION_CHECKSUM;
  static final byte NUMERIC = 0;
  static final byte BINARY = 1;
  static final byte SORTED = 2;
  static final byte SORTED_SET = 3;
}
