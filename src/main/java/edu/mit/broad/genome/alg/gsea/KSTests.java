/*
 * Copyright (c) 2003-2020 Broad Institute, Inc., Massachusetts Institute of Technology, and Regents of the University of California.  All rights reserved.
 */
package edu.mit.broad.genome.alg.gsea;

import edu.mit.broad.genome.MismatchedSizeException;
import edu.mit.broad.genome.NamingConventions;
import edu.mit.broad.genome.alg.*;
import edu.mit.broad.genome.alg.markers.PermutationTest;
import edu.mit.broad.genome.math.*;
import edu.mit.broad.genome.objects.*;
import edu.mit.broad.genome.objects.esmatrix.db.*;
import edu.mit.broad.genome.objects.strucs.DatasetTemplate;
import edu.mit.broad.genome.objects.strucs.TemplateRandomizerType;
import edu.mit.broad.vdb.chip.Chip;
import org.apache.log4j.Logger;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kolmogorov-Smirnov Enrichment Test related methods
 * <p/>
 * Many things here are specific to gsea;
 *
 * @author Aravind Subramanian
 * @see KSCore
 */
public class KSTests {

    private final Logger log = Logger.getLogger(KSTests.class);

    private final KSCore core;

    private static final int LOG_FREQ = 5;

    private PrintStream sout;

    /**
     * Class Constructor.
     * Almost Stateless
     * os -> for quick stdout NOT for logging
     */
    public KSTests(final PrintStream os) {
        this.sout = os;
        this.core = new KSCore();
    }

    public EnrichmentDb executeGsea(final DatasetTemplate dt, final GeneSet[] gsets, final int nperm, final Metric metric,
    		final SortMode sort, final Order order, final RandomSeedGenerator rst, final TemplateRandomizerType rt, 
    		final Map<String, Boolean> mps, final GeneSetCohort.Generator gcohgen, final boolean permuteTemplate, 
    		final int numMarkers, final List<RankedList> store_rnd_ranked_lists_here_opt) throws Exception {
        final Dataset ds = dt.getDataset(false);
        final Template t = dt.getTemplate();
		log.debug("!!!! Executing for: " + ds.getName() + " # samples: " + ds.getNumCol());
		
		try {
			if (permuteTemplate) {
			    return shuffleTemplate(nperm, metric, sort, order, mps, 
			    		ds, t, gsets, gcohgen, rt, rst, numMarkers, store_rnd_ranked_lists_here_opt);
			} else {
			    return shuffleGeneSet(nperm, metric, sort, order, mps, ds, t, gsets, gcohgen, rst);
			}
		}
		finally {
			log.info("Finished permutations ... creating reports");
		}
    }

    public EnrichmentDb executeGsea(final RankedList rl_real, final GeneSet[] gsets, final int nperm, 
    		final RandomSeedGenerator rst, final Chip chip,final GeneSetCohort.Generator gcohgen) throws Exception {
        log.debug("!!!! Executing for: " + rl_real.getName() + " # features: " + rl_real.getSize());

        EnrichmentResult[] results = shuffleGeneSet_precannedRankedList(nperm, rl_real, null, gsets, chip, gcohgen, rst);
        return new EnrichmentDb(rl_real.getName(),
                rl_real, null, null, results, new Metrics.None(), new HashMap<String, Boolean>(), 
                SortMode.REAL, Order.DESCENDING, nperm, null, null);
    }

    // ------------------------------------------------------------------------ //
    // --------------------------- TEMPLATE CALCULATIONS ----------------------//
    // ------------------------------------------------------------------------ //
    private EnrichmentDb shuffleTemplate(final int nperm, final Metric metric, final SortMode sort, final Order order,
    		final Map<String, Boolean> metricParams, final Dataset ds, final Template template, final GeneSet[] gsets, 
    		final GeneSetCohort.Generator gcohgen, final TemplateRandomizerType rt, final RandomSeedGenerator rst, 
    		final int numMarkers, final List<RankedList> store_rnd_ranked_lists_here_opt) {
        final Template[] rndTemplates = TemplateFactoryRandomizer.createRandomTemplates(nperm, template, rt, rst);
        log.debug("Done generating rnd templates: " + rndTemplates.length);

        log.debug("shuffleTemplate with -- nperm: " + rndTemplates.length + " Order: " + order + " Sort: " + sort + " gsets: " + gsets.length);
        final String dstName = NamingConventions.generateName(ds, template, true);
        final Chip chip = ds.getAnnot().getChip();

        final DatasetMetrics dm = new DatasetMetrics();
        final RankedList rlReal;
        PermutationTest ptest = new PermutationTest(dstName, numMarkers, rndTemplates.length, 
                metric, sort, order, metricParams, ds, template, null, template.isCategorical());

        rlReal = dm.scoreDataset(metric, sort, order, metricParams, ds, template);

        // calc real scores
        if (rlReal.getSize() != ds.getNumRow()) {// sanity check
            throw new MismatchedSizeException();
        }

        final GeneSetCohort gcoh = gcohgen.createGeneSetCohort(rlReal, gsets, true); // @note ASSUME already qualified
        final EnrichmentScore[] realScores = core.calculateKSScore(gcoh, true); // need to store details as we need the hit indices
        final Vector[] rndEss = new Vector[gsets.length];
        for (int g = 0; g < gsets.length; g++) {
            rndEss[g] = new Vector(rndTemplates.length);
        }

        // Each row is a "geneset", and each column a randomization
        for (int c = 0; c < rndTemplates.length; c++) {
            final RankedList rndRl = dm.scoreDataset(metric, sort, order, metricParams, ds, rndTemplates[c]);

            if (store_rnd_ranked_lists_here_opt != null) {
                store_rnd_ranked_lists_here_opt.add(rndRl);
            }

        	// TODO: eval for performance.
        	// Could use sout.print() instead, to avoid String concat.  Could also try to avoid the modulo call:
        	//   int nextLogPoint = LOG_FREQ; // outside loop
        	//   if (c == nextLogPoint) { // inside loop
        	//      // print message
        	//      nextLogPoint += LOG_FREQ
        	//   }
            if (c % LOG_FREQ == 0) {
                StringBuffer ib = new StringBuffer("Iteration: ").append(c + 1).append('/').append(rndTemplates.length);
                ib.append(" for ").append(dstName);
                //sout.println(ib.toString());    // dont use log!
                System.out.println(ib.toString());
            }

            // DO THE RND CALC
            // @note better to just clone the existing real gcoh rather than generate a whole new one
            // as only the ranked list has changed and not the feature or gene set content
            final GeneSetCohort gcohRnd = gcohgen.createGeneSetCohort(rndRl, gsets, false);
            //System.out.println("starting calc: " + gcoh.getNumGeneSets());
            final EnrichmentScore[] rndScores = core.calculateKSScore(gcohRnd, false);
            //System.out.println("done calc");

            for (int g = 0; g < gsets.length; g++) {
                rndEss[g].setElement(c, rndScores[g].getES());
            }

            ptest.addRnd(rndTemplates[c], rndRl);

        } // End computation loop

        // 1 result for every gene set
        final EnrichmentResult[] results = new EnrichmentResult[gsets.length];
        for (int g = 0; g < gsets.length; g++) {
            results[g] = new EnrichmentResult(rlReal, template, gsets[g], chip, realScores[g], rndEss[g], null);
        }

        ptest.doCalc();

        return new EnrichmentDb(dstName, rlReal, ds, template,
                results, metric, metricParams, sort, order, rndTemplates.length, null, ptest);
    }

    // ------------------------------------------------------------------------ //
    // -------------------------------- GENE TAG CALCULATIONS ------------------//
    // ------------------------------------------------------------------------ //

    // this is the CORE method
    private EnrichmentResult[] shuffleGeneSet_precannedRankedList(final int nperm, final RankedList rlReal, 
    		final Template t_opt, final GeneSet[] gsetsReal, final Chip chip_opt, final GeneSetCohort.Generator gcohgen,
    		final RandomSeedGenerator rst) {

        final EnrichmentResult[] results = new EnrichmentResult[gsetsReal.length];
        final GeneSetCohort gcohReal = gcohgen.createGeneSetCohort(rlReal, gsetsReal, true);

        final EnrichmentScore[] real_scores = core.calculateKSScore(gcohReal, true); // @note usually always store deep for the real one

        // The make rnd gene sets for every real one
        for (int g = 0; g < gsetsReal.length; g++) {
        	// TODO: eval for performance.
        	// Could use sout.print() instead, to avoid String concat.  Could also try to avoid the modulo call:
        	//   int nextLogPoint = LOG_FREQ; // outside loop
        	//   if (g == nextLogPoint) { // inside loop
        	//      // print message
        	//      nextLogPoint += LOG_FREQ
        	//   }
            if (g % LOG_FREQ == 0) {
                sout.println("shuffleGeneSet for GeneSet " + (g + 1) + "/" + gsetsReal.length + " nperm: " + nperm);
            }

            // now create random GeneSets and calc the ksscore for every rnd GeneSet
            Vector rndEss;
            if (nperm > 0) {
                final GeneSet[] rndgsets = GeneSetGenerators.createRandomGeneSetsFixedSize(nperm, rlReal, gsetsReal[g], rst);
                final GeneSetCohort gcohRnd = gcohReal.clone(rndgsets);
                rndEss = new Vector(rndgsets.length);
                final EnrichmentScore[] rnds = core.calculateKSScore(gcohRnd, false); // never store deep for rnds
                for (int r = 0; r < rndgsets.length; r++) {
                    rndEss.setElement(r, rnds[r].getES());
                }
            } else {
                rndEss = new Vector(0);
            }

            results[g] = new EnrichmentResult(rlReal, t_opt, gsetsReal[g], chip_opt, real_scores[g], rndEss, null);
        }

        return results;
    }

    private EnrichmentDb shuffleGeneSet(final int nperm, final Metric metric, final SortMode sort, final Order order,
    		final Map<String, Boolean> metricParams, final Dataset ds, final Template template, final GeneSet[] gsets, 
    		final GeneSetCohort.Generator gen, final RandomSeedGenerator rst) {
        if (ds == null) {
            throw new IllegalArgumentException("Param ds cannot be null");
        }

        // The same (real template) scored dataset for all gsets
        final DatasetMetrics dm = new DatasetMetrics();
        final ScoredDataset rlReal = dm.scoreDataset(metric, sort, order, metricParams, ds, template);
        final Chip chip = ds.getAnnot().getChip();

        final EnrichmentResult[] results = shuffleGeneSet_precannedRankedList(nperm,
                rlReal, template, gsets, chip, gen, rst);

        final String name = NamingConventions.generateName(ds, template, true);
        return new EnrichmentDb(name, rlReal, ds, template,
                results, metric, metricParams, sort, order, nperm, null, null);
    }
}