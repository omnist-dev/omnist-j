package dev.omnist.validation;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.*;
import dev.omnist.schema.Record;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Schema-driven coercion of a parsed {@link Document} into a fully-typed document (omnist-spec §8).
 *
 * <p>Given a document produced by any codec and a {@link Schema}, {@code Materializer} walks
 * the document tree and applies type coercions where an exact-type match is not present:
 * integral {@code double} values are narrowed to {@link dev.omnist.document.Scalar.IntegerScalar};
 * {@code integer} values are widened to {@link dev.omnist.document.Scalar.NumberScalar};
 * and ISO-8601 strings are parsed into the appropriate temporal scalar type.
 * Structural violations (unexpected fields, cardinality mismatches, type mismatches) accumulate
 * as {@link ValidationDiagnostic} records and are thrown together as a {@link ValidationException}.
 *
 * <p>This class is stateless; all behavior is via the static {@link #materialize} entry point.
 */
public final class Materializer {

    private static final Pattern ANCHORED_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ANCHORED_TIME = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?$");
    private static final Pattern ANCHORED_DATETIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?$");

    private Materializer() {}

    /**
     * Applies schema-driven coercion to {@code node}, returning a fully-typed {@link Document}.
     *
     * <p>The root of {@code node} is matched against {@code schema.root()} and then recursively
     * against each field's declared type. Structural violations and type mismatches are accumulated
     * and thrown together as a single {@link ValidationException} at the end of the walk.
     *
     * @param node   the document to coerce; typically returned by a codec's {@code read} method
     * @param schema the schema to validate and coerce against
     * @return the coerced document tree; structurally identical to {@code node} with scalar values
     *         possibly replaced by correctly-typed instances
     * @throws ValidationException if any cardinality, field, or type constraint is violated
     * @throws RuntimeException    if nesting depth or node count exceed hard limits (200 / 1,000,000)
     */
    public static Document materialize(Document node, Schema schema) {

        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        int[] budget = new int[]{0};
        
        Document out = materializeType(node, schema, new Type.Ref(schema.root()), "$", 0, budget, diagnostics);
        
        if (!diagnostics.isEmpty()) {
            throw new ValidationException(new ValidationResult(false, List.copyOf(diagnostics)));
        }
        return out;
    }

    private static Document materializeType(Document node, Schema schema, Type type, String path, int depth, int[] budget, List<ValidationDiagnostic> diagnostics) {
        budget[0]++;
        if (budget[0] > 1_000_000) {
            throw new RuntimeException(path + ": too many nodes materialized (over 1000000)");
        }
        if (depth > 200) {
            throw new RuntimeException(path + ": nesting exceeds the maximum depth (200)");
        }

        Object d = resolveType(schema, type);
        if (d instanceof Type.Any) {
            return node;
        }
        if (d instanceof Type.Scalar scalar) {
            return materializeScalar(node, scalar, path, diagnostics);
        }
        if (d instanceof Record record) {
            return materializeRecord(node, schema, record, path, depth, budget, diagnostics);
        }
        return node;
    }

    private static Object resolveType(Schema schema, Type type) {
        if (type instanceof Type.Ref ref) {
            return schema.records().get(ref.name());
        }
        return type;
    }

    private static Document materializeRecord(Document node, Schema schema, Record record, String path, int depth, int[] budget, List<ValidationDiagnostic> diagnostics) {
        if (!(node instanceof Node n)) {
            diagnostics.add(new ValidationDiagnostic(path, "validate.shape-mismatch", "expected an object, got a value"));
            return node;
        }

        Map<String, Field> fieldMap = new HashMap<>();
        for (Field f : record.fields()) {
            fieldMap.put(f.label(), f);
        }

        List<Edge> edges = new ArrayList<>();
        Map<String, Integer> totals = dev.omnist.document.PathUtils.countLabels(n);
        Map<String, Integer> seen = new HashMap<>();

        for (Edge edge : n.edges()) {
            String label = edge.label();
            int i = seen.getOrDefault(label, 0);
            seen.put(label, i + 1);
            int total = totals.getOrDefault(label, 1);

            String childPath = dev.omnist.document.PathUtils.childPath(path, label, i, total);
            Field f = fieldMap.get(label);

            if (f == null) {
                diagnostics.add(new ValidationDiagnostic(childPath, "validate.unexpected-field", "field not declared on record " + record.name()));
                edges.add(edge);
            } else {
                Document materializedChild = materializeType((Document) edge.target(), schema, f.type(), childPath, depth + 1, budget, diagnostics);
                edges.add(new Edge(label, (Target) materializedChild));
            }
        }

        for (Field f : record.fields()) {
            int c = totals.getOrDefault(f.label(), 0);
            if (c < f.min() || (f.max() != null && c > f.max())) {
                String boundStr = f.max() == null ? "unbounded" : String.valueOf(f.max());
                diagnostics.add(new ValidationDiagnostic(path, "validate.cardinality",
                        "field '" + f.label() + "' occurs " + c + " time(s), expected [" + f.min() + "," + boundStr + "]"));
            }
        }

        return new Node(edges);
    }

    private static Document materializeScalar(Document value, Type.Scalar s, String path, List<ValidationDiagnostic> diagnostics) {
        if (value instanceof Node) {
            diagnostics.add(new ValidationDiagnostic(path, "validate.shape-mismatch", "expected a scalar value, got an object"));
            return value;
        }

        // value == null is not a reachable case: materializeScalar's only real
        // caller path passes (Document) edge.target(), which Edge's constructor
        // guarantees is never null; a top-level null document would hit
        // materializeRecord's instanceof Node check first, never reaching here.
        if (value instanceof Value.NullValue) {
            if (!s.nullable()) {
                diagnostics.add(new ValidationDiagnostic(path, "validate.null-not-allowed", "null not allowed here"));
            }
            return Value.NULL;
        }

        // value is not a Node (checked above) and not a NullValue (checked above);
        // Value is sealed to permit only Scalar and NullValue, so value is
        // exhaustively a Scalar here -- no reachable fallback case remains.
        Scalar valScalar = (Scalar) value;

        dev.omnist.schema.ScalarKind targetKind = s.kind();
        dev.omnist.document.ScalarKind valueKind = valScalar.kind();

        // 1. Exact match passthrough
        if (valueKind.name().equals(targetKind.name())) {
            return value;
        }

        // 2. integer <- integral number
        if (targetKind == dev.omnist.schema.ScalarKind.INTEGER && valueKind == dev.omnist.document.ScalarKind.NUMBER) {
            double d = ((NumberScalar) value).value();
            if (d % 1 == 0) {
                return new IntegerScalar(BigDecimalToBigInteger(d));
            }
        }

        // 3. number <- integer
        if (targetKind == dev.omnist.schema.ScalarKind.NUMBER && valueKind == dev.omnist.document.ScalarKind.INTEGER) {
            BigInteger bi = ((IntegerScalar) value).value();
            double d = bi.doubleValue();
            if (!Double.isInfinite(d) && new java.math.BigDecimal(d).compareTo(new java.math.BigDecimal(bi)) == 0) {
                return new NumberScalar(d);
            }
        }

        // 4. date/time/datetime <- string
        if (valueKind == dev.omnist.document.ScalarKind.STRING) {
            String str = ((StringScalar) value).value();
            if (targetKind == dev.omnist.schema.ScalarKind.DATE) {
                if (ANCHORED_DATE.matcher(str).matches()) {
                    try {
                        return new DateScalar(LocalDate.parse(str));
                    } catch (Exception ignored) {}
                }
            } else if (targetKind == dev.omnist.schema.ScalarKind.TIME) {
                if (ANCHORED_TIME.matcher(str).matches()) {
                    try {
                        return new TimeScalar(parseTimeValue(str));
                    } catch (Exception ignored) {}
                }
            } else if (targetKind == dev.omnist.schema.ScalarKind.DATETIME) {
                if (ANCHORED_DATETIME.matcher(str).matches()) {
                    try {
                        return new DateTimeScalar(parseDateTimeValue(str));
                    } catch (Exception ignored) {}
                }
            }
        }

        // 5. Inexact conversion failure
        diagnostics.add(new ValidationDiagnostic(path, "materialize.inexact-conversion",
                "value cannot be read as " + targetKind.keyword() + " (not a value-exact conversion)"));
        return value;
    }

    private static BigInteger BigDecimalToBigInteger(double d) {
        return java.math.BigDecimal.valueOf(d).toBigInteger();
    }

    private static TimeValue parseTimeValue(String text) {
        if (text.endsWith("Z")) {
            java.time.LocalTime t = java.time.LocalTime.parse(text.substring(0, text.length() - 1));
            return TimeValue.of(t, java.time.ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        // text.indexOf(':') < signPos is always true whenever signPos > 0: this
        // method is only ever called with text ANCHORED_TIME already matched,
        // whose mandatory "HH:MM" prefix puts the first ':' at index 2, while a
        // +/-HH:MM offset suffix can't start before index 5 -- same reasoning as
        // OmlLexer.parseTimeValue's twin check.
        if (signPos > 0 && text.indexOf(':') < signPos) {
            java.time.LocalTime t = java.time.LocalTime.parse(text.substring(0, signPos));
            java.time.ZoneOffset offset = java.time.ZoneOffset.of(text.substring(signPos));
            return TimeValue.of(t, offset);
        }
        return TimeValue.of(java.time.LocalTime.parse(text));
    }

    private static DateTimeValue parseDateTimeValue(String text) {
        if (text.endsWith("Z")) {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(text.substring(0, text.length() - 1));
            return DateTimeValue.of(dt, java.time.ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 10) {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(text.substring(0, signPos));
            java.time.ZoneOffset offset = java.time.ZoneOffset.of(text.substring(signPos));
            return DateTimeValue.of(dt, offset);
        }
        return DateTimeValue.of(java.time.LocalDateTime.parse(text));
    }
}
