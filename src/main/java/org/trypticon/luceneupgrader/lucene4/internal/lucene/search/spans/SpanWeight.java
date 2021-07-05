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
package org.trypticon.luceneupgrader.lucene4.internal.lucene.search.spans;

import org.trypticon.luceneupgrader.lucene4.internal.lucene.index.AtomicReaderContext;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.index.IndexReaderContext;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.index.Term;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.index.TermContext;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.search.*;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.search.similarities.Similarity;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.search.similarities.Similarity.SimScorer;
import org.trypticon.luceneupgrader.lucene4.internal.lucene.util.Bits;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

public class SpanWeight extends Weight {
  protected Similarity similarity;
  protected Map<Term,TermContext> termContexts;
  protected SpanQuery query;
  protected Similarity.SimWeight stats;

  public SpanWeight(SpanQuery query, IndexSearcher searcher)
    throws IOException {
    this.similarity = searcher.getSimilarity();
    this.query = query;
    
    termContexts = new HashMap<>();
    TreeSet<Term> terms = new TreeSet<>();
    query.extractTerms(terms);
    final IndexReaderContext context = searcher.getTopReaderContext();
    final TermStatistics termStats[] = new TermStatistics[terms.size()];
    int i = 0;
    for (Term term : terms) {
      TermContext state = TermContext.build(context, term);
      termStats[i] = searcher.termStatistics(term, state);
      termContexts.put(term, state);
      i++;
    }
    final String field = query.getField();
    if (field != null) {
      stats = similarity.computeWeight(query.getBoost(), 
                                       searcher.collectionStatistics(query.getField()), 
                                       termStats);
    }
  }

  @Override
  public Query getQuery() { return query; }

  @Override
  public float getValueForNormalization() throws IOException {
    return stats == null ? 1.0f : stats.getValueForNormalization();
  }

  @Override
  public void normalize(float queryNorm, float topLevelBoost) {
    if (stats != null) {
      stats.normalize(queryNorm, topLevelBoost);
    }
  }

  @Override
  public Scorer scorer(AtomicReaderContext context, Bits acceptDocs) throws IOException {
    if (stats == null) {
      return null;
    } else {
      return new SpanScorer(query.getSpans(context, acceptDocs, termContexts), this, similarity.simScorer(stats, context));
    }
  }

  @Override
  public Explanation explain(AtomicReaderContext context, int doc) throws IOException {
    SpanScorer scorer = (SpanScorer) scorer(context, context.reader().getLiveDocs());
    if (scorer != null) {
      int newDoc = scorer.advance(doc);
      if (newDoc == doc) {
        float freq = scorer.sloppyFreq();
        SimScorer docScorer = similarity.simScorer(stats, context);
        ComplexExplanation result = new ComplexExplanation();
        result.setDescription("weight("+getQuery()+" in "+doc+") [" + similarity.getClass().getSimpleName() + "], result of:");
        Explanation scoreExplanation = docScorer.explain(doc, new Explanation(freq, "phraseFreq=" + freq));
        result.addDetail(scoreExplanation);
        result.setValue(scoreExplanation.getValue());
        result.setMatch(true);          
        return result;
      }
    }
    
    return new ComplexExplanation(false, 0.0f, "no matching term");
  }
}
