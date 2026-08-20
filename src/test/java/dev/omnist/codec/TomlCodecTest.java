package dev.omnist.codec;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TomlCodecTest {

    @Test
    @DisplayName("read parses native temporal literals natively without schema")
    void testNativeTemporals() {
        String toml = "date = 2024-01-01\n" +
                      "datetime = 2024-01-01T12:30:00Z\n" +
                      "time = 12:30:00\n";
        Document doc = TomlCodec.read(toml);
        assertTrue(doc instanceof Node);
        Node node = (Node) doc;

        Edge eDate = node.edges().stream().filter(e -> e.label().equals("date")).findFirst().orElse(null);
        assertNotNull(eDate);
        assertTrue(eDate.target() instanceof DateScalar);
        assertEquals(LocalDate.of(2024, 1, 1), ((DateScalar) eDate.target()).value());

        Edge eDt = node.edges().stream().filter(e -> e.label().equals("datetime")).findFirst().orElse(null);
        assertNotNull(eDt);
        assertTrue(eDt.target() instanceof DateTimeScalar);
        assertEquals(LocalDate.of(2024, 1, 1), ((DateTimeScalar) eDt.target()).value().dateTime().toLocalDate());

        Edge eTime = node.edges().stream().filter(e -> e.label().equals("time")).findFirst().orElse(null);
        assertNotNull(eTime);
        assertTrue(eTime.target() instanceof TimeScalar);
        assertEquals(LocalTime.of(12, 30, 0), ((TimeScalar) eTime.target()).value().time());
    }

    @Test
    @DisplayName("read supports large integers up to 4300 digits and rejects larger")
    void testLargeIntegers() {
        // 50-digit integer
        String val50 = "12345678901234567890123456789012345678901234567890";
        String toml = "large = " + val50 + "\n";
        Document doc = TomlCodec.read(toml);
        assertTrue(doc instanceof Node);
        Node node = (Node) doc;

        Edge eLarge = node.edges().stream().filter(e -> e.label().equals("large")).findFirst().orElse(null);
        assertNotNull(eLarge);
        assertTrue(eLarge.target() instanceof IntegerScalar);
        assertEquals(new BigInteger(val50), ((IntegerScalar) eLarge.target()).value());

        // > 4300 digits -> throws ParseError / RuntimeException
        StringBuilder sb = new StringBuilder("too_large = ");
        for (int i = 0; i < 4305; i++) {
            sb.append("9");
        }
        sb.append("\n");
        assertThrows(RuntimeException.class, () -> TomlCodec.read(sb.toString()));
    }

    @Test
    @DisplayName("read and write enforce top-level table constraint")
    void testTopLevelTableConstraint() {
        // top-level must be table
        assertThrows(RuntimeException.class, () -> TomlCodec.read("123"));

        // writing a bare scalar should throw WriteException
        Document bare = new StringScalar("test");
        assertThrows(WriteException.class, () -> TomlCodec.write(bare));
    }

    @Test
    @DisplayName("write serializes dates natively and handles null omission")
    void testWriteNullOmissionAndDate() {
        Node node = new Node(List.of(
            new Edge("date", new DateScalar(LocalDate.of(2024, 1, 1))),
            new Edge("nullable", Value.NULL)
        ));

        WriteReport report = new WriteReport();
        String toml = TomlCodec.write(node, false, report);

        // Date should be written natively (unquoted)
        assertTrue(toml.contains("date = 2024-01-01"));
        // Null should be omitted
        assertFalse(toml.contains("nullable"));

        assertEquals(1, report.adjustments().size());
        assertEquals("format.null-unrepresentable", report.adjustments().get(0).code());
    }

    // ==========================================================================
    // Coverage-gap-driven batch (inputs verified against real TomlCodec/tomlj
    // behavior via a scratch diagnostic before writing assertions)
    // ==========================================================================

    @Test
    @DisplayName("read: unterminated triple-quoted strings hit EOF before the closing delimiter")
    void testReadUnterminatedTripleQuotedStrings() {
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = \"\"\"unterminated\n"));
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = '''unterminated\n"));
    }

    @Test
    @DisplayName("read: triple-double-quoted string with a backslash escape preserves the escape through preprocessing")
    void testReadTripleQuotedStringWithEscape() {
        Document doc = TomlCodec.read("a = \"\"\"he said \\\"hi\\\"\"\"\"\n");
        Node node = (Node) doc;
        assertEquals(new StringScalar("he said \"hi\""), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: raw triple-single-quoted string does not process backslash escapes")
    void testReadRawTripleSingleQuotedString() {
        Document doc = TomlCodec.read("a = '''raw\\nstring'''\n");
        Node node = (Node) doc;
        assertEquals(new StringScalar("raw\\nstring"), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: integer literal exceeding 18 digits routes through the __omnist_int__ string-wrapping path")
    void testReadLongIntegerLiteral() {
        String longDigits = "1".repeat(25);
        Document doc = TomlCodec.read("a = " + longDigits + "\n");
        Node node = (Node) doc;
        assertEquals(new IntegerScalar(new BigInteger(longDigits)), node.edges().get(0).target());
    }

    @Test
    @DisplayName("read: long hex/octal/binary literals route through __omnist_int__ with the correct radix")
    void testReadLongRadixIntegerLiterals() {
        String hex = "0x" + "F".repeat(20);
        Document hexDoc = TomlCodec.read("a = " + hex + "\n");
        assertEquals(new IntegerScalar(new BigInteger("F".repeat(20), 16)),
            ((Node) hexDoc).edges().get(0).target());

        String octal = "0o" + "7".repeat(20);
        Document octDoc = TomlCodec.read("a = " + octal + "\n");
        assertEquals(new IntegerScalar(new BigInteger("7".repeat(20), 8)),
            ((Node) octDoc).edges().get(0).target());

        String binary = "0b" + "1".repeat(20);
        Document binDoc = TomlCodec.read("a = " + binary + "\n");
        assertEquals(new IntegerScalar(new BigInteger("1".repeat(20), 2)),
            ((Node) binDoc).edges().get(0).target());

        String plusDecimal = "+" + "1".repeat(20);
        Document plusDoc = TomlCodec.read("a = " + plusDecimal + "\n");
        assertEquals(new IntegerScalar(new BigInteger("1".repeat(20))),
            ((Node) plusDoc).edges().get(0).target());
    }

    @Test
    @DisplayName("read: uppercase-prefixed long radix literals (0X/0O/0B) route through the same __omnist_int__ unwrap")
    void testReadUppercasePrefixedLongRadixIntegerLiterals() {
        String hex = "0X" + "F".repeat(20);
        Document hexDoc = TomlCodec.read("a = " + hex + "\n");
        assertEquals(new IntegerScalar(new BigInteger("F".repeat(20), 16)),
            ((Node) hexDoc).edges().get(0).target());

        String octal = "0O" + "7".repeat(20);
        Document octDoc = TomlCodec.read("a = " + octal + "\n");
        assertEquals(new IntegerScalar(new BigInteger("7".repeat(20), 8)),
            ((Node) octDoc).edges().get(0).target());

        String binary = "0B" + "1".repeat(20);
        Document binDoc = TomlCodec.read("a = " + binary + "\n");
        assertEquals(new IntegerScalar(new BigInteger("1".repeat(20), 2)),
            ((Node) binDoc).edges().get(0).target());
    }

    @Test
    @DisplayName("read: invalid hex/octal/binary tokens (isHex/isOctal/isBinary false) fail parsing")
    void testReadInvalidRadixTokens() {
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = 0x1G\n"));
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = 0o18\n"));
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = 0b12\n"));
    }

    @Test
    @DisplayName("read: integer exceeding the 4300-digit safety limit throws")
    void testReadIntegerDigitLimitExceeded() {
        String tooLong = "1".repeat(4301);
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = " + tooLong + "\n"));
    }

    @Test
    @DisplayName("read: object depth exceeding 200 throws")
    void testReadDepthLimitExceeded() {
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= 205; i++) {
            if (i > 1) header.append(".");
            header.append("t").append(i);
        }
        String toml = "[" + header + "]\nx = 1\n";
        assertThrows(RuntimeException.class, () -> TomlCodec.read(toml));
    }

    @Test
    @DisplayName("check(): a non-Node document returns an empty report without throwing")
    void testCheckNonNodeDocument() {
        WriteReport rep = TomlCodec.check(new StringScalar("bare"));
        assertTrue(rep.adjustments().isEmpty());
    }

    @Test
    @DisplayName("read: a duplicate key produces a TomlParseResult with errors (not a thrown parse exception)")
    void testReadDuplicateKeyProducesResultErrors() {
        assertThrows(RuntimeException.class, () -> TomlCodec.read("a = 1\na = 2\n"));
    }

    @Test
    @DisplayName("read: an array of inline tables unwraps each TomlTable element")
    void testReadArrayOfInlineTables() {
        Document doc = TomlCodec.read("arr = [{x=1}, {x=2}]\n");
        Node node = (Node) doc;
        assertEquals(2, node.edges().size());
        Node first = (Node) node.edges().get(0).target();
        assertEquals(new IntegerScalar(BigInteger.ONE), first.edges().get(0).target());
    }

    @Test
    @DisplayName("write: strict mode throws WriteException, and write-side depth limit")
    void testWriteStrictAndDepthLimit() {
        Node nullDoc = new Node(List.of(new Edge("x", Value.NULL)));
        assertThrows(WriteException.class, () -> TomlCodec.write(nullDoc, true, null));

        Node deep = new Node(List.of());
        for (int i = 0; i < 205; i++) {
            deep = new Node(List.of(new Edge("child", deep)));
        }
        Node finalDeep = deep;
        assertThrows(WriteException.class, () -> TomlCodec.write(finalDeep));
    }

    @Test
    @DisplayName("write: strict mode succeeds when there are no adjustments to report")
    void testWriteStrictModeSucceedsWithNoAdjustments() {
        Node cleanDoc = new Node(List.of(new Edge("x", new IntegerScalar(BigInteger.ONE))));
        String toml = TomlCodec.write(cleanDoc, true, null);
        assertTrue(toml.contains("x = 1"));
    }

    @Test
    @DisplayName("prepareToml: Value.NullValue branch (reflection -- stripNulls always removes null edges before prepareToml runs through the real write() path)")
    void testPrepareTomlNullValueViaReflection() throws Exception {
        java.lang.reflect.Method prepareToml = TomlCodec.class.getDeclaredMethod(
            "prepareToml", Document.class, String.class, int.class, boolean.class);
        prepareToml.setAccessible(true);
        Object result = prepareToml.invoke(null, Value.NULL, "$", 0, false);
        assertNull(result);
    }

    @Test
    @DisplayName("write: nested sub-table, array-of-tables, quoted key, and list value round-trip")
    void testWriteNestedTableArrayOfTablesAndQuotedKey() {
        Node doc = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("simple", new StringScalar("v")),
            new Edge("my key", new StringScalar("needs quoting")),
            new Edge("sub", new Node(List.of(
                new Edge("nested", new IntegerScalar(BigInteger.ONE))
            ))),
            new Edge("items", new Node(List.of(new Edge("x", new IntegerScalar(BigInteger.ONE))))),
            new Edge("items", new Node(List.of(new Edge("x", new IntegerScalar(BigInteger.TWO)))))
        )))));
        String toml = TomlCodec.write(doc);
        assertTrue(toml.contains("\"my key\""));
        assertTrue(toml.contains("[root.sub]"));
        assertTrue(toml.contains("[[root.items]]"));

        Document readBack = TomlCodec.read(toml);
        assertNotNull(readBack);
    }

    @Test
    @DisplayName("read: array of arrays is rejected")
    void testReadArrayOfArraysRejected() {
        assertThrows(RuntimeException.class, () -> TomlCodec.read("arr = [[1, 2], [3, 4]]\n"));
    }

    @Test
    @DisplayName("write: isListOfMaps handles an empty list and a list of non-map elements (plain repeated field)")
    void testWriteListOfMapsFalseBranches() {
        // Empty list: isListOfMaps short-circuits to false without checking an element.
        Node emptyList = new Node(List.of(new Edge("root", new Node(List.of()))));
        String tomlEmpty = TomlCodec.write(emptyList);
        assertNotNull(tomlEmpty);

        // Non-empty list whose first element is not a Map: a plain repeated
        // scalar field, not an array-of-tables.
        Node plainRepeated = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("tags", new StringScalar("a")),
            new Edge("tags", new StringScalar("b"))
        )))));
        String toml = TomlCodec.write(plainRepeated);
        assertFalse(toml.contains("[["));
        Document readBack = TomlCodec.read(toml);
        assertNotNull(readBack);
    }

    @Test
    @DisplayName("write: both true and false boolean values, and a string containing a literal double-quote")
    void testWriteBooleanBothValuesAndQuoteEscaping() {
        Node doc = new Node(List.of(new Edge("root", new Node(List.of(
            new Edge("t", new BooleanScalar(true)),
            new Edge("f", new BooleanScalar(false)),
            new Edge("s", new StringScalar("has \"quotes\" inside"))
        )))));
        String toml = TomlCodec.write(doc);
        assertTrue(toml.contains("t = true"));
        assertTrue(toml.contains("f = false"));
        assertTrue(toml.contains("\\\"quotes\\\""));

        Document readBack = TomlCodec.read(toml);
        Node readBackNode = (Node) ((Node) readBack).edges().get(0).target();
        assertEquals(3, readBackNode.edges().size());
        boolean foundTrue = false, foundFalse = false, foundQuotes = false;
        for (Edge e : readBackNode.edges()) {
            if (e.target().equals(new BooleanScalar(true))) foundTrue = true;
            if (e.target().equals(new BooleanScalar(false))) foundFalse = true;
            if (e.target().equals(new StringScalar("has \"quotes\" inside"))) foundQuotes = true;
        }
        assertTrue(foundTrue && foundFalse && foundQuotes);
    }

    @Test
    @DisplayName("read: number/date/time token shapes exercise every isTokenChar disjunct")
    void testReadTokenShapesExerciseEveryTokenChar() {
        // Underscore digit separator, decimal point, lowercase 't'/'z' date-time
        // separators (RFC 3339 permits lowercase as an alternative to 'T'/'Z'),
        // and a ':'-bearing bare time, alongside the already-covered uppercase
        // 'T'/'Z' forms in testNativeTemporals.
        String toml = "big = 1_000_000\n" +
                      "pi = 3.14\n" +
                      "lowerDateTime = 2024-01-01t12:30:00z\n" +
                      "bareTime = 12:30:00\n";
        Document doc = TomlCodec.read(toml);
        assertTrue(doc instanceof Node);
    }

    @Test
    @DisplayName("Issue #43: TOML writer escapes forbidden control characters in values and labels")
    void testTomlWriterEscapesControlCharacters() {
        // Test all C0 control characters (0..31) and DEL (127)
        for (int i = 0; i <= 31; i++) {
            char c = (char) i;
            String val = "pre" + c + "post";
            String label = "k" + c + "key";
            Node doc = new Node(List.of(new Edge(label, new StringScalar(val))));
            String written = TomlCodec.write(doc);
            
            // Check that unescaped raw control characters (other than tab/newline/cr) are not present literally
            if (i != 9 && i != 10 && i != 13) {
                assertFalse(written.contains(String.valueOf(c)), "Written TOML should not contain literal control byte " + i);
            }
            Document readBack = TomlCodec.read(written);
            assertEquals(doc, readBack, "Round-trip failed for control byte " + i);
        }

        // Test DEL (127)
        char del = (char) 127;
        Node delDoc = new Node(List.of(new Edge("del_" + del, new StringScalar("val_" + del))));
        String delWritten = TomlCodec.write(delDoc);
        assertFalse(delWritten.contains(String.valueOf(del)));
        Document delReadBack = TomlCodec.read(delWritten);
        assertEquals(delDoc, delReadBack);
    }
}
