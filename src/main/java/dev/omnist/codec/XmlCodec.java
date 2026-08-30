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

/**
 * Codec for reading and writing the Omnist Document model as XML (omnist-spec §7.3),
 * built on {@code javax.xml.parsers}/DOM.
 *
 * <p><b>Reading</b>: always parses namespace-aware; a document's single root element
 * becomes the sole top-level edge; child elements become nested edges, preserving
 * repeated-element order (this is the one codec where cross-label interleaving both
 * reads and writes faithfully — every other codec's writer groups same-label edges).
 * Attribute and namespace-prefix information is discarded on read; each drop is
 * reported via {@code format.attribute-dropped}/{@code format.namespace-dropped}
 * (omnist-spec §8.3.8, closing §9.4 D-3) when a {@link WriteReport} is supplied
 * to {@link #read(String, Schema, WriteReport)}.
 *
 * <p><b>Writing</b>: scalar leaves are stringified per XML's own type rules
 * ({@link #XML_INT_RE}/{@link #XML_NUM_RE}); a leaf label that isn't a legal XML
 * name is sanitized, falling back to an underscore-prefixed form.
 *
 * <p>This class is stateless; all methods are {@code static}.
 */
public final class XmlCodec {

    private static final Pattern XML_INT_RE = Pattern.compile("^-?(0|[1-9]\\d*)$");
    private static final Pattern XML_NUM_RE = Pattern.compile("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$");
    private static final Pattern XML_ILLEGAL_CHAR = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uD800-\\uDFFF\\uFFFE\\uFFFF]");
    private static final Pattern XML_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");

    private XmlCodec() {}

    /** Maximum accepted input length in characters, guarding against oversized XML input. */
    public static final int MAX_INPUT_LENGTH = 2_000_000;

    /**
     * Parses XML text into a {@link Document} without schema guidance.
     * Equivalent to {@code read(text, null)}.
     *
     * @param text the XML text; must not be {@code null}
     * @return the parsed document
     * @throws RuntimeException if the XML is not well-formed or exceeds {@link #MAX_INPUT_LENGTH}
     */
    public static Document read(String text) {
        return read(text, null, null);
    }

    /**
     * Parses XML text into a {@link Document}, optionally with schema guidance.
     *
     * <p>Unlike the other three format codecs (whose {@code read(text, schema)}
     * overload ignores {@code schema} entirely, or in JsonCodec's case accepts it
     * without acting on it — schema-driven coercion there happens in a later
     * {@link dev.omnist.validation.Materializer} stage instead), XML text is
     * inherently ambiguous about scalar kind: every attribute and element value is
     * just a string until something says otherwise. When {@code schema} is
     * non-{@code null}, its root record's field types are used to pre-resolve
     * strings that look like booleans/integers/numbers into the matching Java type
     * before the document is built, so a later validate/materialize call sees a
     * typed value rather than a string it would otherwise have to coerce blind.
     *
     * @param text   the XML text; must not be {@code null}
     * @param schema if non-{@code null}, guides scalar-kind resolution for
     *               ambiguous string content as described above; if {@code null},
     *               every scalar is read as a plain string
     * @return the parsed document
     * @throws RuntimeException if the XML is not well-formed or exceeds {@link #MAX_INPUT_LENGTH}
     */
    public static Document read(String text, Schema schema) {
        return read(text, schema, null);
    }

    /**
     * Parses XML text into a {@link Document}, optionally with schema guidance and a
     * read-side diagnostic report.
     *
     * <p>Unlike write-side {@code format.*} adjustments, XML read never fails or coerces
     * because of a read-side adjustment; {@code report}, when non-{@code null}, simply
     * accumulates a warning for each lossy conversion the read had to make (omnist-spec
     * Sec8.3.8's {@code format.attribute-dropped} and {@code format.namespace-dropped}):
     * an element's attributes have no Document representation and are discarded, and a
     * namespace-prefixed tag ({@code <ns:b>}) is read as its local name ({@code b}),
     * discarding the prefix and any namespace binding.
     *
     * @param text   the XML text; must not be {@code null}
     * @param schema if non-{@code null}, guides scalar-kind resolution as described above
     * @param report if non-{@code null}, every read-side adjustment is appended here
     * @return the parsed document
     * @throws RuntimeException if the XML is not well-formed or exceeds {@link #MAX_INPUT_LENGTH}
     */
    public static Document read(String text, Schema schema, WriteReport report) {
        if (text == null) {
            throw new IllegalArgumentException("input text cannot be null");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new DocumentParseException("$", "document.parse-error", "invalid XML: input exceeds maximum size limit of " + MAX_INPUT_LENGTH + " characters");
        }

        org.w3c.dom.Element rootElem;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
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
            rootElem = requireRootElement(domDoc);
        } catch (Exception e) {
            throw new DocumentParseException("$", "document.parse-error", "invalid XML: " + e.getMessage(), e);
        }

        int[] budget = new int[]{0};
        WriteReport rep = new WriteReport();
        String rootLabel = localName(rootElem);
        Object rootContent = xmlToNode(rootElem, "$", "$." + rootLabel, 0, budget, rep);
        if (report != null) {
            report.addAll(rep.adjustments());
        }

        if (schema != null) {
            Object rootType = schema.records().get(schema.root());
            if (rootType != null) {
                // xmlPretype matches a List<Object[]>'s edge labels against a
                // Record's field names, so it must be applied to rootContent
                // itself (the root element's own child edges) -- not to a
                // [rootLabel, rootContent] wrapper, whose single "label" (the
                // XML tag name) never matches any field of the root record.
                rootContent = xmlPretype(rootContent, schema, rootType);
            }
        }

        List<Object[]> rawList = new ArrayList<>();
        rawList.add(new Object[]{rootLabel, rootContent});
        return buildDoc(rawList, "$", 0, budget);
    }

    /**
     * Extracted purely as a reflection seam for a defensive branch: unreachable in
     * practice, since {@code DocumentBuilder.parse} either throws (malformed input,
     * caught by {@code read}'s caller) or returns a {@code Document} per the
     * well-formedness contract, which always has exactly one root element. See
     * {@code XmlCodecReflectionTest} for the mocked-{@code Document} test that
     * exercises the null case this guards against.
     */
    private static org.w3c.dom.Element requireRootElement(org.w3c.dom.Document domDoc) {
        org.w3c.dom.Element rootElem = domDoc.getDocumentElement();
        if (rootElem == null) {
            throw new DocumentParseException("$", "document.parse-error", "no document element found");
        }
        return rootElem;
    }

    private static Object xmlToNode(org.w3c.dom.Element elem, String path, String docPath, int depth, int[] budget, WriteReport rep) {
        Limits limits = Limits.DEFAULT;
        if (depth > limits.maxDepth()) {
            throw new DocumentParseException(path, "document.limit.depth", path + ": nesting exceeds the maximum depth (" + limits.maxDepth() + ")");
        }

        int prefixColon = elem.getTagName().indexOf(':');
        if (prefixColon >= 0) {
            rep.add(docPath, "format.namespace-dropped",
                    "namespace prefix '" + elem.getTagName().substring(0, prefixColon) + "' discarded on read; element read as local name '" + localName(elem) + "' with no namespace binding",
                    "warning");
        }
        org.w3c.dom.NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Attr attr = (org.w3c.dom.Attr) attrs.item(i);
            String attrName = attr.getName();
            if (attrName.equals("xmlns") || attrName.startsWith("xmlns:")) {
                continue;
            }
            rep.add(docPath, "format.attribute-dropped",
                    "attribute discarded on read (no Document path can represent an XML attribute)",
                    "warning");
            break;
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
            // CDATA_SECTION_NODE is not reachable here: the DocumentBuilderFactory
            // this class configures always sets setCoalescing(true) (see read()
            // above), which merges CDATA sections into plain TEXT_NODE content
            // before the DOM is ever exposed to this code -- verified empirically
            // that a CDATA section's node type is TEXT_NODE (3), never
            // CDATA_SECTION_NODE (4), under this exact configuration.
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                    String text = child.getNodeValue();
                    // text != null's false side is unreachable in practice: per the DOM
                    // spec, a Text node's getNodeValue() returns its (possibly empty, but
                    // never null) character data -- kept as defensive handling for the
                    // nullable-Object-returning Node.getNodeValue() signature itself.
                    if (text != null && !text.trim().isEmpty()) {
                        throw new DocumentParseException(path, "document.unlabeled-element", path + ": mixed content (text alongside child elements) is outside the data-XML profile");
                    }
                }
            }

            List<Object[]> edges = new ArrayList<>();
            for (org.w3c.dom.Element c : childElements) {
                String localName = localName(c);
                Object childNode = xmlToNode(c, path + "." + localName, docPath + "." + localName, depth + 1, budget, rep);
                edges.add(new Object[]{localName, childNode});
            }
            return edges;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                    sb.append(child.getNodeValue());
                }
            }
            return sb.toString();
        }
    }

    private static String localName(org.w3c.dom.Element el) {
        // The DocumentBuilderFactory is deliberately non-namespace-aware (see #read),
        // so getLocalName() always returns null here -- getTagName() (the raw,
        // possibly-prefixed tag text) is the only source, with any prefix stripped below.
        String name = el.getTagName();
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
                        String clean = s.startsWith("-") ? s.substring(1) : s;
                        if (clean.length() > Limits.DEFAULT.maxIntegerDigits()) {
                            throw new DocumentParseException("$", "document.limit.int-digits", "document.limit.int-digits: Integer literal digit count (" + clean.length() + ") exceeds maximum limit of " + Limits.DEFAULT.maxIntegerDigits());
                        }
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
        Limits limits = Limits.DEFAULT;
        // No depth guard here: read() only reaches buildDoc after xmlToNode has
        // already walked the DOM tree that this List/Object[] structure mirrors
        // 1:1 (one xmlToNode level per buildDoc level) and thrown if any depth
        // exceeded 200, so that bound is already established.
        if (val instanceof List<?> list) {
            budget[0]++;
            if (budget[0] > limits.maxNodeCount()) {
                throw new DocumentParseException(path, "document.limit.nodes", path + ": too many nodes materialized (over " + limits.maxNodeCount() + ")");
            }
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

    /**
     * Serializes a {@link Document} to XML text, non-strict (dropping/sanitizing
     * unrepresentable content rather than throwing). Equivalent to
     * {@code write(node, false, null)}.
     *
     * @param node the document to serialize; must have exactly one top-level edge
     *             (XML requires a single root element)
     * @return the XML text
     * @throws WriteException if {@code node} does not have exactly one top-level edge
     */
    public static String write(Document node) {
        return write(node, false, null);
    }

    /**
     * Serializes a {@link Document} to XML text.
     *
     * @param node   the document to serialize; must have exactly one top-level edge
     *               (XML requires a single root element)
     * @param strict if {@code true}, throws when the document contains any adjustment
     *               (e.g. a sanitized element name); if {@code false}, applies the
     *               adjustment and continues
     * @param report if non-{@code null}, every adjustment made during writing is appended here
     * @return the XML text
     * @throws WriteException if {@code node} does not have exactly one top-level edge,
     *         or if {@code strict} is {@code true} and an adjustment was required
     */
    public static String write(Document node, boolean strict, WriteReport report) {
        WriteReport rep = check(node);
        if (report != null) {
            report.addAll(rep.adjustments());
        }
        if (!(node instanceof Node root) || root.edges().size() != 1) {
            throw new WriteException("XML needs exactly one document element; the root node must have a single top-level edge (a single-rooted Document)", rep);
        }
        // Fail, don't invent (issues #88/#90): a label/string XML's own syntax cannot
        // represent, or an empty internal node (indistinguishable from an empty string
        // leaf on read-back), fails unconditionally -- regardless of strict -- since no
        // single well-defined substitute exists that doesn't risk silent data loss or
        // a collision with a genuinely different, independently-valid input.
        if (rep.adjustments().stream().anyMatch(a -> "write.unsupported-value".equals(a.code()))) {
            throw new WriteException(rep.toString(), rep);
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
            // Confirmed-unreachable as of issue #90: write() always calls check() first
            // and fails unconditionally (write.unsupported-value) on an empty internal
            // node before writeNode is ever reached with one, so node.edges() is never
            // empty here. Distinct from the empty-string-LEAF self-closing case below,
            // which is still reachable (an empty string is a legitimately representable
            // XML leaf value, not an ambiguous shape).
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

    /**
     * Returns {@code name} unchanged. {@code write()} always calls {@link #check} first
     * and fails unconditionally (issue #88, {@code write.unsupported-value}) before this
     * is ever reached with a name that isn't already a valid XML name, so there is no
     * fallback to compute here anymore.
     */
    private static String xmlName(String name) {
        return name;
    }

    /**
     * Escapes {@code text} for XML output. {@code write()} always calls {@link #check}
     * first and fails unconditionally (issue #88) on any character XML 1.0 cannot
     * represent, so no U+FFFD substitution is needed here anymore -- this is reached
     * only with already-legal XML character data.
     *
     * <p>A literal carriage return is escaped as the numeric character reference
     * {@code &#13;} (issue #91): XML mandates line-ending normalization on parse, so a
     * raw '\r' and a raw '\n' are indistinguishable on read-back, but a numeric character
     * reference is exempt from that normalization and survives a compliant parser intact.
     */
    private static String xmlSanitize(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                case '\r' -> sb.append("&#13;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String xmlText(Object v) {
        // Called only from writeNode's non-Node (i.e. Value) branch, so v is
        // exhaustively a Value here -- sealed to Scalar's 7 variants | NullValue,
        // all handled below; no fallback is reachable.
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
        NumberScalar s = (NumberScalar) v;
        return Double.toString(s.value());
    }

    /**
     * Computes what adjustments {@link #write(Document)} would make to {@code node}
     * without actually producing XML text.
     *
     * @param node the document to check
     * @return the adjustments (e.g. sanitized names, dropped nulls) that a write of
     *         {@code node} would require
     */
    public static WriteReport check(Document node) {
        WriteReport rep = new WriteReport();
        scanXml(node, "$", rep, 0);
        return rep;
    }

    private static void scanXml(Document doc, String path, WriteReport rep, int depth) {
        if (depth > 200) {
            throw new WriteException("nesting exceeds the maximum depth (200)");
        }
        if (depth == 0 && doc instanceof Node root && root.edges().size() != 1) {
            rep.add("$", "format.multiple-roots",
                    "XML requires exactly one root element; document has " + root.edges().size() + " top-level edges",
                    "error");
            return;
        }
        if (doc instanceof Node node) {
            if (node.edges().isEmpty()) {
                // Issue #90: an empty internal node is indistinguishable on read-back from
                // an empty string leaf, so there's no safe substitute -- fail unconditionally.
                rep.add(path, "write.unsupported-value",
                        "empty internal node (no edges) has no representable XML syntax: " +
                        "a self-closing <tag /> reads back as the empty-string leaf '', not []",
                        "error");
                return;
            }
            Map<String, Integer> totals = dev.omnist.document.PathUtils.countLabels(node);
            Map<String, Integer> seen = new HashMap<>();
            for (Edge edge : node.edges()) {
                String label = edge.label();
                int i = seen.getOrDefault(label, 0);
                seen.put(label, i + 1);
                int total = totals.getOrDefault(label, 1);
                String p = dev.omnist.document.PathUtils.childPath(path, label, i, total);
                if (!XML_NAME.matcher(label).matches()) {
                    // Issue #88: two different labels can sanitize to the same XML name
                    // (e.g. "my label" and "my_label" both -> <my_label>), silently
                    // colliding on read-back with no diagnostic -- fail unconditionally.
                    rep.add(p, "write.unsupported-value",
                            "label '" + label + "' has no representable XML syntax (not a valid XML name)",
                            "error");
                }
                scanXml((Document) edge.target(), p, rep, depth + 1);
            }
        } else if (doc instanceof Value.NullValue) {
            // Issue #89: a null leaf written as an empty XML element is indistinguishable
            // on read-back from an empty string leaf -- fail unconditionally.
            rep.add(path, "write.unsupported-value", "null has no representable XML syntax", "error");
        } else if (doc instanceof Scalar s) {
            if (s instanceof DateScalar || s instanceof TimeScalar || s instanceof DateTimeScalar) {
                rep.add(path, "format.temporal-stringified",
                        "temporal value written as text (reads back as a string)", "warning");
            } else if (s instanceof BooleanScalar || s instanceof IntegerScalar || s instanceof NumberScalar) {
                rep.add(path, "format.value-stringified",
                        "non-string scalar written as text (reads back as a string)", "warning");
            }
            
            String strVal = xmlText(doc);
            if (XML_ILLEGAL_CHAR.matcher(strVal).find()) {
                // Issue #88: no substitute exists for a character XML 1.0 cannot
                // represent at all (e.g. a C0 control other than tab/LF/CR) -- fail
                // unconditionally rather than silently replacing it with U+FFFD.
                rep.add(path, "write.unsupported-value",
                        "string contains a character XML 1.0 cannot represent " +
                        "(e.g. a C0 control other than tab/LF/CR)",
                        "error");
            }
            // Issue #91: a carriage return is now escaped losslessly as the numeric
            // character reference &#13; (see xmlSanitize) instead of being written raw
            // and warned about -- nothing to report here anymore.
        }
    }
}
