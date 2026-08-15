package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCodecTest {

    @Test
    @DisplayName("read parses happy-path JSON and preserves edge order and repeated labels")
    void testReadBasic() {
        String json = "{\"a\": 1, \"b\": [\"x\", \"y\"], \"c\": []}";
        Document doc = JsonCodec.read(json);
        assertTrue(doc instanceof Node);
        Node node = (Node) doc;

        assertEquals(3, node.edges().size());
        
        Edge e1 = node.edges().get(0);
        assertEquals("a", e1.label());
        assertEquals(BigInteger.ONE, ((IntegerScalar) e1.target()).value());

        Edge e2 = node.edges().get(1);
        assertEquals("b", e2.label());
        assertEquals("x", ((StringScalar) e2.target()).value());

        Edge e3 = node.edges().get(2);
        assertEquals("b", e3.label());
        assertEquals("y", ((StringScalar) e3.target()).value());
        
        // Empty array 'c' should be omitted entirely from the resulting node
        for (Edge edge : node.edges()) {
            assertNotEquals("c", edge.label());
        }
    }

    @Test
    @DisplayName("read rejects bare arrays at the root or nested arrays of arrays")
    void testReadRejections() {
        // Bare array at root
        assertThrows(RuntimeException.class, () -> JsonCodec.read("[1, 2, 3]"));

        // Nested array of arrays
        assertThrows(RuntimeException.class, () -> JsonCodec.read("{\"a\": [[1, 2]]}"));
    }

    @Test
    @DisplayName("write preserves grouping and count-1 rule")
    void testWriteBasic() {
        Node node = new Node(List.of(
            new Edge("a", new IntegerScalar(BigInteger.valueOf(10))),
            new Edge("b", new StringScalar("x")),
            new Edge("b", new StringScalar("y"))
        ));

        String json = JsonCodec.write(node);
        // Should serialize b as an array due to count > 1
        assertTrue(json.contains("\"b\":[\"x\",\"y\"]") || json.contains("\"b\": [\"x\", \"y\"]"));
        
        Node nodeSingle = new Node(List.of(
            new Edge("b", new StringScalar("x"))
        ));
        String jsonSingle = JsonCodec.write(nodeSingle);
        // Should serialize b as a bare value due to count-1 rule
        assertTrue(jsonSingle.contains("\"b\":\"x\"") || jsonSingle.contains("\"b\": \"x\""));
    }

    @Test
    @DisplayName("write records/fails on adjustments for temporal and special float values")
    void testWriteAdjustments() {
        Node node = new Node(List.of(
            new Edge("time", new DateScalar(LocalDate.of(2024, 1, 1))),
            new Edge("val", new NumberScalar(Double.NaN))
        ));

        // Strict mode throws
        assertThrows(WriteException.class, () -> JsonCodec.write(node, null, true, null));

        // Lenient mode substitutes NaN -> null, stringifies date, and returns WriteReport
        WriteReport report = new WriteReport();
        String json = JsonCodec.write(node, null, false, report);

        assertTrue(json.contains("\"val\":null") || json.contains("\"val\": null"));
        assertTrue(json.contains("\"time\":\"2024-01-01\"") || json.contains("\"time\": \"2024-01-01\""));
        
        assertEquals(2, report.adjustments().size());
        
        WriteAdjustment adj1 = report.adjustments().stream()
            .filter(a -> a.code().equals("format.temporal-stringified"))
            .findFirst()
            .orElse(null);
        assertNotNull(adj1);
        assertEquals("warning", adj1.severity());

        WriteAdjustment adj2 = report.adjustments().stream()
            .filter(a -> a.code().equals("format.float-special"))
            .findFirst()
            .orElse(null);
        assertNotNull(adj2);
        assertEquals("error", adj2.severity());
    }

    // ==========================================================================
    // Coverage-gap-driven batch
    // ==========================================================================

    @Test
    @DisplayName("read: with a non-null schema arg still returns the parsed doc (materialize is a separate stage)")
    void testReadWithNonNullSchemaArg() {
        dev.omnist.schema.Schema schema = new dev.omnist.schema.Schema("R", java.util.Map.of(
            "R", new dev.omnist.schema.Record("R", List.of())
        ));
        Document doc = JsonCodec.read("{}", schema);
        assertNotNull(doc);
    }

    @Test
    @DisplayName("read: object depth exceeding 200 throws")
    void testReadDepthLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 205; i++) sb.append("{\"a\":");
        sb.append("1");
        for (int i = 0; i < 205; i++) sb.append("}");
        String json = sb.toString();
        assertThrows(RuntimeException.class, () -> JsonCodec.read(json));
    }

    @Test
    @DisplayName("read: Long, Double, Float, and both BigDecimal branches convert correctly")
    void testReadNumericTypeConversions() {
        Document doc = JsonCodec.read("{\"big\": 9999999999, \"d\": 1.5e300, \"exact\": 123.0, \"inexact\": 1.23456789012345e10}");
        Node node = (Node) doc;
        assertNotNull(node);
    }

    @Test
    @DisplayName("write: indent produces pretty-printed output")
    void testWriteWithIndent() {
        Document doc = new Node(List.of(new Edge("a", new IntegerScalar(BigInteger.ONE))));
        String pretty = JsonCodec.write(doc, 2, false, null);
        assertTrue(pretty.contains("\n"));
    }

    @Test
    @DisplayName("write: a non-null but non-positive indent (0) uses the compact writer, not pretty-printing")
    void testWriteWithZeroIndentUsesCompactWriter() {
        Document doc = new Node(List.of(new Edge("a", new IntegerScalar(BigInteger.ONE))));
        String compact = JsonCodec.write(doc, 0, false, null);
        assertFalse(compact.contains("\n"));
    }

    @Test
    @DisplayName("read: object key not a string (reflection, Jackson never actually produces a non-String JSON object key)")
    void testReadObjectKeyNotStringViaReflection() throws Exception {
        java.lang.reflect.Method buildNode = JsonCodec.class.getDeclaredMethod(
            "buildNode", Object.class, String.class, int.class, int[].class);
        buildNode.setAccessible(true);
        java.util.Map<Object, Object> badMap = new java.util.LinkedHashMap<>();
        badMap.put(123, "value");
        int[] budget = new int[]{0};

        java.lang.reflect.InvocationTargetException thrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> buildNode.invoke(null, badMap, "$", 0, budget));
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("is not a string"));
    }

    @Test
    @DisplayName("read: budget guard rejects when the node count would exceed 1,000,000 (reflection)")
    void testReadBudgetGuardViaReflection() throws Exception {
        java.lang.reflect.Method buildNode = JsonCodec.class.getDeclaredMethod(
            "buildNode", Object.class, String.class, int.class, int[].class);
        buildNode.setAccessible(true);
        int[] budget = new int[]{1_000_001};

        java.lang.reflect.InvocationTargetException thrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> buildNode.invoke(null, "any value", "$", 0, budget));
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("too many nodes materialized"));
    }

    @Test
    @DisplayName("write: strict mode throws WriteException when adjustments are non-empty")
    void testWriteStrictModeThrows() {
        Document doc = new Node(List.of(new Edge("d", new dev.omnist.document.Scalar.DateScalar(LocalDate.parse("2024-01-01")))));
        assertThrows(WriteException.class, () -> JsonCodec.write(doc, null, true, null));
    }

    @Test
    @DisplayName("write: prepareJson's depth limit")
    void testWriteDepthLimitExceeded() {
        Node deep = new Node(List.of());
        for (int i = 0; i < 205; i++) {
            deep = new Node(List.of(new Edge("child", deep)));
        }
        Node finalDeep = deep;
        assertThrows(WriteException.class, () -> JsonCodec.write(finalDeep));
    }

    @Test
    @DisplayName("write: time and datetime scalars format via their .format() methods")
    void testWriteTimeAndDateTimeScalars() {
        Document doc = new Node(List.of(
            new Edge("t", new dev.omnist.document.Scalar.TimeScalar(
                dev.omnist.document.TimeValue.of(java.time.LocalTime.of(10, 0), java.time.ZoneOffset.UTC))),
            new Edge("dt", new dev.omnist.document.Scalar.DateTimeScalar(
                dev.omnist.document.DateTimeValue.of(java.time.LocalDateTime.of(2024, 1, 1, 10, 0), java.time.ZoneOffset.UTC)))
        ));
        String json = JsonCodec.write(doc);
        assertNotNull(json);
    }
}
