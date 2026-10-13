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

    /** The spelling the reader hands to tomlj for an integer literal whose value may not fit a long. */
    private static final String INT_MARKER_PREFIX = "__omnist_int_";

    /**
     * Whether the token starting at {@code from} is the left-hand side of a key/value pair: only
     * spaces and tabs separate it from the {@code =}.
     */
    private static boolean isKey(String text, int from) {
        int j = from;
        while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
            j++;
        }
        return j < text.length() && text.charAt(j) == '=';
    }

    private static String preprocessToml(String text, String marker) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = text.length();
        // The scan has no TOML grammar, so it tracks just enough to leave keys and table headers
        // alone: a number-shaped token there is a name, not a value.
        boolean atLineStart = true;
        boolean inHeader = false;
        int nesting = 0;
        while (i < n) {
            char first = text.charAt(i);
            if (first == '\n') {
                atLineStart = true;
                inHeader = false;
            } else if (first != ' ' && first != '\t' && first != '\r') {
                if (atLineStart && first == '[' && nesting == 0) {
                    inHeader = true;
                } else if (!inHeader && (first == '[' || first == '{')) {
                    nesting++;
                } else if (!inHeader && (first == ']' || first == '}') && nesting > 0) {
                    nesting--;
                }
                atLineStart = false;
            }
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
                    if (inHeader || isKey(text, i)) {
                        sb.append(token);
                    } else if (isHex(token) || isOctal(token) || isBinary(token) || isDecimal(token)) {
                        // An integer of more than 18 digits may not fit a long: hand tomlj a
                        // string and let toScalar convert it, bounded by the digit limit (D-28).
                        if (countDigits(token) > 18) {
                            sb.append('"').append(marker).append(token).append('"');
                        } else {
                            sb.append(token);
                        }
                    } else {
                        // D-29: a literal that rounds to infinity is accepted and reads as one, and
                        // one that rounds to zero reads as 0.0; tomlj refuses both ("too large",
                        // "too small") as syntax errors.
                        String extreme = extremeFloat(token);
                        sb.append(extreme != null ? extreme : token);
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }
        return sb.toString();
    }

    /**
     * The spelling tomlj accepts for a float literal whose value rounds to an infinity ({@code inf},
     * {@code -inf}) or, with a nonzero mantissa, to zero ({@code 0.0}, {@code -0.0}) (D-29); null for
     * any other token.
     */
    private static String extremeFloat(String token) {
        // Only reached for a token that is not an integer literal, so a match is a float.
        if (!isFloatLiteral(token)) {
            return null;
        }
        String clean = token.replace("_", "");
        boolean negative = clean.charAt(0) == '-';
        double value = Double.parseDouble(clean);
        if (Double.isInfinite(value)) {
            return negative ? "-inf" : "inf";
        }
        String mantissa = clean.split("[eE]")[0];
        if (value == 0.0 && mantissa.chars().anyMatch(c -> c >= '1')) {
            return negative ? "-0.0" : "0.0";
        }
        return null;
    }

    /**
     * Whether {@code token} is a TOML float literal: an optional sign, digits, an optional fraction
     * and an optional exponent, an underscore only between two digits. Scanned by hand: a regular
     * expression with a repeated group recurses once per digit and overflows the stack on a long
     * literal.
     */
    private static boolean isFloatLiteral(String token) {
        int i = 0;
        if (token.charAt(0) == '+' || token.charAt(0) == '-') {
            i++;
        }
        // TOML forbids a leading zero in the integer part (01e999, 0_1e5): not a float literal, so
        // tomlj reports it as the syntax error it is instead of the D-29 rewrite reading it.
        if (i + 1 < token.length() && token.charAt(i) == '0'
                && (token.charAt(i + 1) == '_' || (token.charAt(i + 1) >= '0' && token.charAt(i + 1) <= '9'))) {
            return false;
        }
        i = digitRun(token, i);
        if (i > 0 && i < token.length() && token.charAt(i) == '.') {
            i = digitRun(token, i + 1);
        }
        if (i > 0 && i < token.length() && (token.charAt(i) == 'e' || token.charAt(i) == 'E')) {
            i++;
            if (i < token.length() && (token.charAt(i) == '+' || token.charAt(i) == '-')) {
                i++;
            }
            i = digitRun(token, i);
        }
        return i == token.length();
    }

    /**
     * The index after the run of digits (with single underscores between digits) that starts at
     * {@code from}, or -1 if there is no digit there.
     */
    private static int digitRun(String token, int from) {
        int i = from;
        if (i >= token.length() || !Character.isDigit(token.charAt(i))) {
            return -1;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '_' && i + 1 < token.length() && token.charAt(i + 1) >= '0' && token.charAt(i + 1) <= '9') {
                i += 2;
            } else {
                break;
            }
        }
        return i;
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

    /**
     * Parses TOML text into a {@link Document}.
     *
     * @param text the TOML text; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException if the TOML is syntactically invalid or is larger than
     *         {@link Limits#DEFAULT_MAX_INPUT_BYTES} bytes
     */
    public static Document read(String text) {
        return readWithLimits(text, Limits.DEFAULT);
    }

    /**
     * Parses TOML text into a {@link Document}, refusing input larger than
     * {@link Limits#maxInputBytes()} bytes (D-23) and honouring the depth, node-count and
     * integer-digit bounds of {@code limits} (D-9, E-4a) with the diagnostics the OML reader gives:
     * {@code document.limit.depth} and {@code document.limit.nodes} at {@code $},
     * {@code document.limit.int-digits} at the Document path of the integer, counting the digits
     * of the value whatever the literal's radix or underscores (D-28).
     *
     * @param text   the TOML text; must not be {@code null}
     * @param limits the limits; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException as {@link #read(String)}, with {@code document.limit.input-size}
     *         at {@code $} for an input over the maximum
     */
    public static Document readWithLimits(String text, Limits limits) {
        text = CodecInput.prepare(text, "TOML", java.util.Objects.requireNonNull(limits, "limits").maxInputBytes());

        // A marker no input can contain by accident or by design: a TOML string that happens to
        // start with it must stay a string.
        String marker = INT_MARKER_PREFIX + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()) + "__";
        TomlParseResult result;
        try {
            result = Toml.parse(preprocessToml(text, marker));
        } catch (Exception | AssertionError | StackOverflowError e) {
            throw unexpectedFailure(e, limits);
        }
        return documentFromParseResult(result, marker, limits);
    }

    /**
     * Maps a failure thrown (rather than reported) by tomlj. It parses nested arrays and inline
     * tables recursively, so a hostile input can exhaust the stack before any depth limit is
     * consulted: that is a depth violation (D-13), not a crash and not a syntax error. Anything
     * else it throws is reported as {@code parse.codec-syntax}.
     */
    static DocumentParseException unexpectedFailure(Throwable e, Limits limits) {
        if (e instanceof StackOverflowError) {
            return new DocumentParseException("$", "document.limit.depth",
                    "$: nesting exceeds the maximum depth (" + limits.maxDepth() + ")", e);
        }
        return CodecInput.syntax("TOML", String.valueOf(e.getMessage()), 1, 1, e);
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

    private static Document documentFromParseResult(TomlParseResult result, String marker, Limits limits) {
        if (result.hasErrors()) {
            org.tomlj.TomlParseError first = result.errors().get(0);
            // TomlParseError.position() is never null: tomlj builds every error with a position.
            org.tomlj.TomlPosition at = first.position();
            throw CodecInput.syntax("TOML", String.valueOf(first.getMessage()), at.line(), at.column(), first);
        }

        return buildTable(result, "$", 0, new int[]{0}, marker, limits);
    }

    /**
     * Builds a Node from a table, edges in the order the keys were written (D-1). tomlj's
     * {@code toMap()} is a {@code HashMap} that loses that order, so the table is walked through
     * its own {@code keySet()}, which keeps it, and each value is fetched by a one-element key
     * path (a key may contain a dot).
     */
    private static Document buildTable(org.tomlj.TomlTable table, String path, int depth, int[] budget, String marker, Limits limits) {
        // The OML reader's diagnostics for the same Document (E-4a): depth and node count at `$`.
        if (depth > limits.maxDepth()) {
            throw new DocumentParseException("$", "document.limit.depth", "$: nesting exceeds the maximum depth (" + limits.maxDepth() + ")");
        }
        budget[0]++;
        if (budget[0] > limits.maxNodeCount()) {
            throw new DocumentParseException("$", "document.limit.nodes", "$: too many nodes materialized (over " + limits.maxNodeCount() + ")");
        }
        List<Edge> edges = new ArrayList<>();
        for (String k : table.keySet()) {
            Object v = table.get(List.of(k));
            String kp = PathUtils.child(path, k);
            if (v instanceof org.tomlj.TomlArray array) {
                for (int i = 0; i < array.size(); i++) {
                    Object item = array.get(i);
                    // E-10: the index appears on every edge of a label that occurs more than once.
                    String ip = PathUtils.childPath(path, k, i, array.size());
                    if (item instanceof org.tomlj.TomlArray) {
                        throw new DocumentParseException(kp + "[" + i + "]", "document.unlabeled-element", kp + "[" + i + "]: an array of arrays has no labeled-edge form");
                    }
                    edges.add(new Edge(k, (Target) buildValue(item, ip, depth + 1, budget, marker, limits)));
                }
            } else {
                edges.add(new Edge(k, (Target) buildValue(v, kp, depth + 1, budget, marker, limits)));
            }
        }
        return new dev.omnist.document.Node(edges);
    }

    private static Document buildValue(Object val, String path, int depth, int[] budget, String marker, Limits limits) {
        if (val instanceof org.tomlj.TomlTable table) {
            return buildTable(table, path, depth, budget, marker, limits);
        }
        if (depth > limits.maxDepth()) {
            throw new DocumentParseException("$", "document.limit.depth", "$: nesting exceeds the maximum depth (" + limits.maxDepth() + ")");
        }
        return toScalar(val, path, marker, limits);
    }

    private static Value toScalar(Object value, String path, String marker, Limits limits) {
        if (value == null) {
            return Value.NULL;
        }
        if (value instanceof String s) {
            if (s.startsWith(marker)) {
                String literal = s.substring(marker.length());
                String clean = literal.replace("_", "");
                String sign = clean.startsWith("-") ? "-" : "";
                if (clean.startsWith("+") || clean.startsWith("-")) {
                    clean = clean.substring(1);
                }
                BigInteger bi;
                if (clean.startsWith("0x") || clean.startsWith("0X")) {
                    bi = IntegerDigits.parse(clean.substring(2), 16, limits.maxIntegerDigits());
                } else if (clean.startsWith("0o") || clean.startsWith("0O")) {
                    bi = IntegerDigits.parse(clean.substring(2), 8, limits.maxIntegerDigits());
                } else if (clean.startsWith("0b") || clean.startsWith("0B")) {
                    bi = IntegerDigits.parse(clean.substring(2), 2, limits.maxIntegerDigits());
                } else {
                    bi = IntegerDigits.parse(clean, 10, limits.maxIntegerDigits());
                }
                if (bi == null) {
                    throw IntegerDigits.error(path, limits.maxIntegerDigits());
                }
                return new IntegerScalar(sign.isEmpty() ? bi : bi.negate());
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
            // D-28: the digits of the value, whatever the literal's radix or underscores.
            if (IntegerDigits.exceeds(l, limits.maxIntegerDigits())) {
                throw IntegerDigits.error(path, limits.maxIntegerDigits());
            }
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
        // Fail, don't invent (issue #89): a null leaf has no TOML token, and silently
        // dropping the edge erases its existence entirely -- read the output back and
        // there's zero trace an edge with that label ever existed. Fails unconditionally,
        // regardless of strict.
        if (rep.adjustments().stream().anyMatch(a -> "write.unsupported-value".equals(a.code()))) {
            throw new WriteException(rep.toString(), rep);
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
            return TimeValue.localText(lt);
        }
        if (val instanceof LocalDateTime ldt) {
            return DateTimeValue.localText(ldt);
        }
        if (val instanceof OffsetDateTime odt) {
            return DateTimeValue.of(odt.toLocalDateTime(), odt.getOffset()).format();
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
        stripNulls(node, "$", rep, 0, new boolean[]{false});
        return rep;
    }

    private static Document stripNulls(Document doc, String path, WriteReport rep, int depth) {
        return stripNulls(doc, path, rep, depth, new boolean[]{false});
    }

    private static Document stripNulls(Document doc, String path, WriteReport rep, int depth, boolean[] interleavingFound) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (doc instanceof dev.omnist.document.Node node) {
            if (!interleavingFound[0] && dev.omnist.document.PathUtils.hasInterleavedLabels(node)) {
                rep.add("$", "format.interleaving-lost",
                        "cross-label edge interleaving cannot be represented; grouping same-label edges together lost the original relative order",
                        "warning");
                interleavingFound[0] = true;
            }
            List<Edge> edges = new ArrayList<>();
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
                Document child = (Document) edge.target();
                if (child instanceof StringScalar str && !Encodability.isEncodable(str.value())) {
                    rep.add(p, "write.unsupported-value", "string value has no UTF-8 encoding (a lone surrogate)", "error");
                }
                if (child instanceof TimeScalar ts && ts.value().offset() != null) {
                    rep.add(p, "format.temporal-stringified",
                            "time-of-day with an offset written as a string (TOML has no offset time)", "warning");
                }
                if (child instanceof Value.NullValue) {
                    rep.add(p, "write.unsupported-value", "null has no representable TOML syntax", "error");
                    continue;
                }
                edges.add(new Edge(label, (Target) stripNulls(child, p, rep, depth + 1, interleavingFound)));
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
                Object child = prepareToml((Document) edge.target(), PathUtils.child(path, label), depth + 1, strict);
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
            // TOML has no time-of-day literal with an offset (E-37 rule 4): an ISO-8601 string.
            return time.value().offset() == null ? time.value().time() : time.value().format();
        }
    }

}
