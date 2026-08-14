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
                    int start = i;
                    while (i < n && isTokenChar(text.charAt(i))) {
                        i++;
                    }
                    if (i == start) {
                        sb.append(c);
                        i++;
                    } else {
                        String token = text.substring(start, i);
                        if (isHex(token) || isOctal(token) || isBinary(token) || isDecimal(token)) {
                            int digitCount = countDigits(token);
                            if (digitCount > 4300) {
                                throw new RuntimeException("Integer exceeds maximum digit limit of 4300");
                            }
                            if (digitCount > 18) {
                                sb.append("\"__omnist_int__").append(token).append("\"");
                            } else {
                                sb.append(token);
                            }
                        } else {
                            sb.append(token);
                        }
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
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
               c == '_' || c == '+' || c == '-' || c == '.' || c == ':' || c == 'T' || c == 'z' || c == 'Z';
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
        if (token.isEmpty()) return false;
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

    public static final int MAX_INPUT_LENGTH = 2_000_000;

    public static Document read(String text) {
        return read(text, null);
    }

    public static Document read(String text, Schema schema) {
        if (text == null) {
            throw new IllegalArgumentException("input text cannot be null");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new RuntimeException("invalid TOML: input exceeds maximum size limit of " + MAX_INPUT_LENGTH + " characters");
        }

        String preprocessed;
        TomlParseResult result;
        try {
            preprocessed = preprocessToml(text);
            result = Toml.parse(preprocessed);
        } catch (Exception | AssertionError e) {
            throw new RuntimeException("invalid TOML: " + e.getMessage(), e);
        }
        if (result.hasErrors()) {
            throw new RuntimeException("invalid TOML: " + result.errors().get(0).toString());
        }

        Map<String, Object> raw = result.toMap();
        if (raw == null) {
            throw new RuntimeException("invalid TOML: no document found");
        }

        int[] budget = new int[]{0};
        return buildNode(raw, "$", 0, budget);
    }

    private static Document buildNode(Object val, String path, int depth, int[] budget) {
        budget[0]++;
        if (budget[0] > 1_000_000) {
            throw new RuntimeException(path + ": too many nodes materialized (over 1000000)");
        }
        if (depth > 200) {
            throw new RuntimeException(path + ": nesting exceeds the maximum depth (200)");
        }
        if (val instanceof org.tomlj.TomlTable tt) {
            val = tt.toMap();
        }
        if (val instanceof org.tomlj.TomlArray ta) {
            val = ta.toList();
        }
        if (val instanceof Map<?, ?> map) {
            List<Edge> edges = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String k)) {
                    throw new RuntimeException(path + ": object key " + entry.getKey() + " is not a string");
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
                            throw new RuntimeException(kp + "[" + i + "]: an array of arrays has no labeled-edge form");
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
        if (val instanceof List<?>) {
            throw new RuntimeException(path + ": a bare array has no labeled-edge form (arrays appear only as a repeated field)");
        }
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

    public static String write(Document node) {
        return write(node, false, null);
    }

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
        Map<String, Object> grouped = (Map<String, Object>) grouped(prepared, 0);

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
        StringBuilder sb = new StringBuilder();
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
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

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
            Map<String, Integer> counts = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                int i = counts.getOrDefault(label, 0);
                counts.put(label, i + 1);
                String p = path.equals("$") ? "$." + label : path + "." + label;
                if (i > 0) {
                    p = p + "[" + i + "]";
                }
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

    private static Object grouped(Object node, int depth) {
        // No depth guard here: grouped()'s tree mirrors prepareToml's output,
        // which mirrors the stripped document already bounded by stripNulls.
        if (!(node instanceof List<?> list)) {
            return node;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Object item : list) {
            Object[] edge = (Object[]) item;
            String label = (String) edge[0];
            counts.put(label, counts.getOrDefault(label, 0) + 1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object item : list) {
            Object[] edge = (Object[]) item;
            String label = (String) edge[0];
            Object child = grouped(edge[1], depth + 1);
            if (counts.get(label) > 1) {
                @SuppressWarnings("unchecked")
                List<Object> targetList = (List<Object>) out.computeIfAbsent(label, k -> new ArrayList<>());
                targetList.add(child);
            } else {
                out.put(label, child);
            }
        }
        return out;
    }
}
