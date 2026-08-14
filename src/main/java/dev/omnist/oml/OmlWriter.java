package dev.omnist.oml;

import dev.omnist.document.*;

import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * OML (Omnist Markup Language) Canonical Writer (omnist-spec §4 & §9.5).
 * Serializes a {@link Document} into canonical OML text format.
 */
public class OmlWriter {

    private OmlWriter() {}

    private static final Pattern IDENT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    public static String write(Document doc) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, doc, 0, "\n");
        return sb.toString();
    }

    public static String writeCompact(Document doc) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, doc, 0, "; ");
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Document doc, int indentLevel, String edgeSeparator) {
        // Exhaustive switch over Document's sealed permits (Node, Value) -- the
        // compiler enforces completeness, so no unreachable default branch exists.
        switch (doc) {
            case Node node -> writeNode(sb, node, indentLevel, edgeSeparator);
            case Value val -> writeScalarOrNull(sb, val);
        }
    }

    private static void writeNode(StringBuilder sb, Node node, int indentLevel, String edgeSeparator) {
        boolean first = true;
        for (Edge edge : node.edges()) {
            if (!first) {
                sb.append(edgeSeparator);
            }
            writeLabel(sb, edge.label());
            sb.append(": ");

            Target target = edge.target();
            if (target instanceof Node childNode) {
                sb.append("{");
                if (childNode.edges().isEmpty()) {
                    sb.append("}");
                } else {
                    if ("\n".equals(edgeSeparator)) {
                        sb.append("\n");
                        writeNodeIndented(sb, childNode, indentLevel + 1, edgeSeparator);
                        sb.append("\n");
                        appendIndent(sb, indentLevel);
                    } else {
                        sb.append(" ");
                        writeNode(sb, childNode, indentLevel + 1, edgeSeparator);
                        sb.append(" ");
                    }
                    sb.append("}");
                }
            } else if (target instanceof Value val) {
                writeScalarOrNull(sb, val);
            }
            first = false;
        }
    }

    private static void writeNodeIndented(StringBuilder sb, Node node, int indentLevel, String edgeSeparator) {
        boolean first = true;
        for (Edge edge : node.edges()) {
            if (!first) {
                sb.append("\n");
            }
            appendIndent(sb, indentLevel);
            writeLabel(sb, edge.label());
            sb.append(": ");

            Target target = edge.target();
            if (target instanceof Node childNode) {
                sb.append("{");
                if (childNode.edges().isEmpty()) {
                    sb.append("}");
                } else {
                    sb.append("\n");
                    writeNodeIndented(sb, childNode, indentLevel + 1, edgeSeparator);
                    sb.append("\n");
                    appendIndent(sb, indentLevel);
                    sb.append("}");
                }
            } else if (target instanceof Value val) {
                writeScalarOrNull(sb, val);
            }
            first = false;
        }
    }

    private static void appendIndent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    /**
     * Canonical bare label rule (§4.4):
     * The canonical writer MUST emit a bare label only when label text matches IDENT
     * and is not one of null, true, false, nan, inf. It MUST quote otherwise.
     */
    private static void writeLabel(StringBuilder sb, String label) {
        if (isBareLabelLegal(label)) {
            sb.append(label);
        } else {
            writeQuotedString(sb, label);
        }
    }

    private static boolean isBareLabelLegal(String label) {
        if (label == null || label.isEmpty()) return false;
        if (!IDENT_PATTERN.matcher(label).matches()) return false;
        return switch (label) {
            case "null", "true", "false", "nan", "inf" -> false;
            default -> true;
        };
    }

    private static void writeScalarOrNull(StringBuilder sb, Value value) {
        if (value == null || value == Value.NULL) {
            sb.append("null");
            return;
        }
        if (value instanceof Scalar scalar) {
            if (scalar instanceof Scalar.StringScalar str) {
                writeQuotedString(sb, str.value());
            } else if (scalar instanceof Scalar.IntegerScalar intVal) {
                sb.append(intVal.value().toString());
            } else if (scalar instanceof Scalar.NumberScalar numVal) {
                double d = numVal.value();
                if (Double.isNaN(d)) {
                    sb.append("nan");
                } else if (d == Double.POSITIVE_INFINITY) {
                    sb.append("inf");
                } else if (d == Double.NEGATIVE_INFINITY) {
                    sb.append("-inf");
                } else {
                    sb.append(Double.toString(d));
                }
            } else if (scalar instanceof Scalar.BooleanScalar boolVal) {
                sb.append(boolVal.value() ? "true" : "false");
            } else if (scalar instanceof Scalar.DateScalar dateVal) {
                sb.append(dateVal.value().toString());
            } else if (scalar instanceof Scalar.TimeScalar timeScalar) {
                TimeValue tv = timeScalar.value();
                sb.append(tv.time().toString());
                if (tv.offset() != null) {
                    if (ZoneOffset.UTC.equals(tv.offset())) {
                        sb.append("Z");
                    } else {
                        sb.append(tv.offset().getId());
                    }
                }
            } else if (scalar instanceof Scalar.DateTimeScalar dtScalar) {
                DateTimeValue dtv = dtScalar.value();
                sb.append(dtv.dateTime().toString());
                if (dtv.offset() != null) {
                    if (ZoneOffset.UTC.equals(dtv.offset())) {
                        sb.append("Z");
                    } else {
                        sb.append(dtv.offset().getId());
                    }
                }
            }
        }
    }

    /**
     * Canonical string escaping (§4.5):
     * Emits standard double-quoted string with canonical escape processing.
     */
    private static void writeQuotedString(StringBuilder sb, String str) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
