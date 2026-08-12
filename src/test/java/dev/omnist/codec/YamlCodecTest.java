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
}
