package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.PathUtils;
import dev.omnist.document.Scalar.StringScalar;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule C-9 of omnist-spec section 7.3: every writer fails with {@code write.unsupported-value}, and
 * unconditionally, on a string value or an edge label that has no UTF-8 encoding. In Java that is a
 * lone surrogate: a high surrogate not followed by a low one, or a low surrogate not preceded by a
 * high one. A valid surrogate pair (an astral code point) and U+FFFD are ordinary text.
 *
 * <p>A writer must not emit such a string in any spelling, raw or as an escape, because a reader of
 * the output (a UTF-8 sink) could not accept it. The check is a fast pass that builds no path
 * ({@link #allEncodable}); only when it finds a failure does {@link #firstFailure} walk the document
 * again, in document order, to build the Document path of the node holding the string, or, for a
 * label, of the node holding the edge (a path cannot quote a label).
 */
public final class Encodability {

    private static final String CODE = "write.unsupported-value";
    private static final String STRING_MESSAGE = "string value has no UTF-8 encoding (a lone surrogate)";

    private Encodability() {}

    /**
     * Whether {@code s} has a UTF-8 encoding, that is, holds no lone surrogate.
     *
     * @param s the string
     * @return {@code true} if every surrogate is half of a valid pair
     */
    public static boolean isEncodable(String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            // One test for "is a surrogate": 0xD800 to 0xDFFF is exactly the characters whose top
            // five bits are 11011.
            if ((s.charAt(i) & 0xF800) != 0xD800) {
                continue;
            }
            if (Character.isHighSurrogate(s.charAt(i)) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * The fast pass: whether every edge label and string value of {@code doc} has a UTF-8 encoding.
     * Builds no path and recurses on no Java stack, so a very deep document is safe.
     *
     * @param doc the document
     * @return {@code true} if nothing in it is unencodable
     */
    public static boolean allEncodable(Document doc) {
        if (doc instanceof StringScalar str) {
            return isEncodable(str.value());
        }
        if (!(doc instanceof Node root)) {
            return true;
        }
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            List<Edge> edges = pending.pop().edges();
            for (int i = 0, n = edges.size(); i < n; i++) {
                Edge edge = edges.get(i);
                if (!isEncodable(edge.label())) {
                    return false;
                }
                Object target = edge.target();
                if (target instanceof Node child) {
                    pending.push(child);
                } else if (target instanceof StringScalar str && !isEncodable(str.value())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** One failure of C-9: where, and what. */
    private record Failure(String path, String message) {}

    /**
     * Adds a {@code write.unsupported-value} error to {@code rep}, at the Document path of the first
     * offending node in document order, if {@code doc} holds a string or label with no UTF-8
     * encoding; adds nothing otherwise. Costs only the fast pass when the document is clean.
     *
     * @param doc the document
     * @param rep the report to add to
     */
    static void check(Document doc, WriteReport rep) {
        if (allEncodable(doc)) {
            return;
        }
        Failure failure = firstFailure(doc);
        rep.add(failure.path(), CODE, failure.message(), "error");
    }

    /**
     * Thrown by a writer that meets an unencodable string while it is writing, so that it needs no
     * separate scan first; the writer catches it and turns it into the {@link #refusal} of C-9.
     * Carries no stack trace and no message: it never leaves the writer.
     */
    public static final class LoneSurrogate extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** The single instance: the exception carries no state. */
        public static final LoneSurrogate INSTANCE = new LoneSurrogate();

        private LoneSurrogate() {
            super(null, null, false, false);
        }
    }

    /**
     * The C-9 refusal for a document that holds a string value or an edge label with no UTF-8
     * encoding: a {@link WriteException} whose report has one {@code write.unsupported-value} error
     * at the Document path of the first offending node. Call it only for such a document.
     *
     * @param doc the document that was being written
     * @return the exception to throw
     */
    public static WriteException refusal(Document doc) {
        WriteReport rep = new WriteReport();
        check(doc, rep);
        return new WriteException(rep.toString(), rep);
    }

    /** The slow pass: only called when {@link #allEncodable} said no, so it always finds one. */
    private static Failure firstFailure(Document doc) {
        if (doc instanceof StringScalar) {
            return new Failure("$", STRING_MESSAGE);
        }
        return find((Node) doc, "$");
    }

    /** The first failure in {@code node}'s subtree in document order, or {@code null} if it has none. */
    private static Failure find(Node node, String path) {
        for (Edge edge : node.edges()) {
            if (!isEncodable(edge.label())) {
                return new Failure(path, "edge label has no UTF-8 encoding (a lone surrogate)");
            }
        }
        Map<String, Integer> totals = PathUtils.countLabels(node);
        Map<String, Integer> seen = new HashMap<>();
        for (Edge edge : node.edges()) {
            int i = seen.getOrDefault(edge.label(), 0);
            seen.put(edge.label(), i + 1);
            String childPath = PathUtils.childPath(path, edge.label(), i, totals.get(edge.label()));
            Object target = edge.target();
            if (target instanceof StringScalar str && !isEncodable(str.value())) {
                return new Failure(childPath, STRING_MESSAGE);
            }
            if (target instanceof Node child) {
                Failure inner = find(child, childPath);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }
}
