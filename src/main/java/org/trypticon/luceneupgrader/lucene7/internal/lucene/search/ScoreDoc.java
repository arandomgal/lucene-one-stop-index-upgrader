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
package org.trypticon.luceneupgrader.lucene7.internal.lucene.search;



public class ScoreDoc {

  public float score;

  public int doc;

  public int shardIndex;

  public ScoreDoc(int doc, float score) {
    this(doc, score, -1);
  }

  public ScoreDoc(int doc, float score, int shardIndex) {
    this.doc = doc;
    this.score = score;
    this.shardIndex = shardIndex;
  }
  
  // A convenience method for debugging.
  @Override
  public String toString() {
    return "doc=" + doc + " score=" + score + " shardIndex=" + shardIndex;
  }
}
