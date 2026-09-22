package dev.omnist.schema;

import dev.omnist.codec.WriteException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Property coverage for omnist-spec OSD-14 (unwritable label) and OSD-15 (canonical
 * escaping) that no conformance vector can pin (DIV-5: a vector's schema input is OSD
 * text, so a schema with a C0-control label has no vector form at all).
 *
 * <p>Checks {@code parseSchema(toOsd(s)) == s} for arbitrary non-empty labels that
 * contain no {@code [}, {@code ]} (not legal labels at all, S-8/OSD-2) and no C0
 * control character, and that every label containing a C0 control character always
 * fails to write.
 */
class Osd14LabelPropertyTest {

    private static Schema schemaWithLabel(String label) {
        Record r = new Record("R", List.of(
                new Field(label, new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        return new Schema("R", records);
    }

    @Property(tries = 2000, shrinking = ShrinkingMode.OFF)
    void arbitraryWritableLabelRoundTripsThroughCanonicalOsd(@ForAll("writableLabels") String label) {
        Schema schema = schemaWithLabel(label);
        String written = OsdWriter.write(schema);
        assertEquals(schema, OsdReader.read(written));
    }

    @Property(tries = 2000, shrinking = ShrinkingMode.OFF)
    void arbitraryWritableLabelRoundTripsThroughCompactOsd(@ForAll("writableLabels") String label) {
        Schema schema = schemaWithLabel(label);
        String written = OsdWriter.writeCompact(schema);
        assertEquals(schema, OsdReader.read(written));
    }

    @Property(tries = 500, shrinking = ShrinkingMode.OFF)
    void anyLabelContainingAC0ControlAlwaysFailsToWrite(@ForAll("labelsWithC0") String label) {
        assertThrows(WriteException.class, () -> OsdWriter.write(schemaWithLabel(label)));
    }

    @Provide
    Arbitrary<String> writableLabels() {
        Arbitrary<Character> asciiPrintable = Arbitraries.chars()
                .range((char) 0x20, (char) 0x7E)
                .filter(c -> c != '[' && c != ']');
        Arbitrary<Character> nonAscii = Arbitraries.of('é', '€', 'ü', '中');
        Arbitrary<Character> either = Arbitraries.oneOf(asciiPrintable, nonAscii);
        return either.list().ofMinSize(1).ofMaxSize(12)
                .map(list -> {
                    StringBuilder sb = new StringBuilder();
                    for (char c : list) {
                        sb.append(c);
                    }
                    return sb.toString();
                });
    }

    @Provide
    Arbitrary<String> labelsWithC0() {
        Arbitrary<Character> c0 = Arbitraries.chars().range((char) 0x00, (char) 0x1F);
        Arbitrary<String> prefix = Arbitraries.strings().withChars("abc".toCharArray()).ofMinLength(0).ofMaxLength(3);
        Arbitrary<String> suffix = Arbitraries.strings().withChars("xyz".toCharArray()).ofMinLength(0).ofMaxLength(3);
        return Combinators.combine(prefix, c0, suffix).as((p, c, s) -> p + c + s);
    }
}
