package dev.omnist.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.Schema;

import java.io.IOException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Codec for reading and writing the Omnist Document model as JSON (omnist-spec §7.3).
 *
 * <p><b>Reading</b>: JSON objects map to {@link dev.omnist.document.Node} values; JSON arrays
 * appearing as object-field values map to repeated edges with the same label; bare top-level
 * arrays and nested arrays are rejected because they have no labeled-edge representation.
 * Temporal scalars ({@link DateScalar}, {@link TimeScalar}, {@link DateTimeScalar}) are
 * written as ISO-8601 strings with an accompanying {@code format.temporal-stringified} adjustment.
 *
 * <p><b>Writing</b>: {@link Double#NaN} and {@link Double#isInfinite(double) infinite} values
 * are replaced with {@code null} and reported as {@code format.float-special} errors.
 *
 * <p>This class is stateless; all methods are {@code static}.
 */
public final class JsonCodec {
    public static final int MAX_INPUT_LENGTH = 2_000_000;

    private static final ObjectMapper MAPPER;
    static {
        com.fasterxml.jackson.core.StreamReadConstraints constraints = com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNumberLength(10_000)
                .build();
        com.fasterxml.jackson.core.JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        MAPPER = new ObjectMapper(factory);
    }

    private JsonCodec() {}

    /**
     * Parses JSON text into a {@link Document}.
     *
     * @param text the JSON text; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException if the JSON is syntactically invalid, or if the root value is an array,
     *         or if nesting depth exceeds 200, or if node count exceeds 1,000,000
     */
    public static Document read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("input text cannot be null");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new DocumentParseException("$", "document.parse-error", "invalid JSON: input exceeds maximum size limit of " + MAX_INPUT_LENGTH + " characters");
        }
        try {
            Object raw = MAPPER.readValue(text, Object.class);
            int[] budget = new int[]{0};
            Document doc = buildNode(raw, "$", 0, budget);
            return doc;
        } catch (IOException e) {
            throw new DocumentParseException("$", "document.parse-error", "invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * @deprecated The {@code schema} parameter is ignored for JSON. Use {@link #read(String)}
     *             followed by {@link dev.omnist.validation.Materializer#materialize(Document, Schema)}
     *             if schema-driven coercion is required.
     */
    @Deprecated(since = "0.1.0-alpha", forRemoval = true)
    public static Document read(String text, Schema schema) {
        return read(text);
    }

    private static Document buildNode(Object val, String path, int depth, int[] budget) {
        Limits limits = Limits.DEFAULT;
        if (depth > limits.maxDepth()) {
            throw new DocumentParseException(path, "document.limit.depth", path + ": nesting exceeds the maximum depth (" + limits.maxDepth() + ")");
        }
        if (val instanceof Map<?, ?> map) {
            budget[0]++;
            if (budget[0] > limits.maxNodeCount()) {
                throw new DocumentParseException(path, "document.limit.nodes", path + ": too many nodes materialized (over " + limits.maxNodeCount() + ")");
            }
            List<Edge> edges = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Defensive: Invalid JSON (non-string keys) should never occur with ObjectMapper#readValue
                if (!(entry.getKey() instanceof String k)) {
                    throw new DocumentParseException(path, "document.unlabeled-element", path + ": object key " + entry.getKey() + " is not a string");
                }
                Object v = entry.getValue();
                String kp = path.equals("$") ? "$." + k : path + "." + k;
                if (v instanceof List<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        if (item instanceof List<?>) {
                            throw new DocumentParseException(kp + "[" + i + "]", "document.unlabeled-element", kp + "[" + i + "]: an array of arrays has no labeled-edge form");
                        }
                        Document child = buildNode(item, kp + "[" + i + "]", depth + 2, budget);
                        edges.add(new Edge(k, (Target) child));
                    }
                } else {
                    Document child = buildNode(v, kp, depth + 1, budget);
                    edges.add(new Edge(k, (Target) child));
                }
            }
            return new Node(edges);
        }
        if (val instanceof List<?>) {
            throw new DocumentParseException(path, "document.unlabeled-element", path + ": a bare array has no labeled-edge form (arrays appear only as a repeated field)");
        }
        return toScalar(val);
    }

    private static Value toScalar(Object value) {
        if (value == null) {
            return Value.NULL;
        }
        if (value instanceof String s) {
            return new StringScalar(s);
        }
        if (value instanceof Boolean b) {
            return new BooleanScalar(b);
        }
        if (value instanceof BigInteger bi) {
            String s = bi.toString();
            int digits = s.startsWith("-") ? s.length() - 1 : s.length();
            if (digits > Limits.DEFAULT.maxIntegerDigits()) {
                throw new DocumentParseException("$", "document.limit.int-digits", "document.limit.int-digits: Integer literal digit count (" + digits + ") exceeds maximum limit of " + Limits.DEFAULT.maxIntegerDigits());
            }
            return new IntegerScalar(bi);
        }
        if (value instanceof Integer i) {
            return new IntegerScalar(BigInteger.valueOf(i));
        }
        if (value instanceof Long l) {
            return new IntegerScalar(BigInteger.valueOf(l));
        }
        if (value instanceof Double d) {
            return new NumberScalar(d);
        }
        // UNREACHABLE with MAPPER's current default configuration: verified
        // empirically that ObjectMapper#readValue(text, Object.class) only ever
        // produces String/Boolean/Integer/Long/Double for JSON numbers, never
        // Float or BigDecimal (that requires enabling USE_BIG_DECIMAL_FOR_FLOATS).
        // Kept as defensive handling in case that configuration ever changes.
        if (value instanceof Float f) {
            return new NumberScalar(f.doubleValue());
        }
        if (value instanceof java.math.BigDecimal bd) {
            try {
                return new IntegerScalar(bd.toBigIntegerExact());
            } catch (ArithmeticException e) {
                return new NumberScalar(bd.doubleValue());
            }
        }
        // UNREACHABLE: Jackson's ObjectMapper only ever produces null, String, Boolean, BigInteger,
        // Integer, Long, Double from standard JSON with this MAPPER's default config — all handled above.
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }

    /**
     * Serializes a {@link Document} to compact JSON with no indentation.
     * Equivalent to {@code write(node, null, false, null)}.
     *
     * @param node the document to serialize
     * @return the JSON text
     * @throws WriteException if the document has structural issues (e.g. non-representable values)
     */
    public static String write(Document node) {
        return write(node, null, false, null);
    }

    /**
     * Serializes a {@link Document} to JSON, optionally indented and in strict mode.
     *
     * <p>Temporal scalars are written as ISO-8601 strings; {@link Double#NaN} and
     * infinite values are replaced with {@code null} and reported as {@code format.float-special}.
     * Repeated edges with the same label are grouped into a JSON array under that key.
     *
     * @param node   the document to serialize
     * @param indent if positive, pretty-print with that many spaces; {@code null} or {@code 0} for compact
     * @param strict if {@code true}, throws a {@link WriteException} instead of accumulating adjustments
     * @param report if non-{@code null}, receives all format adjustment records; may be {@code null}
     * @return the JSON text
     * @throws WriteException if {@code strict} is {@code true} and any adjustment is required
     */
    public static String write(Document node, Integer indent, boolean strict, WriteReport report) {
        WriteReport rep = check(node);
        if (report != null) {
            report.addAll(rep.adjustments());
        }
        if (strict && !rep.adjustments().isEmpty()) {
            throw new WriteException(rep.toString(), rep);
        }
        Object prepared = prepareJson(node, "$", 0, strict);
        Object grouped = dev.omnist.document.PathUtils.groupEdges(prepared);
        try {
            if (indent != null && indent > 0) {
                com.fasterxml.jackson.core.util.DefaultPrettyPrinter printer = new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
                com.fasterxml.jackson.core.util.DefaultIndenter indenter = new com.fasterxml.jackson.core.util.DefaultIndenter(" ".repeat(indent), com.fasterxml.jackson.core.util.DefaultIndenter.SYS_LF);
                printer.indentObjectsWith(indenter);
                printer.indentArraysWith(indenter);
                return MAPPER.writer(printer).writeValueAsString(grouped);
            } else {
                return MAPPER.writeValueAsString(grouped);
            }
        } catch (IOException e) {
            // Not observed to be reachable: `grouped` is built entirely from
            // this codec's own controlled types (List/Map/String/Boolean/
            // BigInteger/Double), so Jackson's in-memory writeValueAsString has
            // no real failure mode here short of a custom-serializer bug.
            // Kept as defensive handling for the checked-exception contract.
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks a {@link Document} for JSON-representability issues without serializing it.
     * Returns a {@link WriteReport} that callers can inspect before deciding whether to proceed.
     *
     * @param node the document to check
     * @return a report of any format adjustments that would be applied during {@link #write}
     */
    public static WriteReport check(Document node) {
        WriteReport rep = new WriteReport();
        scanJson(node, "$", 0, rep, new boolean[]{false});
        return rep;
    }

    private static void scanJson(Document doc, String path, int depth, WriteReport rep, boolean[] interleavingFound) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (doc instanceof Node node) {
            if (!interleavingFound[0] && dev.omnist.document.PathUtils.hasInterleavedLabels(node)) {
                rep.add("$", "format.interleaving-lost",
                        "cross-label edge interleaving cannot be represented; grouping same-label edges together lost the original relative order",
                        "warning");
                interleavingFound[0] = true;
            }
            Map<String, Integer> totals = dev.omnist.document.PathUtils.countLabels(node);
            Map<String, Integer> seen = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                int i = seen.getOrDefault(label, 0);
                seen.put(label, i + 1);
                int total = totals.getOrDefault(label, 1);
                String p = dev.omnist.document.PathUtils.childPath(path, label, i, total);
                scanJson((Document) edge.target(), p, depth + 1, rep, interleavingFound);
            }
        } else if (doc instanceof Scalar s) {
            if (s instanceof DateScalar || s instanceof TimeScalar || s instanceof DateTimeScalar) {
                rep.add(path, "format.temporal-stringified", "temporal value written as an ISO-8601 string", "warning");
            } else if (s instanceof NumberScalar num) {
                double d = num.value();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    rep.add(path, "format.float-special", d + " is not valid JSON; wrote null", "error");
                }
            }
        }
    }

    private static Object prepareJson(Document doc, String path, int depth, boolean strict) {
        // No depth guard here: write() only reaches prepareJson after check()'s
        // scanJson pass has already walked this same document and thrown if any
        // depth exceeded 200, so that bound is already established.
        // Exhaustive over Document's sealed hierarchy (Node, Value -> Scalar's 7
        // variants | NullValue) -- every case is handled, no fallback is reachable.
        if (doc instanceof Node node) {
            List<Object[]> edges = new ArrayList<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                Object child = prepareJson((Document) edge.target(), path + "." + label, depth + 1, strict);
                edges.add(new Object[]{label, child});
            }
            return edges;
        } else if (doc instanceof Value.NullValue) {
            return null;
        } else if (doc instanceof StringScalar str) {
            return str.value();
        } else if (doc instanceof BooleanScalar bool) {
            return bool.value();
        } else if (doc instanceof IntegerScalar integer) {
            return integer.value();
        } else if (doc instanceof NumberScalar num) {
            double d = num.value();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return d;
        } else if (doc instanceof DateScalar date) {
            return date.value().toString();
        } else if (doc instanceof TimeScalar time) {
            return time.value().format();
        } else {
            DateTimeScalar dt = (DateTimeScalar) doc;
            return dt.value().format();
        }
    }

}
