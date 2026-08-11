package dev.omnist.validation;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.*;
import dev.omnist.schema.Record;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

public final class Materializer {

    private static final Pattern ANCHORED_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ANCHORED_TIME = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?$");
    private static final Pattern ANCHORED_DATETIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?$");

    private Materializer() {}

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
        Map<String, Integer> counts = new HashMap<>();

        for (Edge edge : n.edges()) {
            String label = edge.label();
            int i = counts.getOrDefault(label, 0);
            counts.put(label, i + 1);

            String childPath = path + "." + label + (i > 0 ? "[" + i + "]" : "");
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
            int c = counts.getOrDefault(f.label(), 0);
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

        if (value == Value.NULL || value == null) {
            if (!s.nullable()) {
                diagnostics.add(new ValidationDiagnostic(path, "validate.null-not-allowed", "null not allowed here"));
            }
            return Value.NULL;
        }

        if (!(value instanceof Scalar valScalar)) {
            diagnostics.add(new ValidationDiagnostic(path, "validate.shape-mismatch", "expected a scalar value"));
            return value;
        }

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
            return new NumberScalar(bi.doubleValue());
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
