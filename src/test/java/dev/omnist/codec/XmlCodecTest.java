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
    @DisplayName("write generates all adjustments (stringifications, sanitization, CR normalization, illegal chars)")
    void testWriteAdjustments() {
        Node child = new Node(List.of(
            new Edge("empty_seq", new Node(List.of())),
            new Edge("invalid label !", new StringScalar("val")),
            new Edge("nullable", Value.NULL),
            new Edge("bool", new BooleanScalar(true)),
            new Edge("int", new IntegerScalar(BigInteger.TEN)),
            new Edge("cr", new StringScalar("line1\rline2")),
            new Edge("illegal", new StringScalar("char \u0000"))
        ));
        Node root = new Node(List.of(new Edge("root", child)));

        WriteReport report = new WriteReport();
        String xml = XmlCodec.write(root, false, report);

        // Verify elements are written
        assertTrue(xml.contains("<root>"));
        assertTrue(xml.contains("<empty_seq />") || xml.contains("<empty_seq/>"));
        // Invalid label sanitized to safe XML name
        assertTrue(xml.contains("invalid_label__"));
        // Illegal character U+0000 replaced with U+FFFD
        assertTrue(xml.contains("char \uFFFD"));

        List<WriteAdjustment> adjs = report.adjustments();
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.shape-empty-ambiguous")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.key-sanitized")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.null-unrepresentable")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.value-stringified")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.string-cr-normalized")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("format.string-illegal-char")));
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
        Node doc = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("x", Value.NULL)
        )))));
        assertThrows(WriteException.class, () -> XmlCodec.write(doc, true, null));
    }

    @Test
    @DisplayName("write: xmlName sanitizes an all-invalid label to an underscore-prefixed safe name")
    void testWriteXmlNameSanitizationFallback() {
        Node doc = new Node(List.of(new Edge("123", new StringScalar("v"))));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("<_123>") || xml.contains("<_"));
    }

    @Test
    @DisplayName("write: date/time/datetime/integer/number scalar text formatting")
    void testWriteScalarTextFormatting() {
        Node doc = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("d", new DateScalar(java.time.LocalDate.parse("2024-01-01"))),
            new Edge("t", new TimeScalar(dev.omnist.document.TimeValue.of(java.time.LocalTime.of(10, 0), java.time.ZoneOffset.UTC))),
            new Edge("dt", new DateTimeScalar(dev.omnist.document.DateTimeValue.of(java.time.LocalDateTime.of(2024, 1, 1, 10, 0), java.time.ZoneOffset.UTC))),
            new Edge("n", new IntegerScalar(BigInteger.valueOf(42))),
            new Edge("f", new NumberScalar(3.14))
        )))));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("2024-01-01"));
        assertTrue(xml.contains("42"));
        assertTrue(xml.contains("3.14"));
    }
}
