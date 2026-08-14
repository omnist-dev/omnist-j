package dev.omnist.schema;

import java.util.Map;

/**
 * OSD (Omnist Schema Definition) Canonical Writer (omnist-spec §5.9).
 * Serializes a {@link Schema} into canonical OSD text format.
 */
public final class OsdWriter {

    private OsdWriter() {}

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
