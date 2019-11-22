/*
 *  Copyright (c) 2003-2019 Broad Institute, Inc., Massachusetts Institute of Technology, and Regents of the University of California.  All rights reserved.
 */
package org.genepattern.modules;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import edu.mit.broad.genome.Conf;
import edu.mit.broad.genome.utils.ZipUtility;
import xtools.api.AbstractTool;
import xtools.gsea.LeadingEdgeTool;

public class LeadingEdgeToolWrapper extends AbstractModule {
    private static final Logger klog = Logger.getLogger(LeadingEdgeToolWrapper.class);
    
    // Suppressing the static-access warnings because this is the recommended usage according to the Commons-CLI docs.
    @SuppressWarnings("static-access")
    private static Options setupCliOptions() {
        Options options = new Options();
        options.addOption(OptionBuilder.withArgName("enrichmentResult").hasArg().create("enrichment_zip"));
        options.addOption(OptionBuilder.withArgName("geneSets").hasArg().create("gsets"));
        options.addOption(OptionBuilder.withArgName("outputFileName").hasArg().create("output_file_name"));
        options.addOption(OptionBuilder.withArgName("imgFormat").hasArg().create("imgFormat"));
        options.addOption(OptionBuilder.withArgName("altDelim").hasArg().create("altDelim"));
        options.addOption(OptionBuilder.withArgName("extraPlots").hasArg().create("extraPlots"));
        options.addOption(OptionBuilder.withArgName("createZip").hasArg().create("zip_report"));
        options.addOption(OptionBuilder.withArgName("outFile").hasArg().create("out"));
        options.addOption(OptionBuilder.withArgName("resultsDir").hasArg().create("dir"));
        options.addOption(OptionBuilder.withArgName("devMode").hasArg().create("dev_mode"));
        options.addOption(OptionBuilder.withArgName("gpModuleMode").hasArg().create("run_as_genepattern"));
        return options;
    }

    public static void main(final String[] args) throws Exception {
        // Success flag. We set this to *false* until proven otherwise by a successful Tool run. This saves having to catch
        // all manner of exceptions along the way; just allow them to propagate to the top-level handler.
        boolean success = false;

        AbstractTool tool = null;

        File analysis = null;
        File tmp_working = null;
        File cwd = null;
        try {
            Options opts = setupCliOptions();
            CommandLineParser parser = new PosixParser();
            CommandLine cl = parser.parse(opts, args);

            // We want to check *all* params before reporting any errors so that the user sees everything that went wrong.
            boolean paramProcessingError = false;

            // Properties object to gather parameter settings to be passed to the Tool
            Properties paramProps = new Properties();

            // The GP modules should declare they are running in GP mode.  This has minor effects on the error messages
            // and runtime behavior.
            boolean gpMode = StringUtils.equalsIgnoreCase(cl.getOptionValue("run_as_genepattern"), "true");
            
            if (gpMode) {
                // Turn off debugging in the GSEA code and tell it not to create directories
                // TODO: confirm the "mkdir" property works as expected
                System.setProperty("debug", "false");
                System.setProperty("mkdir", "false");

                // Set the GSEA update check String to show this is coming from the modules.
                System.setProperty("UPDATE_CHECK_EXTRA_PROJECT_INFO", "GP_MODULES");

                String outOption = cl.getOptionValue("out");
                if (StringUtils.isNotBlank(outOption)) {
                    klog.warn("-out parameter ignored; only valid wih -run_as_genepattern false.");
                }
    
                // Define a working directory, to be cleaned up on exit. The name starts with a '.' so it's hidden from GP & file system.
                // Also, define a dedicated directory for building the report output
                cwd = new File(System.getProperty("user.dir"));
                tmp_working = new File(".tmp_gsea");
                analysis = new File(tmp_working, "analysis");
                analysis.mkdirs();
            } else {
                // Set the GSEA update check String to show this is CLI usage.
                System.setProperty("UPDATE_CHECK_EXTRA_PROJECT_INFO", "GSEA_CLI");
            }
            
            // Enable any developer-only settings. For now, this just disables the update check; may do more in the future
            boolean devMode = StringUtils.equalsIgnoreCase(cl.getOptionValue("dev_mode"), "true");
            if (devMode) {
                System.setProperty("MAKE_GSEA_UPDATE_CHECK", "false");
            }

            final boolean createZip = StringUtils.equalsIgnoreCase(cl.getOptionValue("zip_report"), "true");

            String outputFileName = cl.getOptionValue("output_file_name");
            if (StringUtils.isNotBlank(outputFileName)) {
                if (!gpMode) {
                    klog.warn("-output_file_name parameter ignored; only valid wih -run_as_genepattern true.");
                }
                else if (!outputFileName.toLowerCase().endsWith(".zip")) {
                    outputFileName += ".zip";
                }
            } else {
                if (gpMode) outputFileName = "leading_edge_report.zip";
            }

            // Enrichment resultfile
            String enrichmentResultZip = cl.getOptionValue("enrichment_zip");
            if (StringUtils.isNotBlank(enrichmentResultZip)) {
                if (gpMode) {
                    enrichmentResultZip = copyFileWithoutBadChars(enrichmentResultZip, tmp_working);
                    paramProcessingError |= (enrichmentResultZip == null);
                }
            } else {
                String paramName = (gpMode) ? "enrichment.result.zip.file" : "-enrichment_zip";
                klog.error("Required parameter '" + paramName + "' not found");
                paramProcessingError = true;
            }

            if (paramProcessingError) {
                // Should probably use BadParamException and set an errorCode, use it to look up a Wiki Help page.
                throw new Exception("There were one or more errors with the job parameters.  Please check log output for details.");
            }

            klog.info("Parameters passing to LeadingEdgeTool.main:");
            if (gpMode) {
                setParam("out", analysis.getAbsolutePath(), paramProps, klog);
                String dirOption = cl.getOptionValue("dir");
                if (StringUtils.isNotBlank(dirOption)) {
                        klog.warn("-dir parameter ignored; only valid wih -run_as_genepattern false.");
                }
                
                // Unzip the input file to the working directory
                final File inputExpanded = new File(tmp_working, "inputExpanded");
                final ZipUtility zipUtility = new ZipUtility();
                zipUtility.unzip(new File(enrichmentResultZip), inputExpanded);
                setParam("dir", inputExpanded.getAbsolutePath(), paramProps, klog);
            } else {
                // For regular CLI mode just pass through -out instead of setting tmpdir
                setOptionValueAsParam("out", cl, paramProps, klog);
                setOptionValueAsParam("dir", cl, paramProps, klog);
            }

            // Set up the gene sets. Only add 'gsets' param if the user has provided any.
            // It's fine if none are provided - the underlying Tool will interpret that as
            // a directive to use all of them for the analysis.
            String geneSets = cl.getOptionValue("gsets", "").trim();
            if (StringUtils.isNotBlank(geneSets)) {
                setParam("gsets", geneSets, paramProps, klog);
            }

            String altDelim = cl.getOptionValue("altDelim", "");
            if (StringUtils.isNotBlank(altDelim)) {
                if (altDelim.length() > 1) {
                    String paramName = (gpMode) ? "alt.delim" : "--altDelim";
                    klog.error("Invalid " + paramName + " '" + altDelim
                            + "' specified. This must be only a single character and no whitespace.");
                    paramProcessingError = true;
                } else {
                    setParam("altDelim", altDelim, paramProps, klog);
                }
            }

            // Finally, load up the remaining simple parameters. We'll let LeadingEdgeTool validate these.
            setOptionValueAsParam("imgFormat", cl, paramProps, klog);
            setOptionValueAsParam("extraPlots", cl, paramProps, klog);
            setParam("zip_report", Boolean.toString(createZip), paramProps, klog);

            setParam("gui", "false", paramProps, klog);

            tool = new LeadingEdgeTool(paramProps);
            try {
                success = AbstractTool.module_main(tool);
            } finally {
                if (gpMode) {
                    try {
                        if (!analysis.exists()) return;
                        copyAnalysisToCurrentDir(cwd, analysis, createZip, outputFileName);
                    } catch (IOException ioe) {
                        klog.error("Error during clean-up:");
                        throw ioe;
                    }
                }
            }
        } catch (Throwable t) {
            success = false;
            klog.error("Error while processng:");
            klog.error(t.getMessage());
            t.printStackTrace(System.err);
        } finally {
            try {
                if (cwd != null && tmp_working != null) {
                    cleanUpAnalysisDirs(cwd, tmp_working);
                }
            } finally {
                Conf.exitSystem(!success);
            }
        }
    }
}
