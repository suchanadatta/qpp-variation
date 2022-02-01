/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.feedback;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.trec.TRECQuery;
import java.util.*;
import java.io.IOException;

/**
 *
 * @author Debasis
 */

class KLDivScoreComparator implements Comparator<ScoreDoc> {

    @Override
    public int compare(ScoreDoc a, ScoreDoc b) {
        return a.score < b.score? -1 : a.score == b.score? 0 : 1;
    }    
}

public class RelevanceModelIId {
    TRECQuery trecQuery;
    TopDocs topDocs;
    float mixingLambda;
    int numTopDocs;
    RetrievedDocsTermStats retrievedDocsTermStats;
    float fbweight;
    IndexReader reader;
    IndexSearcher searcher;

    static final float TERM_SEL_DF_THRESH = 0.8f;
    static final float MIXING_LAMBDA = 0.8f;
    static final float FBWEIGHT = 0.2f;


    public RelevanceModelIId(IndexSearcher searcher, TRECQuery trecQuery, TopDocs topDocs, int numTopDocs) {
        this.reader = searcher.getIndexReader();
        this.searcher = searcher;
        this.trecQuery = trecQuery;

        this.topDocs = topDocs;
        this.numTopDocs = numTopDocs;
    }
    
    public RetrievedDocsTermStats getRetrievedDocsTermStats() {
        return this.retrievedDocsTermStats;
    }
    
    public void buildTermStats() throws Exception {
        retrievedDocsTermStats = new
                RetrievedDocsTermStats(reader, topDocs, numTopDocs);
        retrievedDocsTermStats.buildAllStats();
        reader = retrievedDocsTermStats.getReader();
    }
    
    float mixTfIdf(RetrievedDocTermInfo w) {
        return MIXING_LAMBDA *w.getTf()/(float)retrievedDocsTermStats.sumTf +
                (1- MIXING_LAMBDA)*w.getDf()/retrievedDocsTermStats.sumDf;
    }

    float mixTfIdf(RetrievedDocTermInfo w, PerDocTermVector docvec) {
        RetrievedDocTermInfo wGlobalInfo = retrievedDocsTermStats.termStats.get(w.getTerm());
        return mixingLambda*w.getTf()/(float)docvec.sum_tf +
                (1-mixingLambda)*wGlobalInfo.getDf()/retrievedDocsTermStats.sumDf;
    }

    public void computeFdbkWeights() throws Exception {
        float p_q;
        float p_w;
        
        buildTermStats();
        
        /* For each w \in V (vocab of top docs),
         * compute f(w) = \sum_{q \in qwvecs} K(w,q) */
        for (Map.Entry<String, RetrievedDocTermInfo> e : retrievedDocsTermStats.termStats.entrySet()) {
            float total_p_q = 0;
            RetrievedDocTermInfo w = e.getValue();
            p_w = mixTfIdf(w);
            
            Set<Term> qTerms = this.trecQuery.getQueryTerms(searcher);
            for (Term qTerm : qTerms) {
                
                // Get query term frequency
                RetrievedDocTermInfo qtermInfo = retrievedDocsTermStats.getTermStats(qTerm.toString());
                if (qtermInfo == null) {
                    System.err.println("No KDE for query term: " + qTerm.toString());
                    continue;
                }
                p_q = qtermInfo.getTf()/(float)retrievedDocsTermStats.sumTf;
                
                total_p_q += Math.log(1+p_q);
            }
            w.setWeight(p_w * (float)Math.exp(total_p_q-1));
        }
    }
    
    public float getQueryClarity() {
        float klDiv = 0;
        // For each v \in V (vocab of top ranked documents)
        for (RetrievedDocTermInfo w: retrievedDocsTermStats.getTermStats().values()) {
            float p_w_C = w.getDf()/retrievedDocsTermStats.sumDf;
            klDiv += w.getWeight() * Math.log(w.getWeight()/p_w_C);
        }
        return klDiv;
    }

    public TopDocs rerankDocs() {
        ScoreDoc[] klDivScoreDocs = new ScoreDoc[this.topDocs.scoreDocs.length];
        float klDiv;
        float p_w_D;    // P(w|D) for this doc D
        final float EPSILON = 0.0001f;

        // For each document
        for (int i = 0; i < topDocs.scoreDocs.length; i++) {
            klDiv = 0;
            klDivScoreDocs[i] = new ScoreDoc(topDocs.scoreDocs[i].doc, klDiv);
            PerDocTermVector docVector = this.retrievedDocsTermStats.docTermVecs.get(i);

            // For each v \in V (vocab of top ranked documents)
            for (Map.Entry<String, RetrievedDocTermInfo> e : retrievedDocsTermStats.termStats.entrySet()) {
                RetrievedDocTermInfo w = e.getValue();

                float ntf = docVector.getNormalizedTf(w.getTerm());
                if (ntf == 0)
                    ntf = EPSILON;
                p_w_D = ntf;
                klDiv += w.getWeight() * Math.log(w.getWeight()/p_w_D);
            }
            klDivScoreDocs[i].score = klDiv;
        }

        // Sort the scoredocs in ascending order of the KL-Div scores
        Arrays.sort(klDivScoreDocs, new KLDivScoreComparator());
        //+++LUCENE_COMPATIBILITY: Sad there's no #ifdef like C!
        // 8.x CODE
        TopDocs rerankedDocs = new TopDocs(topDocs.totalHits, klDivScoreDocs);
        // 5.x CODE
        //TopDocs rerankedDocs = new TopDocs(topDocs.totalHits, klDivScoreDocs, klDivScoreDocs[0].score);
        //---LUCENE_COMPATIBILITY
        return rerankedDocs;
    }

}
