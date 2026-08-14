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
}
