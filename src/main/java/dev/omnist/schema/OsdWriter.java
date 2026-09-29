package dev.omnist.schema;

import dev.omnist.codec.WriteException;
import dev.omnist.codec.WriteReport;
import java.util.Map;

/**
 * OSD (Omnist Schema Definition) Canonical Writer (omnist-spec §5.9).
 * Serializes a {@link Schema} into canonical OSD text format.
 */
public final class OsdWriter {

    private OsdWriter() {}

    /**
     * Serializes a {@link Schema} to canonical OSD text format (omnist-spec §5.9).
     * Records appear first in declaration order, each on its own line; the {@code root}
     * declaration appears last.
     *
     * @param schema the schema to serialize; must not be {@code null}
     * @return the canonical OSD text; never {@code null}
     */
    public static String write(Schema schema) {
        StringBuilder sb = new StringBuilder();

        // 1. Records in declaration order (§5.9)
        for (Map.Entry<String, Record> entry : schema.records().entrySet()) {
            writeRecordCanonical(sb, entry.getValue());
            sb.append("\n");
        }

        // 2. root last (§5.9)
        sb.append("root ").append(schema.root()).append("\n");

        return sb.toString();
    }

    /**
     * Serializes a {@link Schema} to compact single-line OSD text.
     * All record and field declarations are placed on a single line with space separators,
     * suitable for embedding in test fixtures or compact output contexts.
     *
     * @param schema the schema to serialize; must not be {@code null}
     * @return the compact OSD text; never {@code null}
     */
    public static String writeCompact(Schema schema) {

        StringBuilder sb = new StringBuilder();

        boolean first = true;
        for (Map.Entry<String, Record> entry : schema.records().entrySet()) {
            if (!first) {
                sb.append(" ");
            }
            writeRecordCompact(sb, entry.getValue());
            first = false;
        }

        sb.append(" root ").append(schema.root());

        return sb.toString();
    }

    private static void writeRecordCanonical(StringBuilder sb, Record record) {
        sb.append("record ").append(record.name()).append(" {\n");

        for (Field field : record.fields()) {
            checkLabel(field.label(), record.name());
            sb.append("    ");
            writeQuotedString(sb, field.label());
            writeCardinality(sb, field.min(), field.max());
            sb.append(": ");
            writeType(sb, field.type());
            sb.append(",\n");
        }

        sb.append("}");
    }

    private static void writeRecordCompact(StringBuilder sb, Record record) {
        sb.append("record ").append(record.name()).append(" {");

        boolean first = true;
        for (Field field : record.fields()) {
            checkLabel(field.label(), record.name());
            if (!first) {
                sb.append(" ");
            } else {
                sb.append(" ");
            }
            writeQuotedString(sb, field.label());
            writeCardinality(sb, field.min(), field.max());
            sb.append(": ");
            writeType(sb, field.type());
            sb.append(",");
            first = false;
        }
        if (!record.fields().isEmpty()) {
            sb.append(" ");
        }
        sb.append("}");
    }

    /**
     * Cardinality writing rules (§5.9):
     * - [1,1] is omitted
     * - min == max -> [n]
     * - min != max:
     *   - [m,n]
     *   - [m,] if max == null and min > 0
     *   - [,n] if min == 0 and max != null
     *   - [,] if min == 0 and max == null
     */
    private static void writeCardinality(StringBuilder sb, int min, Integer max) {
        if (min == 1 && max != null && max == 1) {
            return; // Omitted per §5.9
        }

        sb.append(" [");
        if (max != null && min == max) {
            sb.append(min);
        } else {
            if (min > 0) {
                sb.append(min);
            }
            sb.append(",");
            if (max != null) {
                sb.append(max);
            }
        }
        sb.append("]");
    }

    private static void writeType(StringBuilder sb, Type type) {
        // Exhaustive over Type's sealed permits (Scalar, Ref, Any) -- the
        // compiler proves completeness, so no unreachable branch exists.
        switch (type) {
            case Type.Scalar scalar -> {
                sb.append(scalar.kind().keyword());
                if (scalar.nullable()) {
                    sb.append("?");
                }
            }
            case Type.Ref ref -> sb.append(ref.name());
            case Type.Any ignored -> sb.append("any");
        }
    }

    /**
     * Rejects a field label that OSD cannot spell (omnist-spec OSD-14, §5.9/§8.3.9):
     * a C0 control character (U+0000–U+001F) in a label has no OSD text at all, since
     * §5.3.1 bans the raw byte in a string body — escape context included — and OSD's
     * unescaping is weak. Fails unconditionally, regardless of strict mode, with
     * {@code write.unsupported-value} whose path is the Schema path of the record
     * holding the field (never {@code record.label}, since a path cannot quote a
     * label containing the very byte that has no spelling).
     *
     * @param label      the field label to check
     * @param recordPath the Schema path of the record holding this field, e.g. {@code "R"}
     * @throws WriteException if {@code label} contains a C0 control character
     */
    private static void checkLabel(String label, String recordPath) {
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c <= 0x1F) {
                WriteReport rep = new WriteReport();
                rep.add(recordPath, "write.unsupported-value",
                        "field label contains a C0 control character (U+" + String.format("%04X", (int) c)
                                + "), which has no OSD spelling", "error");
                throw new WriteException(rep.toString(), rep);
            }
        }
    }

    private static void writeQuotedString(StringBuilder sb, String str) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }
}
