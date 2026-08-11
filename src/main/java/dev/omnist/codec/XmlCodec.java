package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;

import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

public final class XmlCodec {

    private static final Pattern XML_INT_RE = Pattern.compile("^-?(0|[1-9]\\d*)$");
    private static final Pattern XML_NUM_RE = Pattern.compile("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$");
    private static final Pattern XML_ILLEGAL_CHAR = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uD800-\\uDFFF\\uFFFE\\uFFFF]");
    private static final Pattern XML_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");

    private XmlCodec() {}

    public static Document read(String text) {
        return read(text, null);
    }

    public static Document read(String text, Schema schema) {
        org.w3c.dom.Element rootElem;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setCoalescing(true);
            
            // Secure configuration to block XXE / DTD expansion
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);

            InputSource is = new InputSource(new StringReader(text));
            org.w3c.dom.Document domDoc = dbf.newDocumentBuilder().parse(is);
            rootElem = domDoc.getDocumentElement();
            if (rootElem == null) {
                throw new RuntimeException("no document element found");
            }
        } catch (Exception e) {
            throw new RuntimeException("invalid XML: " + e.getMessage(), e);
        }

        int[] budget = new int[]{0};
        String rootLabel = localName(rootElem);
        Object rootContent = xmlToNode(rootElem, "$", 0, budget);
        List<Object[]> rawList = new ArrayList<>();
        rawList.add(new Object[]{rootLabel, rootContent});
        Object raw = rawList;

        if (schema != null) {
            Object rootType = schema.records().get(schema.root());
            if (rootType != null) {
                raw = xmlPretype(rawList, schema, rootType);
            }
        }

        return buildDoc(raw, "$", 0, budget);
    }

    private static Object xmlToNode(org.w3c.dom.Element elem, String path, int depth, int[] budget) {
        budget[0]++;
        if (budget[0] > 1_000_000) {
            throw new RuntimeException(path + ": too many nodes materialized (over 1000000)");
        }
        if (depth > 200) {
            throw new RuntimeException(path + ": nesting exceeds the maximum depth (200)");
        }

        List<org.w3c.dom.Element> childElements = new ArrayList<>();
        org.w3c.dom.NodeList children = elem.getChildNodes();
        
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                childElements.add((org.w3c.dom.Element) child);
            }
        }

        if (!childElements.isEmpty()) {
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    String text = child.getNodeValue();
                    if (text != null && !text.trim().isEmpty()) {
                        throw new RuntimeException(path + ": mixed content (text alongside child elements) is outside the data-XML profile");
                    }
                }
            }
            
            List<Object[]> edges = new ArrayList<>();
            for (org.w3c.dom.Element c : childElements) {
                String localName = localName(c);
                Object childNode = xmlToNode(c, path + "." + localName, depth + 1, budget);
                edges.add(new Object[]{localName, childNode});
            }
            return edges;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    sb.append(child.getNodeValue());
                }
            }
            return sb.toString();
        }
    }

    private static String localName(org.w3c.dom.Element el) {
        String name = el.getLocalName();
        if (name == null) {
            name = el.getTagName();
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name;
    }

    private static Object xmlPretype(Object node, Schema schema, Object typeResolved) {
        if (typeResolved instanceof Type.Any) {
            return node;
        }
        if (typeResolved instanceof Type.Scalar scalar) {
            if (node instanceof String s) {
                if (scalar.kind() == ScalarKind.BOOLEAN) {
                    if ("true".equals(s)) return Boolean.TRUE;
                    if ("false".equals(s)) return Boolean.FALSE;
                } else if (scalar.kind() == ScalarKind.INTEGER) {
                    if (XML_INT_RE.matcher(s).matches()) {
                        return new BigInteger(s);
                    }
                } else if (scalar.kind() == ScalarKind.NUMBER) {
                    if (XML_NUM_RE.matcher(s).matches()) {
                        return Double.parseDouble(s);
                    }
                }
            }
            return node;
        }
        if (typeResolved instanceof Record record) {
            if (node instanceof List<?> list) {
                List<Object[]> out = new ArrayList<>();
                for (Object item : list) {
                    Object[] edge = (Object[]) item;
                    String label = (String) edge[0];
                    Object child = edge[1];
                    Field field = record.field(label);
                    if (field != null) {
                        Object nextType = resolveType(schema, field.type());
                        out.add(new Object[]{label, xmlPretype(child, schema, nextType)});
                    } else {
                        out.add(edge);
                    }
                }
                return out;
            }
        }
        return node;
    }

    private static Object resolveType(Schema schema, Type type) {
        if (type instanceof Type.Ref ref) {
            return schema.records().get(ref.name());
        }
        return type;
    }

    private static Document buildDoc(Object val, String path, int depth, int[] budget) {
        budget[0]++;
        if (budget[0] > 1_000_000) {
            throw new RuntimeException(path + ": too many nodes materialized (over 1000000)");
        }
        if (depth > 200) {
            throw new RuntimeException(path + ": nesting exceeds the maximum depth (200)");
        }
        if (val instanceof List<?> list) {
            List<Edge> edges = new ArrayList<>();
            for (Object item : list) {
                Object[] edge = (Object[]) item;
                String label = (String) edge[0];
                Object childVal = edge[1];
                String nextPath = path.equals("$") ? "$." + label : path + "." + label;
                Document child = buildDoc(childVal, nextPath, depth + 1, budget);
                edges.add(new Edge(label, (Target) child));
            }
            return new Node(edges);
        } else if (val instanceof String s) {
            return new StringScalar(s);
        } else if (val instanceof Boolean b) {
            return new BooleanScalar(b);
        } else if (val instanceof BigInteger bi) {
            return new IntegerScalar(bi);
        } else if (val instanceof Double d) {
            return new NumberScalar(d);
        }
        throw new IllegalArgumentException("Unknown value type: " + val.getClass());
    }

    public static String write(Document node) {
        return write(node, false, null);
    }

    public static String write(Document node, boolean strict, WriteReport report) {
        if (!(node instanceof Node root) || root.edges().size() != 1) {
            throw new WriteException("XML needs exactly one document element; the root node must have a single top-level edge (a single-rooted Document)");
        }

        WriteReport rep = check(node);
        if (report != null) {
            report.addAll(rep.adjustments());
        }
        if (strict && !rep.adjustments().isEmpty()) {
            throw new WriteException(rep.toString(), rep);
        }

        StringBuilder sb = new StringBuilder();
        Edge rootEdge = root.edges().get(0);
        writeNode((Document) rootEdge.target(), xmlName(rootEdge.label()), 0, sb);
        sb.append("\n");
        return sb.toString();
    }

    private static void writeNode(Document doc, String tagName, int depth, StringBuilder sb) {
        String pad = "\n" + "  ".repeat(depth);
        sb.append("<").append(tagName);
        if (doc instanceof Node node) {
            if (node.edges().isEmpty()) {
                sb.append(" />");
            } else {
                sb.append(">");
                for (Edge edge : node.edges()) {
                    sb.append("\n").append("  ".repeat(depth + 1));
                    writeNode((Document) edge.target(), xmlName(edge.label()), depth + 1, sb);
                }
                sb.append(pad).append("</").append(tagName).append(">");
            }
        } else {
            String text = xmlSanitize(xmlText(doc));
            if (text.isEmpty()) {
                sb.append(" />");
            } else {
                sb.append(">").append(text).append("</").append(tagName).append(">");
            }
        }
    }

    private static String xmlName(String name) {
        if (XML_NAME.matcher(name).matches()) {
            return name;
        }
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isEmpty() || !XML_NAME.matcher(safe).matches()) {
            safe = "_" + safe;
        }
        return safe;
    }

    private static String xmlSanitize(String text) {
        return XML_ILLEGAL_CHAR.matcher(text).replaceAll("\uFFFD");
    }

    private static String xmlText(Object v) {
        if (v instanceof BooleanScalar b) {
            return b.value() ? "true" : "false";
        }
        if (v instanceof Value.NullValue || v == null) {
            return "";
        }
        if (v instanceof DateScalar date) {
            return date.value().toString();
        }
        if (v instanceof TimeScalar time) {
            return time.value().time().toString();
        }
        if (v instanceof DateTimeScalar dt) {
            DateTimeValue dtv = dt.value();
            if (dtv.offset() != null) {
                return java.time.OffsetDateTime.of(dtv.dateTime(), dtv.offset()).toString();
            } else {
                return dtv.dateTime().toString();
            }
        }
        if (v instanceof StringScalar s) {
            return s.value();
        }
        if (v instanceof IntegerScalar s) {
            return s.value().toString();
        }
        if (v instanceof NumberScalar s) {
            return Double.toString(s.value());
        }
        return v.toString();
    }

    public static WriteReport check(Document node) {
        WriteReport rep = new WriteReport();
        scanXml(node, "$", rep, 0);
        return rep;
    }

    private static void scanXml(Document doc, String path, WriteReport rep, int depth) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (doc instanceof Node node) {
            if (node.edges().isEmpty()) {
                rep.add(path, "shape.empty_ambiguous",
                        "empty internal node (no edges) written as <tag /> and reads back as the empty-string leaf '', not []",
                        "warning");
                return;
            }
            Map<String, Integer> counts = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                int i = counts.getOrDefault(label, 0);
                counts.put(label, i + 1);
                String p = path.equals("$") ? "$." + label : path + "." + label;
                if (i > 0) {
                    p = p + "[" + i + "]";
                }
                if (!XML_NAME.matcher(label).matches()) {
                    rep.add(p, "key.sanitized",
                            "label '" + label + "' isn't a valid XML name; written sanitized",
                            "warning");
                }
                scanXml((Document) edge.target(), p, rep, depth + 1);
            }
        } else if (doc instanceof Value.NullValue) {
            rep.add(path, "format.null-unrepresentable", "null written as an empty element", "warning");
        } else if (doc instanceof Scalar s) {
            if (s instanceof DateScalar || s instanceof TimeScalar || s instanceof DateTimeScalar) {
                rep.add(path, "format.temporal-stringified",
                        "temporal value written as text (reads back as a string)", "warning");
            } else if (s instanceof BooleanScalar || s instanceof IntegerScalar || s instanceof NumberScalar) {
                rep.add(path, "value.stringified",
                        "non-string scalar written as text (reads back as a string)", "warning");
            }
            
            String strVal = xmlText(doc);
            if (XML_ILLEGAL_CHAR.matcher(strVal).find()) {
                rep.add(path, "string.illegal_xml_char",
                        "string contains a character XML 1.0 cannot represent " +
                        "(e.g. a C0 control other than tab/LF/CR); it is replaced " +
                        "with U+FFFD on write so the output stays well-formed",
                        "error");
            }
            if (strVal.contains("\r")) {
                rep.add(path, "string.cr_normalized",
                        "string contains a carriage return ('\\r'); XML mandates " +
                        "line-ending normalization on parse, so '\\r' (and '\\r\\n') " +
                        "read back as '\\n'",
                        "warning");
            }
        }
    }
}
