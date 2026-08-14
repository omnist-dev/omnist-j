package dev.omnist;

import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.codec.*;
import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.oml.*;
import dev.omnist.document.Limits;
import dev.omnist.schema.*;
import dev.omnist.validation.*;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final coverage test suite — targets all remaining uncovered lines in
 * oml, codec, schema, algebra, and validation packages.
 * Dead code (documented UNREACHABLE) is excluded by design.
 */
class FinalCoverageTest {

    // ==========================================================================
    // SCHEMA: Field compact-constructor guards (lines 10, 13)
    // ==========================================================================

    @Test
    void field_negativeMinThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new Field("f", new Type.Scalar(dev.omnist.schema.ScalarKind.STRING, false), -1, 1));
    }

    @Test
    void field_maxLessThanMinThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new Field("f", new Type.Scalar(dev.omnist.schema.ScalarKind.STRING, false), 3, 1));
    }

    // ==========================================================================
    // SCHEMA: Schema and Record null-guard constructors (Schema.java:11, Record.java:9)
    // ==========================================================================

    @Test
    void schema_nullRecordsBecomesEmptyMap() {
        Schema s = new Schema("R", null);
        assertNotNull(s.records());
        assertTrue(s.records().isEmpty());
    }

    @Test
    void record_nullFieldsBecomesEmptyList() {
        dev.omnist.schema.Record r = new dev.omnist.schema.Record("R", null);
        assertNotNull(r.fields());
        assertTrue(r.fields().isEmpty());
    }

    // ==========================================================================
    // SCHEMA: OsdParseException getters (lines 22, 26)
    // ==========================================================================

    @Test
    void osdParseException_fiveArgGetters() {
        OsdParseException ex = new OsdParseException(5, 10, "code", "path", "msg");
        assertEquals(5, ex.getLine());
        assertEquals(10, ex.getColumn());
    }

    @Test
    void osdParseException_threeArgGetters() {
        OsdParseException ex = new OsdParseException(3, 7, "short msg");
        assertEquals(3, ex.getLine());
        assertEquals(7, ex.getColumn());
    }

    // ==========================================================================
    // SCHEMA: OsdLexer null-source guard (line 34)
    // ==========================================================================

    @Test
    void osdLexer_nullSourceBecomesEmptyString() {
        OsdLexer lexer = new OsdLexer(null);
        List<OsdLexer.Token> tokens = lexer.tokenizeAll();
        assertEquals(1, tokens.size());
        assertEquals(OsdLexer.TokenType.EOF, tokens.get(0).type());
    }

    // ==========================================================================
    // SCHEMA: OsdReader parse-error branches (lines 46, 80, 94, 124, 133, 169, 170, 198, 210, 211, 219)
    // ==========================================================================

    @Test
    void osdReader_duplicateRecordThrows() {
        // line 36: duplicate record
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": string }\nrecord R { \"y\": string }\nroot R\n"));
    }

    @Test
    void osdReader_duplicateRootThrows() {
        // line 42: duplicate root
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": string }\nroot R\nroot R\n"));
    }

    @Test
    void osdReader_rootMissingIdentThrows() {
        // line 46: root not followed by IDENT
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": string }\nroot {\n"));
    }

    @Test
    void osdReader_unexpectedTopLevelTokenThrows() {
        // line 51: not 'record' or 'root'
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("{ } root R"));
    }

    @Test
    void osdReader_reservedNameAsRecordThrows() {
        // line 87: reserved scalar keyword as record name
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record string { \"x\": string }\nroot string\n"));
    }

    @Test
    void osdReader_missingBraceAfterRecordName() {
        // line 94: expected '{' after record name
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R \"x\": string }\nroot R\n"));
    }

    @Test
    void osdReader_unquotedFieldLabel() {
        // line 103: field label not STRING
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { x: string }\nroot R\n"));
    }

    @Test
    void osdReader_duplicateFieldLabel() {
        // line 109: duplicate field label
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": string, \"x\": integer }\nroot R\n"));
    }

    @Test
    void osdReader_missingColonAfterLabel() {
        // line 124: expected ':' after label
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" string }\nroot R\n"));
    }

    @Test
    void osdReader_quotedStringInTypePosition() {
        // line 130: STRING token in type position
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": \"string\" }\nroot R\n"));
    }

    @Test
    void osdReader_missingClosingBrace() {
        // line 170: EOF before '}' in record
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": string\nroot R\n"));
    }

    @Test
    void osdReader_emptyCardinalityThrows() {
        // line 182: empty cardinality []
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" []: string }\nroot R\n"));
    }

    @Test
    void osdReader_dotInCardinalityThrows() {
        // line 185: decimal in cardinality
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" [1.5]: string }\nroot R\n"));
    }

    @Test
    void osdReader_negativeCardinalityThrows() {
        // line 188: negative bound
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" [-1]: string }\nroot R\n"));
    }

    @Test
    void osdReader_tooManyPartsInCardinalityThrows() {
        // line 198: more than 2 comma-separated parts
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" [1,2,3]: string }\nroot R\n"));
    }

    @Test
    void osdReader_maxLessThanMinThrows() {
        // line 207: max < min
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" [3,1]: string }\nroot R\n"));
    }

    @Test
    void osdReader_invalidIntegerInCardinalityThrows() {
        // line 211: NumberFormatException in cardinality
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\" [abc]: string }\nroot R\n"));
    }

    @Test
    void osdReader_nullableAnyThrows() {
        // line 151: any? is illegal
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"x\": any? }\nroot R\n"));
    }

    @Test
    void osdReader_nullableRefThrows() {
        // line 156: Ref? is illegal
        assertThrows(OsdParseException.class,
            () -> OsdReader.read("record Inner { \"a\": string }\nrecord R { \"x\": Inner? }\nroot R\n"));
    }

    // ==========================================================================
    // SCHEMA: OsdWriter compact multi-record separator (lines 129, 130)
    // ==========================================================================

    @Test
    void osdWriter_compactTwoRecordsSeparator() {
        // writeCompact: separator between 2+ records
        Schema schema = OsdReader.read(
            "record A { \"x\": string }\nrecord B { \"a\": A }\nroot B\n");
        String compact = OsdWriter.writeCompact(schema);
        assertNotNull(compact);
        assertTrue(compact.contains("record A"), "Expected record A in: " + compact);
        assertTrue(compact.contains("record B"), "Expected record B in: " + compact);
        Schema readBack = OsdReader.read(compact);
        assertEquals(schema, readBack);
    }

    // ==========================================================================
    // OML LEXER: remaining uncovered branches
    // ==========================================================================

    @Test
    void omlLexer_nullSourceViaReader() {
        // OmlLexer constructor: source != null ? source : ""
        // and OmlReader constructor: limits != null ? limits : Limits.DEFAULT
        Document doc = OmlReader.read("", null);
        assertNotNull(doc);
    }

    @Test
    void omlLexer_controlCharInMultilineStringThrows() {
        // parseMultilineString: c < 0x0020 and not \t, \n, \r -> throw
        String oml = "s: \"\"\"" + (char) 0x01 + "\"\"\"";
        assertThrows(OmlParseException.class, () -> OmlReader.read(oml));
    }

    @Test
    void omlLexer_integerDigitCountLimit() {
        // Rule 8 INTEGER: digits > limits.maxIntegerDigits() -> throw
        StringBuilder bigNum = new StringBuilder();
        for (int i = 0; i < 5000; i++) bigNum.append('1');
        String oml = "n: " + bigNum;
        assertThrows(OmlParseException.class, () -> OmlReader.read(oml));
    }

    @Test
    void omlLexer_rawStringUnterminated() {
        // parseRawString: EOF before closing ' -> throw
        assertThrows(OmlParseException.class, () -> OmlReader.read("s: 'unterminated"));
    }

    @Test
    void omlLexer_multilineStringUnterminatedThrows() {
        // parseMultilineString: EOF before closing """ -> throw
        assertThrows(OmlParseException.class, () -> OmlReader.read("s: \"\"\"unterminated"));
    }

    @Test
    void omlLexer_unexpectedCharThrows() {
        // Rule 9 fallthrough: character doesn't match any rule
        assertThrows(OmlParseException.class, () -> OmlReader.read("x: @invalid"));
    }

    @Test
    void omlLexer_escapedForwardSlash() {
        // parseDQuoteString: case '/' -> sb.append('/')
        Document doc = OmlReader.read("s: \"path\\/file\"");
        assertNotNull(doc);
    }

    @Test
    void omlLexer_escapedBackspace() {
        // parseDQuoteString: case 'b' -> sb.append('\b')
        Document doc = OmlReader.read("s: \"back\\bspace\"");
        assertNotNull(doc);
    }

    @Test
    void omlLexer_escapedFormfeed() {
        // parseDQuoteString: case 'f' -> sb.append('\f')
        Document doc = OmlReader.read("s: \"form\\ffeed\"");
        assertNotNull(doc);
    }

    @Test
    void omlLexer_invalidEscapeThrows() {
        // parseDQuoteString: default -> throw error for unknown escape
        assertThrows(OmlParseException.class, () -> OmlReader.read("s: \"\\q\""));
    }

    @Test
    void omlLexer_controlCharInDquoteStringThrows() {
        // parseDQuoteString: c < 0x20 (not escape)
        String oml = "s: \"" + (char) 0x01 + "\"";
        assertThrows(OmlParseException.class, () -> OmlReader.read(oml));
    }

    // ==========================================================================
    // OML READER: remaining branches
    // ==========================================================================

    @Test
    void omlReader_reservedWordAsLabelThrows() {
        // parseLabel: 'null', 'true', 'false' reserved words cannot be bare labels
        assertThrows(OmlParseException.class, () -> OmlReader.read("null: 1"));
        assertThrows(OmlParseException.class, () -> OmlReader.read("true: 1"));
        assertThrows(OmlParseException.class, () -> OmlReader.read("false: 1"));
    }

    @Test
    void omlReader_arrayWithBracedNodesInside() {
        // parseArrayElements: LBRACE inside array -> nested object in array
        Document doc = OmlReader.read("items: [{ x: 1 }, { x: 2 }]");
        assertNotNull(doc);
    }

    @Test
    void omlReader_arrayDepthLimit() {
        // parseArrayElements: depth > maxDepth when LBRACE inside array
        Limits limits = new Limits(1, 10, 1000);
        assertThrows(OmlParseException.class,
            () -> OmlReader.read("a: [{ x: 1 }]", limits));
    }

    @Test
    void omlReader_arrayTrailingCommaAllowed() {
        // parseArrayElements: trailing comma before ] is allowed (RBRACKET after comma)
        Document doc = OmlReader.read("a: [1, 2, 3,]");
        assertNotNull(doc);
    }

    @Test
    void omlReader_separatorAfterCommaInArrayThrows() {
        // parseArrayElements: SEPARATOR after comma inside array -> throw
        assertThrows(OmlParseException.class, () -> OmlReader.read("a: [1,\n2]"));
    }

    @Test
    void omlReader_nodeCountLimit() {
        // createNode: materializedNodeCount > maxNodeCount.
        // Node count increments only per actually-constructed Node (root + nested
        // braces), not per scalar edge -- so the limit must be tripped with nested
        // node structures: root + 2 inner nodes = 3 nodes, exceeding maxNodeCount=2.
        Limits limits = new Limits(50, 2, 5000);
        assertThrows(OmlParseException.class,
            () -> OmlReader.read("a: { x: 1 }\nb: { y: 2 }", limits));
    }

    @Test
    void omlReader_depthLimitInBraces() {
        // parseNodeEdges: depth > maxDepth when LBRACE
        Limits limits = new Limits(1, 10, 1000);
        assertThrows(OmlParseException.class,
            () -> OmlReader.read("a: { b: 1 }", limits));
    }

    @Test
    void omlReader_labelFromStringToken() {
        // parseLabel: STRING token as label (quoted label)
        Document doc = OmlReader.read("\"quoted-label\": \"value\"");
        assertNotNull(doc);
    }

    @Test
    void omlReader_invalidLabelTokenThrows() {
        // parseLabel: token is neither STRING nor IDENT
        assertThrows(OmlParseException.class, () -> OmlReader.read("42: value"));
    }

    @Test
    void omlReader_missingClosingBraceInNodeThrows() {
        // parseNodeEdges(insideBraces=true): no '}' at closing
        assertThrows(OmlParseException.class, () -> OmlReader.read("a: { b: 1"));
    }

    // ==========================================================================
    // OML WRITER: remaining scalar branches
    // ==========================================================================

    @Test
    void omlWriter_nanInfNegInf() {
        // writeScalarOrNull: NaN, +Inf, -Inf
        Node doc = new Node(List.of(
            new Edge("nan", new NumberScalar(Double.NaN)),
            new Edge("inf", new NumberScalar(Double.POSITIVE_INFINITY)),
            new Edge("ninf", new NumberScalar(Double.NEGATIVE_INFINITY))
        ));
        String written = OmlWriter.write(doc);
        assertTrue(written.contains("nan"), "Expected nan in: " + written);
        assertTrue(written.contains("inf"), "Expected inf in: " + written);
        assertTrue(written.contains("-inf"), "Expected -inf in: " + written);
    }

    @Test
    void omlWriter_boolScalar() {
        // writeScalarOrNull: BooleanScalar
        Node doc = new Node(List.of(
            new Edge("t", new BooleanScalar(true)),
            new Edge("f", new BooleanScalar(false))
        ));
        String written = OmlWriter.write(doc);
        assertTrue(written.contains("true"));
        assertTrue(written.contains("false"));
    }

    @Test
    void omlWriter_dateScalar() {
        // writeScalarOrNull: DateScalar
        Node doc = new Node(List.of(
            new Edge("d", new DateScalar(LocalDate.of(2024, 6, 15)))
        ));
        String written = OmlWriter.write(doc);
        assertTrue(written.contains("2024-06-15"), "Expected date in: " + written);
    }

    @Test
    void omlWriter_timeNoOffset() {
        // writeScalarOrNull: TimeScalar with null offset
        TimeValue tv = TimeValue.of(LocalTime.of(9, 30));
        Node doc = new Node(List.of(new Edge("t", new TimeScalar(tv))));
        String written = OmlWriter.write(doc);
        assertTrue(written.contains("09:30"), "Expected time in: " + written);
    }

    @Test
    void omlWriter_dateTimeNoOffset() {
        // writeScalarOrNull: DateTimeScalar with null offset
        DateTimeValue dtv = DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 12, 0));
        Node doc = new Node(List.of(new Edge("dt", new DateTimeScalar(dtv))));
        String written = OmlWriter.write(doc);
        assertTrue(written.contains("2024-01-01"), "Expected datetime in: " + written);
    }

    @Test
    void omlWriter_nodeIndented_deeplyNestedNode() {
        // writeNodeIndented: target is Node with children (non-empty inner branch)
        Node inner = new Node(List.of(
            new Edge("x", new IntegerScalar(BigInteger.ONE))
        ));
        Node outer = new Node(List.of(
            new Edge("obj", new Node(List.of(
                new Edge("inner", inner)
            )))
        ));
        String written = OmlWriter.write(outer);
        assertNotNull(written);
        assertTrue(written.contains("inner"), "Expected 'inner' in: " + written);
    }

    @Test
    void omlWriter_labelIsReservedWord() {
        // writeLabel: reserved words (null, true, false, nan, inf) -> must be quoted
        Node doc = new Node(List.of(
            new Edge("null", new StringScalar("val")),
            new Edge("true", new StringScalar("val")),
            new Edge("nan", new StringScalar("val"))
        ));
        String written = OmlWriter.write(doc);
        // Reserved labels should appear quoted in output
        assertTrue(written.contains("\"null\"") || written.contains("\"true\"") || written.contains("\"nan\""),
            "Expected quoted reserved label in: " + written);
    }

    // ==========================================================================
    // CODEC: WriteReport.toString() empty branch (line 24)
    // ==========================================================================

    @Test
    void writeReport_emptyToString() {
        // toString() with empty adjustments -> "no adjustments"
        WriteReport rep = new WriteReport();
        assertEquals("no adjustments", rep.toString());
    }

    // ==========================================================================
    // CODEC: YAML remaining branches
    // ==========================================================================

    @Test
    void yamlCodec_multiDocumentThrows() {
        // read: it.hasNext() after first doc -> throw
        String yaml = "key: val\n---\nkey2: val2\n";
        assertThrows(RuntimeException.class, () -> YamlCodec.read(yaml));
    }

    @Test
    void yamlCodec_nullInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> YamlCodec.read(null));
    }

    @Test
    void yamlCodec_tooLargeInputThrows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_000_001; i++) sb.append('a');
        assertThrows(RuntimeException.class, () -> YamlCodec.read(sb.toString()));
    }

    @Test
    void yamlCodec_dateTimeWithSpaceSeparator() {
        // parseDateTimeValue: replaces space with T
        String yaml = "dt: 2024-01-01 12:00:00\n";
        Document doc = YamlCodec.read(yaml);
        assertNotNull(doc);
    }

    @Test
    void yamlCodec_dateTimeZSuffix() {
        // parseDateTimeValue: endsWith("Z") or endsWith("z")
        String yaml = "dt: 2024-01-01T12:00:00Z\n";
        Document doc = YamlCodec.read(yaml);
        assertNotNull(doc);
    }

    @Test
    void yamlCodec_nelCharInLabelWarning() {
        // scanYaml: label contains U+0085 (NEL) -> format.string-line-break-char
        Node doc = new Node(List.of(
            new Edge("key\u0085", new StringScalar("val"))
        ));
        WriteReport rep = new WriteReport();
        YamlCodec.write(doc, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.string-line-break-char")),
            "Expected NEL warning in label");
    }

    @Test
    void yamlCodec_nelCharInStringValueWarning() {
        // scanYaml: StringScalar value contains U+0085 -> format.string-line-break-char
        Node doc = new Node(List.of(
            new Edge("s", new StringScalar("val\u0085ue"))
        ));
        WriteReport rep = new WriteReport();
        YamlCodec.write(doc, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.string-line-break-char")),
            "Expected NEL string value warning");
    }

    @Test
    void yamlCodec_readWithSchemaReturnsDoc() {
        // read(text, schema): schema != null path
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n");
        String yaml = "x: hello\n";
        Document doc = YamlCodec.read(yaml, schema);
        assertNotNull(doc);
    }

    @Test
    void yamlCodec_dateTimeInPrepareYaml() {
        // prepareYaml: DateTimeScalar -> DateTimeValue
        DateTimeValue dtv = DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 12, 0));
        Node doc = new Node(List.of(new Edge("dt", new DateTimeScalar(dtv))));
        String written = YamlCodec.write(doc);
        assertNotNull(written);
    }

    @Test
    void yamlCodec_repeatedFieldGrouped() {
        // grouped: counts.get(label) > 1 -> list branch in YAML
        Node doc = new Node(List.of(
            new Edge("tag", new StringScalar("a")),
            new Edge("tag", new StringScalar("b"))
        ));
        String written = YamlCodec.write(doc);
        assertNotNull(written);
        assertTrue(written.contains("tag"), "Expected tag in: " + written);
    }

    @Test
    void yamlCodec_buildNodeBareArrayThrows() {
        // buildNode: val instanceof List -> bare array error
        String yaml = "- item1\n- item2\n";
        assertThrows(RuntimeException.class, () -> YamlCodec.read(yaml));
    }

    @Test
    void yamlCodec_buildNodeArrayOfArraysThrows() {
        // buildNode: item instanceof List inside list -> nested array error
        String yaml = "items:\n  - [1, 2]\n  - [3, 4]\n";
        assertThrows(RuntimeException.class, () -> YamlCodec.read(yaml));
    }

    // ==========================================================================
    // CODEC: TOML remaining branches
    // ==========================================================================

    @Test
    void tomlCodec_nullInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> TomlCodec.read(null));
    }

    @Test
    void tomlCodec_tooLargeInputThrows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_000_001; i++) sb.append('a');
        assertThrows(RuntimeException.class, () -> TomlCodec.read(sb.toString()));
    }

    @Test
    void tomlCodec_preprocessTripleQuotedLiteralStrings() {
        // preprocessToml: '''...''' literal multi-line string path
        String toml = "s = '''hello\nworld'''\n";
        Document doc = TomlCodec.read(toml);
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_localTimeRead() {
        // toScalar: LocalTime branch
        String toml = "t = 12:00:00\n";
        Document doc = TomlCodec.read(toml);
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_localDateTimeRead() {
        // toScalar: LocalDateTime branch
        String toml = "dt = 2024-01-01T12:00:00\n";
        Document doc = TomlCodec.read(toml);
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_bigIntegerReadLargeDecimal() {
        // toScalar: __omnist_int__ with decimal large integer (> 18 digits)
        String toml = "n = 12345678901234567890\n";
        Document doc = TomlCodec.read(toml);
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_bigIntegerReadLargeHex() {
        // toScalar: __omnist_int__ with 0x prefix (> 18 hex digits)
        // countDigits skips "0x", counts hex digits; need > 18 -> use 19 hex digits
        // 0x + 19 hex chars = 0x1234567890ABCDEF123 (19 hex digits after 0x)
        String toml = "n = 0x1234567890ABCDEF123\n";
        Document doc = TomlCodec.read(toml);
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_bigIntegerReadLargeOctal() {
        // toScalar: __omnist_int__ with 0o prefix
        String toml = "n = 0o1234567012345670123456\n";
        Document doc = TomlCodec.read(toml);
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_bigIntegerReadLargeBinary() {
        // toScalar: __omnist_int__ with 0b prefix (> 18 digits = 19 binary digits)
        StringBuilder bin = new StringBuilder("n = 0b");
        for (int i = 0; i < 65; i++) bin.append('1');
        bin.append('\n');
        Document doc = TomlCodec.read(bin.toString());
        assertNotNull(doc);
    }

    @Test
    void tomlCodec_dateTimeWithOffsetWrite() {
        // prepareToml: DateTimeScalar with offset -> OffsetDateTime
        DateTimeValue dtv = DateTimeValue.of(
            LocalDateTime.of(2024, 1, 1, 12, 0), ZoneOffset.of("+05:30"));
        Node doc = new Node(List.of(new Edge("dt", new DateTimeScalar(dtv))));
        String written = TomlCodec.write(doc);
        assertNotNull(written);
        assertTrue(written.contains("dt"), "Expected dt in: " + written);
    }

    @Test
    void tomlCodec_dateTimeNoOffsetWrite() {
        // prepareToml: DateTimeScalar without offset -> LocalDateTime
        DateTimeValue dtv = DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 12, 0));
        Node doc = new Node(List.of(new Edge("dt", new DateTimeScalar(dtv))));
        String written = TomlCodec.write(doc);
        assertNotNull(written);
    }

    @Test
    void tomlCodec_timeWrite() {
        // prepareToml: TimeScalar -> LocalTime
        TimeValue tv = TimeValue.of(LocalTime.of(12, 0));
        Node doc = new Node(List.of(new Edge("t", new TimeScalar(tv))));
        String written = TomlCodec.write(doc);
        assertNotNull(written);
        assertTrue(written.contains("t = "), "Expected t in: " + written);
    }

    @Test
    void tomlCodec_dateWrite() {
        // prepareToml: DateScalar -> LocalDate
        Node doc = new Node(List.of(
            new Edge("d", new DateScalar(LocalDate.of(2024, 6, 15)))
        ));
        String written = TomlCodec.write(doc);
        assertNotNull(written);
        assertTrue(written.contains("2024-06-15"), "Expected date in: " + written);
    }

    @Test
    void tomlCodec_boolAndNumberWrite() {
        // formatVal: Boolean -> "true"/"false", numbers
        Node doc = new Node(List.of(
            new Edge("b", new BooleanScalar(true)),
            new Edge("n", new IntegerScalar(BigInteger.valueOf(42))),
            new Edge("f", new NumberScalar(3.14))
        ));
        String written = TomlCodec.write(doc);
        assertTrue(written.contains("true"));
    }

    @Test
    void tomlCodec_listOfScalarsWrite() {
        // formatVal: List branch (repeated scalar field -> TOML array)
        Node doc = new Node(List.of(
            new Edge("tag", new StringScalar("a")),
            new Edge("tag", new StringScalar("b"))
        ));
        String written = TomlCodec.write(doc);
        assertNotNull(written);
        assertTrue(written.contains("tag"));
    }

    @Test
    void tomlCodec_stripNullsWarning() {
        // stripNulls: NullValue child -> format.null-unrepresentable warning
        Node doc = new Node(List.of(
            new Edge("a", Value.NULL),
            new Edge("b", new StringScalar("val"))
        ));
        WriteReport rep = new WriteReport();
        String written = TomlCodec.write(doc, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.null-unrepresentable")),
            "Expected null-unrepresentable warning");
        assertTrue(written.contains("b"));
    }

    @Test
    void tomlCodec_escapedBellFormfeed() {
        // escapeString: \b, \f cases
        Node doc = new Node(List.of(
            new Edge("s", new StringScalar("bell\bformfeed\f"))
        ));
        String written = TomlCodec.write(doc);
        assertTrue(written.contains("\\b"), "Expected \\b in: " + written);
        assertTrue(written.contains("\\f"), "Expected \\f in: " + written);
    }

    @Test
    void tomlCodec_writeScalarRootThrows() {
        // write: !(node instanceof Node) -> WriteException
        Value scalar = new StringScalar("not a node");
        assertThrows(WriteException.class, () -> TomlCodec.write(scalar));
    }

    @Test
    void tomlCodec_repeatedFieldGrouped() {
        // grouped: counts.get(label) > 1 -> list branch in TOML
        Node doc = new Node(List.of(
            new Edge("x", new IntegerScalar(BigInteger.ONE)),
            new Edge("x", new IntegerScalar(BigInteger.TWO))
        ));
        String written = TomlCodec.write(doc);
        assertNotNull(written);
    }

    // ==========================================================================
    // CODEC: JSON remaining branches
    // ==========================================================================

    @Test
    void jsonCodec_readWithSchema() {
        // read(text, schema): schema != null path
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Document doc = JsonCodec.read("{\"x\": \"hello\"}", schema);
        assertNotNull(doc);
    }

    @Test
    void jsonCodec_bareArrayThrows() {
        // buildNode: val instanceof List -> bare array error
        assertThrows(RuntimeException.class, () -> JsonCodec.read("[1, 2, 3]"));
    }

    @Test
    void jsonCodec_arrayOfArraysThrows() {
        // buildNode: item instanceof List -> nested array error
        assertThrows(RuntimeException.class,
            () -> JsonCodec.read("{\"x\": [[1,2],[3,4]]}"));
    }

    @Test
    void jsonCodec_bigDecimalFractionalRead() {
        // toScalar: BigDecimal with fraction -> NumberScalar
        String json = "{\"n\": 123456789012345678901234567890.5}";
        Document doc = JsonCodec.read(json);
        assertNotNull(doc);
    }

    @Test
    void jsonCodec_infWrittenAsNull() {
        // scanJson: Infinity -> format.float-special warning; prepareJson: returns null
        Node doc = new Node(List.of(
            new Edge("v", new NumberScalar(Double.POSITIVE_INFINITY))
        ));
        WriteReport rep = new WriteReport();
        JsonCodec.write(doc, null, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.float-special")),
            "Expected float-special warning");
    }

    @Test
    void jsonCodec_integerValueInPrepare() {
        // prepareJson: IntegerScalar -> BigInteger
        Node doc = new Node(List.of(
            new Edge("n", new IntegerScalar(BigInteger.valueOf(12345)))
        ));
        String json = JsonCodec.write(doc);
        assertTrue(json.contains("12345"), "Expected integer in: " + json);
    }

    @Test
    void jsonCodec_repeatedFieldGrouped() {
        // grouped: counts.get(label) > 1 -> list branch
        Node doc = new Node(List.of(
            new Edge("tag", new StringScalar("a")),
            new Edge("tag", new StringScalar("b"))
        ));
        String json = JsonCodec.write(doc);
        assertNotNull(json);
        assertTrue(json.contains("tag"), "Expected tag in: " + json);
    }

    // ==========================================================================
    // CODEC: XML remaining branches
    // ==========================================================================

    @Test
    void xmlCodec_nullInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> XmlCodec.read(null));
    }

    @Test
    void xmlCodec_tooLargeInputThrows() {
        StringBuilder sb = new StringBuilder("<r>");
        for (int i = 0; i < 2_000_001; i++) sb.append('a');
        sb.append("</r>");
        assertThrows(RuntimeException.class, () -> XmlCodec.read(sb.toString()));
    }

    @Test
    void xmlCodec_mixedContentThrows() {
        // xmlToNode: mixed content (text alongside child elements)
        String xml = "<root>text<child>val</child></root>";
        assertThrows(RuntimeException.class, () -> XmlCodec.read(xml));
    }

    @Test
    void xmlCodec_schemaBoolean() {
        // xmlPretype: scalar BOOLEAN -> true/false string conversion
        Schema schema = OsdReader.read("record R { \"flag\": boolean }\nroot R\n");
        String xml = "<root><flag>true</flag></root>";
        Document doc = XmlCodec.read(xml, schema);
        assertNotNull(doc);
    }

    @Test
    void xmlCodec_schemaInteger() {
        // xmlPretype: scalar INTEGER -> BigInteger conversion
        Schema schema = OsdReader.read("record R { \"n\": integer }\nroot R\n");
        String xml = "<root><n>42</n></root>";
        Document doc = XmlCodec.read(xml, schema);
        assertNotNull(doc);
    }

    @Test
    void xmlCodec_schemaNumber() {
        // xmlPretype: scalar NUMBER -> Double conversion
        Schema schema = OsdReader.read("record R { \"v\": number }\nroot R\n");
        String xml = "<root><v>3.14</v></root>";
        Document doc = XmlCodec.read(xml, schema);
        assertNotNull(doc);
    }

    @Test
    void xmlCodec_emptyNodeWrittenAsSelfClosing() {
        // writeNode: node.edges().isEmpty() -> " />" branch
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of()))
        ));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("/>"), "Expected self-closing tag in: " + xml);
    }

    @Test
    void xmlCodec_emptyStringValueWrittenAsSelfClosing() {
        // writeNode: text.isEmpty() -> " />" branch
        Node doc = new Node(List.of(
            new Edge("root", new StringScalar(""))
        ));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("/>"), "Expected self-closing for empty string in: " + xml);
    }

    @Test
    void xmlCodec_xmlNameSanitization() {
        // xmlName: name with leading digit -> sanitized with underscore prefix
        Node doc = new Node(List.of(
            new Edge("123bad", new StringScalar("val"))
        ));
        // Should not throw; name gets sanitized
        String xml = XmlCodec.write(doc);
        assertNotNull(xml);
    }

    @Test
    void xmlCodec_nullValueWrittenAsEmptyElement() {
        // xmlText: Value.NullValue -> "" -> self-closing; scanXml -> format.null-unrepresentable
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("v", Value.NULL)
            )))
        ));
        WriteReport rep = new WriteReport();
        String xml = XmlCodec.write(doc, false, rep);
        assertNotNull(xml);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.null-unrepresentable")),
            "Expected null-unrepresentable warning");
    }

    @Test
    void xmlCodec_boolWritten() {
        // xmlText: BooleanScalar -> "true"
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("flag", new BooleanScalar(true))
            )))
        ));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("true"), "Expected true in: " + xml);
    }

    @Test
    void xmlCodec_datetimeWithOffsetWritten() {
        // xmlText: DateTimeScalar with offset -> OffsetDateTime.toString()
        DateTimeValue dtv = DateTimeValue.of(
            LocalDateTime.of(2024, 1, 1, 12, 0), ZoneOffset.of("+05:30"));
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("dt", new DateTimeScalar(dtv))
            )))
        ));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("2024"), "Expected datetime in: " + xml);
    }

    @Test
    void xmlCodec_datetimeNoOffsetWritten() {
        // xmlText: DateTimeScalar no offset -> dateTime().toString()
        DateTimeValue dtv = DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 12, 0));
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("dt", new DateTimeScalar(dtv))
            )))
        ));
        String xml = XmlCodec.write(doc);
        assertTrue(xml.contains("2024"), "Expected datetime in: " + xml);
    }

    @Test
    void xmlCodec_unsanitizedLabelWarning() {
        // scanXml: !XML_NAME.matcher(label).matches() -> format.key-sanitized
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("123", new StringScalar("val"))
            )))
        ));
        WriteReport rep = new WriteReport();
        XmlCodec.write(doc, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.key-sanitized")),
            "Expected key-sanitized warning");
    }

    @Test
    void xmlCodec_illegalCharWarning() {
        // scanXml: XML_ILLEGAL_CHAR found -> format.string-illegal-char
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("s", new StringScalar("abc" + (char) 0x01 + "def"))
            )))
        ));
        WriteReport rep = new WriteReport();
        XmlCodec.write(doc, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.string-illegal-char")),
            "Expected illegal-char warning");
    }

    @Test
    void xmlCodec_crInStringWarning() {
        // scanXml: strVal.contains(\r) -> format.string-cr-normalized
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("s", new StringScalar("line1\r\nline2"))
            )))
        ));
        WriteReport rep = new WriteReport();
        XmlCodec.write(doc, false, rep);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.string-cr-normalized")),
            "Expected cr-normalized warning");
    }

    @Test
    void xmlCodec_multipleRootsCheck() {
        // scanXml: depth==0 and root.edges().size() != 1 -> format.multiple-roots
        Node doc = new Node(List.of(
            new Edge("a", new StringScalar("1")),
            new Edge("b", new StringScalar("2"))
        ));
        WriteReport rep = XmlCodec.check(doc);
        assertTrue(rep.adjustments().stream()
            .anyMatch(a -> a.code().equals("format.multiple-roots")),
            "Expected multiple-roots warning");
    }

    @Test
    void xmlCodec_multipleRootsThrowsOnWrite() {
        // write: root.edges().size() != 1 -> WriteException
        Node doc = new Node(List.of(
            new Edge("a", new StringScalar("1")),
            new Edge("b", new StringScalar("2"))
        ));
        assertThrows(WriteException.class, () -> XmlCodec.write(doc));
    }

    @Test
    void xmlCodec_strictWriteThrows() {
        // write(strict=true): throws when adjustments present
        Node doc = new Node(List.of(
            new Edge("root", new Node(List.of(
                new Edge("s", new StringScalar("abc" + (char) 0x01 + "def"))
            )))
        ));
        assertThrows(WriteException.class, () -> XmlCodec.write(doc, true, null));
    }

    @Test
    void xmlCodec_schemaAnyType() {
        // xmlPretype: Type.Any -> return node unchanged
        Schema schema = OsdReader.read("record R { \"x\": any }\nroot R\n");
        String xml = "<root><x>val</x></root>";
        Document doc = XmlCodec.read(xml, schema);
        assertNotNull(doc);
    }

    // ==========================================================================
    // ALGEBRA: SchemaAlgebra remaining
    // ==========================================================================

    @Test
    void algebra_equivalent() {
        // equivalent(): compatibleWith(a,b) && compatibleWith(b,a)
        Schema a = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string }\nroot R\n");
        assertTrue(SchemaAlgebra.equivalent(a, b));
    }

    @Test
    void algebra_equivalentFalse() {
        Schema a = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string, \"y\": integer }\nroot R\n");
        assertFalse(SchemaAlgebra.equivalent(a, b));
    }

    @Test
    void algebra_equivalenceClasses_refinementLoop() {
        // equivalenceClasses: refinement loop fires when newBlocks.size() != blocks.size()
        // Use 4 records with structural similarity that initial partition merges but refinement splits
        Schema schema = OsdReader.read(
            "record A { \"x\": B }\n" +
            "record B { \"y\": string }\n" +
            "record C { \"x\": D }\n" +
            "record D { \"z\": integer }\n" +
            "root A\n"
        );
        List<List<String>> classes = SchemaAlgebra.equivalenceClasses(schema);
        assertNotNull(classes);
        assertFalse(classes.isEmpty());
    }

    @Test
    void algebra_inferMixedShapeWithAllowAny() {
        // inferFieldType: someNodes && allowAny -> AnyFallback
        Node n1 = new Node(List.of(new Edge("x", new StringScalar("string"))));
        Node n2 = new Node(List.of(new Edge("x",
            new Node(List.of(new Edge("nested", new StringScalar("obj")))))));
        dev.omnist.algebra.InferResult result = SchemaAlgebra.inferWithReport(
            List.of(n1, n2), "R", true);
        assertNotNull(result);
        assertFalse(result.fallbacks().isEmpty(), "Expected AnyFallback for mixed shape");
    }

    @Test
    void algebra_inferMixedShapeWithoutAllowAnyThrows() {
        // inferFieldType: someNodes && !allowAny -> IllegalArgumentException
        Node n1 = new Node(List.of(new Edge("x", new StringScalar("string"))));
        Node n2 = new Node(List.of(new Edge("x",
            new Node(List.of(new Edge("nested", new StringScalar("obj")))))));
        assertThrows(IllegalArgumentException.class,
            () -> SchemaAlgebra.inferWithReport(List.of(n1, n2), "R", false));
    }

    @Test
    void algebra_inferMultipleScalarKindsWithAllowAny() {
        // inferFieldType: kinds.size() > 1 && allowAny -> AnyFallback
        Node n1 = new Node(List.of(new Edge("x", new StringScalar("str"))));
        Node n2 = new Node(List.of(new Edge("x", new BooleanScalar(true))));
        dev.omnist.algebra.InferResult result = SchemaAlgebra.inferWithReport(
            List.of(n1, n2), "R", true);
        assertNotNull(result);
        assertFalse(result.fallbacks().isEmpty(), "Expected AnyFallback for conflicting scalars");
    }

    @Test
    void algebra_inferMultipleScalarKindsThrows() {
        // inferFieldType: kinds.size() > 1 && !allowAny -> IllegalArgumentException
        Node n1 = new Node(List.of(new Edge("x", new StringScalar("str"))));
        Node n2 = new Node(List.of(new Edge("x", new BooleanScalar(true))));
        assertThrows(IllegalArgumentException.class,
            () -> SchemaAlgebra.inferWithReport(List.of(n1, n2), "R", false));
    }

    @Test
    void algebra_inferNullOnlyField() {
        // inferFieldType: kinds.isEmpty() -> STRING with nullable (null-only values)
        Node n1 = new Node(List.of(new Edge("x", Value.NULL)));
        Schema schema = SchemaAlgebra.infer(List.of(n1), "R", false);
        assertNotNull(schema);
    }

    @Test
    void algebra_inferUniqueNameCollision() {
        // unique: collision -> appends number suffix
        // Two edges with the same label but each is a different node triggers collision
        Node n1 = new Node(List.of(
            new Edge("items", new Node(List.of(new Edge("a", new StringScalar("x"))))),
            new Edge("items", new Node(List.of(new Edge("b", new StringScalar("y")))))
        ));
        Schema schema = SchemaAlgebra.infer(List.of(n1), "R", false);
        assertNotNull(schema);
    }

    @Test
    void algebra_inferIdentifierStripsLeadingDigits() {
        // identifier(): strips leading digits/underscores
        // Triggered when a field label starts with a digit
        Node n1 = new Node(List.of(
            new Edge("123field", new StringScalar("val"))
        ));
        Schema schema = SchemaAlgebra.infer(List.of(n1), "R", false);
        assertNotNull(schema);
    }

    @Test
    void algebra_sub_anyOnLeft() {
        // sub: da instanceof Type.Any -> false (only Any on RHS can absorb)
        Schema a = OsdReader.read("record R { \"x\": any }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string }\nroot R\n");
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_sub_scalarVsRecord() {
        // sub: scalar vs record -> false (value vs object mismatch)
        Schema a = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Schema b = OsdReader.read(
            "record Inner { \"y\": string }\nrecord R { \"x\": Inner }\nroot R\n");
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_sub_nullableScalarNotSubOfNonNullable() {
        // scalarSub: a.nullable() && !b.nullable() -> false
        Schema a = OsdReader.read("record R { \"x\": string? }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string }\nroot R\n");
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_sub_integerSubtypeOfNumber() {
        // scalarSub: INTEGER is subtype of NUMBER
        Schema a = OsdReader.read("record R { \"x\": integer }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": number }\nroot R\n");
        assertTrue(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_sub_aMissingFieldRequiredByB() {
        // recordSub: A missing field required by B -> false
        Schema a = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string, \"y\": integer }\nroot R\n");
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_sub_aHasExtraFieldNotInB() {
        // recordSub: A emits label B doesn't have -> false (B is closed)
        Schema a = OsdReader.read("record R { \"x\": string, \"extra\": integer }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string }\nroot R\n");
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_sub_cardinalityNotSub() {
        // cardinalitySub: minB > minA -> false
        Schema a = OsdReader.read("record R { \"x\" [0,1]: string }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\": string }\nroot R\n"); // min=1 required
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_le_xUnboundedYBounded() {
        // le: x == null (unbounded), y != null -> false
        Schema a = OsdReader.read("record R { \"x\" [,]: string }\nroot R\n");
        Schema b = OsdReader.read("record R { \"x\" [0,5]: string }\nroot R\n");
        assertFalse(SchemaAlgebra.compatibleWith(a, b));
    }

    @Test
    void algebra_reachablePlain_orphanRecord() {
        // reachablePlain is used by lint to detect orphan records
        Schema schema = OsdReader.read(
            "record A { \"x\": string }\n" +
            "record Orphan { \"y\": string }\n" +
            "root A\n"
        );
        List<dev.omnist.algebra.LintFinding> findings = SchemaAlgebra.lint(schema);
        assertNotNull(findings);
        assertTrue(findings.stream()
            .anyMatch(f -> f.message().contains("Orphan") || f.code().contains("orphan") ||
                          f.message().contains("reachable")),
            "Expected orphan lint finding: " + findings);
    }

    @Test
    void algebra_normalize_mergesStructurallyIdenticalRecords() {
        // normalize calls remap internally to merge equivalent records
        Schema schema = OsdReader.read(
            "record A { \"x\": string }\n" +
            "record B { \"x\": string }\n" +
            "record Root { \"a\": A, \"b\": B }\n" +
            "root Root\n"
        );
        Schema normalized = SchemaAlgebra.normalize(schema);
        assertNotNull(normalized);
    }

    // ==========================================================================
    // VALIDATION: remaining Validator branches
    // ==========================================================================

    @Test
    void validator_rootRecordMissing() {
        // Validator.validate: rootRecord == null (root name not in records map)
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Other", new dev.omnist.schema.Record("Other", List.of(
            new Field("x", new Type.Scalar(dev.omnist.schema.ScalarKind.STRING, false), 1, 1)
        )));
        Schema schema = new Schema("Missing", records);
        Node doc = new Node(List.of(new Edge("x", new StringScalar("hello"))));
        ValidationResult res = Validator.validate(doc, schema);
        assertFalse(res.isValid());
        assertTrue(res.diagnostics().stream()
            .anyMatch(d -> d.code().equals("validate.shape-mismatch")),
            "Expected shape-mismatch for missing root record");
    }

    @Test
    void validator_unknownRecordRef() {
        // conformTarget: record == null (ref to missing record)
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Root", new dev.omnist.schema.Record("Root", List.of(
            new Field("child", new Type.Ref("Missing"), 1, 1)
        )));
        Schema schema = new Schema("Root", records);
        Node doc = new Node(List.of(
            new Edge("child", new Node(List.of(
                new Edge("x", new StringScalar("val"))
            )))
        ));
        ValidationResult res = Validator.validate(doc, schema);
        assertFalse(res.isValid());
        assertTrue(res.diagnostics().stream()
            .anyMatch(d -> d.code().equals("validate.shape-mismatch")),
            "Expected shape-mismatch for unknown ref");
    }

    @Test
    void validator_bareScalarDocumentTopLevel() {
        // Validator.validate: document (Scalar) is not a Target -> shape-mismatch
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n");
        StringScalar bare = new StringScalar("not-an-object");
        ValidationResult res = Validator.validate(bare, schema);
        assertFalse(res.isValid());
        assertTrue(res.diagnostics().stream()
            .anyMatch(d -> d.code().equals("validate.shape-mismatch")),
            "Expected shape-mismatch for bare scalar document");
    }

    @Test
    void validator_scalarTargetWhereRecordExpected() {
        // conformTarget: !(target instanceof Node) when Ref field type
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Inner", new dev.omnist.schema.Record("Inner", List.of(
            new Field("y", new Type.Scalar(dev.omnist.schema.ScalarKind.STRING, false), 1, 1)
        )));
        records.put("Root", new dev.omnist.schema.Record("Root", List.of(
            new Field("child", new Type.Ref("Inner"), 1, 1)
        )));
        Schema schema = new Schema("Root", records);
        Node doc = new Node(List.of(
            new Edge("child", new StringScalar("not-a-node"))
        ));
        ValidationResult res = Validator.validate(doc, schema);
        assertFalse(res.isValid());
        assertTrue(res.diagnostics().stream()
            .anyMatch(d -> d.code().equals("validate.shape-mismatch")),
            "Expected shape-mismatch for scalar where record expected");
    }

    // ==========================================================================
    // MATERIALIZER: remaining branches
    // ==========================================================================

    @Test
    void materializer_booleanStringConversionFails() {
        // materializeScalar: STRING -> BOOLEAN but invalid string -> ValidationException
        Schema schema = OsdReader.read("record R { \"x\": boolean }\nroot R\n");
        Node doc = new Node(List.of(new Edge("x", new StringScalar("not-a-bool"))));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_inexactConversionFails() {
        // materializeScalar: non-integral Double where INTEGER expected -> ValidationException
        Schema schema = OsdReader.read("record R { \"x\": integer }\nroot R\n");
        Node doc = new Node(List.of(new Edge("x", new NumberScalar(1.5))));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_unexpectedField() {
        // materializeRecord: f == null -> unexpected-field diagnostic -> ValidationException
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Node doc = new Node(List.of(
            new Edge("x", new StringScalar("val")),
            new Edge("extra", new StringScalar("unexpected"))
        ));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_cardinalityViolation() {
        // materializeRecord: c < f.min() -> cardinality diagnostic -> ValidationException
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n"); // x required
        Node doc = new Node(List.of()); // x missing -> c=0 < min=1
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_anyTypePassthrough() {
        // materializeType: d instanceof Type.Any -> return node unchanged
        Schema schema = OsdReader.read("record R { \"x\": any }\nroot R\n");
        Node doc = new Node(List.of(new Edge("x", new StringScalar("anything"))));
        Document result = Materializer.materialize(doc, schema);
        assertNotNull(result);
    }

    @Test
    void materializer_objectWhereScalarExpected() {
        // materializeScalar: value instanceof Node -> shape-mismatch
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Node doc = new Node(List.of(
            new Edge("x", new Node(List.of(
                new Edge("nested", new StringScalar("val"))
            )))
        ));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_dateStringConversionFails() {
        // materializeScalar: STRING -> DATE but invalid format
        Schema schema = OsdReader.read("record R { \"d\": date }\nroot R\n");
        Node doc = new Node(List.of(new Edge("d", new StringScalar("not-a-date"))));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_invalidTimeFormatFails() {
        // materializeScalar: STRING -> TIME but invalid format
        Schema schema = OsdReader.read("record R { \"t\": time }\nroot R\n");
        Node doc = new Node(List.of(new Edge("t", new StringScalar("not-a-time"))));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    @Test
    void materializer_invalidDatetimeFormatFails() {
        // materializeScalar: STRING -> DATETIME but invalid format
        Schema schema = OsdReader.read("record R { \"dt\": datetime }\nroot R\n");
        Node doc = new Node(List.of(new Edge("dt", new StringScalar("not-a-datetime"))));
        assertThrows(ValidationException.class, () -> Materializer.materialize(doc, schema));
    }

    // ==========================================================================
    // Batch 2: Validator, OsdLexer, OsdWriter
    // ==========================================================================

    @Test
    void validator_rootRecordNotDefinedInSchema() {
        // validate(): schema.root() names a record that isn't in schema.records()
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Other", new dev.omnist.schema.Record("Other", List.of()));
        Schema schema = new Schema("Missing", records);
        Document doc = new Node(List.of());
        ValidationResult result = dev.omnist.validation.Validator.validate(doc, schema);
        assertFalse(result.isValid());
        assertEquals("validate.shape-mismatch", result.diagnostics().get(0).code());
    }

    @Test
    void osdLexer_unterminatedEscapeInString() {
        // parseOsdString: backslash as the last character before EOF
        assertThrows(dev.omnist.schema.OsdParseException.class,
            () -> OsdReader.read("record R { \"a\\"));
    }

    @Test
    void osdWriter_compactWithZeroFieldRecord() {
        // writeRecordCompact: record.fields().isEmpty() -- no trailing space appended
        dev.omnist.schema.Record r = new dev.omnist.schema.Record("Empty", List.of());
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Empty", r);
        Schema schema = new Schema("Empty", records);
        String compact = OsdWriter.writeCompact(schema);
        assertTrue(compact.contains("record Empty {}"));
    }

    @Test
    void osdWriter_quotedLabelWithEscapedCharacters() {
        // writeQuotedString: label containing a literal quote and backslash
        Field f = new Field("a\"b\\c", new Type.Scalar(dev.omnist.schema.ScalarKind.STRING, false), 1, 1);
        dev.omnist.schema.Record r = new dev.omnist.schema.Record("R", List.of(f));
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);
        String written = OsdWriter.write(schema);
        assertTrue(written.contains("\\\"") && written.contains("\\\\"));
        Schema roundTrip = OsdReader.read(written);
        assertEquals(schema, roundTrip);
    }

    // ==========================================================================
    // Batch 3: OsdReader parse-error branches
    // ==========================================================================

    @Test
    void osdReader_expectedRecordNameIdentifier() {
        // parseRecord: token after 'record' keyword is not an IDENT
        assertThrows(dev.omnist.schema.OsdParseException.class,
            () -> OsdReader.read("record \"quoted\" { \"a\": string } root R"));
    }

    @Test
    void osdReader_expectedTypeIdentifier() {
        // parseField: token in type position is neither STRING nor IDENT
        assertThrows(dev.omnist.schema.OsdParseException.class,
            () -> OsdReader.read("record R { \"a\": [1,1] } root R"));
    }

    @Test
    void osdReader_unclosedRecordBrace() {
        // parseRecord: EOF reached before closing '}'
        assertThrows(dev.omnist.schema.OsdParseException.class,
            () -> OsdReader.read("record R { \"a\": string"));
    }
}
