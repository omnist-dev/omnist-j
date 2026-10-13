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
    /** Jackson's own cap on the length of one number token, above the reference digit limit. */
    private static final int NUMBER_LENGTH_CAP = 10_000;

    private static final ObjectMapper MAPPER = mapper(NUMBER_LENGTH_CAP);

    private static ObjectMapper mapper(int maxNumberLength) {
        com.fasterxml.jackson.core.StreamReadConstraints constraints = com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNumberLength(maxNumberLength)
                .build();
        com.fasterxml.jackson.core.JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        return new ObjectMapper(factory);
    }

    /**
     * C-14: the hex digits of a four-digit unicode escape are lowercase. Jackson writes the control
     * character U+001F with an uppercase F; this rewrites each such escape with lowercase digits.
     * It walks the text so an escaped backslash followed by the letters of an escape stays as it
     * was: a backslash is skipped together with the character it escapes.
     */
    static String lowercaseEscapes(String json) {
        StringBuilder out = new StringBuilder(json.length());
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                // Jackson's output is well-formed: a backslash is always followed by the character
                // it escapes, and a "u" by exactly four hex digits.
                char next = json.charAt(i + 1);
                out.append(c).append(next);
                i += 2;
                if (next == 'u') {
                    out.append(json.substring(i, i + 4).toLowerCase(java.util.Locale.ROOT));
                    i += 4;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private JsonCodec() {}

    /**
     * Parses JSON text into a {@link Document}.
     *
     * @param text the JSON text; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException if the JSON is syntactically invalid, or if the root value is an array,
     *         or if nesting depth exceeds 200, or if node count exceeds 1,000,000, or if the input
     *         is larger than {@link Limits#DEFAULT_MAX_INPUT_BYTES} bytes
     */
    public static Document read(String text) {
        return readWithLimits(text, Limits.DEFAULT);
    }

    /**
     * Parses JSON text into a {@link Document}, refusing input larger than
     * {@link Limits#maxInputBytes()} bytes (D-23) and honouring the depth, node-count and
     * integer-digit bounds of {@code limits} (D-9, E-4a) with the diagnostics the OML reader gives:
     * {@code document.limit.depth} and {@code document.limit.nodes} at {@code $},
     * {@code document.limit.int-digits} at the Document path of the integer.
     *
     * <p>An integer literal of more than 10 000 digits (or of more than {@code maxIntegerDigits}
     * when that is larger) is refused by the JSON parser itself as {@code parse.codec-syntax}.
     *
     * @param text   the JSON text; must not be {@code null}
     * @param limits the limits; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException as {@link #read(String)}, with {@code document.limit.input-size}
     *         at {@code $} for an input over the maximum
     */
    public static Document readWithLimits(String text, Limits limits) {
        text = CodecInput.prepare(text, "JSON", java.util.Objects.requireNonNull(limits, "limits").maxInputBytes());
        ObjectMapper mapper = limits.maxIntegerDigits() > NUMBER_LENGTH_CAP ? mapper(limits.maxIntegerDigits()) : MAPPER;
        Object raw;
        try {
            raw = mapper.readValue(text, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw syntaxError(e);
        }
        int[] budget = new int[]{0};
        return buildNode(raw, "$", 0, budget, limits);
    }

    /** Maps a Jackson failure to {@code parse.codec-syntax} at Jackson's own line and column. */
    static DocumentParseException syntaxError(com.fasterxml.jackson.core.JsonProcessingException e) {
        com.fasterxml.jackson.core.JsonLocation at = e.getLocation();
        int line = at == null ? 1 : at.getLineNr();
        int column = at == null ? 1 : at.getColumnNr();
        return CodecInput.syntax("JSON", e.getOriginalMessage(), line, column, e);
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

    private static Document buildNode(Object val, String path, int depth, int[] budget, Limits limits) {
        // The OML reader's diagnostics for the same Document (E-4a): depth and node count at `$`.
        if (depth > limits.maxDepth()) {
            throw new DocumentParseException("$", "document.limit.depth", "$: nesting exceeds the maximum depth (" + limits.maxDepth() + ")");
        }
        if (val instanceof Map<?, ?> map) {
            budget[0]++;
            if (budget[0] > limits.maxNodeCount()) {
                throw new DocumentParseException("$", "document.limit.nodes", "$: too many nodes materialized (over " + limits.maxNodeCount() + ")");
            }
            List<Edge> edges = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Defensive: Invalid JSON (non-string keys) should never occur with ObjectMapper#readValue
                if (!(entry.getKey() instanceof String k)) {
                    throw new DocumentParseException(path, "document.unlabeled-element", path + ": object key " + entry.getKey() + " is not a string");
                }
                Object v = entry.getValue();
                String kp = PathUtils.child(path, k);
                if (v instanceof List<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        // E-10: the index appears on every edge of a label that occurs more than once.
                        String ip = PathUtils.childPath(path, k, i, list.size());
                        if (item instanceof List<?>) {
                            throw new DocumentParseException(kp + "[" + i + "]", "document.unlabeled-element", kp + "[" + i + "]: an array of arrays has no labeled-edge form");
                        }
                        Document child = buildNode(item, ip, depth + 1, budget, limits);
                        edges.add(new Edge(k, (Target) child));
                    }
                } else {
                    Document child = buildNode(v, kp, depth + 1, budget, limits);
                    edges.add(new Edge(k, (Target) child));
                }
            }
            return new Node(edges);
        }
        if (val instanceof List<?>) {
            throw new DocumentParseException(path, "document.unlabeled-element", path + ": a bare array has no labeled-edge form (arrays appear only as a repeated field)");
        }
        return toScalar(val, path, limits);
    }

    private static Value toScalar(Object value, String path, Limits limits) {
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
            if (IntegerDigits.exceeds(bi, limits.maxIntegerDigits())) {
                throw IntegerDigits.error(path, limits.maxIntegerDigits());
            }
            return new IntegerScalar(bi);
        }
        if (value instanceof Integer i) {
            if (IntegerDigits.exceeds(i, limits.maxIntegerDigits())) {
                throw IntegerDigits.error(path, limits.maxIntegerDigits());
            }
            return new IntegerScalar(BigInteger.valueOf(i));
        }
        if (value instanceof Long l) {
            if (IntegerDigits.exceeds(l, limits.maxIntegerDigits())) {
                throw IntegerDigits.error(path, limits.maxIntegerDigits());
            }
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
        // Fail, don't invent (issue #90): NaN/Infinity has no JSON token, and substituting
        // null is unsafe -- a genuine null and a substituted NaN/Infinity both produce the
        // identical null token and are indistinguishable on read-back. Fails unconditionally,
        // regardless of strict.
        if (rep.adjustments().stream().anyMatch(a -> "write.unsupported-value".equals(a.code()))) {
            throw new WriteException(rep.toString(), rep);
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
                return lowercaseEscapes(MAPPER.writer(printer).writeValueAsString(grouped));
            } else {
                return lowercaseEscapes(MAPPER.writeValueAsString(grouped));
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
                // C-9, folded into this pass (which already visits every node and builds its path):
                // a label with no UTF-8 encoding fails at the node holding the edge.
                if (!Encodability.isEncodable(label)) {
                    rep.add(path, "write.unsupported-value", "edge label has no UTF-8 encoding (a lone surrogate)", "error");
                }
                int i = seen.getOrDefault(label, 0);
                seen.put(label, i + 1);
                int total = totals.getOrDefault(label, 1);
                String p = dev.omnist.document.PathUtils.childPath(path, label, i, total);
                scanJson((Document) edge.target(), p, depth + 1, rep, interleavingFound);
            }
        } else if (doc instanceof StringScalar str) {
            if (!Encodability.isEncodable(str.value())) {
                rep.add(path, "write.unsupported-value", "string value has no UTF-8 encoding (a lone surrogate)", "error");
            }
        } else if (doc instanceof Scalar s) {
            if (s instanceof DateScalar || s instanceof TimeScalar || s instanceof DateTimeScalar) {
                rep.add(path, "format.temporal-stringified", "temporal value written as an ISO-8601 string", "warning");
            } else if (s instanceof NumberScalar num) {
                double d = num.value();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    rep.add(path, "write.unsupported-value", d + " has no representable JSON syntax", "error");
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
                Object child = prepareJson((Document) edge.target(), PathUtils.child(path, label), depth + 1, strict);
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
            // Confirmed-unreachable as of issue #90: write() always calls check() first
            // and fails unconditionally (write.unsupported-value) on a NaN/Infinite value
            // before prepareJson is ever reached with one, so d is always finite here.
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
