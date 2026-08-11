package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TomlCodecTest {

    @Test
    @DisplayName("read parses native temporal literals natively without schema")
    void testNativeTemporals() {
        String toml = "date = 2024-01-01\n" +
                      "datetime = 2024-01-01T12:30:00Z\n" +
                      "time = 12:30:00\n";
        Document doc = TomlCodec.read(toml);
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

        Edge eTime = node.edges().stream().filter(e -> e.label().equals("time")).findFirst().orElse(null);
        assertNotNull(eTime);
        assertTrue(eTime.target() instanceof TimeScalar);
        assertEquals(LocalTime.of(12, 30, 0), ((TimeScalar) eTime.target()).value().time());
    }

    @Test
    @DisplayName("read supports large integers up to 4300 digits and rejects larger")
    void testLargeIntegers() {
        // 50-digit integer
        String val50 = "12345678901234567890123456789012345678901234567890";
        String toml = "large = " + val50 + "\n";
        Document doc = TomlCodec.read(toml);
        assertTrue(doc instanceof Node);
        Node node = (Node) doc;

        Edge eLarge = node.edges().stream().filter(e -> e.label().equals("large")).findFirst().orElse(null);
        assertNotNull(eLarge);
        assertTrue(eLarge.target() instanceof IntegerScalar);
        assertEquals(new BigInteger(val50), ((IntegerScalar) eLarge.target()).value());

        // > 4300 digits -> throws ParseError / RuntimeException
        StringBuilder sb = new StringBuilder("too_large = ");
        for (int i = 0; i < 4305; i++) {
            sb.append("9");
        }
        sb.append("\n");
        assertThrows(RuntimeException.class, () -> TomlCodec.read(sb.toString()));
    }

    @Test
    @DisplayName("read and write enforce top-level table constraint")
    void testTopLevelTableConstraint() {
        // top-level must be table
        assertThrows(RuntimeException.class, () -> TomlCodec.read("123"));

        // writing a bare scalar should throw WriteException
        Document bare = new StringScalar("test");
        assertThrows(WriteException.class, () -> TomlCodec.write(bare));
    }

    @Test
    @DisplayName("write serializes dates natively and handles null omission")
    void testWriteNullOmissionAndDate() {
        Node node = new Node(List.of(
            new Edge("date", new DateScalar(LocalDate.of(2024, 1, 1))),
            new Edge("nullable", Value.NULL)
        ));

        WriteReport report = new WriteReport();
        String toml = TomlCodec.write(node, false, report);

        // Date should be written natively (unquoted)
        assertTrue(toml.contains("date = 2024-01-01"));
        // Null should be omitted
        assertFalse(toml.contains("nullable"));

        assertEquals(1, report.adjustments().size());
        assertEquals("null.omitted", report.adjustments().get(0).code());
    }
}
