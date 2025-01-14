package mkTails.main;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import mkTails.algorithms.Bisimulation;
import mkTails.invariants.BinaryInvariant;
import mkTails.invariants.ITemporalInvariant;
import mkTails.invariants.TemporalInvariantSet;
import mkTails.invariants.concurrency.NeverConcurrentInvariant;
import mkTails.invariants.constraints.TempConstrainedInvariant;
import mkTails.invariants.miners.ChainWalkingTOInvMiner;
import mkTails.invariants.miners.DAGWalkingPOInvMiner;
import mkTails.invariants.miners.IPOInvariantMiner;
import mkTails.invariants.miners.ITOInvariantMiner;
import mkTails.invariants.miners.TransitiveClosureInvMiner;
import mkTails.main.options.AbstractOptions;
import mkTails.main.options.Options;
import mkTails.main.parser.ParseException;
import mkTails.main.parser.TraceParser;
import mkTails.model.ChainsTraceGraph;
import mkTails.model.DAGsTraceGraph;
import mkTails.model.EventNode;
import mkTails.model.PartitionGraph;
import mkTails.model.Trace;
import mkTails.model.Transition;
import mkTails.model.event.Event;
import mkTails.model.event.EventType;
import mkTails.model.export.DotExportFormatter;
import mkTails.model.export.GmlExportFormatter;
import mkTails.model.export.GraphExportFormatter;
import mkTails.model.export.GraphExporter;
import mkTails.model.export.JsonExporter;
import mkTails.model.export.LtsExporter;
import mkTails.model.interfaces.IGraph;
import mkTails.model.interfaces.INode;
import mkTails.model.interfaces.IRelationPath;
import mkTails.model.testgeneration.AbstractTestCase;
import mkTails.tests.SynopticLibTest;
import mkTails.util.BriefLogFormatter;
import mkTails.util.SynopticJar;
import mkTails.util.time.ITime;

/**
 * Contains entry points for the command line version of Synoptic or a
 * derivative project. The AbstractMain instance is a singleton that maintains
 * options and other state for a single run of some Main.
 */
public abstract class AbstractMain {
    public static Logger logger = null;

    /**
     * Singleton instance of this class.
     */
    public static AbstractMain instance = null;

    /**
     * Global source of pseudo-random numbers.
     */
    public Random random;

    /**
     * Formatter to use for exporting graphs (DOT/GML formatter).
     */
    public GraphExportFormatter graphExportFormatter = null;

    /**
     * Options parsed from the command line or set in some other way.
     */
    public AbstractOptions options = null;

    /**
     * The instance of either SynopticOptions or PerfumeOptions from which plume
     * methods can be called and from which the AbstractOptions object above
     * (options) was created
     */
    public static Options plumeOpts = null;

    /**
     * Return the singleton instance of AbstractMain, first asserting that the
     * instance isn't null.
     */
    public static AbstractMain getInstance() {
        assert (instance != null);
        return instance;
    }

    /**
     * Uses the parsed opts to set up static state in Main. This state includes
     * everything necessary to run Synoptic -- input log files, regular
     * expressions, etc.
     * 
     * @param options
     *            Parsed command line arguments.
     * @return
     * @throws IOException
     * @throws URISyntaxException
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     * @throws ParseException
     */
    public static GraphExportFormatter processArgs(AbstractOptions options)
            throws IOException, URISyntaxException, IllegalArgumentException,
            IllegalAccessException {

        setUpLogging(options);

        // Display help for all option groups, including unpublicized ones
        if (options.allHelp) {
            options.printLongHelp();
            return null;
        }

        // Display help just for the 'publicized' option groups
        if (options.help) {
            AbstractOptions.plumeOpts.printShortHelp();
            return null;
        }

        if (options.version) {
            String changesetID = SynopticJar.getHgChangesetID();
            if (changesetID != null) {
                System.out.println("Synoptic repo changeset " + changesetID);
            } else {
                System.out.println("Synoptic repo changeset not available.");
            }
            return null;
        }

        // Setup the appropriate graph export formatter object.
        GraphExportFormatter graphExportFormatter;
        if (options.exportAsGML) {
            graphExportFormatter = new GmlExportFormatter();
        } else {
            graphExportFormatter = new DotExportFormatter();
        }

        if (options.runAllTests) {
            List<String> testClasses = SynopticJar
                    .getTestsInPackage("mkTails.tests.units.");
            testClasses.addAll(SynopticJar
                    .getTestsInPackage("mkTails.tests.integration."));
            SynopticLibTest.runTests(testClasses);
            // Terminate after we are done running tests.
            return null;
        } else if (options.runTests) {
            List<String> testClassesUnits = SynopticJar
                    .getTestsInPackage("mkTails.tests.units.");
            SynopticLibTest.runTests(testClassesUnits);
            // Terminate after we are done running tests.
            return null;
        }

        if (AbstractOptions.plumeOpts.logFilenames.size() == 0) {
            logger.severe("No log filenames specified, exiting. Specify log files at the end of the command line with no options.");
            return null;
        }

        if (options.dumpIntermediateStages
                && AbstractOptions.outputPathPrefix == null) {
            logger.severe("Cannot dump intermediate stages without an output path prefix. Set this prefix with:\n\t"
                    + AbstractOptions.plumeOpts.getOptDesc("outputPathPrefix"));
            return null;
        }

        if (options.logLvlVerbose || options.logLvlExtraVerbose) {
            AbstractOptions.plumeOpts.printOptionValues();
        }

        return graphExportFormatter;
    }

    protected static long loggerInfoStart(String msg) {
        logger.info(msg);
        return System.currentTimeMillis();
    }

    protected static void loggerInfoEnd(String msg, long startTime) {
        logger.info(msg + (System.currentTimeMillis() - startTime) + "ms");
    }

    /**
     * Sets up and configures the Main.logger object based on command line
     * arguments.
     * 
     * <pre>
     * Assumes that Main.options is initialized.
     * </pre>
     */
    public static void setUpLogging(AbstractOptions opts) {
        if (logger != null) {
            return;
        }
        // Get the top Logger instance
        logger = Logger.getLogger("");

        // Handler for console (reuse it if it already exists)
        Handler consoleHandler = null;

        // See if there is already a console handler
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                consoleHandler = handler;
                break;
            }
        }

        if (consoleHandler == null) {
            // No console handler found, create a new one
            consoleHandler = new ConsoleHandler();
            logger.addHandler(consoleHandler);
        }

        // The consoleHandler will write out anything the logger gives it
        consoleHandler.setLevel(Level.ALL);

        // consoleHandler.setFormatter(new CustomFormatter());

        // Set the logger's log level based on command line arguments
        if (opts.logLvlQuiet) {
            logger.setLevel(Level.WARNING);
        } else if (opts.logLvlVerbose) {
            logger.setLevel(Level.FINE);
        } else if (opts.logLvlExtraVerbose) {
            logger.setLevel(Level.FINEST);
        } else {
            logger.setLevel(Level.INFO);
        }

        consoleHandler.setFormatter(new BriefLogFormatter());
        return;
    }

    /**
     * Given a potentially wild-carded file path, finds all those files that
     * match the expression. TODO: make sure that the same file doesn't appear
     * twice in the returned list
     * 
     * @param fileArg
     *            The file path which may potentially contain wildcards.
     * @return An array of File handles which match.
     * @throws Exception
     */
    public static File[] getFiles(String fileArg) throws Exception {
        int wildix = fileArg.indexOf("*");
        if (wildix == -1) {
            return new File[] { new File(fileArg) };
        }
        String uptoWild = fileArg.substring(0, wildix);
        String path = FilenameUtils.getFullPath(uptoWild);
        String filter = FilenameUtils.getName(uptoWild)
                + fileArg.substring(wildix);
        File dir = new File(path).getAbsoluteFile();
        // TODO: check that listFiles is working properly recursively here.
        File[] results = dir.listFiles((FileFilter) new WildcardFileFilter(
                filter));
        if (results == null) {
            throw new Exception("Wildcard match failed: "
                    + (dir.isDirectory() ? dir.toString() + " not a directory"
                            : " for unknown reason"));
        }
        return results;
    }

    /******************************************************************************/
    /**
     * Main instance methods below.
     */

    /**
     * Returns the filename for an intermediate dot file based on the given
     * stage name and round number. Adheres to the convention specified above in
     * usage, namely that the filename is of the format:
     * outputPathPrefix.stage-S.round-R.dot
     * 
     * @param stageName
     *            Stage name string, e.g. "r" for refinement
     * @param roundNum
     *            Round number within the stage
     * @return string filename for an intermediate dot file
     */
    public String getIntermediateDumpFilename(String stageName, int roundNum) {
        return AbstractOptions.outputPathPrefix + ".stage-" + stageName
                + ".round-" + roundNum;
    }

    /**
     * Serializes g using a dot/gml format and optionally outputs a png file
     * corresponding to the serialized format (dot format export only).
     * 
     * @throws IOException
     */
    private <T extends INode<T>> void exportGraph(String baseFilename,
            IGraph<T> g, boolean outputEdgeLabelsCondition,
            boolean imageGenCondition) {
        // Check for a request not to output the model
        if (options.noModelOutput && !options.exportAsGML) {
            logger.info("Not outputting model due to flag --noModelOutput");
            return;
        }

        if (AbstractOptions.outputPathPrefix == null) {
            logger.warning("Cannot output initial graph. Specify output path prefix using:\n\t"
                    + AbstractOptions.plumeOpts.getOptDesc("outputPathPrefix"));
            return;
        }

        String filename = null;
        if (options.exportAsGML) {
            filename = baseFilename + ".gml";
        } else {
            filename = baseFilename + ".dot";
        }
        try {
            GraphExporter.exportGraph(filename, g, outputEdgeLabelsCondition);
        } catch (IOException e) {
            logger.fine("Unable to export graph to " + filename);
        }

        if (imageGenCondition) {
            // Currently we support only .dot -> .png generation
            GraphExporter.generatePngFileFromDotFile(filename);
        }
    }

    /**
     * Export the trace graph g.
     */
    public <T extends INode<T>> void exportTraceGraph(String baseFilename,
            IGraph<T> g) {
        // false below : never include edge labels on exported initial graphs

        // Main.dumpInitialGraphPngFile && !exportAsGML below : whether to
        // convert exported graph to a png file -- the user must have explicitly
        // requested this and the export must be in non-GML format (i.e., dot
        // format).
        exportGraph(baseFilename, g, false, options.dumpTraceGraphPngFile
                && !options.exportAsGML);
    }

    /**
     * Export g as a non-initial graph.
     */
    public <T extends INode<T>> void exportNonInitialGraph(String baseFilename,
            IGraph<T> g) {
        // Main.outputEdgeLabels below : the condition for including edge labels
        // on exported graphs.

        // !exportAsGML below : the condition for exporting an image to png file
        // is that it is not in GML format (i.e., it is in dot format so we can
        // use the 'dot' command).
        exportGraph(baseFilename, g, options.outputEdgeLabels,
                !options.exportAsGML);
    }

    private void processPOLog(TraceParser parserIn, List<EventNode> parsedEvents)
            throws ParseException, FileNotFoundException {
        TraceParser parser = parserIn;

        // //////////////////
        DAGsTraceGraph traceGraph = genDAGsTraceGraph(parser, parsedEvents);
        // //////////////////

        // Parser can be garbage-collected.
        parser = null;

        // TODO: vector time index sets aren't used yet.
        if (AbstractOptions.separateVTimeIndexSets != null) {
            // separateVTimeIndexSets is assumed to be in a format like:
            // "1,2;3;4,5,6" where the sets are {1,2}, {3}, {4,5,6}.
            LinkedList<LinkedHashSet<Integer>> indexSets = new LinkedList<LinkedHashSet<Integer>>();
            for (String strSet : AbstractOptions.separateVTimeIndexSets
                    .split(";")) {
                LinkedHashSet<Integer> iSet = new LinkedHashSet<Integer>();
                indexSets.add(iSet);
                for (String index : strSet.split(",")) {
                    iSet.add(Integer.parseInt(index));
                }
            }
        }

        // //////////////////
        TemporalInvariantSet minedInvs = minePOInvariants(
                options.useTransitiveClosureMining, traceGraph);
        // //////////////////

        logger.info("Mined " + minedInvs.numInvariants() + " invariants");

        int totalNCwith = 0;
        for (ITemporalInvariant inv : minedInvs.getSet()) {
            if (inv instanceof NeverConcurrentInvariant) {
                totalNCwith++;
            }
        }
        logger.info("\tMined " + totalNCwith
                + " NeverConcurrentWith invariants");

        if (options.dumpInvariants) {
            logger.info("Mined invariants:\n" + minedInvs.toPrettyString());
        }

        if (options.outputInvariantsToFile) {
            String invariantsFilename = AbstractOptions.outputPathPrefix
                    + ".invariants.txt";
            logger.info("Outputting invarians to file: " + invariantsFilename);
            minedInvs.outputToFile(invariantsFilename,
                    options.outputSupportCount);
        }
    }

    /**
     * Parses all the log filenames, constructing and returning a list of parsed
     * events.
     * 
     * @param parser
     * @param logFilenames
     * @return
     * @throws Exception
     */
    static public List<EventNode> parseEvents(TraceParser parser,
            List<String> logFilenames) throws Exception {
        long startTime = loggerInfoStart("Parsing input files..");

        List<EventNode> parsedEvents = new ArrayList<EventNode>();
        for (String fileArg : logFilenames) {
            logger.fine("\tprocessing fileArg: " + fileArg);
            File[] files = getFiles(fileArg);
            if (files.length == 0) {
                throw new ParseException(
                        "The set of input files is empty. Please specify a set of existing files to parse.");
            }
            for (File file : files) {
                logger.fine("\tcalling parseTraceFile with file: "
                        + file.getAbsolutePath());
                parsedEvents.addAll(parser.parseTraceFile(file, -1));
            }
        }
        loggerInfoEnd("Parsing took ", startTime);

        return parsedEvents;
    }

    static public ChainsTraceGraph genChainsTraceGraph(TraceParser parser,
            List<EventNode> parsedEvents) throws ParseException {
        long startTime = loggerInfoStart("Generating inter-event temporal relation...");
        ChainsTraceGraph inputGraph = parser
                .generateDirectTORelation(parsedEvents);
        loggerInfoEnd("Generating temporal relation took ", startTime);
        return inputGraph;
    }

    static public DAGsTraceGraph genDAGsTraceGraph(TraceParser parser,
            List<EventNode> parsedEvents) throws ParseException {
        long startTime = loggerInfoStart("Generating inter-event temporal relation...");
        DAGsTraceGraph traceGraph = parser
                .generateDirectPORelation(parsedEvents);
        loggerInfoEnd("Generating temporal relation took ", startTime);
        return traceGraph;
    }

    /**
     * Mines and returns the totally ordered invariants from the trace graph of
     * the input log.
     * 
     * @param useTransitiveClosureMining
     * @param traceGraph
     * @return
     */
    public TemporalInvariantSet mineTOInvariants(
            boolean useTransitiveClosureMining, ChainsTraceGraph traceGraph) {
        return mineTOInvariantsCommon(useTransitiveClosureMining, traceGraph);
    }

    protected TemporalInvariantSet mineTOInvariantsCommon(
            boolean useTransitiveClosureMining, ChainsTraceGraph traceGraph) {
        ITOInvariantMiner miner;

        if (useTransitiveClosureMining) {
            miner = new TransitiveClosureInvMiner();
        } else {
            miner = new ChainWalkingTOInvMiner();
        }

        long startTime = loggerInfoStart("Mining invariants ["
                + miner.getClass().getName() + "]..");
        TemporalInvariantSet minedInvs = miner.computeInvariants(traceGraph,
                options.multipleRelations, options.outputSupportCount);

        // Remove the interrupted by invariants from the mined set (not used in
        // Synoptic).
        if (!options.usePerformanceInfo) {
            TemporalInvariantSet minedInvsCopy = new TemporalInvariantSet();
            minedInvsCopy.add(minedInvs);
            for (ITemporalInvariant minedInv : minedInvsCopy) {
                if (minedInv instanceof TempConstrainedInvariant<?>) {
                    minedInvs.remove(minedInv);
                }
            }
        }

        loggerInfoEnd("Mining took ", startTime);

        // Miner can be garbage-collected.
        miner = null;
        return minedInvs;
    }

    /**
     * Mines and returns a set of partially ordered invariants from the DAG
     * trace graph of an input log.
     * 
     * @param useTransitiveClosureMining
     * @param traceGraph
     * @return
     */
    public TemporalInvariantSet minePOInvariants(
            boolean useTransitiveClosureMining, DAGsTraceGraph traceGraph) {

        IPOInvariantMiner miner;
        if (useTransitiveClosureMining) {
            miner = new TransitiveClosureInvMiner();
        } else {
            miner = new DAGWalkingPOInvMiner(options.mineNeverConcurrentWithInv);
        }

        long startTime = loggerInfoStart("Mining invariants ["
                + miner.getClass().getName() + "]..");
        TemporalInvariantSet minedInvs = miner.computeInvariants(traceGraph);
        loggerInfoEnd("Mining took ", startTime);
        // Miner can be garbage-collected.
        miner = null;
        return minedInvs;
    }

    /**
     * Uses the values of static variables in Main to (1) read and parse the
     * input log files, (2) to mine invariants from the parsed files, and (3)
     * construct an initial partition graph model of the parsed files.
     * 
     * @return The initial partition graph built from the parsed files or null.
     *         Returns null when the arguments passed to Main require an early
     *         termination.
     * @throws Exception
     */
    public PartitionGraph createInitialPartitionGraph() throws Exception {
        TraceParser parser = new TraceParser(options.regExps,
                AbstractOptions.partitionRegExp,
                AbstractOptions.separatorRegExp, options.dateFormat);
        List<EventNode> parsedEvents;
        try {
            parsedEvents = parseEvents(parser,
                    AbstractOptions.plumeOpts.logFilenames);
        } catch (ParseException e) {
            logger.severe("Caught ParseException -- unable to continue, exiting. Try cmd line option:\n\t"
                    + AbstractOptions.plumeOpts.getOptDesc("help"));
            logger.severe(e.toString());
            return null;
        }

        if (options.debugParse) {
            // Terminate since the user is interested in debugging the parser.
            logger.info("Terminating. To continue further, re-run without the debugParse option.");
            return null;
        }

        // PO Logs are processed differently.
        if (!parser.logTimeTypeIsTotallyOrdered()) {
            logger.warning("Partially ordered log input detected. Only mining invariants since refinement/coarsening is not yet supported.");
            processPOLog(parser, parsedEvents);
            return null;
        }

        if (parsedEvents.size() == 0) {
            logger.severe("Did not parse any events from the input log files. Stopping.");
            return null;
        }

        // //////////////////
        ChainsTraceGraph traceGraph = genChainsTraceGraph(parser, parsedEvents);
        // //////////////////

        // Parsing information can be garbage-collected.
        parser = null;
        parsedEvents = null;

        // Perform trace-wise normalization if requested
        if (options.traceNormalization) {
            AbstractMain.normalizeTraceGraph(traceGraph);
        }

        if (options.dumpTraceGraphDotFile) {
            logger.info("Exporting trace graph ["
                    + traceGraph.getNodes().size() + " nodes]..");
            exportTraceGraph(AbstractOptions.outputPathPrefix + ".tracegraph",
                    traceGraph);
        }

        // //////////////////
        TemporalInvariantSet minedInvs = mineTOInvariants(
                options.useTransitiveClosureMining, traceGraph);
        // //////////////////

        logger.info("Mined " + minedInvs.numInvariants() + " invariants");

        // Check if the support counts for all the invariants that have a count
        // is above the
        // threshold and if not then remove the invariant
        for (Iterator<ITemporalInvariant> it = minedInvs.iterator(); it
                .hasNext();) {
            ITemporalInvariant inv = it.next();
            if (inv instanceof BinaryInvariant) {
                if (((BinaryInvariant) inv).getStatistics() != null
                        && ((BinaryInvariant) inv).getStatistics().supportCount <= options.supportCountThreshold) {
                    it.remove();
                }
            }
        }

        // Removed IntrBy Invariants if necessary
        if (options.ignoreIntrByInvs) {
            for (Iterator<ITemporalInvariant> it = minedInvs.iterator(); it
                    .hasNext();) {
                ITemporalInvariant inv = it.next();

                if (inv.getShortName().equals("IntrBy")) {
                    it.remove();
                }
            }
        }

        if (AbstractOptions.ignoreInvsOverETypeSet != null) {

            // Split string options.ignoreInvsOverETypeSet by the ";" delimiter:
            List<String> stringEtypesToIgnore = Arrays
                    .asList(AbstractOptions.ignoreInvsOverETypeSet.split(";"));

            logger.info("Ignoring invariants over event-types set: "
                    + stringEtypesToIgnore.toString());

            boolean removeInv;
            for (Iterator<ITemporalInvariant> it = minedInvs.iterator(); it
                    .hasNext();) {
                ITemporalInvariant inv = it.next();

                // To remove an invariant inv, the event types associated with
                // inv must all come from the list stringEtypesToIgnore, we
                // check this here:
                removeInv = true;
                for (EventType eType : inv.getPredicates()) {
                    if (!stringEtypesToIgnore.contains(eType.getETypeLabel())) {
                        removeInv = false;
                        break;
                    }
                }
                if (removeInv) {
                    it.remove();
                }
            }

        }

        if (options.dumpInvariants) {
            logger.info("Mined invariants:\n" + minedInvs.toPrettyString());
        }

        if (options.outputSupportCount) {
            logger.info("Mined invariants and support counts:\n"
                    + minedInvs.supportCountToPrettyString());
        }

        if (options.outputInvariantsToFile) {
            String invariantsFilename = AbstractOptions.outputPathPrefix
                    + ".invariants.txt";
            logger.info("Outputting invariants to file: " + invariantsFilename);
            minedInvs.outputToFile(invariantsFilename,
                    options.outputSupportCount);
        }

        if (options.onlyMineInvariants) {
            return null;
        }

        // //////////////////
        // Create the initial partitioning graph.
        long startTime = loggerInfoStart("Creating initial partition graph.");
        PartitionGraph pGraph = new PartitionGraph(traceGraph, true, minedInvs);
        loggerInfoEnd("Creating partition graph took ", startTime);
        // //////////////////

        if (options.dumpInitialPartitionGraph) {
            exportGraph(AbstractOptions.outputPathPrefix + ".condensed",
                    pGraph, true, true);
        }

        return pGraph;
    }

    /**
     * Perform trace-wise normalization on the trace graph. In other words,
     * scale each trace to the range [0,1] based on the min and max absolute
     * times of any event within that trace.
     * 
     * @param traceGraph
     *            An trace graph where events and transitions contain time
     *            information
     */
    public static void normalizeTraceGraph(ChainsTraceGraph traceGraph) {
        logger.info("Normalizing each trace to the range [0,1] ...");

        Set<IRelationPath> relationPaths = new HashSet<IRelationPath>();

        // Get all traces w.r.t. only the time relation
        for (Trace trace : traceGraph.getTraces()) {
            Set<IRelationPath> subgraphs = trace
                    .getSingleRelationPaths(Event.defTimeRelationStr);
            relationPaths.addAll(subgraphs);
        }

        // Traverse each trace to normalize the absolute times of its events
        for (IRelationPath relationPath : relationPaths) {
            ITime minTime = null;
            ITime maxTime = null;

            // Find the min and max absolute time of any event in this trace
            EventNode cur = relationPath.getFirstNode();
            while (!cur.getAllTransitions().isEmpty()) {
                if (maxTime == null || maxTime.lessThan(cur.getTime())) {
                    maxTime = cur.getTime();
                }
                if (minTime == null || cur.getTime().lessThan(minTime)) {
                    minTime = cur.getTime();
                }

                // Get the next event in this trace
                cur = cur
                        .getTransitionsWithIntersectingRelations(
                                traceGraph.getRelations()).get(0).getTarget();
            }

            ITime rangeTime = null;

            // Compute the range of this trace's times
            if (maxTime != null) {
                rangeTime = maxTime.computeDelta(minTime);
            } else {
                logger.fine("Warning: Trace beginning with "
                        + relationPath.getFirstNode()
                        + " cannot be normalized because it seems to contain no times");
                continue;
            }

            // Normalize absolute time of each of this trace's events by
            // subtracting the min and dividing by the range
            cur = relationPath.getFirstNode();
            while (!cur.getAllTransitions().isEmpty()) {
                cur.getEvent().setTime(
                        cur.getTime().computeDelta(minTime)
                                .normalize(rangeTime));

                // Get the next event in this trace
                cur = cur
                        .getTransitionsWithIntersectingRelations(
                                traceGraph.getRelations()).get(0).getTarget();
            }
        }

        // Update transition time deltas to match new normalized event times
        for (EventNode event : traceGraph.getNodes()) {
            for (Transition<EventNode> trans : event.getAllTransitions()) {

                // Get normalized times of the transition's source and target
                // events
                ITime srcTime = trans.getSource().getTime();
                ITime targetTime = trans.getTarget().getTime();

                // Compute and store normalized transition time delta
                if (targetTime != null) {
                    ITime delta = targetTime.computeDelta(srcTime);
                    trans.setTimeDelta(delta);
                }
            }
        }
    }

    /**
     * Runs the Synoptic algorithm starting from the initial graph (pGraph). The
     * pGraph is assumed to be fully initialized and ready for refinement. The
     * Synoptic algorithm first runs a refinement algorithm, and then runs a
     * coarsening algorithm.
     * 
     * @param pGraph
     *            The initial graph model to start refining.
     */
    public void runSynoptic(PartitionGraph pGraph) {
        long startTime;

        if (options.logLvlVerbose || options.logLvlExtraVerbose) {
            System.out.println("");
            System.out.println("");
        }

        // //////////////////
        startTime = loggerInfoStart("Refining (Splitting)...");
        Bisimulation.splitUntilAllInvsSatisfied(pGraph);
        loggerInfoEnd("Splitting took ", startTime);
        // //////////////////

        if (options.logLvlVerbose || options.logLvlExtraVerbose) {
            System.out.println("");
            System.out.println("");
        }

        // //////////////////
        startTime = loggerInfoStart("Coarsening (Merging)..");
        Bisimulation.mergePartitions(pGraph);
        loggerInfoEnd("Merging took ", startTime);
        // //////////////////

        // At this point, we have the final model in the pGraph object.

        // TODO: check that none of the initially mined mkTails.invariants are
        // unsatisfied in the result

        // export the resulting graph
        if (AbstractOptions.outputPathPrefix != null) {
            logger.info("Exporting final graph [" + pGraph.getNodes().size()
                    + " nodes]..");
            startTime = System.currentTimeMillis();

            exportNonInitialGraph(AbstractOptions.outputPathPrefix, pGraph);

            logger.info("Exporting took "
                    + (System.currentTimeMillis() - startTime) + "ms");

            // if test generation is enabled, export all bounded, predicted
            // abstract tests
            if (options.testGeneration) {
                Set<AbstractTestCase> testSuite = SynopticTestGeneration
                        .deriveAbstractTests(pGraph);
                int testID = 0;
                for (AbstractTestCase testCase : testSuite) {
                    String baseFilename = AbstractOptions.outputPathPrefix
                            + "-test" + testID;
                    exportNonInitialGraph(baseFilename, testCase);
                    testID++;
                }
            }
        } else {
            logger.warning("Cannot output final graph. Specify output path prefix using:\n\t"
                    + AbstractOptions.plumeOpts.getOptDesc("outputPathPrefix"));
        }

        // Export a JSON object if requested
        if (options.outputJSON) {
            logger.info("Exporting final graph as a JSON object...");
            startTime = System.currentTimeMillis();

            JsonExporter.exportJsonObject(AbstractOptions.outputPathPrefix,
                    pGraph);

            logger.info("Exporting JSON object took "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }

        // Export in LTS format if requested
        if (options.outputLTS) {
            logger.info("Exporting final graph in LTS format...");
            startTime = System.currentTimeMillis();

            LtsExporter.exportLTS(AbstractOptions.outputPathPrefix, pGraph);

            logger.info("Exporting in LTS format took "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }
    }
}
