package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.NumberScalar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** D-29: a number literal that rounds to infinity is accepted and reads as one; underflow reads as 0.0. */
class TomlFloatRangeTest {

    private static Node root(Document d) {
        return (Node) d;
    }

    @ParameterizedTest
    @ValueSource(strings = {"a = 1e999\n", "a = 1.5e400\n", "a = 1_0e9_99\n"})
    @DisplayName("D-29: a TOML float that rounds to infinity reads as +Infinity")
    void tomlOverflowReadsAsInfinity(String text) {
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), root(TomlCodec.read(text)).edges().get(0).target());
    }

    @Test
    @DisplayName("D-29: a negative overflow is -Infinity, an underflow is 0.0 (-0.0 when negative), a signed explicit infinity still reads")
    void tomlNegativeOverflowAndUnderflow() {
        assertEquals(new NumberScalar(Double.NEGATIVE_INFINITY), root(TomlCodec.read("a = -1e999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), root(TomlCodec.read("a = +1e999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(0.0), root(TomlCodec.read("a = 1e-999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(-0.0), root(TomlCodec.read("a = -1e-999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(Double.NEGATIVE_INFINITY), root(TomlCodec.read("a = -inf\n")).edges().get(0).target());
        assertEquals(new NumberScalar(1.5e300), root(TomlCodec.read("a = 1.5e300\n")).edges().get(0).target());
        assertEquals(new NumberScalar(0.0), root(TomlCodec.read("a = 0.0e999\n")).edges().get(0).target());
    }

    @Test
    @DisplayName("an overflowing float inside an inline table and a multi-line array is rewritten; a nested array line is not a table header")
    void tomlFloatsInsideTablesAndArraysAreRewritten() {
        Node t = root(TomlCodec.read("t = {a = 1e999, b = 2}\n"));
        Node inline = (Node) t.edges().get(0).target();
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), inline.edges().get(0).target());
        // the nested array on a line of its own is refused as an array of arrays, not as a syntax error
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> TomlCodec.read("a = [\n  [1e999],\n  [2]\n]\n"));
        assertEquals("document.unlabeled-element", e.getCode());
        Node plain = root(TomlCodec.read("a = [\n  1e999,\n  2\n]\n"));
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), plain.edges().get(0).target());
    }

    @Test
    @DisplayName("a very long float literal is read, not refused as a depth violation by a recursive scan")
    void aVeryLongFloatLiteralIsRead() {
        assertEquals(new NumberScalar(1.0), root(TomlCodec.read("a = 1." + "0".repeat(300_000) + "\n")).edges().get(0).target());
        assertEquals(new NumberScalar(0.0), root(TomlCodec.read("a = 0." + "0".repeat(300_000) + "1e999\n")).edges().get(0).target());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @ValueSource(strings = {"a = 1e\n", "a = 1e+\n", "a = 1.e999\n", "a = 1__0e999\n", "a = 1_e999\n", "a = 1.5_e999\n", "a = 1e9_\n", "a = 1e+_9\n"})
    @DisplayName("a token that is almost a float is left to the parser and refused as a syntax error, never read as an infinity")
    void almostFloatsAreSyntaxErrors(String text) {
        DocumentParseException e = assertThrows(DocumentParseException.class, () -> TomlCodec.read(text));
        assertEquals("parse.codec-syntax", e.getCode());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @ValueSource(strings = {"a = 01e999\n", "a = 0_1E+999\n", "a = 00.1e-999\n", "a = -01e400\n", "a = +00e999\n", "a = 01.5e999\n"})
    @DisplayName("a float with a leading zero in its integer part is invalid TOML and stays a syntax error, not an infinity or zero")
    void leadingZeroFloatsAreSyntaxErrors(String text) {
        DocumentParseException e = assertThrows(DocumentParseException.class, () -> TomlCodec.read(text));
        assertEquals("parse.codec-syntax", e.getCode());
    }

    @Test
    @DisplayName("valid neighbours of the leading-zero rule still read: 0e999, 0.5e999, -0e999, 1e999")
    void validZeroNeighboursStillRead() {
        assertEquals(new NumberScalar(0.0), root(TomlCodec.read("a = 0e999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), root(TomlCodec.read("a = 0.5e999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(-0.0), root(TomlCodec.read("a = -0e999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), root(TomlCodec.read("a = 1e999\n")).edges().get(0).target());
        assertEquals(new NumberScalar(0.0), root(TomlCodec.read("a = 0.0e-999\n")).edges().get(0).target());
    }
}
