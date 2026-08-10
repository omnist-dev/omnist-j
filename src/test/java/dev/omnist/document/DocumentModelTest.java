package dev.omnist.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentModelTest {

    @Test
    @DisplayName("Bare-scalar and bare-null construct and compare correctly as Document (omnist-spec §2.2)")
    void testBareScalarAndNullDocument() {
        Document bareStringDoc = new Scalar.StringScalar("hello");
        Document bareIntDoc = new Scalar.IntegerScalar(BigInteger.valueOf(42));
        Document bareNullDoc = Value.NULL;

        assertInstanceOf(Document.class, bareStringDoc);
        assertInstanceOf(Document.class, bareIntDoc);
        assertInstanceOf(Document.class, bareNullDoc);

        assertEquals(new Scalar.StringScalar("hello"), bareStringDoc);
        assertEquals(new Scalar.IntegerScalar(BigInteger.valueOf(42)), bareIntDoc);
        assertEquals(Value.NULL, bareNullDoc);
        assertNotEquals(bareStringDoc, bareIntDoc);
    }

    @Test
    @DisplayName("Repeated labels (arrays) preserve edge insertion order exactly")
    void testRepeatedLabelsAndEdgeOrder() {
        Edge e1 = new Edge("item", new Scalar.StringScalar("pen"));
        Edge e2 = new Edge("note", new Scalar.StringScalar("rush"));
        Edge e3 = new Edge("item", new Scalar.StringScalar("pad"));

        Node node = new Node(List.of(e1, e2, e3));
        Document doc = node;

        assertEquals(3, node.edges().size());
        assertEquals("item", node.edges().get(0).label());
        assertEquals(new Scalar.StringScalar("pen"), node.edges().get(0).target());
        assertEquals("note", node.edges().get(1).label());
        assertEquals(new Scalar.StringScalar("rush"), node.edges().get(1).target());
        assertEquals("item", node.edges().get(2).label());
        assertEquals(new Scalar.StringScalar("pad"), node.edges().get(2).target());
        assertInstanceOf(Document.class, doc);
    }

    @Test
    @DisplayName("All 7 Scalar variants and Value.NULL round-trip values unchanged")
    void testScalarVariantsRoundTrip() {
        // 1. String
        Scalar.StringScalar sStr = new Scalar.StringScalar("hello omnist");
        assertEquals("hello omnist", sStr.value());
        assertEquals(ScalarKind.STRING, sStr.kind());

        // 2. Integer
        BigInteger bigInt = new BigInteger("123456789012345678901234567890");
        Scalar.IntegerScalar sInt = new Scalar.IntegerScalar(bigInt);
        assertEquals(bigInt, sInt.value());
        assertEquals(ScalarKind.INTEGER, sInt.kind());

        // 3. Number
        Scalar.NumberScalar sNum = new Scalar.NumberScalar(3.1415926535);
        assertEquals(3.1415926535, sNum.value());
        assertEquals(ScalarKind.NUMBER, sNum.kind());

        // 4. Boolean
        Scalar.BooleanScalar sBool = new Scalar.BooleanScalar(true);
        assertTrue(sBool.value());
        assertEquals(ScalarKind.BOOLEAN, sBool.kind());

        // 5. Date
        LocalDate dateVal = LocalDate.of(2026, 8, 10);
        Scalar.DateScalar sDate = new Scalar.DateScalar(dateVal);
        assertEquals(dateVal, sDate.value());
        assertEquals(ScalarKind.DATE, sDate.kind());

        // 6. Time (with and without UTC offset)
        TimeValue localTimeVal = TimeValue.of(LocalTime.of(12, 30, 45));
        Scalar.TimeScalar sTimeLocal = new Scalar.TimeScalar(localTimeVal);
        assertEquals(localTimeVal, sTimeLocal.value());
        assertNull(sTimeLocal.value().offset());
        assertEquals(ScalarKind.TIME, sTimeLocal.kind());

        TimeValue offsetTimeVal = TimeValue.of(LocalTime.of(12, 30, 45), ZoneOffset.ofHours(2));
        Scalar.TimeScalar sTimeOffset = new Scalar.TimeScalar(offsetTimeVal);
        assertEquals(offsetTimeVal, sTimeOffset.value());
        assertEquals(ZoneOffset.ofHours(2), sTimeOffset.value().offset());

        // 7. DateTime (with and without UTC offset)
        DateTimeValue localDateTimeVal = DateTimeValue.of(LocalDateTime.of(2026, 8, 10, 12, 30, 45));
        Scalar.DateTimeScalar sDateTimeLocal = new Scalar.DateTimeScalar(localDateTimeVal);
        assertEquals(localDateTimeVal, sDateTimeLocal.value());
        assertNull(sDateTimeLocal.value().offset());
        assertEquals(ScalarKind.DATE_TIME, sDateTimeLocal.kind());

        DateTimeValue offsetDateTimeVal = DateTimeValue.of(LocalDateTime.of(2026, 8, 10, 12, 30, 45), ZoneOffset.UTC);
        Scalar.DateTimeScalar sDateTimeOffset = new Scalar.DateTimeScalar(offsetDateTimeVal);
        assertEquals(offsetDateTimeVal, sDateTimeOffset.value());
        assertEquals(ZoneOffset.UTC, sDateTimeOffset.value().offset());

        // NullValue
        Target nullTarget = Value.NULL;
        Document nullDoc = Value.NULL;
        assertInstanceOf(Value.NullValue.class, nullTarget);
        assertInstanceOf(Value.NullValue.class, nullDoc);
    }

    @Test
    @DisplayName("Node equality is order-sensitive per invariant D-1")
    void testNodeEqualityOrderSensitivity() {
        Edge eA = new Edge("a", new Scalar.IntegerScalar(BigInteger.ONE));
        Edge eB = new Edge("b", new Scalar.IntegerScalar(BigInteger.TWO));

        Node node1 = new Node(List.of(eA, eB));
        Node node2 = new Node(List.of(eA, eB));
        Node nodeReordered = new Node(List.of(eB, eA));

        assertEquals(node1, node2, "Nodes with identical edges in identical order MUST be equal");
        assertNotEquals(node1, nodeReordered, "Nodes with same edges in different order MUST NOT be equal (invariant D-1)");
    }

    @Test
    @DisplayName("Limits record provides reference defaults and validates custom inputs")
    void testLimitsRecordDefaults() {
        Limits def = Limits.DEFAULT;
        assertEquals(200, def.maxDepth());
        assertEquals(1_000_000, def.maxNodeCount());
        assertEquals(4_300, def.maxIntegerDigits());

        Limits custom = new Limits(50, 10_000, 500);
        assertEquals(50, custom.maxDepth());
        assertEquals(10_000, custom.maxNodeCount());
        assertEquals(500, custom.maxIntegerDigits());

        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 100, 100));
    }
}
