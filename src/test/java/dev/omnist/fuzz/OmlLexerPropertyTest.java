package dev.omnist.fuzz;

import dev.omnist.document.Document;
import dev.omnist.oml.OmlReader;
import net.jqwik.api.*;

/**
 * Grammar-aware property tests for OmlLexer's compound-condition branches
 * (isReservedFloatWord's boundary check, multiline-string opening-newline
 * detection) that FuzzTest's fully-random strings rarely reach by chance.
 */
public class OmlLexerPropertyTest {

    @Property(tries = 3000, shrinking = ShrinkingMode.OFF)
    void reservedWordBoundaryNeverCrashes(@ForAll("reservedWordLikeValues") String value) {
        String oml = "a: " + value + "\n";
        try {
            Document doc = OmlReader.read(oml);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Property(tries = 2000, shrinking = ShrinkingMode.OFF)
    void multilineOpeningVariationsNeverCrash(@ForAll("multilineOpenings") String opening) {
        String oml = "a: \"\"\"" + opening + "body\"\"\"\n";
        try {
            Document doc = OmlReader.read(oml);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Provide
    Arbitrary<String> reservedWordLikeValues() {
        Arbitrary<String> word = Arbitraries.of("nan", "inf", "-inf", "null", "true", "false");
        Arbitrary<String> suffix = Arbitraries.strings()
            .withChars("abcxyz019_-+.: \t".toCharArray())
            .ofMinLength(0)
            .ofMaxLength(6);
        return Combinators.combine(word, suffix).as((w, s) -> w + s);
    }

    @Provide
    Arbitrary<String> multilineOpenings() {
        return Arbitraries.of("", "\n", "\r\n", "\r", " ", "\n\n", "\t\n", "\r\n\r\n");
    }
}
