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
package org.trypticon.luceneupgrader.lucene5.internal.lucene.search;


import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.trypticon.luceneupgrader.lucene5.internal.lucene.util.Bits;

class BooleanTopLevelScorers {
  

  static class BoostedScorer extends FilterScorer {
    final float boost;
    
    BoostedScorer(Scorer in, float boost) {
      super(in);
      this.boost = boost;
    }

    @Override
    public float score() throws IOException {
      return in.score() * boost;
    }

    @Override
    public Collection<ChildScorer> getChildren() {
      return Collections.singleton(new ChildScorer(in, "BOOSTED"));
    }
  }

  static class BoostedBulkScorer extends BulkScorer {

    final BulkScorer in;
    final float boost;

    BoostedBulkScorer(BulkScorer scorer, float boost) {
      this.in = scorer;
      this.boost = boost;
    }

    @Override
    public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
      final LeafCollector wrapped = new FilterLeafCollector(collector) {
        @Override
        public void setScorer(Scorer scorer) throws IOException {
          super.setScorer(new BoostedScorer(scorer, boost));
        }
      };
      return in.score(wrapped, acceptDocs, min, max);
    }

    @Override
    public long cost() {
      return in.cost();
    }

  }


  static class CoordinatingConjunctionScorer extends ConjunctionScorer {
    private final float coords[];
    private final int reqCount;
    private final Scorer req;
    private final Scorer opt;
    
    CoordinatingConjunctionScorer(Weight weight, float coords[], Scorer req, int reqCount, Scorer opt) {
      super(weight, Arrays.asList(req, opt), Arrays.asList(req, opt));
      this.coords = coords;
      this.req = req;
      this.reqCount = reqCount;
      this.opt = opt;
    }
    
    @Override
    public float score() throws IOException {
      return (req.score() + opt.score()) * coords[reqCount + opt.freq()];
    }
  }
  

  static class ReqSingleOptScorer extends ReqOptSumScorer {
    // coord factor if just the required part matches
    private final float coordReq;
    // coord factor if both required and optional part matches 
    private final float coordBoth;
    
    public ReqSingleOptScorer(Scorer reqScorer, Scorer optScorer, float coordReq, float coordBoth) {
      super(reqScorer, optScorer);
      this.coordReq = coordReq;
      this.coordBoth = coordBoth;
    }

    @Override
    public float score() throws IOException {
      // TODO: sum into a double and cast to float if we ever send required clauses to BS1
      int curDoc = reqScorer.docID();
      float score = reqScorer.score();

      int optScorerDoc = optIterator.docID();
      if (optScorerDoc < curDoc) {
        optScorerDoc = optIterator.advance(curDoc);
      }
      
      if (optScorerDoc == curDoc) {
        score = (score + optScorer.score()) * coordBoth;
      } else {
        score = score * coordReq;
      }
      
      return score;
    }
  }


  static class ReqMultiOptScorer extends ReqOptSumScorer {
    private final int requiredCount;
    private final float coords[];
    
    public ReqMultiOptScorer(Scorer reqScorer, Scorer optScorer, int requiredCount, float coords[]) {
      super(reqScorer, optScorer);
      this.requiredCount = requiredCount;
      this.coords = coords;
    }

    @Override
    public float score() throws IOException {
      // TODO: sum into a double and cast to float if we ever send required clauses to BS1
      int curDoc = reqScorer.docID();
      float score = reqScorer.score();

      int optScorerDoc = optIterator.docID();
      if (optScorerDoc < curDoc) {
        optScorerDoc = optIterator.advance(curDoc);
      }
      
      if (optScorerDoc == curDoc) {
        score = (score + optScorer.score()) * coords[requiredCount + optScorer.freq()];
      } else {
        score = score * coords[requiredCount];
      }
      
      return score;
    }
  }
}