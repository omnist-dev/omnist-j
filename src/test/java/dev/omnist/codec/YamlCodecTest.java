package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class YamlCodecTest {

    @Test
    @DisplayName("read parses implicit temporal types natively without schema")
    void testImplicitTemporals() {
        String yaml = "date: 2024-01-01\n" +
                      "datetime: 2024-01-01T12:30:00Z\n" +
                      "time: 12:00:00\n";
        Document doc = YamlCodec.read(yaml);
        assertTrue(doc instanceof Node);
        Node node = (Node) doc;

        Edge eDate = node.edges().stream().filter(e -> e.label().equals("date")).findFirst().orElse(null);
        assertNotNull(eDate);
        assertTrue(eDate.target() instanceof DateScalar);
        assertEquals(LocalDate.of(2024, 1, 1), ((DateScalar) eDate.target()).value());

        Edge eDt = node.edges().stream().filter(e -> e.label().equals("datetime")).findFirst().orElse(null);
        assertNotNull(eDt);
        assertTrue(eDt.target() instanceof DateTimeScalar);
        assertEquals(LocalDate.of(2024, 1, 1), ((DateTimeScalar) eDt.target()).value().dateTime().toLocalDate());

        // standalone time-of-day resolves to integer (sexagesimal 43200) per spec
        Edge eTime = node.edges().stream().filter(e -> e.label().equals("time")).findFirst().orElse(null);
        assertNotNull(eTime);
        assertTrue(eTime.target() instanceof IntegerScalar);
        assertEquals(BigInteger.valueOf(43200), ((IntegerScalar) eTime.target()).value());
    }

    @Test
    @DisplayName("read enforces Norway problem rules (boolean keys throw, y/n keys stay strings)")
    void testNorwayProblem() {
        // unquoted 'on:' key resolves to boolean true, which is a non-string key -> throws!
        assertThrows(RuntimeException.class, () -> YamlCodec.read("on: foo"));
        assertThrows(RuntimeException.class, () -> YamlCodec.read("yes: foo"));
        assertThrows(RuntimeException.class, () -> YamlCodec.read("no: foo"));

        // unquoted 'y:' and 'n:' keys must remain strings -> accepted!
        Document docY = YamlCodec.read("y: foo");
        assertNotNull(docY);

        Document docN = YamlCodec.read("n: foo");
        assertNotNull(docN);
    }

    @Test
    @DisplayName("read rejects multi-document streams")
    void testStreamRejection() {
        String stream = "a: 1\n---\nb: 2";
        assertThrows(RuntimeException.class, () -> YamlCodec.read(stream));
    }

    @Test
    @DisplayName("write serializes dates natively and reports warning on TimeValue or NEL")
    void testWriteBasic() {
        Node node = new Node(List.of(
            new Edge("date", new DateScalar(LocalDate.of(2024, 1, 1))),
            new Edge("time", new TimeScalar(TimeValue.of(LocalTime.of(12, 30))))
        ));

        WriteReport report = new WriteReport();
        String yaml = YamlCodec.write(node, false, report);

        // Date should be written natively as unquoted timestamp
        assertTrue(yaml.contains("date: 2024-01-01"));
        // Time is stringified
        assertTrue(yaml.contains("time: '12:30'") || yaml.contains("time: \"12:30\"") || yaml.contains("time: 12:30"));

        assertEquals(1, report.adjustments().size());
        assertEquals("format.temporal-stringified", report.adjustments().get(0).code());
    }

    @Test
    void testWriteNel() {
        Node doc = new Node(List.of(
            new Edge("a\u0085b", new Scalar.StringScalar("x\u0085y"))
        ));
        WriteReport rep = new WriteReport();
        String out = YamlCodec.write(doc, false, rep);
        assertTrue(out.contains("\"a\\Nb\"") || out.contains("\"a\\u0085b\""));
        assertTrue(out.contains("\"x\\Ny\"") || out.contains("\"x\\u0085b\""));
        assertEquals("format.string-line-break-char", rep.adjustments().get(0).code());

        // Verify round-trip parse reads back original U+0085 characters
        Document readBack = YamlCodec.read(out);
        assertEquals(doc, readBack);
    }

    // ==========================================================================
    // Coverage-gap-driven batch (inputs verified against real SnakeYAML behavior
    // via a scratch diagnostic before writing assertions)
    // ==========================================================================

    @Test
    @DisplayName("read: YAML 1.1-style boolean word (CustomResolver's BOOL implicit pattern)")
    void testReadYaml11StyleBoolean() {
        Document doc = YamlCodec.read("a: yes\nb: off\n", null);
        Node node = (Node) doc;
        assertEquals(new BooleanScalar(true), node.edges().get(0).target());
        assertEquals(new BooleanScalar(false), node.edges().get(1).target());
    }

    @Test
    @DisplayName("read: non-string mapping key throws")
    void testReadNonStringKeyThrows() {
        assertThrows(RuntimeException.class, () -> YamlCodec.read("123: foo\n", null));
    }

    @Test
    @DisplayName("read: object depth exceeding 200 throws")
    void testReadDepthLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 205; i++) sb.append("a:\n").append("  ".repeat(i + 1));
        sb.append("1\n");
        assertThrows(RuntimeException.class, () -> YamlCodec.read(sb.toString(), null));
    }

    @Test
    @DisplayName("read: timestamp construction falls through to SnakeYAML's default on invalid custom formats")
    void testReadTimestampFallsThroughToDefault() {
        assertThrows(RuntimeException.class, () -> YamlCodec.read("a: !!timestamp \"garbage\"\n", null));
    }

    @Test
    @DisplayName("read: timestamp with a space-separated single-digit UTC offset falls through to " +
                 "SnakeYAML's native ConstructYamlTimestamp (neither the plain-date check nor this " +
                 "codec's own parseDateTimeValue accept a bare '-5' offset)")
    void testReadTimestampFallsThroughToDefaultOnSuccessfulNativeParse() {
        // Verified empirically: this codec's parseDateTimeValue rejects
        // "2001-12-14 21:59:43.10 -5" (DateTimeParseException, unparsed trailing "-5"
        // after the space->'T' substitution), but SnakeYAML's own ConstructYamlTimestamp
        // accepts it and resolves to 2001-12-15T02:59:43.100 UTC (java.util.Date -- the
        // codec's own java.util.Date branch converts it to a DateTimeScalar with offset Z).
        Document doc = YamlCodec.read("a: !!timestamp \"2001-12-14 21:59:43.10 -5\"\n", null);
        Node node = (Node) doc;
        Object target = node.edges().get(0).target();
        assertInstanceOf(DateTimeScalar.class, target);
        DateTimeScalar dts = (DateTimeScalar) target;
        assertEquals(LocalDate.of(2001, 12, 15), dts.value().dateTime().toLocalDate());
        assertEquals(java.time.LocalTime.of(2, 59, 43, 100_000_000), dts.value().dateTime().toLocalTime());
        assertEquals(java.time.ZoneOffset.UTC, dts.value().offset());
    }

    @Test
    @DisplayName("read: datetime-with-space-separator via CustomConstructor's parseDateTimeValue path")
    void testReadDateTimeWithSpaceSeparator() {
        Document doc = YamlCodec.read("a: 2024-01-01 10:00:00\n", null);
        Node node = (Node) doc;
        assertInstanceOf(DateTimeScalar.class, node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: a 10-char date-shaped value with invalid month/day fails LocalDate.parse and cascades")
    void testReadDateShapeMatchesButInvalidValueCascades() {
        // "9999-99-99" is length-10 with dashes at positions 4/7 (matches the
        // shape check) but month 99/day 99 are invalid, so LocalDate.parse
        // throws and this falls through to the next constructor in the
        // cascade -- exercising both the shape-match true branch and the catch.
        Document doc = YamlCodec.read("a: !!timestamp \"9999-99-99\"\n", null);
        assertNotNull(doc);
    }

    @Test
    @DisplayName("read: a length-10 timestamp value whose dashes aren't at positions 4/7 fails the shape check")
    void testReadTimestampLength10WrongDashPositionsFallsThrough() {
        // "2024/01/01" is exactly 10 chars (matching the length check) but has no
        // '-' at all, so indexOf('-') == 4 is false -- a distinct branch outcome
        // from both "9999-99-99" (shape matches, value invalid) and "garbage"
        // (length doesn't even match).
        assertThrows(RuntimeException.class, () -> YamlCodec.read("a: !!timestamp \"2024/01/01\"\n", null));
    }

    @Test
    @DisplayName("read: a length-10 value with a dash at position 4 but not position 7 fails the shape check")
    void testReadTimestampLength10DashAt4NotAt7FallsThrough() {
        // "2024-01000" is 10 chars with indexOf('-') == 4 (matching that sub-check)
        // but only one dash, so lastIndexOf('-') == 4, not 7 -- the remaining
        // distinct branch outcome among the three-condition shape check.
        assertThrows(RuntimeException.class, () -> YamlCodec.read("a: !!timestamp \"2024-01000\"\n", null));
    }

    @Test
    @DisplayName("write: strict mode throws WriteException, and prepareYaml/scanYaml depth limits")
    void testWriteStrictAndDepthLimit() {
        Document tDoc = new Node(List.of(new Edge("t", new TimeScalar(
            dev.omnist.document.TimeValue.of(LocalTime.of(10, 0), java.time.ZoneOffset.UTC)))));
        assertThrows(WriteException.class, () -> YamlCodec.write(tDoc, true, null));

        Node deep = new Node(List.of());
        for (int i = 0; i < 205; i++) {
            deep = new Node(List.of(new Edge("child", deep)));
        }
        Node finalDeep = deep;
        assertThrows(WriteException.class, () -> YamlCodec.write(finalDeep));
    }

    @Test
    @DisplayName("read: an integer beyond Integer range but within Long resolves via the Long branch")
    void testReadLargeIntegerResolvesAsLong() {
        // Verified via a scratch diagnostic that SnakeYAML's default Yaml
        // produces java.lang.Long (not BigInteger) for this magnitude -- a
        // genuinely reachable branch, not a trip-wire.
        Document doc = YamlCodec.read("a: 99999999999\n", null);
        Node node = (Node) doc;
        assertEquals(new IntegerScalar(BigInteger.valueOf(99999999999L)), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: budget and depth guards (reflection)")
    void testReadBudgetAndDepthGuardsViaReflection() throws Exception {
        java.lang.reflect.Method buildNode = YamlCodec.class.getDeclaredMethod(
            "buildNode", Object.class, String.class, int.class, int[].class);
        buildNode.setAccessible(true);

        int[] overBudget = new int[]{1_000_001};
        java.lang.reflect.InvocationTargetException budgetThrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> buildNode.invoke(null, java.util.Map.of("k", "v"), "$", 0, overBudget));
        assertTrue(budgetThrown.getCause().getMessage().contains("too many nodes materialized"));

        int[] freshBudget = new int[]{0};
        java.lang.reflect.InvocationTargetException depthThrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> buildNode.invoke(null, "any value", "$", 201, freshBudget));
        assertTrue(depthThrown.getCause().getMessage().contains("nesting exceeds the maximum depth"));
    }

    @Test
    @DisplayName("write: strict mode succeeds when the document requires no adjustments")
    void testWriteStrictModeSucceedsWithNoAdjustments() {
        Document doc = new Node(List.of(new Edge("a", new IntegerScalar(BigInteger.ONE))));
        String yaml = YamlCodec.write(doc, true, null);
        assertNotNull(yaml);
    }

    @Test
    @DisplayName("Issue #84 (D-3): cross-label edge interleaving lost by grouping is reported via format.interleaving-lost at $")
    void testInterleavingLostDiagnostic() {
        Document doc = new Node(List.of(
            new Edge("m", new StringScalar("A")),
            new Edge("x", new StringScalar("X")),
            new Edge("m", new StringScalar("B"))
        ));
        WriteReport report = new WriteReport();
        YamlCodec.write(doc, false, report);

        assertEquals(1, report.adjustments().stream().filter(a -> a.code().equals("format.interleaving-lost")).count());
        WriteAdjustment adj = report.adjustments().stream()
            .filter(a -> a.code().equals("format.interleaving-lost"))
            .findFirst().orElseThrow();
        assertEquals("$", adj.path());
        assertEquals("warning", adj.severity());
    }

    @Test
    @DisplayName("Issue #84 (D-3): a contiguous repeat of the same label does NOT fire format.interleaving-lost")
    void testContiguousRepeatDoesNotFireInterleavingLost() {
        Document doc = new Node(List.of(
            new Edge("m", new StringScalar("A")),
            new Edge("m", new StringScalar("B")),
            new Edge("x", new StringScalar("X"))
        ));
        WriteReport report = new WriteReport();
        YamlCodec.write(doc, false, report);

        assertTrue(report.adjustments().stream().noneMatch(a -> a.code().equals("format.interleaving-lost")),
            "unexpected interleaving-lost for a contiguous repeat: " + report);
    }
}
