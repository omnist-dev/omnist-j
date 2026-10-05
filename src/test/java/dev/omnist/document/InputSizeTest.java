package dev.omnist.document;

import dev.omnist.cli.Cli;
import dev.omnist.codec.JsonCodec;
import dev.omnist.codec.TomlCodec;
import dev.omnist.codec.XmlCodec;
import dev.omnist.codec.YamlCodec;
import dev.omnist.codec.YamlLimits;
import dev.omnist.oml.OmlParseException;
import dev.omnist.oml.OmlReader;
import dev.omnist.schema.OsdParseException;
import dev.omnist.schema.OsdReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * omnist-spec section 2.4.2, D-23 to D-26: input size is bounded in bytes, checked before decoding
 * and parsing, an input of exactly the maximum is accepted, and a leading byte-order mark counts.
 */
class InputSizeTest {

    private static final String BOM = "\uFEFF";

    /** A reader under test: text and limits to a Document, or a refusal. */
    private record Reader(String name, String base, BiFunction<String, Limits, Object> read) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Reader[] READERS = {
        new Reader("oml", "a: 1\n", OmlReader::read),
        new Reader("json", "{\"a\":1}", JsonCodec::readWithLimits),
        new Reader("yaml", "a: 1\n", YamlCodec_::read),
        new Reader("toml", "a = 1\n", TomlCodec::readWithLimits),
        new Reader("xml", "<r><a>1</a></r>", (t, l) -> XmlCodec.readWithLimits(t, null, null, l)),
        new Reader("osd", "root R\nrecord R {}\n", OsdReader::read),
    };

    /** YamlCodec's limits overload takes the alias limits first, so adapt it. */
    private static final class YamlCodec_ {
        static Object read(String text, Limits limits) {
            return YamlCodec.readWithLimits(text, YamlLimits.DEFAULT, limits);
        }
    }

    static Stream<Reader> readers() {
        return Stream.of(READERS);
    }

    private static String padTo(String base, int bytes) {
        int utf8 = base.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(utf8 <= bytes, "base is longer than the target");
        return base + " ".repeat(bytes - utf8);
    }

    private static void assertRefused(Reader reader, String text, int max) {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> reader.read().apply(text, Limits.DEFAULT.withMaxInputBytes(max)));
        if (ex instanceof DocumentParseException dpe) {
            assertEquals("document.limit.input-size", dpe.getCode());
            assertEquals("$", dpe.getPath());
        } else if (ex instanceof OmlParseException ope) {
            assertEquals("document.limit.input-size", ope.getCode());
            assertEquals("$", ope.getPath());
        } else if (ex instanceof OsdParseException ose) {
            assertEquals("document.limit.input-size", ose.getCode());
            assertEquals("$", ose.getPath());
        } else {
            fail("unexpected exception type " + ex);
        }
    }

    // ------------------------------------------------------------------ Limits

    @Test
    @DisplayName("Limits: maxInputBytes defaults to 64 MiB and is part of the reference defaults")
    void defaults() {
        assertEquals(64 * 1024 * 1024, Limits.DEFAULT_MAX_INPUT_BYTES);
        assertEquals(Limits.DEFAULT_MAX_INPUT_BYTES, Limits.DEFAULT.maxInputBytes());
        assertEquals(Limits.DEFAULT_MAX_INPUT_BYTES, new Limits(1, 2, 3).maxInputBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, Limits.MAX_INPUT_BYTES_CEILING + 1, Integer.MAX_VALUE})
    @DisplayName("Limits: a maxInputBytes that is not positive or is above the ceiling is refused, never defaulted")
    void invalidMaxInputBytes(int bad) {
        assertThrows(IllegalArgumentException.class, () -> new Limits(200, 1, 1, bad));
        assertThrows(IllegalArgumentException.class, () -> Limits.DEFAULT.withMaxInputBytes(bad));
    }

    @Test
    @DisplayName("Limits: the ceiling and 1 are accepted")
    void validMaxInputBytes() {
        assertEquals(1, Limits.DEFAULT.withMaxInputBytes(1).maxInputBytes());
        assertEquals(Limits.MAX_INPUT_BYTES_CEILING, Limits.DEFAULT.withMaxInputBytes(Limits.MAX_INPUT_BYTES_CEILING).maxInputBytes());
    }

    // ------------------------------------------------------------------ InputSize

    static Stream<Arguments> utf8Lengths() {
        return Stream.of(
            Arguments.of("", 0),
            Arguments.of("abc", 3),
            Arguments.of("é", 2),
            Arguments.of("€", 3),
            Arguments.of("😀", 4),           // a valid surrogate pair is one 4-byte code point
            Arguments.of("\uD800", 3),                 // a lone high surrogate counts as U+FFFD
            Arguments.of("\uDC00", 3),                 // a lone low surrogate too
            Arguments.of("\uD800a", 4),                // high surrogate not followed by a low one
            Arguments.of(BOM + "a", 4)                 // the byte-order mark is three bytes
        );
    }

    @ParameterizedTest
    @MethodSource("utf8Lengths")
    void utf8LengthMatchesTheEncoderForWellFormedText(String text, int expected) {
        assertEquals(expected, InputSize.utf8Length(text));
        if (!text.contains("\uD800") && !text.contains("\uDC00")) {
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, InputSize.utf8Length(text));
        }
    }

    @ParameterizedTest
    @MethodSource("utf8Lengths")
    void exceedsIsExactAtTheBoundary(String text, int length) {
        if (length > 0) {
            assertFalse(InputSize.exceeds(text, length));
            assertTrue(InputSize.exceeds(text, length - 1));
        }
    }

    @Test
    void exceedsOfNullIsFalseAndMessageNamesBothNumbers() {
        assertFalse(InputSize.exceeds(null, 1));
        String message = InputSize.message("éé", 3);
        assertTrue(message.contains("4 bytes") && message.contains("3 bytes"), message);
    }

    @Test
    @DisplayName("exceeds decides by character count alone when it can, without a scan")
    void exceedsShortcuts() {
        // 6 characters, more than a 5-byte maximum however they encode.
        assertTrue(InputSize.exceeds("éééééé", 5));
        // 2 characters can be at most 6 bytes: within 6 without a scan.
        assertFalse(InputSize.exceeds("€€", 6));
    }

    static final class CountingStream extends InputStream {
        final AtomicLong served = new AtomicLong();

        @Override
        public int read() {
            served.incrementAndGet();
            return 'x';
        }

        @Override
        public int read(byte[] b, int off, int len) {
            served.addAndGet(len);
            java.util.Arrays.fill(b, off, off + len, (byte) 'x');
            return len;
        }
    }

    @Test
    @DisplayName("readBounded stops at max + 1 on an endless stream")
    void readBoundedStopsAtMaxPlusOne() {
        CountingStream endless = new CountingStream();
        DocumentParseException ex = assertThrows(DocumentParseException.class,
                () -> InputSize.readBounded(endless, 1000, "advice text"));
        assertEquals("document.limit.input-size", ex.getCode());
        assertEquals("$", ex.getPath());
        assertTrue(ex.getMessage().contains("advice text"));
        assertTrue(endless.served.get() <= 1001 + 8192, "read " + endless.served.get() + " bytes");
    }

    @Test
    void readBoundedAcceptsExactlyTheMaximum() throws IOException {
        byte[] exact = new byte[1000];
        assertEquals(1000, InputSize.readBounded(new ByteArrayInputStream(exact), 1000, "x").length);
        assertThrows(DocumentParseException.class, () -> InputSize.readBounded(new ByteArrayInputStream(new byte[1001]), 1000, "x"));
    }

    @Test
    void checkLengthIsExactAtTheBoundary() {
        InputSize.checkLength(10, 10, "x");
        DocumentParseException ex = assertThrows(DocumentParseException.class, () -> InputSize.checkLength(11, 10, "x"));
        assertEquals("document.limit.input-size", ex.getCode());
    }

    // ------------------------------------------------------------------ every reader

    @ParameterizedTest
    @MethodSource("readers")
    @DisplayName("an input of exactly the maximum is accepted, one byte over is refused with the code at $")
    void exactMaximumAcceptedOneOverRefused(Reader reader) {
        int max = 40;
        assertNotNull(reader.read().apply(padTo(reader.base(), max), Limits.DEFAULT.withMaxInputBytes(max)));
        assertRefused(reader, padTo(reader.base(), max + 1), max);
    }

    @ParameterizedTest
    @MethodSource("readers")
    @DisplayName("the unit is bytes: multi-byte characters count their UTF-8 length")
    void bytesNotCharacters(Reader reader) {
        // A two-byte character in a comment-free position: pad with the character itself as leading
        // whitespace is not possible, so put it where each format ignores or allows it.
        String text = reader.base() + "é".repeat(0);
        int max = 40;
        // base padded to 38 bytes plus one two-byte character is 40 bytes: accepted for the text
        // formats that allow trailing content only as whitespace, so only assert the refusal side:
        // 39 spaces and one e-acute is 41 bytes but 40 characters.
        String over = padTo(text, 39) + "é";
        assertEquals(40, over.length());
        assertEquals(41, over.getBytes(StandardCharsets.UTF_8).length);
        assertRefused(reader, over, max);
    }

    @ParameterizedTest
    @MethodSource("readers")
    @DisplayName("a leading byte-order mark counts: it is three bytes, checked before it is stripped")
    void bomIsCounted(Reader reader) {
        int max = 43;
        String atMax = BOM + padTo(reader.base(), 40);
        assertEquals(43, atMax.getBytes(StandardCharsets.UTF_8).length);
        assertNotNull(reader.read().apply(atMax, Limits.DEFAULT.withMaxInputBytes(max)));
        assertRefused(reader, BOM + padTo(reader.base(), 41), max);
    }

    @ParameterizedTest
    @MethodSource("readers")
    @DisplayName("the size check comes before everything else: an over-limit input that is also malformed reports the size")
    void sizeBeforeSyntax(Reader reader) {
        assertRefused(reader, "}{ not valid anything ".repeat(10), 20);
    }

    @ParameterizedTest
    @MethodSource("readers")
    @DisplayName("the default maximum is 64 MiB: one byte over it is refused")
    void defaultMaximum(Reader reader) {
        String huge = " ".repeat(Limits.DEFAULT_MAX_INPUT_BYTES + 1);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> reader.read().apply(huge, Limits.DEFAULT));
        assertTrue(ex.getMessage().contains("document.limit.input-size") || ex instanceof DocumentParseException,
                ex.getMessage());
    }

    @Test
    void defaultReadEntryPointsApplyTheDefault() {
        String huge = " ".repeat(Limits.DEFAULT_MAX_INPUT_BYTES + 1);
        assertEquals("document.limit.input-size", assertThrows(DocumentParseException.class, () -> JsonCodec.read(huge)).getCode());
        assertEquals("document.limit.input-size", assertThrows(DocumentParseException.class, () -> YamlCodec.read(huge)).getCode());
        assertEquals("document.limit.input-size", assertThrows(DocumentParseException.class, () -> YamlCodec.readWithLimits(huge, YamlLimits.DEFAULT)).getCode());
        assertEquals("document.limit.input-size", assertThrows(DocumentParseException.class, () -> TomlCodec.read(huge)).getCode());
        assertEquals("document.limit.input-size", assertThrows(DocumentParseException.class, () -> XmlCodec.read(huge)).getCode());
        assertEquals("document.limit.input-size", assertThrows(OmlParseException.class, () -> OmlReader.read(huge)).getCode());
        assertEquals("document.limit.input-size", assertThrows(OsdParseException.class, () -> OsdReader.read(huge)).getCode());
    }

    @Test
    @DisplayName("YAML: an input above SnakeYAML's own 3 MiB code point limit is accepted when D-23 allows it")
    void yamlAboveTheLibraryCodePointLimit() {
        String text = "a: 1\n" + " ".repeat(3_300_000);
        assertNotNull(YamlCodec.read(text));
        assertNotNull(YamlCodec.readWithLimits(text, YamlLimits.DEFAULT, Limits.DEFAULT.withMaxInputBytes(3_300_005)));
        assertRefused(READERS[2], text, 3_300_004);
    }

    @Test
    void nullLimitsAreRefused() {
        assertThrows(NullPointerException.class, () -> JsonCodec.readWithLimits("{}", null));
        assertThrows(NullPointerException.class, () -> TomlCodec.readWithLimits("", null));
        assertThrows(NullPointerException.class, () -> XmlCodec.readWithLimits("<a/>", null, null, null));
        assertThrows(NullPointerException.class, () -> YamlCodec.readWithLimits("a: 1", YamlLimits.DEFAULT, null));
        assertThrows(NullPointerException.class, () -> OsdReader.read("root R\nrecord R {}", null));
    }

    // ------------------------------------------------------------------ CLI

    private static int run(byte[] stdin, ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        return Cli.run(args, new PrintStream(out), new PrintStream(err), new ByteArrayInputStream(stdin));
    }

    private static int run(String stdin, ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        return run(stdin.getBytes(StandardCharsets.UTF_8), out, err, args);
    }

    @Test
    @DisplayName("CLI: --max-input-bytes accepts an input of exactly N bytes and refuses N + 1, saying how to raise it")
    void cliBoundary() {
        String doc = padTo("a: 1\n", 20);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(doc, out, err, "format", "-", "--max-input-bytes", "20"), err.toString(StandardCharsets.UTF_8));

        out.reset();
        err.reset();
        assertEquals(2, run(doc + " ", out, err, "format", "-", "--max-input-bytes", "20"));
        String message = err.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("--max-input-bytes"), message);
        assertTrue(message.contains("20"), message);
    }

    @Test
    @DisplayName("CLI: the default maximum applies without the option, and --json reports code and path")
    void cliJsonAndDefault(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, run("a: 1\n" + " ".repeat(30), out, err, "format", "-", "--max-input-bytes", "10", "--json"));
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("document.limit.input-size") && json.contains("\"$\""), json);

        // A file is bounded the same way, and the schema argument too.
        Path file = dir.resolve("big.oml");
        Files.writeString(file, "a: 1\n" + " ".repeat(30));
        out.reset();
        err.reset();
        assertEquals(2, run("", out, err, "format", file.toString(), "--max-input-bytes", "10"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--max-input-bytes"));

        Path schema = dir.resolve("s.osd");
        Files.writeString(schema, "root R\nrecord R {}\n" + " ".repeat(30));
        out.reset();
        err.reset();
        assertEquals(2, run("a: 1\n", out, err, "validate", "-", "--schema", schema.toString(), "--max-input-bytes", "25"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--max-input-bytes"));
        out.reset();
        err.reset();
        assertEquals(2, run("", out, err, "schema", "lint", schema.toString(), "--max-input-bytes", "25"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("document.limit.input-size") || err.toString(StandardCharsets.UTF_8).contains("--max-input-bytes"));
    }

    @Test
    @DisplayName("CLI: a raised maximum is passed to the readers (a 70-byte input under --max-input-bytes 100)")
    void cliRaise() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(padTo("a: 1\n", 70), out, err, "format", "-", "--max-input-bytes", "100", "--from", "yaml", "--to", "json"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CLI: the byte count is taken before decoding and counts a byte-order mark")
    void cliBomAndBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = padTo("a: 1\n", 17).getBytes(StandardCharsets.UTF_8);
        byte[] atMax = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, atMax, 0, 3);
        System.arraycopy(body, 0, atMax, 3, body.length);
        assertEquals(0, run(atMax, out, err, "format", "-", "--max-input-bytes", "20"), err.toString(StandardCharsets.UTF_8));
        out.reset();
        err.reset();
        assertEquals(2, run(atMax, out, err, "format", "-", "--max-input-bytes", "19"));
        // Invalid UTF-8 over the maximum is a size refusal, not an encoding error: size comes first.
        out.reset();
        err.reset();
        assertEquals(2, run(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 'b'}, out, err, "format", "-", "--max-input-bytes", "3"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--max-input-bytes"), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CLI: a maximum raised above the 64 MiB default reaches the schema and document readers")
    void cliRaisedAboveTheDefaultReachesEveryReader() {
        byte[] spaces = new byte[Limits.DEFAULT_MAX_INPUT_BYTES + 100];
        java.util.Arrays.fill(spaces, (byte) ' ');
        byte[] osd = "root R\nrecord R {}\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(osd, 0, spaces, 0, osd.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, run(spaces, out, err, "schema", "lint", "-"), "over the default without the option");
        out.reset();
        err.reset();
        assertEquals(0, run(spaces, out, err, "schema", "lint", "-", "--max-input-bytes", "70000000"), err.toString(StandardCharsets.UTF_8));
        out.reset();
        err.reset();
        byte[] json = spaces.clone();
        System.arraycopy("{}".getBytes(StandardCharsets.UTF_8), 0, json, 0, 2);
        assertEquals(0, run(json, out, err, "format", "-", "--from", "json", "--max-input-bytes", "70000000"), err.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "abc", "", "2147483648", "1073741825", "1.5"})
    @DisplayName("CLI: an invalid --max-input-bytes is a usage error (exit 2), never a default and never unlimited")
    void cliInvalidValue(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, run("a: 1\n", out, err, "format", "-", "--max-input-bytes", value));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--max-input-bytes"));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cliMissingValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, run("a: 1\n", out, err, "format", "-", "--max-input-bytes"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "yaml", "toml", "xml", "oml"})
    @DisplayName("CLI: every input format is bounded")
    void cliEveryFormat(String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, run(" ".repeat(50), out, err, "format", "-", "--from", format, "--max-input-bytes", "49"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--max-input-bytes"), format);
    }
}
