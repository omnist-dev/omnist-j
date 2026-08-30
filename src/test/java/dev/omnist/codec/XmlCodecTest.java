package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class XmlCodecTest {

    @Test
    @DisplayName("read parses element sequence directly preserving order and dropping attributes/prefixes")
    void testBasicRead() {
        String xml = "<root xmlns:ns='http://example.com' a='1'>" +
                     "  <ns:m>first</ns:m>" +
                     "  <x>second</x>" +
                     "  <m>third</m>" +
                     "</root>";
        Document doc = XmlCodec.read(xml);
        assertTrue(doc instanceof Node);
        Node node = (Node) doc;
        assertEquals(1, node.edges().size());

        Edge rootEdge = node.edges().get(0);
        assertEquals("root", rootEdge.label());
        assertTrue(rootEdge.target() instanceof Node);
        Node rootNode = (Node) rootEdge.target();

        // Should have 3 child edges in exact order, namespace prefixes stripped
        assertEquals(3, rootNode.edges().size());
        assertEquals("m", rootNode.edges().get(0).label());
        assertEquals("first", ((StringScalar) rootNode.edges().get(0).target()).value());
        assertEquals("x", rootNode.edges().get(1).label());
        assertEquals("second", ((StringScalar) rootNode.edges().get(1).target()).value());
        assertEquals("m", rootNode.edges().get(2).label());
        assertEquals("third", ((StringScalar) rootNode.edges().get(2).target()).value());
    }

    @Test
    @DisplayName("read performs schema-directed pretyping on booleans, integers, and numbers")
    void testPretyping() {
        String osd = "record Root {\n" +
                     "  \"bool\": boolean,\n" +
                     "  \"int\": integer,\n" +
                     "  \"num\": number,\n" +
                     "  \"str\": string,\n" +
                     "}\n" +
                     "root Root\n";
        Schema schema = OsdReader.read(osd);

        String xml = "<Root>" +
                     "  <bool>true</bool>" +
                     "  <int>-43</int>" +
                     "  <num>3.14</num>" +
                     "  <str>100</str>" +
                     "</Root>";

        // Without schema: all are StringScalar
        Document docNoSchema = XmlCodec.read(xml);
        Node nodeNoSchema = (Node) ((Node) docNoSchema).edges().get(0).target();
        assertTrue(nodeNoSchema.edges().stream().allMatch(e -> e.target() instanceof StringScalar));

        // With schema: pretyped correctly
        Document docSchema = XmlCodec.read(xml, schema);
        Node nodeSchema = (Node) ((Node) docSchema).edges().get(0).target();

        Edge eBool = nodeSchema.edges().stream().filter(e -> e.label().equals("bool")).findFirst().orElse(null);
        assertNotNull(eBool);
        assertTrue(eBool.target() instanceof BooleanScalar);
        assertTrue(((BooleanScalar) eBool.target()).value());

        Edge eInt = nodeSchema.edges().stream().filter(e -> e.label().equals("int")).findFirst().orElse(null);
        assertNotNull(eInt);
        assertTrue(eInt.target() instanceof IntegerScalar);
        assertEquals(BigInteger.valueOf(-43), ((IntegerScalar) eInt.target()).value());

        Edge eNum = nodeSchema.edges().stream().filter(e -> e.label().equals("num")).findFirst().orElse(null);
        assertNotNull(eNum);
        assertTrue(eNum.target() instanceof NumberScalar);
        assertEquals(3.14, ((NumberScalar) eNum.target()).value());

        // str should remain StringScalar ("100") because schema says it is a string!
        Edge eStr = nodeSchema.edges().stream().filter(e -> e.label().equals("str")).findFirst().orElse(null);
        assertNotNull(eStr);
        assertTrue(eStr.target() instanceof StringScalar);
        assertEquals("100", ((StringScalar) eStr.target()).value());
    }

    @Test
    @DisplayName("read throws ParseError on mixed content")
    void testMixedContentRejection() {
        String xml = "<root>text<child/></root>";
        assertThrows(RuntimeException.class, () -> XmlCodec.read(xml));
    }

    @Test
    @DisplayName("write enforces single document element constraint")
    void testWriteRootConstraint() {
        // More than 1 top-level edge
        Node invalidRoot = new Node(List.of(
            new Edge("a", new StringScalar("1")),
            new Edge("b", new StringScalar("2"))
        ));
        assertThrows(WriteException.class, () -> XmlCodec.write(invalidRoot));
    }

    @Test
    @DisplayName("write records a warning for a stringified non-string scalar; a CR is escaped losslessly, not warned about (issue #91)")
    void testWriteValueStringifiedAndCrEscaping() {
        Node child = new Node(List.of(
            new Edge("bool", new BooleanScalar(true)),
            new Edge("int", new IntegerScalar(BigInteger.TEN)),
            new Edge("cr", new StringScalar("line1\rline2"))
        ));
        Node root = new Node(List.of(new Edge("root", child)));

        WriteReport report = new WriteReport();
        String xml = XmlCodec.write(root, false, report);
        assertTrue(xml.contains("<root>"));
        assertTrue(xml.contains("line1&#13;line2"), "Expected &#13; escape in: " + xml);

        List<WriteAdjustment> adjs = report.adjustments();
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.value-stringified")));
        assertTrue(adjs.stream().noneMatch(a -> a.code().equals("format.string-cr-normalized")));
    }

    @Test
    @DisplayName("carriage return written as numeric character reference (issue #91, happy path)")
    void testCarriageReturnEscapedAsNumericCharacterReference() {
        Node root = new Node(List.of(new Edge("root", new Node(List.of(new Edge("x", new StringScalar("a\rb")))))));
        WriteReport report = new WriteReport();
        String xml = XmlCodec.write(root, false, report);
        assertEquals("<root>\n  <x>a&#13;b</x>\n</root>\n", xml);
        assertTrue(report.adjustments().isEmpty());

        Node crlf = new Node(List.of(new Edge("root", new Node(List.of(new Edge("x", new StringScalar("a\r\nb")))))));
        String xmlCrlf = XmlCodec.write(crlf, false, new WriteReport());
        assertEquals("<root>\n  <x>a&#13;\nb</x>\n</root>\n", xmlCrlf);
    }

    @Test
    @DisplayName("an empty internal node cannot be written -- fails unconditionally (issue #90)")
    void testEmptyInternalNodeCannotBeWritten() {
        Node root = new Node(List.of(new Edge("root", new Node(List.of(new Edge("empty_seq", new Node(List.of())))))));
        WriteException ex = assertThrows(WriteException.class, () -> XmlCodec.write(root, false, null));
        assertTrue(ex.report().adjustments().stream()
            .anyMatch(a -> a.code().equals("write.unsupported-value") && a.path().equals("$.root.empty_seq")));
    }

    @Test
    @DisplayName("a label XML syntax cannot represent cannot be written -- fails unconditionally (issue #88)")
    void testIllegalLabelCannotBeWritten() {
        Node root = new Node(List.of(new Edge("root", new Node(List.of(new Edge("invalid label !", new StringScalar("val")))))));
        WriteException ex = assertThrows(WriteException.class, () -> XmlCodec.write(root, false, null));
        assertTrue(ex.report().adjustments().stream()
            .anyMatch(a -> a.code().equals("write.unsupported-value") && a.path().equals("$.root.invalid label !")));
    }

    @Test
    @DisplayName("a null leaf cannot be written to XML -- fails unconditionally (issue #89)")
    void testNullCannotBeWritten() {
        Node root = new Node(List.of(new Edge("root", new Node(List.of(new Edge("nullable", Value.NULL))))));
        WriteException ex = assertThrows(WriteException.class, () -> XmlCodec.write(root, false, null));
        assertTrue(ex.report().adjustments().stream()
            .anyMatch(a -> a.code().equals("write.unsupported-value") && a.path().equals("$.root.nullable")));
    }

    @Test
    @DisplayName("a character XML 1.0 cannot represent cannot be written -- fails unconditionally (issue #88)")
    void testIllegalCharCannotBeWritten() {
        Node root = new Node(List.of(new Edge("root", new Node(List.of(new Edge("illegal", new StringScalar("char \u0000")))))));
        WriteException ex = assertThrows(WriteException.class, () -> XmlCodec.write(root, false, null));
        assertTrue(ex.report().adjustments().stream()
            .anyMatch(a -> a.code().equals("write.unsupported-value") && a.path().equals("$.root.illegal")));
    }

    // ==========================================================================
    // Coverage-gap-driven batch (inputs verified against real XmlCodec/DOM
    // behavior via a scratch diagnostic before writing assertions)
    // ==========================================================================

    @Test
    @DisplayName("read: mixed content (text alongside child elements) throws")
    void testReadMixedContentThrows() {
        assertThrows(RuntimeException.class, () -> XmlCodec.read("<root><a>1</a>text</root>"));
    }

    @Test
    @DisplayName("read: CDATA section content is collected as leaf text")
    void testReadCdataSection() {
        Document doc = XmlCodec.read("<root><![CDATA[hello]]></root>");
        Node node = (Node) doc;
        assertEquals(new StringScalar("hello"), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: whitespace-only text alongside a child element is not mixed content")
    void testReadWhitespaceOnlyTextBesideChildDoesNotThrow() {
        Document doc = XmlCodec.read("<root>\n  <a>1</a>\n</root>");
        assertNotNull(doc);
    }

    @Test
    @DisplayName("read: leaf text collection skips comment nodes (neither TEXT_NODE nor CDATA_SECTION_NODE)")
    void testReadLeafTextSkipsCommentNodes() {
        // Exercises all three outcomes of the TEXT_NODE || CDATA_SECTION_NODE
        // check in one leaf: plain text, a CDATA section, and a comment (which
        // matches neither and must be skipped).
        Document doc = XmlCodec.read("<root>text<![CDATA[cdata]]><!--a comment--></root>");
        Node node = (Node) doc;
        assertEquals(new StringScalar("textcdata"), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: with a schema whose root record name isn't in schema.records()")
    void testReadWithUndefinedSchemaRoot() {
        Schema schema = new Schema("Missing", java.util.Map.of());
        Document doc = XmlCodec.read("<root><a>1</a></root>", schema);
        assertNotNull(doc);
    }

    @Test
    @DisplayName("read: schema-typed boolean/integer/number pretyping, including a Record-typed nested field")
    void testReadSchemaTypedPretyping() {
        Schema schema = OsdReader.read(
            "record Inner { \"x\": integer } " +
            "record Root { \"flag\": boolean, \"n\": integer, \"d\": number, \"inner\": Inner } root Root\n"
        );
        Document doc = XmlCodec.read(
            "<root><flag>true</flag><n>42</n><d>3.14</d><inner><x>7</x></inner></root>", schema);
        Node root = (Node) doc;
        Node node = (Node) root.edges().get(0).target();
        assertEquals(new BooleanScalar(true), node.edges().get(0).target());
        assertEquals(new IntegerScalar(BigInteger.valueOf(42)), node.edges().get(1).target());
        assertEquals(new NumberScalar(3.14), node.edges().get(2).target());
        Node inner = (Node) node.edges().get(3).target();
        assertEquals(new IntegerScalar(BigInteger.valueOf(7)), inner.edges().get(0).target());
    }

    @Test
    @DisplayName("read: pretype false-boolean match and an unschema'd extra field")
    void testReadPretypeFalseBooleanAndExtraField() {
        Schema schema = OsdReader.read("record Root { \"flag\": boolean } root Root\n");
        Document doc = XmlCodec.read(
            "<root><flag>false</flag><extra>unschema'd</extra></root>", schema);
        Node root = (Node) doc;
        Node node = (Node) root.edges().get(0).target();
        assertEquals(new BooleanScalar(false), node.edges().get(0).target());
        // "extra" has no matching field on Root -- passes through unpretyped
        assertEquals(new StringScalar("unschema'd"), node.edges().get(1).target());
    }

    @Test
    @DisplayName("read: pretype passthrough when integer/number-typed text doesn't match the schema's regex")
    void testReadPretypeIntegerAndNumberRegexNoMatchPassthrough() {
        // Verified empirically via a diagnostic against the real codec+schema: "12.5" fails
        // XML_INT_RE (no decimal point allowed) and "abc" fails XML_NUM_RE, so both fields
        // fall through xmlPretype's Type.Scalar branch unconverted, staying StringScalar.
        Schema schema = OsdReader.read("record Root { \"n\": integer, \"d\": number } root Root\n");
        Document doc = XmlCodec.read("<root><n>12.5</n><d>abc</d></root>", schema);
        Node root = (Node) doc;
        Node node = (Node) root.edges().get(0).target();
        assertEquals(new StringScalar("12.5"), node.edges().get(0).target());
        assertEquals(new StringScalar("abc"), node.edges().get(1).target());
    }

    @Test
    @DisplayName("read: a BOOLEAN-kind schema field whose text is neither \"true\" nor \"false\" stays a string")
    void testReadPretypeBooleanKindNeitherTrueNorFalse() {
        Schema schema = OsdReader.read("record Root { \"flag\": boolean } root Root\n");
        Document doc = XmlCodec.read("<root><flag>maybe</flag></root>", schema);
        Node root = (Node) doc;
        Node node = (Node) root.edges().get(0).target();
        assertEquals(new StringScalar("maybe"), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: a STRING-kind schema field falls through xmlPretype's boolean/integer/number checks")
    void testReadPretypeStringKindFallsThroughAllChecks() {
        Schema schema = OsdReader.read("record Root { \"s\": string } root Root\n");
        Document doc = XmlCodec.read("<root><s>hello</s></root>", schema);
        Node root = (Node) doc;
        Node node = (Node) root.edges().get(0).target();
        assertEquals(new StringScalar("hello"), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: a Scalar-typed schema field whose XML content is actually nested elements, not text")
    void testReadPretypeScalarFieldWithNonStringContent() {
        // "n" is schema-typed as a scalar (integer), but the actual XML element has
        // child elements, not text -- xmlToNode returns a List<Object[]> for it, not
        // a String, so xmlPretype's Type.Scalar branch's "node instanceof String" is false.
        Schema schema = OsdReader.read("record Root { \"n\": integer } root Root\n");
        Document doc = XmlCodec.read("<root><n><unexpected>1</unexpected></n></root>", schema);
        assertNotNull(doc);
    }

    @Test
    @DisplayName("read: a Record-typed schema field whose XML content is actually leaf text, not nested elements")
    void testReadPretypeRecordFieldWithNonListContent() {
        // "inner" is schema-typed as a record, but the actual XML element is a leaf
        // with plain text content -- xmlToNode returns a String for it, not a
        // List<Object[]>, so xmlPretype's Record branch's "node instanceof List" is false.
        Schema schema = OsdReader.read("record Inner { \"x\": integer } record Root { \"inner\": Inner } root Root\n");
        Document doc = XmlCodec.read("<root><inner>just text</inner></root>", schema);
        assertNotNull(doc);
    }

    @Test
    @DisplayName("read: a Ref field pointing at an undefined record resolves to null, falling through unpretyped")
    void testReadPretypeRefToUndefinedRecord() {
        // Bypasses OsdReader's own root-reference validation to construct a
        // schema with a dangling Ref, exercising resolveType's null-lookup path.
        dev.omnist.schema.Record root = new dev.omnist.schema.Record("Root", List.of(
            new dev.omnist.schema.Field("other", new dev.omnist.schema.Type.Ref("MissingTarget"), 0, 1)
        ));
        Schema schema = new Schema("Root", java.util.Map.of("Root", root));
        Document doc = XmlCodec.read("<root><other>text</other></root>", schema);
        Node rootNode = (Node) doc;
        Node node = (Node) rootNode.edges().get(0).target();
        assertEquals(new StringScalar("text"), node.edges().get(0).target());
    }

    @Test
    @DisplayName("write: root must have exactly one top-level edge")
    void testWriteRequiresSingleRootEdge() {
        Node multiRoot = new Node(List.of(
            new Edge("a", new StringScalar("1")),
            new Edge("b", new StringScalar("2"))
        ));
        assertThrows(WriteException.class, () -> XmlCodec.write(multiRoot));

        Node scalarLike = new Node(List.of());
        assertThrows(WriteException.class, () -> XmlCodec.write(scalarLike));
    }

    @Test
    @DisplayName("write: strict mode throws WriteException when adjustments are non-empty")
    void testWriteStrictModeThrows() {
        // A null leaf now fails unconditionally regardless of strict (issue #89), so use a
        // value-stringified-only adjustment here to keep covering the strict-only throw path
        // (an adjustment that ISN'T write.unsupported-value, only rejected because strict).
        Node doc = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("x", new BooleanScalar(true))
        )))));
        assertThrows(WriteException.class, () -> XmlCodec.write(doc, true, null));
    }

    @Test
    @DisplayName("write: a label that isn't a valid XML name fails unconditionally, even in non-strict mode (issue #88)")
    void testWriteXmlNameSanitizationFallback() {
        Node doc = new Node(List.of(new Edge("123", new StringScalar("v"))));
        WriteException ex = assertThrows(WriteException.class, () -> XmlCodec.write(doc));
        assertTrue(ex.report().adjustments().stream()
            .anyMatch(a -> a.code().equals("write.unsupported-value")));
    }

    @Test
    @DisplayName("write: date/time/datetime/integer/number scalar text formatting")
    void testWriteScalarTextFormatting() {
        Node doc = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("d", new DateScalar(java.time.LocalDate.parse("2024-01-01"))),
            new Edge("t", new TimeScalar(dev.omnist.document.TimeValue.of(java.time.LocalTime.of(10, 0), java.time.ZoneOffset.UTC))),
            new Edge("dt", new DateTimeScalar(dev.omnist.document.DateTimeValue.of(java.time.LocalDateTime.of(2024, 1, 1, 10, 0), java.time.ZoneOffset.UTC))),
            new Edge("n", new IntegerScalar(BigInteger.valueOf(42))),
            new Edge("f", new NumberScalar(3.14)),
            new Edge("bFalse", new BooleanScalar(false))
        )))));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("2024-01-01"));
        assertTrue(xml.contains("42"));
        assertTrue(xml.contains("3.14"));
        // xmlText's boolean ternary's "false" side: elsewhere only ever written as true.
        assertTrue(xml.contains("<bFalse>false</bFalse>"));
    }

    @Test
    @DisplayName("localName(): getLocalName()==null fallback to getTagName() with colon-stripping (reflection -- XmlCodec always parses namespace-aware, so this never happens through read())")
    void testLocalNameFallbackViaReflection() throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(
            new org.xml.sax.InputSource(new java.io.StringReader("<ns:root>1</ns:root>")));
        org.w3c.dom.Element el = doc.getDocumentElement();
        assertNull(el.getLocalName());

        java.lang.reflect.Method localName = XmlCodec.class.getDeclaredMethod("localName", org.w3c.dom.Element.class);
        localName.setAccessible(true);
        assertEquals("root", localName.invoke(null, el));
    }

    @Test
    @DisplayName("read/write: budget guards and buildDoc's unknown-type throw (reflection)")
    void testBudgetGuardsAndUnknownTypeViaReflection() throws Exception {
        java.lang.reflect.Method xmlToNode = XmlCodec.class.getDeclaredMethod(
            "xmlToNode", org.w3c.dom.Element.class, String.class, String.class, int.class, int[].class, dev.omnist.codec.WriteReport.class);
        xmlToNode.setAccessible(true);
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document domDoc = dbf.newDocumentBuilder().parse(
            new org.xml.sax.InputSource(new java.io.StringReader("<a>1</a>")));
        org.w3c.dom.Element el = domDoc.getDocumentElement();

        java.lang.reflect.Method buildDoc = XmlCodec.class.getDeclaredMethod(
            "buildDoc", Object.class, String.class, int.class, int[].class);
        buildDoc.setAccessible(true);
        int[] overBudget2 = new int[]{1_000_001};
        java.lang.reflect.InvocationTargetException writeThrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> buildDoc.invoke(null, java.util.List.of(new Object[]{"k", "v"}), "$", 0, overBudget2));
        assertTrue(writeThrown.getCause().getMessage().contains("too many nodes materialized"));

        int[] freshBudget = new int[]{0};
        java.lang.reflect.InvocationTargetException unknownTypeThrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> buildDoc.invoke(null, 3.14f, "$", 0, freshBudget));
        assertInstanceOf(IllegalArgumentException.class, unknownTypeThrown.getCause());
    }

    @Test
    @DisplayName("write: strict mode succeeds with no adjustments; an invalid label (digit-led or empty) fails unconditionally either way (issue #88)")
    void testWriteStrictSucceedsAndXmlNameEmptyFallback() {
        Node cleanDoc = new Node(List.of(new Edge("root", new StringScalar("v"))));
        String xml = XmlCodec.write(cleanDoc, true, null);
        assertTrue(xml.contains("<root>"));

        // "123" isn't a valid XML name (a name can't start with a digit) -- issue #88
        // means this now fails unconditionally, with no "_" prefix sanitize fallback.
        Node digitLabel = new Node(List.of(new Edge("123", new StringScalar("v"))));
        WriteException ex1 = assertThrows(WriteException.class, () -> XmlCodec.write(digitLabel));
        assertTrue(ex1.report().adjustments().stream().anyMatch(a -> a.code().equals("write.unsupported-value")));

        // An empty label is likewise not a valid XML name -- also fails unconditionally.
        Node emptyLabel = new Node(List.of(new Edge("", new StringScalar("v"))));
        WriteException ex2 = assertThrows(WriteException.class, () -> XmlCodec.write(emptyLabel));
        assertTrue(ex2.report().adjustments().stream().anyMatch(a -> a.code().equals("write.unsupported-value")));
    }

    @Test
    @DisplayName("Issue #40: write escapes &, <, > in scalar text and does not inject markup")
    void testXmlWriterEscapesMetacharacters() {
        Node injectionDoc = new Node(List.of(new Edge("root", new StringScalar("<admin>true</admin>"))));
        String xml = XmlCodec.write(injectionDoc);
        assertTrue(xml.contains("&lt;admin&gt;true&lt;/admin&gt;"), "XML should contain escaped markup: " + xml);
        assertFalse(xml.contains("<admin>"), "XML should not contain raw injected tag: " + xml);

        Document roundtripped = XmlCodec.read(xml);
        assertTrue(roundtripped instanceof Node);
        Node roundNode = (Node) roundtripped;
        assertEquals(1, roundNode.edges().size());
        assertEquals("root", roundNode.edges().get(0).label());
        assertEquals("<admin>true</admin>", ((StringScalar) roundNode.edges().get(0).target()).value());

        // Adversarial metacharacters that ARE representable still roundtrip fine.
        String adversarial = "<>&\"'\t\n<&amp;>";
        Node advDoc = new Node(List.of(new Edge("root", new StringScalar(adversarial))));
        String advXml = XmlCodec.write(advDoc);
        Document advRound = XmlCodec.read(advXml);
        assertEquals(adversarial, ((StringScalar) ((Node) advRound).edges().get(0).target()).value());

        // \u0000 is a character XML 1.0 cannot represent at all -- fails unconditionally
        // now (issue #88), rather than being silently substituted with U+FFFD.
        Node illegalDoc = new Node(List.of(new Edge("root", new StringScalar("a\u0000b"))));
        WriteException ex = assertThrows(WriteException.class, () -> XmlCodec.write(illegalDoc));
        assertTrue(ex.report().adjustments().stream().anyMatch(a -> a.code().equals("write.unsupported-value")));
    }

    @Test
    @DisplayName("Issue #84 (D-3): dropped attribute is reported via format.attribute-dropped at the owning element's path")
    void testAttributeDroppedDiagnostic() {
        String xml = "<a x=\"1\"><b>hi</b></a>";
        WriteReport report = new WriteReport();
        Document doc = XmlCodec.read(xml, null, report);

        Node root = (Node) doc;
        assertEquals(1, root.edges().size());
        assertEquals("a", root.edges().get(0).label());
        Node aNode = (Node) root.edges().get(0).target();
        assertEquals(1, aNode.edges().size());
        assertEquals("b", aNode.edges().get(0).label());
        assertEquals("hi", ((StringScalar) aNode.edges().get(0).target()).value());

        assertEquals(1, report.adjustments().size());
        WriteAdjustment adj = report.adjustments().get(0);
        assertEquals("$.a", adj.path());
        assertEquals("format.attribute-dropped", adj.code());
        assertEquals("warning", adj.severity());
    }

    @Test
    @DisplayName("Issue #84 (D-3): dropped namespace prefix is reported via format.namespace-dropped at the element's own path")
    void testNamespaceDroppedDiagnostic() {
        String xml = "<a><ns:b>hi</ns:b></a>";
        WriteReport report = new WriteReport();
        Document doc = XmlCodec.read(xml, null, report);

        Node root = (Node) doc;
        Node aNode = (Node) root.edges().get(0).target();
        assertEquals("b", aNode.edges().get(0).label());
        assertEquals("hi", ((StringScalar) aNode.edges().get(0).target()).value());

        assertEquals(1, report.adjustments().size());
        WriteAdjustment adj = report.adjustments().get(0);
        assertEquals("$.a.b", adj.path());
        assertEquals("format.namespace-dropped", adj.code());
        assertEquals("warning", adj.severity());
    }

    @Test
    @DisplayName("Issue #84 (D-3): read(text) / read(text, schema) with no report still succeed (report is opt-in)")
    void testReadWithoutReportStillWorks() {
        assertDoesNotThrow(() -> XmlCodec.read("<a x=\"1\"><b>hi</b></a>"));
        Schema schema = OsdReader.read("record R { \"b\": string }\nroot R\n");
        assertDoesNotThrow(() -> XmlCodec.read("<a x=\"1\"><b>hi</b></a>", schema));
    }

    @Test
    @DisplayName("a plain (non-prefixed, no-attribute) element produces no D-3 diagnostics")
    void testNoSpuriousD3Diagnostics() {
        WriteReport report = new WriteReport();
        XmlCodec.read("<a><b>hi</b></a>", null, report);
        assertTrue(report.adjustments().isEmpty(), "unexpected adjustments: " + report);
    }

    @Test
    @DisplayName("an xmlns declaration attribute is not itself reported as format.attribute-dropped")
    void testXmlnsDeclarationNotReportedAsAttributeDropped() {
        String xml = "<root xmlns:ns='http://example.com'><ns:m>first</ns:m></root>";
        WriteReport report = new WriteReport();
        XmlCodec.read(xml, null, report);

        assertTrue(report.adjustments().stream().noneMatch(a -> a.code().equals("format.attribute-dropped")),
            "xmlns declaration should not be reported as a dropped attribute: " + report);
        assertTrue(report.adjustments().stream().anyMatch(a -> a.code().equals("format.namespace-dropped")));
    }

    @Test
    @DisplayName("a bare default-namespace xmlns attribute (no prefix) is not itself reported as format.attribute-dropped")
    void testBareDefaultXmlnsNotReportedAsAttributeDropped() {
        String xml = "<root xmlns='http://example.com'><b>hi</b></root>";
        WriteReport report = new WriteReport();
        XmlCodec.read(xml, null, report);

        assertTrue(report.adjustments().stream().noneMatch(a -> a.code().equals("format.attribute-dropped")),
            "bare xmlns declaration should not be reported as a dropped attribute: " + report);
    }
}
