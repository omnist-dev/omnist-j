package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.Schema;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Codec for reading and writing the Omnist Document model as TOML (omnist-spec §7.3),
 * built on the {@code tomlj} parser.
 *
 * <p><b>Reading</b>: TOML tables map to {@link dev.omnist.document.Node} values; repeated
 * keys inside an array-of-tables map to repeated edges with the same label. TOML's native
 * date/time/date-time types round-trip directly to their Omnist scalar counterparts.
 *
 * <p><b>Writing</b>: always emits inline-table and inline-array syntax rather than
 * {@code [section]}/{@code [[section]]} headers, sidestepping TOML's header-positional
 * ordering rules. Null-valued leaves cannot be represented in TOML and are dropped, reported
 * via {@link WriteReport}.
 *
 * <p>This class is stateless; all methods are {@code static}.
 */
public final class TomlCodec {

    private TomlCodec() {}

    private static String preprocessToml(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (text.startsWith("\"\"\"", i)) {
                sb.append("\"\"\"");
                i += 3;
                while (i < n && !text.startsWith("\"\"\"", i)) {
                    if (text.startsWith("\\", i) && i + 1 < n) {
                        sb.append(text, i, i + 2);
                        i += 2;
                    } else {
                        sb.append(text.charAt(i));
                        i++;
                    }
                }
                if (i < n) {
                    sb.append("\"\"\"");
                    i += 3;
                }
            } else if (text.startsWith("'''", i)) {
                sb.append("'''");
                i += 3;
                while (i < n && !text.startsWith("'''", i)) {
                    sb.append(text.charAt(i));
                    i++;
                }
                if (i < n) {
                    sb.append("'''");
                    i += 3;
                }
            } else if (text.charAt(i) == '"') {
                sb.append('"');
                i++;
                while (i < n && text.charAt(i) != '"') {
                    if (text.startsWith("\\", i) && i + 1 < n) {
                        sb.append(text, i, i + 2);
                        i += 2;
                    } else {
                        sb.append(text.charAt(i));
                        i++;
                    }
                }
                if (i < n) {
                    sb.append('"');
                    i++;
                }
            } else if (text.charAt(i) == '\'') {
                sb.append('\'');
                i++;
                while (i < n && text.charAt(i) != '\'') {
                    sb.append(text.charAt(i));
                    i++;
                }
                if (i < n) {
                    sb.append('\'');
                    i++;
                }
            } else if (text.charAt(i) == '#') {
                while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                    sb.append(text.charAt(i));
                    i++;
                }
            } else {
                char c = text.charAt(i);
                if ((c >= '0' && c <= '9') || c == '+' || c == '-') {
                    // c is a digit, '+', or '-', all of which satisfy isTokenChar, so
                    // the loop below always advances past `start` at least once --
                    // there's no i == start case to guard against.
                    int start = i;
                    while (i < n && isTokenChar(text.charAt(i))) {
                        i++;
                    }
                    String token = text.substring(start, i);
                    if (isHex(token) || isOctal(token) || isBinary(token) || isDecimal(token)) {
                        int digitCount = countDigits(token);
                        if (digitCount > 4300) {
                            throw new DocumentParseException("$", "document.limit.int-digits", "Integer exceeds maximum digit limit of 4300");
                        }
                        if (digitCount > 18) {
                            sb.append("\"__omnist_int__").append(token).append("\"");
                        } else {
                            sb.append(token);
                        }
                    } else {
                        sb.append(token);
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private static boolean isTokenChar(char c) {
        // 'T'/'z'/'Z' (the date-time separator/UTC-offset markers this scans for)
        // are already inside the 'a'-'z'/'A'-'Z' ranges above, so explicit
        // c == 'T' / 'z' / 'Z' disjuncts here would be provably dead: neither
        // could ever be the deciding clause, since the range check ahead of them
        // in this same expression always short-circuits true first.
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
               c == '_' || c == '+' || c == '-' || c == '.' || c == ':';
    }

    private static int countDigits(String token) {
        int count = 0;
        int start = 0;
        if (token.startsWith("+") || token.startsWith("-")) {
            start = 1;
        }
        if (token.startsWith("0x") || token.startsWith("0o") || token.startsWith("0b") ||
            token.startsWith("0X") || token.startsWith("0O") || token.startsWith("0B")) {
            start += 2;
        }
        for (int i = start; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '_') {
                count++;
            }
        }
        return count;
    }

    private static boolean isHex(String token) {
        if (token.length() < 3) return false;
        char first = token.charAt(0);
        char second = token.charAt(1);
        if (first == '0' && (second == 'x' || second == 'X')) {
            for (int i = 2; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c != '_' && !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isOctal(String token) {
        if (token.length() < 3) return false;
        char first = token.charAt(0);
        char second = token.charAt(1);
        if (first == '0' && (second == 'o' || second == 'O')) {
            for (int i = 2; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c != '_' && !(c >= '0' && c <= '7')) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isBinary(String token) {
        if (token.length() < 3) return false;
        char first = token.charAt(0);
        char second = token.charAt(1);
        if (first == '0' && (second == 'b' || second == 'B')) {
            for (int i = 2; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c != '_' && c != '0' && c != '1') {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isDecimal(String token) {
        // isDecimal's only caller (preprocessToml) always passes a token built from
        // the digit/+/- scan loop above, which never produces an empty string (see
        // that loop's own comment), so there's no real caller path with an empty
        // token to guard against here.
        int start = 0;
        char first = token.charAt(0);
        if (first == '+' || first == '-') {
            start = 1;
        }
        if (start >= token.length()) return false;
        for (int i = start; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '_' && !(c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /** Maximum accepted input length in characters, guarding against oversized TOML input. */
    public static final int MAX_INPUT_LENGTH = 2_000_000;

    /**
     * Parses TOML text into a {@link Document}.
     *
     * @param text the TOML text; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException if the TOML is syntactically invalid or exceeds {@link #MAX_INPUT_LENGTH}
     */
    public static Document read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("input text cannot be null");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new DocumentParseException("$", "document.parse-error", "invalid TOML: input exceeds maximum size limit of " + MAX_INPUT_LENGTH + " characters");
        }

        String preprocessed;
        TomlParseResult result;
        try {
            preprocessed = preprocessToml(text);
            result = Toml.parse(preprocessed);
        } catch (Exception | AssertionError e) {
            throw new DocumentParseException("$", "document.parse-error", "invalid TOML: " + e.getMessage(), e);
        }
        return documentFromParseResult(result);
    }

    /**
     * @deprecated The {@code schema} parameter is ignored for TOML. Use {@link #read(String)}
     *             followed by {@link dev.omnist.validation.Materializer#materialize(Document, Schema)}
     *             if schema-driven coercion is required.
     */
    @Deprecated(since = "0.1.0-alpha", forRemoval = true)
    public static Document read(String text, Schema schema) {
        return read(text);
    }

    // Extracted so the toMap()==null defensive branch can be exercised via
    // reflection with a hand-built TomlParseResult stub, without any
    // production-visible seam: tomlj's TomlTable#toMap() (which
    // TomlParseResult extends) is declared to return Map<String, Object>
    // with no Optional/nullable contract in its own API, and no malformed-
    // but-still-non-error-result input has been found that returns null
    // through the real Toml.parse() path.
    private static Document documentFromParseResult(TomlParseResult result) {
        if (result.hasErrors()) {
            throw new DocumentParseException("$", "document.parse-error", "invalid TOML: " + result.errors().get(0).toString());
        }

        Map<String, Object> raw = result.toMap();
        if (raw == null) {
            throw new DocumentParseException("$", "document.parse-error", "invalid TOML: no document found");
        }

        int[] budget = new int[]{0};
        return buildNode(raw, "$", 0, budget);
    }

    private static Document buildNode(Object val, String path, int depth, int[] budget) {
        Limits limits = Limits.DEFAULT;
        if (depth > limits.maxDepth()) {
            throw new DocumentParseException(path, "document.limit.depth", path + ": nesting exceeds the maximum depth (" + limits.maxDepth() + ")");
        }
        if (val instanceof org.tomlj.TomlTable tt) {
            val = tt.toMap();
        }
        // val instanceof TomlArray is not reachable here: every caller of
        // buildNode already unwraps a TomlArray before recursing (the map-value
        // path at "v instanceof TomlArray" below, and the array-item path,
        // which additionally rejects an array-of-arrays before ever reaching
        // this call) -- the only remaining entry point (read()) always passes
        // result.toMap(), never a bare TomlArray.
        if (val instanceof Map<?, ?> map) {
            budget[0]++;
            if (budget[0] > limits.maxNodeCount()) {
                throw new DocumentParseException(path, "document.limit.nodes", path + ": too many nodes materialized (over " + limits.maxNodeCount() + ")");
            }
            List<Edge> edges = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String k)) {
                    throw new DocumentParseException(path, "document.unlabeled-element", path + ": object key " + entry.getKey() + " is not a string");
                }
                Object v = entry.getValue();
                if (v instanceof org.tomlj.TomlArray ta) {
                    v = ta.toList();
                }
                if (v instanceof org.tomlj.TomlTable tt) {
                    v = tt.toMap();
                }
                String kp = path.equals("$") ? "$." + k : path + "." + k;
                if (v instanceof List<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        if (item instanceof org.tomlj.TomlArray ta2) {
                            item = ta2.toList();
                        }
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
            return new dev.omnist.document.Node(edges);
        }
        // val instanceof List<?> is not reachable here either, for the same
        // reason: a list-valued map entry is handled by the "v instanceof
        // List<?>" branch above (iterating elements, never calling buildNode(v)
        // directly), and the top-level entry point always passes a Map.
        return toScalar(val);
    }

    private static Value toScalar(Object value) {
        if (value == null) {
            return Value.NULL;
        }
        if (value instanceof String s) {
            if (s.startsWith("__omnist_int__")) {
                String literal = s.substring(14);
                BigInteger bi;
                if (literal.startsWith("0x") || literal.startsWith("0X")) {
                    bi = new BigInteger(literal.substring(2).replace("_", ""), 16);
                } else if (literal.startsWith("0o") || literal.startsWith("0O")) {
                    bi = new BigInteger(literal.substring(2).replace("_", ""), 8);
                } else if (literal.startsWith("0b") || literal.startsWith("0B")) {
                    bi = new BigInteger(literal.substring(2).replace("_", ""), 2);
                } else {
                    String clean = literal.replace("_", "");
                    if (clean.startsWith("+")) {
                        clean = clean.substring(1);
                    }
                    bi = new BigInteger(clean);
                }
                return new IntegerScalar(bi);
            }
            return new StringScalar(s);
        }
        if (value instanceof Boolean b) {
            return new BooleanScalar(b);
        }
        if (value instanceof LocalDate ld) {
            return new DateScalar(ld);
        }
        if (value instanceof LocalTime lt) {
            return new TimeScalar(TimeValue.of(lt));
        }
        if (value instanceof LocalDateTime ldt) {
            return new DateTimeScalar(DateTimeValue.of(ldt));
        }
        if (value instanceof OffsetDateTime odt) {
            return new DateTimeScalar(DateTimeValue.of(odt.toLocalDateTime(), odt.getOffset()));
        }
        // Verified empirically: tomlj's TomlParseResult#toMap() only ever
        // produces Long for native integers and Double for native floats --
        // never BigInteger/Integer/Float/BigDecimal. Literals long enough to
        // need BigInteger are already intercepted by preprocessToml's own
        // __omnist_int__ string-wrapping above before tomlj ever parses them.
        // Kept as defensive handling in case tomlj's behavior ever changes.
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
        throw new IllegalArgumentException("Unsupported TOML value type: " + value.getClass().getName());
    }

    /**
     * Serializes a {@link Document} to TOML text, non-strict (dropping unrepresentable
     * leaves rather than throwing). Equivalent to {@code write(node, false, null)}.
     *
     * @param node the document to serialize; must be a {@link dev.omnist.document.Node}
     * @return the TOML text
     * @throws WriteException if {@code node} is not a top-level table
     */
    public static String write(Document node) {
        return write(node, false, null);
    }

    /**
     * Serializes a {@link Document} to TOML text.
     *
     * @param node   the document to serialize; must be a {@link dev.omnist.document.Node}
     *               (TOML requires a top-level table)
     * @param strict if {@code true}, throws when the document contains any adjustment
     *               (e.g. a dropped null); if {@code false}, applies the adjustment and continues
     * @param report if non-{@code null}, every adjustment made during writing is appended here
     * @return the TOML text
     * @throws WriteException if {@code node} is not a top-level table, or if {@code strict}
     *         is {@code true} and an adjustment was required
     */
    public static String write(Document node, boolean strict, WriteReport report) {
        if (!(node instanceof dev.omnist.document.Node)) {
            throw new WriteException("TOML needs a top-level table (the root must be an object)");
        }
        WriteReport rep = check(node);
        if (report != null) {
            report.addAll(rep.adjustments());
        }
        if (strict && !rep.adjustments().isEmpty()) {
            throw new WriteException(rep.toString(), rep);
        }
        Document stripped = stripNulls(node, "$", rep, 0);
        Object prepared = prepareToml(stripped, "$", 0, strict);
        @SuppressWarnings("unchecked")
        Map<String, Object> grouped = (Map<String, Object>) dev.omnist.document.PathUtils.groupEdges(prepared);

        StringBuilder sb = new StringBuilder();
        writeTable("", grouped, sb);
        return sb.toString().trim();
    }

    private static void writeTable(String currentHeader, Map<String, Object> table, StringBuilder sb) {
        if (!currentHeader.isEmpty()) {
            sb.append("[").append(currentHeader).append("]\n");
        }
        
        boolean hasSimple = false;
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map || isListOfMaps(val)) {
                continue;
            }
            String key = formatKey(entry.getKey());
            sb.append(key).append(" = ").append(formatVal(val)).append("\n");
            hasSimple = true;
        }
        if (hasSimple) {
            sb.append("\n");
        }
        
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map) {
                String nextHeader = currentHeader.isEmpty() ? formatKey(entry.getKey()) : currentHeader + "." + formatKey(entry.getKey());
                @SuppressWarnings("unchecked")
                Map<String, Object> subTable = (Map<String, Object>) val;
                writeTable(nextHeader, subTable, sb);
            }
        }
        
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Object val = entry.getValue();
            if (isListOfMaps(val)) {
                String nextHeader = currentHeader.isEmpty() ? formatKey(entry.getKey()) : currentHeader + "." + formatKey(entry.getKey());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) val;
                for (Map<String, Object> item : list) {
                    sb.append("[[").append(nextHeader).append("]]\n");
                    writeTable("", item, sb);
                }
            }
        }
    }

    private static boolean isListOfMaps(Object val) {
        if (val instanceof List<?> list) {
            return !list.isEmpty() && list.get(0) instanceof Map;
        }
        return false;
    }

    private static String formatKey(String key) {
        if (key.matches("^[a-zA-Z0-9_-]+$")) {
            return key;
        }
        return "\"" + escapeString(key) + "\"";
    }

    private static String formatVal(Object val) {
        if (val instanceof String s) {
            return "\"" + escapeString(s) + "\"";
        }
        if (val instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (val instanceof LocalDate ld) {
            return ld.toString();
        }
        if (val instanceof LocalTime lt) {
            return lt.toString();
        }
        if (val instanceof LocalDateTime ldt) {
            return ldt.toString();
        }
        if (val instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (val instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatVal(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return val.toString();
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c <= 0x1F || c == 0x7F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Computes what adjustments {@link #write(Document)} would make to {@code node}
     * without actually producing TOML text — useful for checking whether a document
     * would round-trip cleanly before committing to a strict write.
     *
     * @param node the document to check; non-{@link dev.omnist.document.Node} values
     *             yield an empty report rather than an error
     * @return the adjustments (e.g. dropped nulls) that a write of {@code node} would require
     */
    public static WriteReport check(Document node) {
        WriteReport rep = new WriteReport();
        if (!(node instanceof dev.omnist.document.Node)) {
            return rep;
        }
        stripNulls(node, "$", rep, 0);
        return rep;
    }

    private static Document stripNulls(Document doc, String path, WriteReport rep, int depth) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (doc instanceof dev.omnist.document.Node node) {
            List<Edge> edges = new ArrayList<>();
            Map<String, Integer> totals = dev.omnist.document.PathUtils.countLabels(node);
            Map<String, Integer> seen = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                int i = seen.getOrDefault(label, 0);
                seen.put(label, i + 1);
                int total = totals.getOrDefault(label, 1);
                String p = dev.omnist.document.PathUtils.childPath(path, label, i, total);
                Document child = (Document) edge.target();
                if (child instanceof Value.NullValue) {
                    rep.add(p, "format.null-unrepresentable", "null value dropped (TOML has no null)", "warning");
                    continue;
                }
                edges.add(new Edge(label, (Target) stripNulls(child, p, rep, depth + 1)));
            }
            return new dev.omnist.document.Node(edges);
        }
        return doc;
    }

    private static Object prepareToml(Document doc, String path, int depth, boolean strict) {
        // No depth guard here: write() only reaches prepareToml after stripNulls
        // (invoked directly, and earlier via check()) has already walked this
        // same document and thrown if any depth exceeded 200, so that bound is
        // already established; stripNulls never increases depth.
        if (doc instanceof dev.omnist.document.Node node) {
            List<Object[]> edges = new ArrayList<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                Object child = prepareToml((Document) edge.target(), path + "." + label, depth + 1, strict);
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
            return num.value();
        } else if (doc instanceof DateScalar date) {
            return date.value();
        } else if (doc instanceof DateTimeScalar dt) {
            DateTimeValue dtv = dt.value();
            if (dtv.offset() != null) {
                return OffsetDateTime.of(dtv.dateTime(), dtv.offset());
            } else {
                return dtv.dateTime();
            }
        } else {
            // Exhaustive over Document's sealed hierarchy (Node, Value ->
            // Scalar's 7 variants | NullValue) -- TimeScalar is the only
            // remaining case, no fallback is reachable.
            TimeScalar time = (TimeScalar) doc;
            return time.value().time();
        }
    }

}
