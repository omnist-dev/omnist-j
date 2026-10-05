package dev.omnist.oml;

import dev.omnist.document.DateTimeValue;
import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import dev.omnist.document.TimeValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * omnist-j#122: the grammar's {@code tz-offset} is {@code ("+" / "-") HH ":" MM}; it has no {@code Z}.
 * A {@code Z} after a time or date-time is therefore not part of the literal, and is reported as
 * {@code parse.trailing-content} at its own position, as the Python reference does (verified against
 * omnist master for every row here).
 */
class OmlZSuffixTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "a: 2024-01-01T10:00:00.123456Z|1:30",
        "a: 2024-01-01T10:00:00Z|1:23",
        "a: 2024-01-01T10:00Z|1:20",
        "a: 10:00:00Z|1:12",
        "a: 10:00:00.5Z|1:14",
        "a: 10:00Z|1:9",
        "a: 2024-01-01T10:00:00z|1:23",
        "a: 10:00z|1:9",
    })
    @DisplayName("a Z (or z) after a time or date-time is trailing content at the Z")
    void zIsRejected(String text, String position) {
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(text));
        assertEquals("parse.trailing-content", ex.getCode());
        assertEquals(position, ex.getPath());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "a: 2024-01-01T10:00:00+00:00",
        "a: 2024-01-01T10:00:00.123456+00:00",
        "a: 2024-01-01T10:00:00-05:30",
        "a: 10:00:00+00:00",
        "a: 10:00:00.5-05:30",
    })
    @DisplayName("a numeric offset, +00:00 included, is still accepted")
    void numericOffsetAccepted(String text) {
        assertNotNull(OmlReader.read(text));
    }

    @Test
    @DisplayName("+00:00 reads as UTC")
    void plusZeroIsUtc() {
        Node doc = (Node) OmlReader.read("a: 2024-01-01T10:00:00.123456+00:00");
        Scalar.DateTimeScalar dt = (Scalar.DateTimeScalar) doc.edges().get(0).target();
        assertEquals(ZoneOffset.UTC, dt.value().offset());
    }

    @Test
    @DisplayName("the writer spells UTC +00:00, so its output reads back (it wrote Z, which no reader accepts)")
    void writerOutputReadsBack() {
        Document doc = new Node(List.of(
            new Edge("dt", new Scalar.DateTimeScalar(DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123456000), ZoneOffset.UTC))),
            new Edge("t", new Scalar.TimeScalar(TimeValue.of(LocalTime.of(10, 0, 0), ZoneOffset.UTC)))));
        String text = OmlWriter.write(doc);
        assertFalse(text.contains("Z"), text);
        assertEquals(doc, OmlReader.read(text));
        assertEquals(doc, OmlReader.read(OmlWriter.writeCompact(doc)));
    }
}
