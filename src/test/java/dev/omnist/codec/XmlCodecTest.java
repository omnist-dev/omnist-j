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
        String osd = "record RootRecord {\n" +
                     "  \"bool\": boolean,\n" +
                     "  \"int\": integer,\n" +
                     "  \"num\": number,\n" +
                     "  \"str\": string,\n" +
                     "}\n" +
                     "record Root {\n" +
                     "  \"Root\": RootRecord\n" +
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
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("shape.empty_ambiguous")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("key.sanitized")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("null.omitted")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("value.stringified")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("string.cr_normalized")));
        assertTrue(adjs.stream().anyMatch(a -> a.code().equals("string.illegal_xml_char")));
    }
}
