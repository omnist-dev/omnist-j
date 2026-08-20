package dev.omnist.document;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared utilities for constructing canonical document paths in diagnostics and report adjustments (omnist-spec ?8).
 */
public final class PathUtils {

    private PathUtils() {}

    /**
     * Precomputes the total occurrence counts for each label on a {@link Node}.
     *
     * @param node the node whose direct edges are counted
     * @return a map from edge label to total count on this node
     */
    public static Map<String, Integer> countLabels(Node node) {
        Map<String, Integer> counts = new HashMap<>();
        for (Edge edge : node.edges()) {
            counts.merge(edge.label(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Builds a canonical child path for an edge on a node.
     *
     * <p>When {@code totalCount > 1}, the 0-based index {@code [index]} is included on every occurrence,
     * including the first (e.g. {@code $.item[0]}, {@code $.item[1]}).
     * When {@code totalCount <= 1}, the index is omitted (e.g. {@code $.item}).
     *
     * @param parentPath the path to the parent node (e.g. {@code "$"} or {@code "$.user"})
     * @param label      the edge label
     * @param index      the 0-based occurrence index of this edge among edges with the same label on this node
     * @param totalCount the total number of edges with this label on this node
     * @return the canonical path string
     */
    public static String childPath(String parentPath, String label, int index, int totalCount) {
        String base = parentPath.equals("$") ? "$." + label : (parentPath.isEmpty() ? label : parentPath + "." + label);
        if (totalCount > 1) {
            return base + "[" + index + "]";
        }
        return base;
    }
}
