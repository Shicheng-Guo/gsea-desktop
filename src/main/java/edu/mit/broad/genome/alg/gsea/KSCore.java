/*
 * Copyright (c) 2003-2019 Broad Institute, Inc., Massachusetts Institute of Technology, and Regents of the University of California.  All rights reserved.
 */
package edu.mit.broad.genome.alg.gsea;

import java.util.Arrays;

import edu.mit.broad.genome.math.Vector;
import edu.mit.broad.genome.math.XMath;
import edu.mit.broad.genome.objects.RankedList;
import edu.mit.broad.genome.objects.esmatrix.db.EnrichmentScore;
import gnu.trove.TFloatArrayList;

/**
 * This is the core class that implements the Kolmogorov-Smirnov algorithm
 *
 * @author Aravind Subramanian
 */
public class KSCore {

    /**
     * Class Constructor.
     * Stateless
     */
    public KSCore() {
    }

    // The common (gsea) way
    public EnrichmentScore[] calculateKSScore(final GeneSetCohort gcoh,
                                              final boolean storeDeep) {

        EnrichmentScoreCohort[] cohorts = calculateKSScore_all_modes(gcoh, storeDeep);
        EnrichmentScore[] ess = new EnrichmentScore[cohorts.length];
        for (int i = 0; i < ess.length; i++) {
            ess[i] = cohorts[i].es_maxdev_style;
        }

        return ess;
    }

    // Justin Guinneys implementation
    private EnrichmentScoreCohort[] calculateKSScore_all_modes(final GeneSetCohort gcoh,
                                                               final boolean storeDeep) {
        if (gcoh == null) {
            throw new IllegalArgumentException("Param gcoh cannot be null");
        }

        TFloatArrayList[] scoresAtEachHitIndex = null;
        final int numGeneSets = gcoh.getNumGeneSets();
        
        final int[] rankAtMaxEs = new int[numGeneSets];
        final float[] scoresAtMaxEs = new float[numGeneSets];
        if (storeDeep) {
            scoresAtEachHitIndex = new TFloatArrayList[numGeneSets];
            // init
            for (int g = 0; g < numGeneSets; g++) {
                scoresAtEachHitIndex[g] = new TFloatArrayList();
            }
        }

        // Also store every point for nicer es plots when run from memory
        Vector[] scoresAtEachPoint = new Vector[numGeneSets];
        // init
        int numLabels = gcoh.getNumLabels();
        for (int g = 0; g < numGeneSets; g++) {
            scoresAtEachPoint[g] = new Vector(numLabels);
        }

        // always filled as needed by mann whitney (even if it isnt saved after here)
        final HitIndices[] hitIndices = new HitIndices[numGeneSets];
        for (int g = 0; g < numGeneSets; g++) {
            //log.debug("gene set: " + gcoh.getGeneSet(g).getName() + " numTrue: " + gcoh.getNumTrue(g) + " numMembers: " + gcoh.getGeneSet(g).getNumMembers());
            hitIndices[g] = new HitIndices(gcoh.getNumTrue(g));
        }

        final float[] ess_maxdev = new float[numGeneSets];
        final double[] runningScores = new double[numGeneSets];
        final int[] hitCnt = new int[numGeneSets];

        final float[] ess_pos_list = new float[numGeneSets];
        
        final int[] rankAtMaxEs_pos_list = new int[numGeneSets];
        final float[] scoresAtMaxEs_pos_list = new float[numGeneSets];

        final float[] ess_pos_list_maxdev = new float[numGeneSets];
        final int[] rankAtMaxEs_pos_list_maxdev = new int[numGeneSets];
        final float[] scoresAtMaxEs_pos_list_maxdev = new float[numGeneSets];

        final float[] ess_neg_list = new float[numGeneSets];
        final int[] rankAtMaxEs_neg_list = new int[numGeneSets];
        final float[] scoresAtMaxEs_neg_list = new float[numGeneSets];

        final float[] ess_neg_list_maxdev = new float[numGeneSets];
        final int[] rankAtMaxEs_neg_list_maxdev = new int[numGeneSets];
        final float[] scoresAtMaxEs_neg_list_maxdev = new float[numGeneSets];

        final int[] genesetJumps = new int[numGeneSets];
        Arrays.fill(genesetJumps, -1);

        final RankedList rl = gcoh.getRankedList();

        // START JG CHANGES //
        // Note that this following loop has been found to be very important to overall
        // performance, so be very careful about changes here.
        
        final int rlSize = rl.getSize();
        for (int r = 0; r < rlSize; r++) {

            final boolean isLastRun = r == (rlSize - 1);

            String rowName = rl.getRankName(r);
            final float corr = rl.getScore(r);
            boolean posList = XMath.isPositive(corr);

            int[] genesetIndices = null;
            if (isLastRun) {
                // for the last run, we want to iterate over all genesets
                genesetIndices = new int[numGeneSets];
                for (int i = 0; i < genesetIndices.length; ++i) {
                    genesetIndices[i] = i;
                }
            } else {
                // otherwise, we only care about those genesets associated with this gene
                genesetIndices = gcoh.genesetIndicesForGene(rowName);
            }

            // gene not in any geneset
            if (genesetIndices == null) {
                continue;
            }

            for (int i = 0; i < genesetIndices.length; i++) {
                int g = genesetIndices[i];
                int gap = r - genesetJumps[g] - 1;
                if (gap > 0) {
                    double missPoints = gcoh.getMissPoints(g, rowName);

                    // backfill - this typically only happens on 'real', not permutations
                    double trun = runningScores[g];
                    for (int j = genesetJumps[g] + 1; j < r; j++) {
                        trun -= missPoints;
                        scoresAtEachPoint[g].setElement(j, trun);
                    }

                    runningScores[g] -= gap * missPoints;

                    if (Math.abs(ess_maxdev[g]) < Math.abs(runningScores[g])) { // @note abs here
                        ess_maxdev[g] = (float) runningScores[g]; // @note no abs here!
                        rankAtMaxEs[g] = r - 1; // @note the -1
                        scoresAtMaxEs[g] = rl.getScore(r - 1); // @note the -1 effective when the score is NEG
                    }
                }

                if (isLastRun && !gcoh.isMember(i, rowName)) {
                    runningScores[g] -= gcoh.getMissPoints(g, rowName);
                } else {
                    genesetJumps[g] = r;
                    double sr = gcoh.getHitPoints(g, rowName);
                    if (Double.isNaN(sr) || Double.isInfinite(sr)) { // does this if the total weight is also 0
                        sr = 0.000001d;
                    }

                    runningScores[g] += sr;

                    hitIndices[g].hitsIndices[hitCnt[g]++] = r;

                    if (storeDeep) {// Only store for hits
                        scoresAtEachHitIndex[g].add((float) runningScores[g]);
                    }

                } // End last run loop

                // END JG CHANGES //

                // @note OUTside the loop
                scoresAtEachPoint[g].setElement(r, (float) runningScores[g]);

                if (Math.abs(ess_maxdev[g]) < Math.abs(runningScores[g])) { // @note abs here
                    ess_maxdev[g] = (float) runningScores[g]; // @note no abs here!
                    rankAtMaxEs[g] = r;
                    scoresAtMaxEs[g] = corr;
                }

                // Then the by pos/neg list stuff
                // @note NO abs in some cases - we want max POS (or NEG) deviations from zero
                if (posList) {
                    if (ess_pos_list[g] < runningScores[g]) { // @note NO abs
                        ess_pos_list[g] = (float) runningScores[g];
                        rankAtMaxEs_pos_list[g] = r;
                        scoresAtMaxEs_pos_list[g] = corr;
                    }

                    if (Math.abs(ess_pos_list_maxdev[g]) < Math.abs(runningScores[g])) { // @note YES abs
                        ess_pos_list_maxdev[g] = (float) runningScores[g];
                        rankAtMaxEs_pos_list_maxdev[g] = r;
                        scoresAtMaxEs_pos_list_maxdev[g] = corr;
                    }

                } else {
                    if (ess_neg_list[g] > runningScores[g]) { // Note NO abs
                        ess_neg_list[g] = (float) runningScores[g];
                        rankAtMaxEs_neg_list[g] = r;
                        scoresAtMaxEs_neg_list[g] = corr;
                    }

                    if (Math.abs(ess_neg_list_maxdev[g]) < Math.abs(runningScores[g])) { // @note abs
                        ess_neg_list_maxdev[g] = (float) runningScores[g];
                        rankAtMaxEs_neg_list_maxdev[g] = r;
                        scoresAtMaxEs_neg_list_maxdev[g] = corr;
                    }
                }
            }
        }


        final EnrichmentScoreCohort[] cohorts = new EnrichmentScoreCohort[numGeneSets];

        for (int g = 0; g < numGeneSets; g++) {
            final float mw = (float) XMath.mannWhitney(hitIndices[g].hitsIndices, rlSize);

            cohorts[g] = new EnrichmentScoreCohort();
            cohorts[g].es_maxdev_style = new EnrichmentScoreImplSlim(ess_maxdev[g], rankAtMaxEs[g], scoresAtMaxEs[g], cohorts[g]);
            cohorts[g].es_on_pos_list = new EnrichmentScoreImplSlim(ess_pos_list[g], rankAtMaxEs_pos_list[g], scoresAtMaxEs_pos_list[g], cohorts[g]);
            cohorts[g].es_on_pos_list_maxdev = new EnrichmentScoreImplSlim(ess_pos_list_maxdev[g], rankAtMaxEs_pos_list_maxdev[g], scoresAtMaxEs_pos_list_maxdev[g], cohorts[g]);
            cohorts[g].es_on_neg_list = new EnrichmentScoreImplSlim(ess_neg_list[g], rankAtMaxEs_neg_list[g], scoresAtMaxEs_neg_list[g], cohorts[g]);
            cohorts[g].es_on_neg_list_maxdev = new EnrichmentScoreImplSlim(ess_neg_list_maxdev[g], rankAtMaxEs_neg_list_maxdev[g], scoresAtMaxEs_neg_list_maxdev[g], cohorts[g]);

            cohorts[g].mw = mw;
            cohorts[g].numHits = gcoh.getNumTrue(g);

            if (storeDeep) {
                cohorts[g].fHitIndices_opt = hitIndices[g].hitsIndices;
                cohorts[g].scoresAtEachHitIndex_opt = new Vector(scoresAtEachHitIndex[g]);
            }

            cohorts[g].scoresAtEachPoint_opt = scoresAtEachPoint[g]; //new Vector(scoresAtEachPoint[g]);

        }

        return cohorts;
    }


    protected static class HitIndices {
        int[] hitsIndices;

        HitIndices(int len) {
            this.hitsIndices = new int[len];
        }

    }

    private static class EnrichmentScoreCohort {

        private EnrichmentScore es_maxdev_style;

        private EnrichmentScore es_on_pos_list_maxdev;
        private EnrichmentScore es_on_pos_list;

        private EnrichmentScore es_on_neg_list;
        private EnrichmentScore es_on_neg_list_maxdev;

        private float mw;

        private int numHits;

        // @note deep only
        private int[] fHitIndices_opt;

        private Vector scoresAtEachHitIndex_opt;

        // @note this is redundant (but has more info) with the scoresAtEachHitIndex_opt var
        // usefil for richer plots
        private Vector scoresAtEachPoint_opt;

        private boolean hasDeepInfo() {
            return scoresAtEachHitIndex_opt != null;
        }

        private Vector getESProfile() {
            ensureDeep();
            scoresAtEachHitIndex_opt.setImmutable();
            return scoresAtEachHitIndex_opt;
        }

        private Vector getESProfile_full() {
            ensureDeep();
            scoresAtEachPoint_opt.setImmutable();
            return scoresAtEachPoint_opt;
        }

        private int getNumHits() {
            return numHits;
        }

        private int[] getHitIndices() {
            ensureDeep();
            return fHitIndices_opt;
        }

        private void ensureDeep() {
            if (!hasDeepInfo()) {
                throw new IllegalStateException("EnrichmentScore was NOT consructed in deep mode -- info not available");
            }
        }
    }

    /**
     * Inner class to represent one result
     * NO perms done so NO etc are not applicable
     */
    static class EnrichmentScoreImplSlim implements EnrichmentScore {

        private float fES;
        private int fRankAtES;
        private float fRankScoreAtES;

        private EnrichmentScoreCohort fScoreCoh;

        /**
         * Class constructor
         *
         * @param es
         * @param rankAtEs
         * @param corrAtEs
         * @param nes
         * @param np
         * @param fdr
         * @param fwer
         * @param hitIndices_opt
         * @param runningRaw_opt
         */
        EnrichmentScoreImplSlim(final float es,
                                final int rankAtEs,
                                final float corrAtEs,
                                final EnrichmentScoreCohort coh
        ) {
            this.fES = es;
            this.fRankAtES = rankAtEs;
            this.fRankScoreAtES = corrAtEs;

            // @note shared
            this.fScoreCoh = coh;
        }

        public float getES() {
            return fES;
        }

        public int getRankAtES() {
            return fRankAtES;
        }

        public float getRankScoreAtES() {
            return fRankScoreAtES;
        }

        public float getNES() {
            return Float.NaN;
        }

        public float getNP() {
            return Float.NaN;
        }

        public float getFWER() {
            return Float.NaN;
        }

        public float getFDR() {
            return Float.NaN;
        }

        // @note change done in march 2006 to get nicer plots
        public Vector getESProfile() {
            return fScoreCoh.getESProfile();
        }

        public Vector getESProfile_point_by_point_opt() {
            return fScoreCoh.getESProfile_full();
        }

        public int getNumHits() {
            return fScoreCoh.getNumHits();
        }

        public int[] getHitIndices() {
            return fScoreCoh.getHitIndices();
        }

    } // End inner class EnrichmentScoreImplSlim


} // End KSCore

