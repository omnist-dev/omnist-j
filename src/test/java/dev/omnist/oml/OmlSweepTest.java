package dev.omnist.oml;

import dev.omnist.document.Limits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour added or corrected when this port adopted omnist-spec v0.19.0-beta: OML-25 and
 * omnist-spec#103 (trailing content), the array separator rules of section 4.3.1, E-23 (string
 * errors at the opening quote) and E-11 (document paths for the document.* limits).
 */
class OmlSweepTest {

    private static OmlParseException fail(String text) {
        return assertThrows(OmlParseException.class, () -> OmlReader.read(text), text);
    }

    private static void assertError(String text, String code, String path) {
        OmlParseException ex = fail(text);
        assertEquals(code, ex.getCode(), text);
        assertEquals(path, ex.getPath(), text);
    }

    // ------------------------------------------------------------------ OML-25

    @Test
    void scalarFollowedByAnythingIsTrailingContentAtTheFirstLeftoverToken() {
        assertError("nan: 1", "parse.trailing-content", "1:4");
        assertError("inf: 1", "parse.trailing-content", "1:4");
        assertError("-inf: 1", "parse.trailing-content", "1:5");
        assertError("5: 1", "parse.trailing-content", "1:2");
        assertError("true: 1", "parse.trailing-content", "1:5");
        assertError("false: 1", "parse.trailing-content", "1:6");
        assertError("null: 1", "parse.trailing-content", "1:5");
        assertError("1\n2", "parse.trailing-content", "2:1");
        assertError("2024-01-01: x", "parse.trailing-content", "1:11");
    }

    @Test
    void aTrailingCommentIsNotLeftoverContent() {
        assertEquals(OmlReader.read("1"), OmlReader.read("1 # done"));
        assertEquals(OmlReader.read("nan"), OmlReader.read("nan # done\n"));
    }

    @Test
    void aBareScalarThenABlankLineThenAnotherScalarReportsTheSecond() {
        assertError("5 # c\n6", "parse.trailing-content", "2:1");
    }

    @Test
    void aQuotedOrIdentifierLabelStillTakesTheEdgeBranch() {
        assertEquals(OmlReader.read("a: 1"), OmlReader.read("\"a\": 1"));
        // Inside a node a reserved word in label position is the specific error, not trailing content.
        assertError("a: { null: 1 }", "parse.reserved-word-label", "1:6");
    }

    // ------------------------------------------------------------------ omnist-spec#103

    @Test
    void leftoverAfterACompleteTopLevelEdgeIsTrailingContent() {
        assertError("a: 2024-01-01T99", "parse.trailing-content", "1:14");
        assertError("a: 1 b: 2", "parse.trailing-content", "1:6");
        assertError("a: [1] b: 2", "parse.trailing-content", "1:8");
        assertError("a: {b: 1} c: 2", "parse.trailing-content", "1:11");
        assertError("a: 1 }", "parse.trailing-content", "1:6");
    }

    @Test
    void aMissingSeparatorInsideBracesIsAnUnexpectedToken() {
        assertError("a: { x: 1 y: 2 }", "parse.unexpected-token", "1:11");
        assertError("a: {x: 1 y: 2}", "parse.unexpected-token", "1:10");
        assertError("a: [{x: 1 y: 2}]", "parse.unexpected-token", "1:11");
        assertError("a: { b: { c: 1 d: 2 } }", "parse.unexpected-token", "1:16");
    }

    // ------------------------------------------------------------------ arrays (section 4.3.1)

    @Test
    void anUnterminatedArrayIsAnUnexpectedTokenNotASeparatorError() {
        assertError("a: [1, 2\n", "parse.unexpected-token", "2:1");
        assertError("a: [1\n", "parse.unexpected-token", "2:1");
        assertError("a: [1, 2", "parse.unexpected-token", "1:9");
        assertError("x: {a: [1, 2\n}", "parse.unexpected-token", "2:1");
        assertError("x: {a: [1\n}", "parse.unexpected-token", "2:1");
        assertError("a: [1\n:", "parse.unexpected-token", "2:1");
    }

    @Test
    void aNewlineOrSemicolonWhereACommaIsRequiredAndAnElementFollowsIsASeparatorError() {
        assertError("a: [1\n2]", "parse.separator-in-array", "2:1");
        assertError("a: [1;2]", "parse.separator-in-array", "1:7");
        assertError("a: [1\n  {b: 1}]", "parse.separator-in-array", "2:3");
        assertError("a: [1\n[2]]", "parse.separator-in-array", "2:1");
        assertError("a: [1,\n2\n3]", "parse.separator-in-array", "3:1");
        assertError("a: [\"x\"\n\"y\"]", "parse.separator-in-array", "2:1");
    }

    @Test
    void aMissingCommaWithNoSeparatorIsAnUnexpectedToken() {
        assertError("a: [1 2]", "parse.unexpected-token", "1:7");
    }

    @Test
    void separatorsAreInsignificantAfterTheBracketAfterACommaAndBeforeTheClose() {
        assertEquals(OmlReader.read("a: [1, 2]"), OmlReader.read("a: [\n1,\n2\n]"));
        assertEquals(OmlReader.read("a: [1, 2]"), OmlReader.read("a: [1, 2,\n]"));
        assertEquals(OmlReader.read("a: [1, 2]"), OmlReader.read("a: [ # c\n1, 2 ]"));
    }

    @Test
    void emptyAndNestedArraysAreStillErrors() {
        assertError("a: []", "parse.empty-array", "1:4");
        assertError("a: [\n]", "parse.empty-array", "1:4");
        assertError("b: [[1, 2], [3, 4]]", "parse.nested-array", "1:5");
        assertError("b: [1, [2]]", "parse.nested-array", "1:8");
    }

    @Test
    void bracedElementsInAnArrayAreParsedAndDepthCounted() {
        assertEquals(OmlReader.read("a: {x: 1}\na: {x: 2}"), OmlReader.read("a: [{x: 1}, {x: 2}]"));
        Limits shallow = new Limits(2, 1000, 100);
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read("a: [{b: {c: 1}}]", shallow));
        assertEquals("document.limit.depth", ex.getCode());
        assertEquals("$", ex.getPath());
    }

    // ------------------------------------------------------------------ E-23

    @Test
    void stringBodyErrorsReportTheOpeningQuote() {
        assertError("a: \"x\\qy\"", "parse.invalid-escape", "1:4");
        assertError("a: \"x\\ud800y\"", "parse.unpaired-surrogate", "1:4");
        assertError("a: \"xy\"", "parse.control-character", "1:4");
        assertError("a: \"unterminated", "parse.unterminated-string", "1:4");
        assertError("\n\n  b: 'raw", "parse.unterminated-string", "3:6");
        assertError("a: \"\"\"\nbody\"\"\"", "parse.control-character", "1:4");
    }

    // ------------------------------------------------------------------ E-11: document.* paths

    @Test
    void limitDiagnosticsCarryDocumentPathsNotTextPositions() {
        OmlParseException depth = assertThrows(OmlParseException.class,
            () -> OmlReader.read("a: { b: { c: { d: 1 } } }", new Limits(3, 1000, 100)));
        assertEquals("document.limit.depth", depth.getCode());
        assertEquals("$", depth.getPath());

        OmlParseException nodes = assertThrows(OmlParseException.class,
            () -> OmlReader.read("a: { b: 1 }", new Limits(50, 1, 100)));
        assertEquals("document.limit.nodes", nodes.getCode());
        assertEquals("$", nodes.getPath());

        OmlParseException digits = assertThrows(OmlParseException.class,
            () -> OmlReader.read("n: 1000\n", new Limits(50, 1000, 3)));
        assertEquals("document.limit.int-digits", digits.getCode());
        assertEquals("$.n", digits.getPath());
        assertTrue(digits.getMessage().startsWith("1:4:"), "the message still says where in the text: " + digits.getMessage());
    }

    private static String digitsPath(String text) {
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(text, new Limits(50, 1000, 3)), text);
        assertEquals("document.limit.int-digits", ex.getCode(), text);
        return ex.getPath();
    }

    @Test
    void anOverLongIntegerIsReportedAtItsDocumentPath() {
        assertEquals("$", digitsPath("1000"));
        assertEquals("$", digitsPath("-1000"));
        assertEquals("$.o.n", digitsPath("o: { n: 1000 }"));
        assertEquals("$.o.p.n", digitsPath("o: { p: { n: 1000 } }"));
        // E-10: an index is present exactly when the label occurs more than once in that node,
        // counted over the whole node, so it appears on the FIRST occurrence too.
        assertEquals("$.n[0]", digitsPath("n: 1000\nn: 1"));
        assertEquals("$.n[1]", digitsPath("n: 1\nn: 1000"));
        assertEquals("$.a[1]", digitsPath("a: [1, 1000]"));
        assertEquals("$.o[1].n", digitsPath("o: { x: 1 }\no: { n: 1000 }"));
        assertEquals("$.o[0].n", digitsPath("o: { n: 1000 }\no: { x: 1 }"));
        assertEquals("$.o.n", digitsPath("o: [{ n: 1000 }]"));
        assertEquals("$.o[1].n", digitsPath("o: [{ x: 1 }, { n: 1000 }]"));
        assertEquals("$.m", digitsPath("n: 1\nm: 1000\nk: 1"));
    }

    @Test
    void onlyTheFirstOverLongIntegerIsReportedAndASyntaxErrorLaterWins() {
        assertEquals("$.a", digitsPath("a: 1000\nb: 2000"));
        OmlParseException later = assertThrows(OmlParseException.class,
            () -> OmlReader.read("a: 1000\nb: [", new Limits(50, 1000, 3)));
        assertEquals("parse.unexpected-token", later.getCode());
    }

    @Test
    void anOverLongIntegerIsNeverConvertedToABigInteger() {
        // A 100000-digit literal must be refused without paying to build the number.
        String huge = "n: " + "9".repeat(100_000) + "\n";
        long start = System.nanoTime();
        OmlParseException ex = assertThrows(OmlParseException.class, () -> OmlReader.read(huge));
        assertEquals("document.limit.int-digits", ex.getCode());
        assertEquals("$.n", ex.getPath());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000);
    }

    @Test
    void theLexerFlagsAnOverLongIntegerInsteadOfThrowing() {
        var tokens = new OmlLexer("1000", new Limits(50, 1000, 3)).tokenizeAll();
        assertEquals(OmlLexer.TokenType.INTEGER, tokens.get(0).type());
        assertNull(tokens.get(0).value());
        assertEquals("1000", tokens.get(0).text());
    }

    @Test
    void parseExceptionCarriesAnExplicitPath() {
        OmlParseException ex = new OmlParseException(2, 3, "document.limit.depth", "$.a", "too deep");
        assertEquals("$.a", ex.getPath());
        assertEquals(2, ex.getLine());
        assertEquals(3, ex.getColumn());
        assertEquals("2:3", new OmlParseException(2, 3, "parse.unexpected-token", "x").getPath());
    }

    @Test
    void aNonLabelTokenInLabelPositionInsideBracesIsAnUnexpectedToken() {
        assertError("a: {5: 1}", "parse.unexpected-token", "1:5");
        assertError("a: { \"x\": 1\n, }", "parse.unexpected-token", "2:1");
    }

    @Test
    void aLoneIdentifierOrStringIsNotAnEdgeList() {
        // One token of lookahead (OML-16): nothing follows, so it is a scalar, not an edge.
        assertError("abc", "parse.bare-word", "1:1");
        assertEquals(new dev.omnist.document.Scalar.StringScalar("abc"), OmlReader.read("\"abc\""));
        assertError("abc def", "parse.bare-word", "1:1");
        assertError("\"abc\" def", "parse.trailing-content", "1:7");
    }
}
