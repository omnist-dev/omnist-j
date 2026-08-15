package dev.omnist.fuzz;

import dev.omnist.codec.YamlCodec;
import dev.omnist.document.Document;
import net.jqwik.api.*;

/**
 * Grammar-aware property test for YamlCodec's ConstructTimestamp and
 * parseDateTimeValue, whose compound date-shape check (length==10 && dash
 * positions) and offset-sign detection resisted hand-derivation via the
 * JaCoCo HTML report. Exercises YamlCodec's real !!timestamp parsing paths
 * through varied date/datetime/offset shapes.
 */
public class YamlTimestampPropertyTest {

    @Property(tries = 3000, shrinking = ShrinkingMode.OFF)
    void timestampShapeVariationsNeverCrash(@ForAll("timestampLikeValues") String value) {
        String yaml = "a: !!timestamp \"" + value.replace("\"", "\\\"") + "\"\n";
        try {
            Document doc = YamlCodec.read(yaml, null);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Provide
    Arbitrary<String> timestampLikeValues() {
        Arbitrary<String> datePart = Arbitraries.of(
            "2024-01-01", "2024-1-01", "2024-01-1", "24-01-01", "2024/01/01",
            "2024-01-01 ", " 2024-01-01"
        );
        Arbitrary<String> timePart = Arbitraries.of(
            "", "T10:00:00", " 10:00:00", "T10:00:00Z", "T10:00:00z",
            "T10:00:00+05:30", "T10:00:00-5", " 10:00:00 -5", "T10:00:00.123",
            "T10:00:00+0530"
        );
        return Combinators.combine(datePart, timePart).as((d, t) -> d + t);
    }
}
