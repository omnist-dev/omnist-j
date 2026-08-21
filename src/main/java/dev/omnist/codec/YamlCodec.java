package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.Schema;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Codec for reading and writing the Omnist Document model as YAML (omnist-spec §7.3),
 * built on SnakeYAML.
 *
 * <p><b>Reading</b>: YAML mappings map to {@link dev.omnist.document.Node} values;
 * SnakeYAML's default core-schema resolution handles booleans (including YAML 1.1
 * words like {@code yes}/{@code no}/{@code on}/{@code off}) with no customization
 * needed. Timestamps are custom-resolved to distinguish a bare date from a full
 * date-time before falling back to SnakeYAML's own timestamp construction.
 *
 * <p><b>Writing</b>: cannot achieve round-trip fidelity for {@code time}-kind
 * scalars — no safe bare YAML spelling exists for a time-of-day that doesn't
 * collide with YAML's sexagesimal (base-60) number notation — so {@code time}
 * values are written as quoted ISO-8601 strings via the usual
 * {@code format.temporal-stringified} adjustment.
 *
 * <p>This class is stateless; all methods are {@code static}.
 */
public final class YamlCodec {

    private YamlCodec() {}

    // A prior version of this class subclassed org.yaml.snakeyaml.resolver.Resolver
    // to widen the BOOL tag's implicit-match pattern to accept YAML 1.1-style
    // words (yes/no/on/off). A live diagnostic (a logging Resolver subclass run
    // against this project's pinned SnakeYAML version) confirmed the base
    // Resolver constructor never actually routes Tag.BOOL through
    // addImplicitResolver at all -- only INT and FLOAT are -- so the override
    // had no effect. YAML 1.1 booleans already resolve correctly via
    // SnakeYAML's own default BOOL pattern with no customization needed; the
    // subclass was removed and the base Resolver is used directly below.

    private static class CustomConstructor extends SafeConstructor {
        public CustomConstructor(LoaderOptions loaderOptions) {
            super(loaderOptions);
            this.yamlConstructors.put(Tag.TIMESTAMP, new ConstructTimestamp());
        }

        private class ConstructTimestamp extends AbstractConstruct {
            @Override
            public Object construct(Node node) {
                String val = ((ScalarNode) node).getValue();
                if (val.length() == 10 && val.indexOf('-') == 4 && val.lastIndexOf('-') == 7) {
                    try {
                        return java.time.LocalDate.parse(val);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                try {
                    return parseDateTimeValue(val);
                } catch (Exception e) {
                    // ignore
                }
                return new ConstructYamlTimestamp().construct(node);
            }
        }
    }

    private static DateTimeValue parseDateTimeValue(String text) {
        return DateTimeValue.parse(text.replace(' ', 'T'));
    }

    /** Maximum accepted input length in characters, guarding against oversized YAML input. */
    public static final int MAX_INPUT_LENGTH = 2_000_000;

    /**
     * Parses YAML text into a {@link Document}.
     *
     * @param text the YAML text; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException if the YAML is syntactically invalid or exceeds {@link #MAX_INPUT_LENGTH}
     */
    public static Document read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("input text cannot be null");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new DocumentParseException("$", "document.parse-error", "invalid YAML: input exceeds maximum size limit of " + MAX_INPUT_LENGTH + " characters");
        }

        LoaderOptions loaderOptions = new LoaderOptions();
        CustomConstructor constructor = new CustomConstructor(loaderOptions);
        org.yaml.snakeyaml.resolver.Resolver resolver = new org.yaml.snakeyaml.resolver.Resolver();
        DumperOptions dumperOptions = new DumperOptions();
        Yaml yaml = new Yaml(constructor, new Representer(dumperOptions), dumperOptions, loaderOptions, resolver);

        Object raw;
        try {
            Iterable<Object> docs = yaml.loadAll(text);
            Iterator<Object> it = docs.iterator();
            if (!it.hasNext()) {
                throw new DocumentParseException("$", "document.parse-error", "no document found");
            }
            raw = it.next();
            if (it.hasNext()) {
                throw new DocumentParseException("$", "document.parse-error", "expected a single document in the stream but found another document");
            }
        } catch (DocumentParseException dpe) {
            throw dpe;
        } catch (Exception e) {
            throw new DocumentParseException("$", "document.parse-error", "invalid YAML: " + e.getMessage(), e);
        }

        int[] budget = new int[]{0};
        return buildNode(raw, "$", 0, budget);
    }

    /**
     * @deprecated The {@code schema} parameter is ignored for YAML. Use {@link #read(String)}
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
                if (!(entry.getKey() instanceof String k)) {
                    throw new DocumentParseException(path, "document.unlabeled-element", path + ": object key " + entry.getKey() + " is not a string (unlabeled element in key position)");
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
            return new dev.omnist.document.Node(edges);
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
        if (value instanceof LocalDate ld) {
            return new DateScalar(ld);
        }
        if (value instanceof DateTimeValue dtv) {
            return new DateTimeScalar(dtv);
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
        if (value instanceof java.util.Date d) {
            return new DateTimeScalar(DateTimeValue.of(d.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(), ZoneOffset.UTC));
        }
        // UNREACHABLE: CustomConstructor + SnakeYAML's own SafeConstructor guarantee
        // the type is one of: null, String, Boolean, LocalDate, DateTimeValue, Integer,
        // Long, Double, Float, BigDecimal, or java.util.Date — all handled above.
        throw new IllegalArgumentException("Unsupported YAML value type: " + value.getClass().getName());
    }

    /**
     * Serializes a {@link Document} to YAML text, non-strict (applying adjustments
     * rather than throwing). Equivalent to {@code write(node, false, null)}.
     *
     * @param node the document to serialize
     * @return the YAML text
     */
    public static String write(Document node) {
        return write(node, false, null);
    }

    /**
     * Serializes a {@link Document} to YAML text.
     *
     * @param node   the document to serialize
     * @param strict if {@code true}, throws when the document contains any adjustment
     *               (e.g. a stringified time value); if {@code false}, applies the
     *               adjustment and continues
     * @param report if non-{@code null}, every adjustment made during writing is appended here
     * @return the YAML text
     * @throws WriteException if {@code strict} is {@code true} and an adjustment was required
     */
    public static String write(Document node, boolean strict, WriteReport report) {
        WriteReport rep = check(node);
        if (report != null) {
            report.addAll(rep.adjustments());
        }
        if (strict && !rep.adjustments().isEmpty()) {
            throw new WriteException(rep.toString(), rep);
        }
        Object prepared = prepareYaml(node, "$", 0, strict);
        Object grouped = dev.omnist.document.PathUtils.groupEdges(prepared);

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setAllowUnicode(true);

        CustomRepresenter representer = new CustomRepresenter(dumperOptions);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()), representer, dumperOptions);
        return yaml.dump(grouped);
    }

    /**
     * Computes what adjustments {@link #write(Document)} would make to {@code node}
     * without actually producing YAML text.
     *
     * @param node the document to check
     * @return the adjustments (e.g. stringified time values) that a write of
     *         {@code node} would require
     */
    public static WriteReport check(Document node) {
        WriteReport rep = new WriteReport();
        scanYaml(node, "$", 0, rep);
        return rep;
    }

    private static void scanYaml(Document doc, String path, int depth, WriteReport rep) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (doc instanceof dev.omnist.document.Node node) {
            Map<String, Integer> totals = dev.omnist.document.PathUtils.countLabels(node);
            Map<String, Integer> seen = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                if (label.contains("\u0085")) {
                    rep.add(path, "format.string-line-break-char", "label contains U+0085 (NEL); written double-quoted to round-trip correctly", "warning");
                }
                int i = seen.getOrDefault(label, 0);
                seen.put(label, i + 1);
                int total = totals.getOrDefault(label, 1);
                String p = dev.omnist.document.PathUtils.childPath(path, label, i, total);
                scanYaml((Document) edge.target(), p, depth + 1, rep);
            }
        } else if (doc instanceof Scalar s) {
            if (s instanceof TimeScalar) {
                rep.add(path, "format.temporal-stringified", "time-of-day written as a string (YAML has no standalone time)", "warning");
            } else if (s instanceof StringScalar str) {
                if (str.value().contains("\u0085")) {
                    rep.add(path, "format.string-line-break-char", "value contains U+0085 (NEL); written double-quoted to round-trip correctly", "warning");
                }
            }
        }
    }

    private static Object prepareYaml(Document doc, String path, int depth, boolean strict) {
        // No depth guard here: write() only reaches prepareYaml after check()'s
        // scanYaml pass has already walked this same document and thrown if any
        // depth exceeded 200, so that bound is already established.
        if (doc instanceof dev.omnist.document.Node node) {
            List<Object[]> edges = new ArrayList<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                Object child = prepareYaml((Document) edge.target(), path + "." + label, depth + 1, strict);
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
            return dt.value();
        } else {
            // Exhaustive over Document's sealed hierarchy (Node, Value ->
            // Scalar's 7 variants | NullValue) -- TimeScalar is the only
            // remaining case, no fallback is reachable.
            TimeScalar time = (TimeScalar) doc;
            return time.value().format();
        }
    }

    private static class CustomRepresenter extends Representer {
        public CustomRepresenter(DumperOptions options) {
            super(options);
            this.representers.put(LocalDate.class, new RepresentLocalDate());
            this.representers.put(DateTimeValue.class, new RepresentDateTimeValue());
            this.representers.put(String.class, new RepresentString());
        }

        private class RepresentLocalDate implements Represent {
            @Override
            public Node representData(Object data) {
                LocalDate date = (LocalDate) data;
                return representScalar(Tag.TIMESTAMP, date.toString());
            }
        }

        private class RepresentDateTimeValue implements Represent {
            @Override
            public Node representData(Object data) {
                DateTimeValue dtv = (DateTimeValue) data;
                return representScalar(Tag.TIMESTAMP, dtv.format());
            }
        }

        private class RepresentString implements Represent {
            @Override
            public Node representData(Object data) {
                String s = (String) data;
                DumperOptions.ScalarStyle style = s.contains("\u0085") ? 
                    DumperOptions.ScalarStyle.DOUBLE_QUOTED : null;
                return representScalar(Tag.STR, s, style);
            }
        }
    }
}
