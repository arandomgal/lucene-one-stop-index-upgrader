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
package org.trypticon.luceneupgrader.lucene3.internal.lucene.search;

import org.trypticon.luceneupgrader.lucene3.internal.lucene.index.IndexReader;
import org.trypticon.luceneupgrader.lucene3.internal.lucene.index.Term;
import org.trypticon.luceneupgrader.lucene3.internal.lucene.util.ToStringUtils;

import java.io.IOException;
import java.util.Set;


public class FilteredQuery
extends Query {

  Query query;
  Filter filter;

  public FilteredQuery (Query query, Filter filter) {
    this.query = query;
    this.filter = filter;
  }

  @Override
  public Weight createWeight(final Searcher searcher) throws IOException {
    final Weight weight = query.createWeight (searcher);
    final Similarity similarity = query.getSimilarity(searcher);
    return new Weight() {
      private float value;
        
      // pass these methods through to enclosed query's weight
      @Override
      public float getValue() { return value; }
      
      @Override
      public boolean scoresDocsOutOfOrder() {
        return false;
      }

      public float sumOfSquaredWeights() throws IOException { 
        return weight.sumOfSquaredWeights() * getBoost() * getBoost(); // boost sub-weight
      }

      @Override
      public void normalize (float v) { 
        weight.normalize(v * getBoost()); // incorporate boost
        value = weight.getValue();
      }

      @Override
      public Explanation explain (IndexReader ir, int i) throws IOException {
        Explanation inner = weight.explain (ir, i);
        Filter f = FilteredQuery.this.filter;
        DocIdSet docIdSet = f.getDocIdSet(ir);
        DocIdSetIterator docIdSetIterator = docIdSet == null ? DocIdSet.EMPTY_DOCIDSET.iterator() : docIdSet.iterator();
        if (docIdSetIterator == null) {
          docIdSetIterator = DocIdSet.EMPTY_DOCIDSET.iterator();
        }
        if (docIdSetIterator.advance(i) == i) {
          return inner;
        } else {
          Explanation result = new Explanation
            (0.0f, "failure to match filter: " + f.toString());
          result.addDetail(inner);
          return result;
        }
      }

      // return this query
      @Override
      public Query getQuery() { return FilteredQuery.this; }

      // return a filtering scorer
      @Override
      public Scorer scorer(IndexReader indexReader, boolean scoreDocsInOrder, boolean topScorer) throws IOException {
        // Hackidy-H??ck-Hack for backwards compatibility, as we cannot change IndexSearcher API in 3.x, but still want
        // to move the searchWithFilter implementation to this class: to enable access to our scorer() implementation
        // from IndexSearcher, we moved this method up to the main class. In Lucene trunk,
        // FilteredQuery#getFilteredScorer is inlined here - in 3.x we delegate:
        return FilteredQuery.getFilteredScorer(indexReader, similarity, weight, this, filter);
      }
    };
  }
  

  static Scorer getFilteredScorer(final IndexReader indexReader, final Similarity similarity,
                                  final Weight weight, final Weight wrapperWeight, final Filter filter) throws IOException {
    assert filter != null;

    final DocIdSet filterDocIdSet = filter.getDocIdSet(indexReader);
    if (filterDocIdSet == null) {
      // this means the filter does not accept any documents.
      return null;
    }
    
    final DocIdSetIterator filterIter = filterDocIdSet.iterator();
    if (filterIter == null) {
      // this means the filter does not accept any documents.
      return null;
    }

    // we are gonna advance() this scorer, so we set inorder=true/toplevel=false
    final Scorer scorer = weight.scorer(indexReader, true, false);
    return (scorer == null) ? null : new Scorer(similarity, wrapperWeight) {
      private int scorerDoc = -1, filterDoc = -1;
      
      // optimization: we are topScorer and collect directly using short-circuited algo
      @Override
      public void score(Collector collector) throws IOException {
        int filterDoc = filterIter.nextDoc();
        int scorerDoc = scorer.advance(filterDoc);
        // the normalization trick already applies the boost of this query,
        // so we can use the wrapped scorer directly:
        collector.setScorer(scorer);
        for (;;) {
          if (scorerDoc == filterDoc) {
            // Check if scorer has exhausted, only before collecting.
            if (scorerDoc == DocIdSetIterator.NO_MORE_DOCS) {
              break;
            }
            collector.collect(scorerDoc);
            filterDoc = filterIter.nextDoc();
            scorerDoc = scorer.advance(filterDoc);
          } else if (scorerDoc > filterDoc) {
            filterDoc = filterIter.advance(scorerDoc);
          } else {
            scorerDoc = scorer.advance(filterDoc);
          }
        }
      }
      
      private int advanceToNextCommonDoc() throws IOException {
        for (;;) {
          if (scorerDoc < filterDoc) {
            scorerDoc = scorer.advance(filterDoc);
          } else if (scorerDoc == filterDoc) {
            return scorerDoc;
          } else {
            filterDoc = filterIter.advance(scorerDoc);
          }
        }
      }

      @Override
      public int nextDoc() throws IOException {
        filterDoc = filterIter.nextDoc();
        return advanceToNextCommonDoc();
      }
      
      @Override
      public int advance(int target) throws IOException {
        if (target > filterDoc) {
          filterDoc = filterIter.advance(target);
        }
        return advanceToNextCommonDoc();
      }

      @Override
      public int docID() {
        return scorerDoc;
      }
      
      @Override
      public float score() throws IOException {
        return scorer.score();
      }
    };
  }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    Query rewritten = query.rewrite(reader);
    if (rewritten != query) {
      FilteredQuery clone = (FilteredQuery)this.clone();
      clone.query = rewritten;
      return clone;
    } else {
      return this;
    }
  }

  public Query getQuery() {
    return query;
  }

  public Filter getFilter() {
    return filter;
  }

  // inherit javadoc
  @Override
  public void extractTerms(Set<Term> terms) {
      getQuery().extractTerms(terms);
  }

  @Override
  public String toString (String s) {
    StringBuilder buffer = new StringBuilder();
    buffer.append("filtered(");
    buffer.append(query.toString(s));
    buffer.append(")->");
    buffer.append(filter);
    buffer.append(ToStringUtils.boost(getBoost()));
    return buffer.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof FilteredQuery) {
      FilteredQuery fq = (FilteredQuery) o;
      return (query.equals(fq.query) && filter.equals(fq.filter) && getBoost()==fq.getBoost());
    }
    return false;
  }

  @Override
  public int hashCode() {
    return query.hashCode() ^ filter.hashCode() + Float.floatToRawIntBits(getBoost());
  }
}
