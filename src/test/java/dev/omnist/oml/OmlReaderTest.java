package dev.omnist.oml;

import dev.omnist.document.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class OmlReaderTest {

    @Test
    @DisplayName("Flat document with all 7 scalar kinds + null parses correctly")
    void testFlatDocumentAllScalarKinds() {
        String oml = """
            str: "hello world"
            num_int: 42
            num_float: 3.14159
            flag: true
            d: 2026-08-10
            t: 12:30:45+02:00
            dt: 2026-08-10T12:30:45Z
            empty: null
            """;

        Document doc = OmlReader.read(oml);
        assertInstanceOf(Node.class, doc);
        Node node = (Node) doc;

        assertEquals(8, node.edges().size());

        // 1. string
        assertEquals("str", node.edges().get(0).label());
        assertEquals(new Scalar.StringScalar("hello world"), node.edges().get(0).target());

        // 2. integer
        assertEquals("num_int", node.edges().get(1).label());
        assertEquals(new Scalar.IntegerScalar(BigInteger.valueOf(42)), node.edges().get(1).target());

        // 3. number
        assertEquals("num_float", node.edges().get(2).label());
        assertEquals(new Scalar.NumberScalar(3.14159), node.edges().get(2).target());

        // 4. boolean
        assertEquals("flag", node.edges().get(3).label());
        assertEquals(new Scalar.BooleanScalar(true), node.edges().get(3).target());

        // 5. date
        assertEquals("d", node.edges().get(4).label());
        assertEquals(new Scalar.DateScalar(LocalDate.of(2026, 8, 10)), node.edges().get(4).target());

        // 6. time
        assertEquals("t", node.edges().get(5).label());
        assertEquals(new Scalar.TimeScalar(TimeValue.of(LocalTime.of(12, 30, 45), ZoneOffset.ofHours(2))), node.edges().get(5).target());

        // 7. datetime
        assertEquals("dt", node.edges().get(6).label());
        assertEquals(new Scalar.DateTimeScalar(DateTimeValue.of(LocalDateTime.of(2026, 8, 10, 12, 30, 45), ZoneOffset.UTC)), node.edges().get(6).target());

        // 8. null
        assertEquals("empty", node.edges().get(7).label());
        assertEquals(Value.NULL, node.edges().get(7).target());
    }

    @Test
    @DisplayName("Repeated labels produce separate edges in order (array expansion)")
    void testRepeatedLabelsAndArraySugar() {
        String oml = "item: \"pen\"\nnote: \"rush\"\nitem: \"pad\"";
        Document doc1 = OmlReader.read(oml);
        assertInstanceOf(Node.class, doc1);
        Node n1 = (Node) doc1;
        assertEquals(3, n1.edges().size());
        assertEquals("item", n1.edges().get(0).label());
        assertEquals(new Scalar.StringScalar("pen"), n1.edges().get(0).target());
        assertEquals("note", n1.edges().get(1).label());
        assertEquals(new Scalar.StringScalar("rush"), n1.edges().get(1).target());
        assertEquals("item", n1.edges().get(2).label());
        assertEquals(new Scalar.StringScalar("pad"), n1.edges().get(2).target());

        String omlArray = "b: [1, 2, 3]";
        Document doc2 = OmlReader.read(omlArray);
        assertInstanceOf(Node.class, doc2);
        Node n2 = (Node) doc2;
        assertEquals(3, n2.edges().size());
        assertEquals("b", n2.edges().get(0).label());
        assertEquals(new Scalar.IntegerScalar(BigInteger.valueOf(1)), n2.edges().get(0).target());
        assertEquals("b", n2.edges().get(1).label());
        assertEquals(new Scalar.IntegerScalar(BigInteger.valueOf(2)), n2.edges().get(1).target());
        assertEquals("b", n2.edges().get(2).label());
        assertEquals(new Scalar.IntegerScalar(BigInteger.valueOf(3)), n2.edges().get(2).target());
    }

    @Test
    @DisplayName("Nested structure parses into nested Nodes")
    void testNestedStructure() {
        String oml = """
            name: "Ann"
            address: {
                city: "Zurich"
                postcode: "8001"
            }
            """;
        Document doc = OmlReader.read(oml);
        assertInstanceOf(Node.class, doc);
        Node root = (Node) doc;
        assertEquals(2, root.edges().size());
        assertEquals("name", root.edges().get(0).label());

        assertEquals("address", root.edges().get(1).label());
        assertInstanceOf(Node.class, root.edges().get(1).target());
        Node addr = (Node) root.edges().get(1).target();
        assertEquals(2, addr.edges().size());
        assertEquals("city", addr.edges().get(0).label());
        assertEquals(new Scalar.StringScalar("Zurich"), addr.edges().get(0).target());
    }

    @Test
    @DisplayName("Bare scalar and bare null documents parse into bare Value")
    void testBareScalarAndNullDocument() {
        Document docStr = OmlReader.read("\"hello\"");
        assertEquals(new Scalar.StringScalar("hello"), docStr);

        Document docInt = OmlReader.read("42");
        assertEquals(new Scalar.IntegerScalar(BigInteger.valueOf(42)), docInt);

        Document docNull = OmlReader.read("null");
        assertEquals(Value.NULL, docNull);
    }

    @Test
    @DisplayName("Depth limit enforcement: nesting past maxDepth throws OmlParseException")
    void testDepthLimitEnforcement() {
        Limits customLimits = new Limits(2, 100, 100);
        String validOml = "a: { b: 1 }"; // depth 2
        assertDoesNotThrow(() -> OmlReader.read(validOml, customLimits));

        String invalidOml = "a: { b: { c: 1 } }"; // depth 3 exceeds maxDepth 2
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(invalidOml, customLimits));
        assertTrue(ex.getMessage().contains("depth"), "Exception message should cite depth limit");
    }

    @Test
    @DisplayName("Node count limit enforcement: nodes past maxNodeCount throws OmlParseException")
    void testNodeCountLimitEnforcement() {
        Limits customLimits = new Limits(10, 2, 100); // max 2 nodes
        String validOml = "a: { x: 1 }"; // 2 nodes (root node + a node)
        assertDoesNotThrow(() -> OmlReader.read(validOml, customLimits));

        String invalidOml = "a: { x: 1 }; b: { y: 2 }"; // 3 nodes exceeds max 2
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(invalidOml, customLimits));
        assertTrue(ex.getMessage().contains("Node count"), "Exception message should cite node count limit");
    }

    @Test
    @DisplayName("Integer digit limit enforcement: digit count checked BEFORE BigInteger construction")
    void testIntegerDigitLimitEnforcementBeforeBigInteger() {
        Limits customLimits = new Limits(10, 100, 5); // max 5 digits
        String validOml = "val: 12345";
        assertDoesNotThrow(() -> OmlReader.read(validOml, customLimits));

        String invalidOml = "val: 123456"; // 6 digits exceeds max 5
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(invalidOml, customLimits));
        assertTrue(ex.getMessage().contains("digit"), "Exception message should cite digit limit");
    }

    @Test
    @DisplayName("Forbidden constructs (nested arrays, empty array, reserved bare label, nan bare label) produce OmlParseException")
    void testForbiddenConstructs() {
        // Empty array []
        assertThrows(OmlParseException.class, () -> OmlReader.read("a: []"));

        // Nested array [[1]]
        assertThrows(OmlParseException.class, () -> OmlReader.read("a: [[1]]"));

        // Reserved word as bare label inside node
        assertThrows(OmlParseException.class, () -> OmlReader.read("a: { null: 1 }"));

        // Reserved float nan as bare label
        assertThrows(OmlParseException.class, () -> OmlReader.read("nan: 1"));

        // Invalid string escape
        assertThrows(OmlParseException.class, () -> OmlReader.read("a: \"invalid \\z escape\""));
    }

    @Test
    @DisplayName("Malformed OML text produces OmlParseException with line and column")
    void testMalformedTextPositionReporting() {
        String badOml = "name: \"Ann\"\naddress: { city: \n";
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(badOml));
        assertTrue(ex.getLine() > 0, "Line should be positive");
        assertTrue(ex.getColumn() > 0, "Column should be positive");
    }

    @Test
    @DisplayName("Characterization 1: §4.1 example '2024-01-01T99' bare or value must fail as trailing content")
    void testCharacterization1_MalformedDateTimeTokenization() {
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read("2024-01-01T99"));
        assertTrue(ex.getMessage().contains("Trailing content"), "2024-01-01T99 should fail specifically as trailing content after DATE token 2024-01-01");
    }

    @Test
    @DisplayName("Characterization 2: §4.1 contrast '2024-01-01T10:30' parses successfully as DateTimeScalar")
    void testCharacterization2_ValidDateTimeTokenization() {
        Document doc = assertDoesNotThrow(() -> OmlReader.read("2024-01-01T10:30"));
        assertInstanceOf(Scalar.DateTimeScalar.class, doc);
        Scalar.DateTimeScalar dt = (Scalar.DateTimeScalar) doc;
        assertEquals(DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 10, 30)), dt.value());
    }

    @Test
    @DisplayName("Characterization 3: §4.6 exact pair 'null: 1' at top vs 'a: { null: 1 }' nested reserved label error")
    void testCharacterization3_ReservedLabelTopVsNested() {
        OmlParseException exTop = assertThrows(OmlParseException.class, () -> OmlReader.read("null: 1"));
        assertTrue(exTop.getMessage().contains("Trailing content"), "top-level null: 1 should fail as trailing content after null scalar");

        OmlParseException exNested = assertThrows(OmlParseException.class, () -> OmlReader.read("a: { null: 1 }"));
        assertTrue(exNested.getMessage().contains("Reserved word"), "nested null: 1 should fail as specific reserved word label error");
    }

    @Test
    @DisplayName("Characterization 4: Bare labels starting with reserved spellings ('nanoseconds: 1', 'information: 1') must succeed")
    void testCharacterization4_LabelsStartingWithReservedPrefix() {
        Document doc1 = assertDoesNotThrow(() -> OmlReader.read("nanoseconds: 1"));
        assertInstanceOf(Node.class, doc1);
        Node n1 = (Node) doc1;
        assertEquals("nanoseconds", n1.edges().get(0).label());

        Document doc2 = assertDoesNotThrow(() -> OmlReader.read("information: 1"));
        assertInstanceOf(Node.class, doc2);
        Node n2 = (Node) doc2;
        assertEquals("information", n2.edges().get(0).label());
    }

    @Test
    @DisplayName("Characterization 5: §4.3.1 trailing comma before ']' in array sugar is legal")
    void testCharacterization5_ArrayTrailingComma() {
        Document doc = assertDoesNotThrow(() -> OmlReader.read("b: [1, 2, 3,]"));
        assertInstanceOf(Node.class, doc);
        Node n = (Node) doc;
        assertEquals(3, n.edges().size());
    }
}
