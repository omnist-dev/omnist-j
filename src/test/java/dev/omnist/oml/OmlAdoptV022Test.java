package dev.omnist.oml;

import dev.omnist.schema.OsdParseException;
import dev.omnist.schema.OsdReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** omnist-spec v0.22.0-beta: OML-25/26/27 (leftover tokens, with or without a separator) and E-28/E-29 (code-point columns). */
class OmlAdoptV022Test {

    private static String omlErr(String text) {
        OmlParseException e = assertThrows(OmlParseException.class, () -> OmlReader.read(text));
        return e.getLine() + ":" + e.getColumn() + "|" + e.getCode();
    }

    private static String osdErr(String text) {
        OsdParseException e = assertThrows(OsdParseException.class, () -> OsdReader.read(text));
        return e.getLine() + ":" + e.getColumn() + "|" + e.getCode();
    }

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** In the table below the two characters backslash-n stand for a line feed. */
    @ParameterizedTest(name = "OML-26/27: {0}")
    @CsvSource(delimiter = '|', value = {
            // leftover token after LF
            "a: 1\\n}\\n|2:1|parse.trailing-content",
            "a: 1\\n,\\n|2:1|parse.trailing-content",
            "a: 1\\n]\\n|2:1|parse.trailing-content",
            "a: 1\\n5\\n|2:1|parse.trailing-content",
            "a: 1\\nnan\\n|2:1|parse.trailing-content",
            "a: 1\\ninf\\n|2:1|parse.trailing-content",
            "a: 1\\n{\\n|2:1|parse.trailing-content",
            "a: 1\\n[2]\\n|2:1|parse.trailing-content",
            "a: 1\\n:\\n|2:1|parse.trailing-content",
            "a: 1\\n2024-01-01\\n|2:1|parse.trailing-content",
            "a: 1\\n12:30:00\\n|2:1|parse.trailing-content",
            "a: 1\\n2024-01-01T12:30:00Z\\n|2:1|parse.trailing-content",
            "a: {b: 1}\\n}\\n|2:1|parse.trailing-content",
            // leftover token after a semicolon
            "a: 1; }\\n|1:7|parse.trailing-content",
            "a: 1; ,|1:7|parse.trailing-content",
            "a: 1;5|1:6|parse.trailing-content",
            // no separator at all
            "a: 1 }|1:6|parse.trailing-content",
            "a: 1 ,|1:6|parse.trailing-content",
            "a: 1 b: 2|1:6|parse.trailing-content",
            // a label after a separator is the next edge and reports its own error
            "a: 1\\nnull: 2|2:1|parse.reserved-word-label",
            "a: 1\\ntrue: 2|2:1|parse.reserved-word-label",
            "a: 1\\nb 2|2:3|parse.unexpected-token",
            // scalar branch (OML-25)
            "1\\n}|2:1|parse.trailing-content",
            "1 }|1:3|parse.trailing-content",
            // OML-27: inside a delimiter it stays unexpected-token
            "a: { b: 1 c: 2 }|1:11|parse.unexpected-token",
            "a: [1 2]|1:7|parse.unexpected-token",
            "a: [1, 2\\n|2:1|parse.unexpected-token",
            "a: [1\\n|2:1|parse.unexpected-token",
            "x: {a: [1, 2\\n}|2:1|parse.unexpected-token",
            "a: [1\\n2]|2:1|parse.separator-in-array",
    })
    void leftoverAfterTopLevelEdge(String text, String position, String code) {
        assertEquals(position + "|" + code, omlErr(text.replace("\\n", "\n")));
    }

    @Test
    void labelAfterSeparatorIsTheNextEdge() {
        assertDoesNotThrow(() -> OmlReader.read("a: 1\nb: 2\n"));
        assertDoesNotThrow(() -> OmlReader.read("a: 1; \"b\": 2;\n"));
        assertDoesNotThrow(() -> OmlReader.read("a: 1\n\n"));
    }

    @Test
    void omlColumnCountsCodePoints() {
        // Layout: a: "<X>"; b: "\q"  -> the bad string starts after X.
        String tail = "\"; b: \"\\q\"";
        assertEquals("1:12|parse.invalid-escape", omlErr("a: \"" + cp(0x1F600) + tail));
        assertEquals("1:12|parse.invalid-escape", omlErr("a: \"" + cp(0xE9) + tail));
        // a combining mark is its own code point
        assertEquals("1:13|parse.invalid-escape", omlErr("a: \"e" + cp(0x301) + tail));
        // two astral characters
        assertEquals("1:13|parse.invalid-escape", omlErr("a: \"" + cp(0x1F600) + cp(0x1F601) + tail));
        // a tab counts as one
        assertEquals("1:5|parse.invalid-escape", omlErr("\ta: \"\\q\""));
        // line advances at LF only; CRLF is one break
        assertEquals("2:1|parse.trailing-content", omlErr("a: 1\r\n}\r\n"));
        // an astral character on an earlier line does not shift the next line
        assertEquals("2:4|parse.invalid-escape", omlErr("a: \"" + cp(0x1F600) + "\"\nb: \"\\q\""));
        // lone surrogates (never produced by valid UTF-8) count one each
        assertEquals("1:9|parse.invalid-escape", omlErr("a: \"" + (char) 0xDE00 + "\"; \"\\q\""));
        assertEquals("1:10|parse.invalid-escape", omlErr("a: \"" + (char) 0xD83D + (char) 0x41 + "\"; \"\\q\""));
        // an astral character as the first character of a quoted label
        assertEquals("1:6|parse.invalid-escape", omlErr("\"" + cp(0x1F600) + "\": \"\\q\""));
    }

    @Test
    void osdColumnCountsCodePoints() {
        String ctl = String.valueOf((char) 1);
        String suffix = "\": string, \"b" + ctl + "\": string,\n}\nroot R\n";
        String head = "record R {\n    \"";
        assertEquals("2:18|parse.control-character", osdErr(head + cp(0x1F600) + suffix));
        assertEquals("2:18|parse.control-character", osdErr(head + cp(0xE9) + suffix));
        assertEquals("2:19|parse.control-character", osdErr(head + "e" + cp(0x301) + suffix));
        assertEquals("2:18|parse.control-character", osdErr(head + (char) 0xDE00 + suffix));
        assertEquals("2:15|parse.control-character", osdErr("record R {\n\t\"a" + suffix));
        // an astral character on the previous line does not shift the next line
        assertEquals("3:5|parse.control-character",
                osdErr("record R {\n    \"" + cp(0x1F600) + "\": string,\n    \"b" + ctl + "\": string,\n}\nroot R\n"));
    }
}
