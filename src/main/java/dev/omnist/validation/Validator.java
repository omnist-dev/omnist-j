package dev.omnist.validation;

import dev.omnist.document.*;
import dev.omnist.schema.*;
import dev.omnist.schema.Record;

import java.util.*;

/**
 * OSD Schema Validator (omnist-spec §3.6, §3.6.1, §8.3.4).
 * Validates an in-memory {@link Document} against an in-memory {@link Schema}.
 */
public class Validator {

    private Validator() {}

    /**
     * Validates a Document against a Schema.
     * Collects all validation diagnostics across the document tree into {@link ValidationResult}.
     */
    public static ValidationResult validate(Document document, Schema schema) {
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        Record rootRecord = schema.records().get(schema.root());

        if (rootRecord == null) {
            diagnostics.add(new ValidationDiagnostic("$", "validate.shape-mismatch", "root record not defined in schema: " + schema.root()));
            return new ValidationResult(false, List.copyOf(diagnostics));
        }

        // Document and Target are sealed to the identical permit set (Node, Value),
        // so every Document is exhaustively a Target -- no reachable fallback case.
        conformTarget((Target) document, schema, schema.root(), "$", diagnostics);

        return new ValidationResult(diagnostics.isEmpty(), List.copyOf(diagnostics));
    }

    private static void conformTarget(Target target, Schema schema, String recordName, String path, List<ValidationDiagnostic> diagnostics) {
        if (!(target instanceof Node node)) {
            diagnostics.add(new ValidationDiagnostic(path, "validate.shape-mismatch", "expected an object, got a value"));
            return;
        }

        Record record = schema.records().get(recordName);
        if (record == null) {
            diagnostics.add(new ValidationDiagnostic(path, "validate.shape-mismatch", "unknown record reference: " + recordName));
            return;
        }

        conformRecord(node, schema, record, path, diagnostics);
    }

    private static void conformRecord(Node node, Schema schema, Record record, String path, List<ValidationDiagnostic> diagnostics) {
        Map<String, Field> fieldMap = new HashMap<>();
        for (Field f : record.fields()) {
            fieldMap.put(f.label(), f);
        }

        Map<String, Integer> counts = new HashMap<>();

        // 1. Closedness & Edge Matching in edge order (§3.6.1)
        for (Edge edge : node.edges()) {
            String label = edge.label();
            int i = counts.getOrDefault(label, 0);
            counts.put(label, i + 1);

            String childPath = path + "." + label + (i > 0 ? "[" + i + "]" : "");
            Field f = fieldMap.get(label);

            if (f == null) {
                // Closed record check (§3.6): unexpected field, NO descent into target
                diagnostics.add(new ValidationDiagnostic(childPath, "validate.unexpected-field", "field not declared on record " + record.name()));
            } else {
                conformField(edge.target(), schema, f.type(), childPath, diagnostics);
            }
        }

        // 2. Cardinality Check for all declared fields (§3.6)
        for (Field f : record.fields()) {
            int c = counts.getOrDefault(f.label(), 0);
            if (c < f.min() || (f.max() != null && c > f.max())) {
                String boundStr = f.max() == null ? "unbounded" : String.valueOf(f.max());
                // Path is the parent node path (§3.6)
                diagnostics.add(new ValidationDiagnostic(path, "validate.cardinality",
                        "field '" + f.label() + "' occurs " + c + " time(s), expected [" + f.min() + "," + boundStr + "]"));
            }
        }
    }

    private static void conformField(Target target, Schema schema, Type type, String path, List<ValidationDiagnostic> diagnostics) {
        if (type instanceof Type.Any) {
            // Type.Any: descent stops, accepted unchecked (§3.6)
            return;
        }

        if (type instanceof Type.Scalar scalar) {
            if (target instanceof Node) {
                diagnostics.add(new ValidationDiagnostic(path, "validate.shape-mismatch", "expected a scalar value, got an object"));
                return;
            }

            if (target instanceof Value.NullValue || target == null) {
                if (!scalar.nullable()) {
                    diagnostics.add(new ValidationDiagnostic(path, "validate.null-not-allowed", "null not allowed here"));
                }
                return;
            }

            // target is not a Node (checked above) and not a NullValue (checked above);
            // Value is sealed to permit only Scalar and NullValue, so target is
            // exhaustively a Scalar here -- no reachable fallback case remains.
            Scalar valScalar = (Scalar) target;
            if (!matchesKind(valScalar.kind(), scalar.kind())) {
                diagnostics.add(new ValidationDiagnostic(path, "validate.type-mismatch",
                        "value kind " + valScalar.kind() + " does not match declared kind " + scalar.kind()));
            }
        } else if (type instanceof Type.Ref ref) {
            conformTarget(target, schema, ref.name(), path, diagnostics);
        }
    }

    /**
     * Checks if a document scalar kind satisfies a declared schema scalar kind.
     * Implements §6.3 scalar subtyping: integer values directly satisfy number fields.
     */
    private static boolean matchesKind(dev.omnist.document.ScalarKind valueKind, dev.omnist.schema.ScalarKind declaredKind) {
        if (valueKind.name().equals(declaredKind.name())) {
            return true;
        }
        // Subtyping §6.3: INTEGER value satisfies NUMBER field
        return valueKind == dev.omnist.document.ScalarKind.INTEGER && declaredKind == dev.omnist.schema.ScalarKind.NUMBER;
    }
}
