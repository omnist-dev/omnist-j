package dev.omnist.codec;

import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.TimeValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** A time or date-time is always written with its seconds, in every format. */
class TemporalSecondsTest {


    // ---- the seconds of a written time ----

    @Test
    @DisplayName("a time or date-time is always written with its seconds")
    void temporalsAreWrittenWithSeconds() {
        assertEquals("12:00:00", TimeValue.localText(LocalTime.of(12, 0)));
        assertEquals("12:00:00.123", TimeValue.localText(LocalTime.of(12, 0, 0, 123_000_000)));
        assertEquals("12:00:00", TimeValue.of(LocalTime.of(12, 0)).format());
        assertEquals("2024-01-01T12:30:00", dev.omnist.document.DateTimeValue.localText(java.time.LocalDateTime.of(2024, 1, 1, 12, 30)));
        assertEquals("2024-01-01T12:30:00Z",
                dev.omnist.document.DateTimeValue.of(java.time.LocalDateTime.of(2024, 1, 1, 12, 30), java.time.ZoneOffset.UTC).format());
        assertEquals("t: 12:00:00", dev.omnist.oml.OmlWriter.write(new Node(List.of(
                new Edge("t", new dev.omnist.document.Scalar.TimeScalar(TimeValue.of(LocalTime.of(12, 0)))))))
                .strip());
        assertEquals("<r><t>12:00:00</t></r>", XmlCodec.write(new Node(List.of(new Edge("r", new Node(List.of(
                new Edge("t", new dev.omnist.document.Scalar.TimeScalar(TimeValue.of(LocalTime.of(12, 0))))))))))
                .replaceAll(">\\s+<", "><").strip());
    }
}
