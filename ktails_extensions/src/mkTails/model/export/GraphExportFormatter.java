package mkTails.model.export;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import daikonizer.DaikonInvariants;
import mkTails.model.event.Event;
import mkTails.model.interfaces.INode;
import mkTails.util.time.ITime;

/**
 * Base class representing possible exported/serializing formats for graphs.
 */
public abstract class GraphExportFormatter {
    public static Logger logger = Logger.getLogger("GraphExportFormatter");

    /**
     * Possible colors (X11 scheme) for differentiating edges of different
     * relations (one color per relation).
     */
    List<String> possibleColors;

    /**
     * Maps commonly observed relations to the edge colors from the list above.
     */
    Map<String, String> relationColors;

    /** Color used for the "t" relation and when we run out of colors. */
    static final String defaultRelationColor = "black";

    /**
     * Rounds off edge probability to two decimal places. Used by all edge
     * formatters. NOTE: if this method is modified, make sure to update the
     * synopticgwt.client.model.ModelTab.probToString() method.
     */
    public static String probToString(double prob) {
        return String.format("%.2f", Math.round(prob * 100.0) / 100.0);
    }

    /**
     * Whether or not we've printed a message stating that we've run out of
     * colors.
     */
    boolean reportedColorsDeficiency = false;

    public GraphExportFormatter() {
        // This colors subset is taken from:
        // http://www.graphviz.org/doc/info/colors.html
        possibleColors = new LinkedList<String>(Arrays.asList("darkorange1",
                "goldenrod", "darkseagreen", "green3", "gray66", "indianred",
                "indigo", "yellow1", "thistle"));

        relationColors = new LinkedHashMap<String, String>();
        relationColors.put(Event.defTimeRelationStr, defaultRelationColor);
    }

    /**
     * Returns a color for the relation if one was assigned previously. If not,
     * grabs a new unused color and assigns it to this relation. If all colors
     * are used, prints a warning (once per instance of this class) and returns
     * the defaultRelationColor.
     */
    public String getRelationColor(String relation) {
        if (relationColors.containsKey(relation)) {
            return relationColors.get(relation);
        }
        if (possibleColors.size() == 0) {
            if (!reportedColorsDeficiency) {
                logger.severe("Ran out of colors for relations when exporting graph. Using the default color ("
                        + defaultRelationColor
                        + ") for the remaining relations.");
            }
            reportedColorsDeficiency = true;
        }
        if (possibleColors.size() == 0) {
            return defaultRelationColor;
        }
        String color = possibleColors.remove(0);
        relationColors.put(relation, color);
        return color;
    }

    /**
     * Returns a string that begins a new graph.
     */
    public abstract String beginGraphString();

    /**
     * Returns a string that terminates a graph.
     */
    public abstract String endGraphString();

    /**
     * Serializes a single node in a graph to a string.
     * 
     * @param <T>
     * @param nodeId
     *            a unique node identifier
     * @param node
     *            INode node, whose event type will be used as a label
     * @param isInitial
     *            whether or not node is initial
     * @param isTerminal
     *            whether or not node is terminal
     */
    public abstract <T extends INode<T>> String nodeToString(int nodeId,
            T node, boolean isInitial, boolean isTerminal);

    /**
     * Serializes a single node edge in a graph to a string that represents this
     * edge, along with the probability of the edge transition.
     * 
     * @param nodeSrc
     *            the unique identifier for the source node
     * @param nodeDst
     *            the unique identifier for the target node
     * @param freq
     *            the frequency value to be used as a label on the edge
     * @param relation
     *            a string representing the relation (e.g., "t")
     * @return
     */
    public abstract String edgeToStringWithProb(int nodeSrc, int nodeDst,
            double freq, Set<String> relation);

    /**
     * Serializes a single node edge in a graph to a string that represents this
     * edge, with NO probability of the edge transition.
     * 
     * @param outputEdgeLabels
     *            whether or not to output edge labels
     * @param nodeSrc
     *            the unique identifier for the source node
     * @param nodeDst
     *            the unique identifier for the target node
     * @param freq
     *            the frequency value to be used as a label on the edge
     * @param relation
     *            a string representing the relation (e.g., "t")
     * @return
     */
    public abstract String edgeToStringWithNoProb(int nodeSrc, int nodeDst,
            Set<String> relations);

    /**
     * Serializes a single node edge in a graph to a string. Also, outputs a
     * trace identifier as the edge label.
     * 
     * @param nodeSrc
     *            the unique identifier for the source node
     * @param nodeDst
     *            the unique identifier for the target node
     * @param freq
     *            the frequency value to be used as a label on the edge
     * @param traceId
     *            the identifier of the trace that this edge belongs to.
     * @param relation
     *            a string representing the relation (e.g., "t")
     * @return
     */
    public abstract String edgeToStringWithTraceId(int nodeSrc, int nodeDst,
            int traceId, Set<String> relations);

    /**
     * Serializes a single node edge in a graph to a string given the min and
     * max ITimes of any concrete transitions within this edge (between the
     * source and target nodes), and optionally, the median ITime.
     * 
     * @param nodeSrc
     *            the unique identifier for the source node
     * @param nodeDst
     *            the unique identifier for the target node
     * @param timeMin
     *            minimum ITime of any transition from source to target
     * @param timeMax
     *            maximum ITime of any transition from source to target
     * @param timeMedian
     *            median ITime, or null to not display median
     * @param relations
     *            a string representing the relations (e.g., "t")
     */
    public abstract String edgeToStringWithITimes(int nodeSrc, int nodeDst,
            ITime timeMin, ITime timeMax, ITime timeMedian,
            Set<String> relations);

    /**
     * Serializes a single node edge in a graph to a string given the min and
     * max ITimes of any concrete transitions within this edge (between the
     * source and target nodes) and the probability of the transition.
     * 
     * @param nodeSrc
     *            the unique identifier for the source node
     * @param nodeDst
     *            the unique identifier for the target node
     * @param timeMin
     *            minimum ITime of any transition from source to target
     * @param timeMax
     *            maximum ITime of any transition from source to target
     * @param timeMedian
     *            median ITime, or null to not display median
     * @param freq
     *            the frequency value or probability on the edge
     * @param relations
     *            a string representing the relations (e.g., "t")
     */
    public abstract String edgeToStringWithITimesAndProb(int nodeSrc,
            int nodeDst, ITime timeMin, ITime timeMax, ITime timeMedian,
            double freq, Set<String> relations);

    /**
     * Create the time delta string for an edge given the min, max, and
     * (optionally) median ITimes within it, rounding to the specified number of
     * significant digits.
     * 
     * @return If min==max, returns "min". Else, returns "[min,med,max]" if
     *         median is specified or else "[min,max]" if not
     */
    protected String getITimeString(ITime timeMin, ITime timeMax,
            ITime timeMedian, int sigDigits) {
        // Make time string
        if (timeMin != null && timeMax != null) {

            // Round min and max to a few significant digits for readability
            BigDecimal timeMinDec = new BigDecimal(timeMin.toString())
                    .round(new MathContext(sigDigits));
            BigDecimal timeMaxDec = new BigDecimal(timeMax.toString())
                    .round(new MathContext(sigDigits));

            // Remove trailing zeros and periods from min and max
            String timeMinStr = removeTrailingZeros(timeMinDec.toString());
            String timeMaxStr = removeTrailingZeros(timeMaxDec.toString());

            // Round and format median if provided
            String timeMedStr = "";
            if (timeMedian != null) {
                BigDecimal timeMedDec = new BigDecimal(timeMedian.toString())
                        .round(new MathContext(sigDigits));

                timeMedStr = removeTrailingZeros(timeMedDec.toString()) + ",";
            }

            // String is range (possibly including median) if min != max time or
            // else just the single time if they are equal
            if (!timeMinStr.equals(timeMaxStr)) {
                return "[" + timeMinStr + "," + timeMedStr + timeMaxStr + "]";
            }
            {
                return timeMinStr;
            }
        }
        {
            return "";
        }
    }

    /**
     * Return a string that is "str" minus any trailing zeros and periods,
     * except the first character will not be removed
     */
    public static String removeTrailingZeros(String str) {

        int length = str.length();

        // Cannot trim empty or 1-char strings or strings without a decimal
        if (length <= 1 || str.indexOf('.') == -1) {
            return str;
        }

        // Where we are considering trimming
        int trimInd = str.length() - 1;

        // Do not trim the first character, otherwise loop backwards until a
        // non-zero, non-period character is hit
        while (trimInd > 0
                && (str.charAt(trimInd) == '0' || str.charAt(trimInd) == '.')) {
            trimInd--;

            // Don't trim 0s left of the decimal
            if (str.charAt(trimInd + 1) == '.') {
                break;
            }
        }

        // Trim and return
        return str.substring(0, trimInd + 1);
    }

    /**
     * Serializes a single node edge in a graph to a string that represents this
     * edge, along with the Daikon invariants of the edge transition.
     */
    public abstract String edgeToStringWithDaikonInvs(int nodeSrc, int nodeDst,
            DaikonInvariants daikonInvs, Set<String> relation);

    /**
     * Returns a string with escaped forward slashes and double quotes.
     */
    protected static String quote(String string) {
        final StringBuilder sb = new StringBuilder(string.length() + 2);
        for (int i = 0; i < string.length(); ++i) {
            final char c = string.charAt(i);
            switch (c) {
            case '\n':
                sb.append("\\n");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '"':
                sb.append("\\\"");
                break;
            default:
                sb.append(c);
                break;
            }
        }
        return sb.toString();
    }
}
