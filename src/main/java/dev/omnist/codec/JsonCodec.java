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

public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {}

    public static Document read(String text) {
        return read(text, null);
    }

    public static Document read(String text, Schema schema) {
        try {
            Object raw = MAPPER.readValue(text, Object.class);
            int[] budget = new int[]{0};
            Document doc = buildNode(raw, "$", 0, budget);
            if (schema != null) {
                // Since materialize is a separate stage and not implemented yet,
                // we just return the parsed doc.
                return doc;
            }
            return doc;
        } catch (IOException e) {
            throw new RuntimeException("invalid JSON: " + e.getMessage(), e);
        }
    }

    private static Document buildNode(Object val, String path, int depth, int[] budget) {
        budget[0]++;
        if (budget[0] > 1_000_000) {
            throw new RuntimeException(path + ": too many nodes materialized (over 1000000)");
        }
        if (depth > 200) {
            throw new RuntimeException(path + ": nesting exceeds the maximum depth (200)");
        }
        if (val instanceof Map<?, ?> map) {
            List<Edge> edges = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Defensive: Invalid JSON (non-string keys) should never occur with ObjectMapper#readValue
                if (!(entry.getKey() instanceof String k)) {
                    throw new RuntimeException(path + ": object key " + entry.getKey() + " is not a string");
                }
                Object v = entry.getValue();
                String kp = path.equals("$") ? "$." + k : path + "." + k;
                if (v instanceof List<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
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
            return new Node(edges);
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
            return new StringScalar(s);
        }
        if (value instanceof Boolean b) {
            return new BooleanScalar(b);
        }
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

    public static String write(Document node) {
        return write(node, null, false, null);
    }

    public static String write(Document node, Integer indent, boolean strict, WriteReport report) {
        WriteReport rep = check(node);
        if (report != null) {
            report.addAll(rep.adjustments());
        }
        if (strict && !rep.adjustments().isEmpty()) {
            throw new WriteException(rep.toString(), rep);
        }
        Object prepared = prepareJson(node, "$", 0, strict);
        Object grouped = grouped(prepared, 0);
        try {
            if (indent != null && indent > 0) {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(grouped);
            } else {
                return MAPPER.writeValueAsString(grouped);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static WriteReport check(Document node) {
        WriteReport rep = new WriteReport();
        scanJson(node, "$", 0, rep);
        return rep;
    }

    private static void scanJson(Document doc, String path, int depth, WriteReport rep) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (doc instanceof Node node) {
            Map<String, Integer> counts = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                int i = counts.getOrDefault(label, 0);
                counts.put(label, i + 1);
                String p = path.equals("$") ? "$." + label : path + "." + label;
                if (i > 0) {
                    p = p + "[" + i + "]";
                }
                scanJson((Document) edge.target(), p, depth + 1, rep);
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
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
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

    private static Object grouped(Object node, int depth) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
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
