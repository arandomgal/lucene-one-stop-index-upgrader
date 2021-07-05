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
package org.trypticon.luceneupgrader.lucene6.internal.lucene.search;


import java.io.IOException;
import java.util.Set;

import org.trypticon.luceneupgrader.lucene6.internal.lucene.index.LeafReaderContext;
import org.trypticon.luceneupgrader.lucene6.internal.lucene.index.Term;

public abstract class ConstantScoreWeight extends Weight {

  private float boost;
  private float queryNorm;
  private float queryWeight;

  protected ConstantScoreWeight(Query query) {
    super(query);
    normalize(1f, 1f);
  }

  @Override
  public void extractTerms(Set<Term> terms) {
    // most constant-score queries don't wrap index terms
    // eg. geo filters, doc values queries, ...
    // override if your constant-score query does wrap terms
  }

  @Override
  public final float getValueForNormalization() throws IOException {
    return queryWeight * queryWeight;
  }

  @Override
  public void normalize(float norm, float boost) {
    this.boost = boost;
    queryNorm = norm;
    queryWeight = queryNorm * boost;
  }

  protected final float queryNorm() {
    return queryNorm;
  }

  protected final float boost() {
    return boost;
  }

  protected final float score() {
    return queryWeight;
  }

  @Override
  public Explanation explain(LeafReaderContext context, int doc) throws IOException {
    final Scorer s = scorer(context);
    final boolean exists;
    if (s == null) {
      exists = false;
    } else {
      final TwoPhaseIterator twoPhase = s.twoPhaseIterator();
      if (twoPhase == null) {
        exists = s.iterator().advance(doc) == doc;
      } else {
        exists = twoPhase.approximation().advance(doc) == doc && twoPhase.matches();
      }
    }

    if (exists) {
      return Explanation.match(
          queryWeight, getQuery().toString() + ", product of:",
          Explanation.match(boost, "boost"), Explanation.match(queryNorm, "queryNorm"));
    } else {
      return Explanation.noMatch(getQuery().toString() + " doesn't match id " + doc);
    }
  }

}