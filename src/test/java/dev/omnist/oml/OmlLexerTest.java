package dev.omnist.oml;

import dev.omnist.document.Limits;
import dev.omnist.oml.OmlLexer.Token;
import dev.omnist.oml.OmlLexer.TokenType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OmlLexerTest {

    private List<Token> tokenize(String source) {
        return new OmlLexer(source, Limits.DEFAULT).tokenizeAll();
    }

    @Test
    @DisplayName("comment-after-separator can run to EOF with no trailing newline, or stop at one")
    void testCommentToEofAndCommentToNewline() {
        // No trailing newline after the comment: the inner scan loop's
        // pos < source.length() check exits false via running out of input.
        List<Token> atEof = tokenize("a: 1\n# trailing comment");
        assertEquals(TokenType.EOF, atEof.get(atEof.size() - 1).type());

        // A newline right after the comment: the scan loop exits via the
        // '\n' check instead, a distinct branch outcome from running out of input.
        List<Token> withNewline = tokenize("a: 1\n# comment\nb: 2\n");
        assertTrue(withNewline.stream().anyMatch(t -> t.type() == TokenType.IDENT && t.text().equals("b")));

        // A bare \r right after the comment: the scan loop's third sub-check
        // ('\r', as opposed to the '\n' case above) is the one that stops it.
        List<Token> withCr = tokenize("a: 1\n# comment\rb: 2\n");
        assertTrue(withCr.stream().anyMatch(t -> t.type() == TokenType.IDENT && t.text().equals("b")));
    }

    @Test
    @DisplayName("isIdentContinuationChar: both lowercase and uppercase letters, immediately after a reserved float word")
    void testIdentContinuationBothCases() {
        // "nanX" and "nana": both fail the exact "nan" reserved-word match because
        // a continuation char follows, exercising isIdentContinuationChar's
        // lowercase and uppercase letter sub-checks as the deciding clause.
        List<Token> lower = tokenize("nana");
        assertEquals(TokenType.IDENT, lower.get(0).type());
        assertEquals("nana", lower.get(0).text());

        List<Token> upper = tokenize("nanX");
        assertEquals(TokenType.IDENT, upper.get(0).type());
        assertEquals("nanX", upper.get(0).text());
    }

    @Test
    @DisplayName("isIdentContinuationChar: exhaustive sweep over every boundary of its 4-way OR chain")
    void testIsIdentContinuationCharExhaustive() throws Exception {
        java.lang.reflect.Method m = OmlLexer.class.getDeclaredMethod("isIdentContinuationChar", char.class);
        m.setAccessible(true);
        OmlLexer lexer = new OmlLexer("", Limits.DEFAULT);
        for (char c = 0; c < 256; c++) {
            m.invoke(lexer, c);
        }
        assertEquals(true, m.invoke(lexer, 'a'));
        assertEquals(true, m.invoke(lexer, 'z'));
        assertEquals(true, m.invoke(lexer, 'A'));
        assertEquals(true, m.invoke(lexer, 'Z'));
        assertEquals(true, m.invoke(lexer, '0'));
        assertEquals(true, m.invoke(lexer, '9'));
        assertEquals(true, m.invoke(lexer, '_'));
        assertEquals(true, m.invoke(lexer, '-'));
        assertEquals(false, m.invoke(lexer, ' '));
        assertEquals(false, m.invoke(lexer, '!'));
    }

    @Test
    @DisplayName("multiline string opening delimiter followed by CRLF, LF, or neither")
    void testMultilineStringLineEndingVariants() {
        List<Token> crlf = tokenize("\"\"\"\r\nhello\"\"\"");
        assertEquals("hello", crlf.get(0).text());

        List<Token> lf = tokenize("\"\"\"\nhello\"\"\"");
        assertEquals("hello", lf.get(0).text());

        List<Token> neither = tokenize("\"\"\"hello\"\"\"");
        assertEquals("hello", neither.get(0).text());
    }

    @Test
    @DisplayName("multiline string opener at end of input, and a lone trailing \\r with nothing after it")
    void testMultilineStringOpenerAtEofVariants() {
        // Source ends immediately after the opening \"\"\": pos < source.length()
        // is false, distinct from every case above where more input follows.
        assertThrows(OmlParseException.class, () -> tokenize("\"\"\""));

        // \r is the very last character: pos + 1 < source.length() is false,
        // distinct from a \r that's followed by \n (consumed as CRLF) or by
        // some other char (falls through to the body loop as-is).
        assertThrows(OmlParseException.class, () -> tokenize("\"\"\"\r"));
    }

    @Test
    @DisplayName("\\uXXXX high surrogate followed by an invalid low surrogate throws")
    void testUnicodeEscapeInvalidLowSurrogate() {
        // \ud800 is a valid high surrogate, followed by \u0041 ('A'), which is not
        // in the low-surrogate range -- distinct from both a valid pair and an
        // unpaired high surrogate with no following \\u escape at all.
        assertThrows(OmlParseException.class, () -> tokenize("\"\\ud800\\u0041\""));
        // 0xE000 fails on the range's upper bound instead of its lower bound --
        // a distinct branch outcome from the 0x0041 case above.
        assertThrows(OmlParseException.class, () -> tokenize("\"\\ud800\\ue000\""));
    }

    @Test
    @DisplayName("\\uXXXX unpaired low surrogate (not preceded by a high surrogate) throws")
    void testUnicodeEscapeUnpairedLowSurrogate() {
        assertThrows(OmlParseException.class, () -> tokenize("\"\\udc00\""));
    }

    @Test
    @DisplayName("\\uXXXX above the low-surrogate range, not preceded by a high surrogate, is a plain char")
    void testUnicodeEscapeAboveLowSurrogateRangeIsPlainChar() {
        // 0xE000 fails codeUnit <= 0xDFFF (not a high surrogate either), so it
        // falls through to the plain-char append -- distinct from 0xDC00 above,
        // which is exactly the low-surrogate range's lower bound.
        List<Token> tokens = tokenize("\"\\ue000\"");
        assertEquals("\ue000", tokens.get(0).text());
    }

    @Test
    @DisplayName("\\uXXXX valid surrogate pair produces the combined code point")
    void testUnicodeEscapeValidSurrogatePair() {
        List<Token> tokens = tokenize("\"\\ud83d\\ude00\"");
        assertEquals("\uD83D\uDE00", tokens.get(0).text());
    }

    @Test
    @DisplayName("TIME literal with and without a +/-HH:MM offset")
    void testTimeLiteralWithAndWithoutOffset() {
        List<Token> plain = tokenize("10:00:00");
        assertEquals(TokenType.TIME, plain.get(0).type());

        List<Token> offset = tokenize("10:00:00+05:30");
        assertEquals(TokenType.TIME, offset.get(0).type());
    }
}
