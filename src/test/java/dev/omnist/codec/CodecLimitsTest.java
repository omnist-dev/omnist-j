package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Edge;
import dev.omnist.document.Limits;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.IntegerScalar;
import dev.omnist.document.Scalar.NumberScalar;
import dev.omnist.document.Scalar.StringScalar;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-9 and E-4a: the depth, node-count and integer-digit limits apply to every route into the
 * model, with the OML reader's diagnostics (omnist-j#127); D-28 counts the decimal digits of the
 * value, whatever the literal's spelling.
 */
class CodecLimitsTest {

    private static final Limits DEPTH_3 = new Limits(3, 1_000_000, 4_300);
    private static final Limits NODES_1 = new Limits(200, 1, 4_300);
    private static final Limits DIGITS_3 = new Limits(200, 1_000_000, 3);

    private static final BiFunction<String, Limits, Document> JSON = JsonCodec::readWithLimits;
    private static final BiFunction<String, Limits, Document> YAML =
            (text, limits) -> YamlCodec.readWithLimits(text, YamlLimits.DEFAULT, limits);
    private static final BiFunction<String, Limits, Document> TOML = TomlCodec::readWithLimits;
    private static final BiFunction<String, Limits, Document> XML =
            (text, limits) -> XmlCodec.readWithLimits(text, null, null, limits);

    private static DocumentParseException refused(BiFunction<String, Limits, Document> reader, String text, Limits limits) {
        return assertThrows(DocumentParseException.class, () -> reader.apply(text, limits), text);
    }

    private static void assertRefused(BiFunction<String, Limits, Document> reader, String text, Limits limits,
                                      String code, String path) {
        DocumentParseException e = refused(reader, text, limits);
        assertEquals(code, e.getCode(), text);
        assertEquals(path, e.getPath(), text);
    }

    private static BigInteger integerAt(Document doc, String... labels) {
        Document current = doc;
        for (String label : labels) {
            current = (Document) ((Node) current).edges().stream()
                    .filter(e -> e.label().equals(label)).findFirst().orElseThrow().target();
        }
        return ((IntegerScalar) current).value();
    }

    // ---- depth, node count: the same diagnostic as the OML reader, at $ ----

    @Test
    @DisplayName("a configured depth is honoured by every codec: at the limit it reads, one past it is document.limit.depth at $")
    void depthIsConfigurable() {
        assertDoesNotThrow(() -> JSON.apply("{\"a\": {\"b\": {\"c\": 1}}}", DEPTH_3));
        assertRefused(JSON, "{\"a\": {\"b\": {\"c\": {\"d\": 1}}}}", DEPTH_3, "document.limit.depth", "$");
        assertDoesNotThrow(() -> YAML.apply("a:\n  b:\n    c: 1\n", DEPTH_3));
        assertRefused(YAML, "a:\n  b:\n    c:\n      d: 1\n", DEPTH_3, "document.limit.depth", "$");
        assertDoesNotThrow(() -> TOML.apply("[a.b]\nc = 1\n", DEPTH_3));
        assertRefused(TOML, "[a.b.c]\nd = 1\n", DEPTH_3, "document.limit.depth", "$");
        assertDoesNotThrow(() -> XML.apply("<a><b><c>x</c></b></a>", DEPTH_3));
        assertRefused(XML, "<a><b><c><d>x</d></c></b></a>", DEPTH_3, "document.limit.depth", "$");
    }

    @Test
    @DisplayName("a configured node count is honoured by every codec: document.limit.nodes at $")
    void nodeCountIsConfigurable() {
        assertDoesNotThrow(() -> JSON.apply("{\"a\": 1, \"b\": 2}", NODES_1));
        assertRefused(JSON, "{\"a\": {\"b\": 1}}", NODES_1, "document.limit.nodes", "$");
        assertDoesNotThrow(() -> YAML.apply("a: 1\nb: 2\n", NODES_1));
        assertRefused(YAML, "a:\n  b: 1\n", NODES_1, "document.limit.nodes", "$");
        assertDoesNotThrow(() -> TOML.apply("a = 1\nb = 2\n", NODES_1));
        assertRefused(TOML, "[a]\nb = 1\n", NODES_1, "document.limit.nodes", "$");
        assertDoesNotThrow(() -> XML.apply("<a>1</a>", NODES_1));
        assertRefused(XML, "<r><a>1</a></r>", NODES_1, "document.limit.nodes", "$");
    }

    @Test
    @DisplayName("an array element is one level below its node, as in the OML reader (a repeated edge adds no level)")
    void arrayElementsAddNoLevel() {
        // {a: [{b: 1}]} is root(0) -> a(1) -> b's value(2): within a limit of 2
        Limits two = new Limits(2, 1_000_000, 4_300);
        assertDoesNotThrow(() -> JSON.apply("{\"a\": [{\"b\": 1}]}", two));
        assertDoesNotThrow(() -> YAML.apply("a:\n  - b: 1\n", two));
        assertDoesNotThrow(() -> TOML.apply("[[a]]\nb = 1\n", two));
        assertRefused(JSON, "{\"a\": [{\"b\": {\"c\": 1}}]}", two, "document.limit.depth", "$");
    }

    // ---- integer digits: at the literal's Document path ----

    @Test
    @DisplayName("a configured digit limit is honoured by every codec, at the integer's Document path, sign not counted")
    void digitsAreConfigurableAtTheLiteralsPath() {
        assertEquals(BigInteger.valueOf(999), integerAt(JSON.apply("{\"a\": 999}", DIGITS_3), "a"));
        assertRefused(JSON, "{\"a\": 1000}", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(-999), integerAt(JSON.apply("{\"a\": -999}", DIGITS_3), "a"));
        assertRefused(JSON, "{\"a\": -1000}", DIGITS_3, "document.limit.int-digits", "$.a");
        assertRefused(JSON, "{\"a\": {\"b\": 1000}}", DIGITS_3, "document.limit.int-digits", "$.a.b");
        assertRefused(YAML, "a: 1000\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertRefused(TOML, "a = 1000\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(YAML.apply("a: 999\n", DIGITS_3), "a"));
        assertEquals(BigInteger.valueOf(999), integerAt(TOML.apply("a = 999\n", DIGITS_3), "a"));
    }

    @Test
    @DisplayName("E-10: the path of an integer in a repeated label is indexed; a single-element array stays bare")
    void repeatedLabelPathsAreIndexedOnlyWhenTheLabelRepeats() {
        assertRefused(JSON, "{\"a\": [1, 2000]}", DIGITS_3, "document.limit.int-digits", "$.a[1]");
        assertRefused(YAML, "a: [1, 2000]\n", DIGITS_3, "document.limit.int-digits", "$.a[1]");
        assertRefused(TOML, "a = [1, 2000]\n", DIGITS_3, "document.limit.int-digits", "$.a[1]");
        assertRefused(JSON, "{\"a\": [2000]}", DIGITS_3, "document.limit.int-digits", "$.a");
        assertRefused(YAML, "a: [2000]\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertRefused(TOML, "a = [2000]\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertRefused(JSON, "{\"o\": {\"a\": [1, 2, 2000]}}", DIGITS_3, "document.limit.int-digits", "$.o.a[2]");
    }

    // ---- D-28: the digits of the value, not of the literal ----

    @Test
    @DisplayName("D-28: a hexadecimal, octal or binary literal is counted by the digits of its value (TOML and YAML)")
    void radixLiteralsAreCountedByValue() {
        // 0xFFF = 4095 (4 digits); 0x3E7 = 999 (3); 0o1750 = 1000; 0o1747 = 999; 0b1111101000 = 1000; 0b1111100111 = 999
        assertRefused(TOML, "a = 0xFFF\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(TOML.apply("a = 0x3E7\n", DIGITS_3), "a"));
        assertRefused(TOML, "a = 0o1750\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(TOML.apply("a = 0o1747\n", DIGITS_3), "a"));
        assertRefused(TOML, "a = 0b1111101000\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(TOML.apply("a = 0b1111100111\n", DIGITS_3), "a"));
        assertRefused(YAML, "a: 0xFFF\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(YAML.apply("a: 0x3E7\n", DIGITS_3), "a"));
        assertRefused(YAML, "a: 0b1111101000\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(YAML.apply("a: 0b1111100111\n", DIGITS_3), "a"));
        assertRefused(YAML, "a: 01750\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(YAML.apply("a: 01747\n", DIGITS_3), "a"));
    }

    @Test
    @DisplayName("D-28: underscores and leading zeros are not digits")
    void underscoresAndLeadingZerosAreNotDigits() {
        assertEquals(BigInteger.valueOf(999), integerAt(TOML.apply("a = 9_9_9\n", DIGITS_3), "a"));
        assertRefused(TOML, "a = 1_000\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(YAML.apply("a: 9_9_9\n", DIGITS_3), "a"));
        assertRefused(YAML, "a: 1_000\n", DIGITS_3, "document.limit.int-digits", "$.a");
        // a TOML hex literal with many leading zeros is three digits by value
        assertEquals(BigInteger.valueOf(999),
                integerAt(TOML.apply("a = 0x" + "0".repeat(40) + "3E7\n", DIGITS_3), "a"));
    }

    @Test
    @DisplayName("D-28: a YAML sexagesimal integer is counted by its value")
    void sexagesimalIntegersAreCountedByValue() {
        // 1:00:00 = 3600 (four digits), 16:39 = 999
        assertRefused(YAML, "a: 1:00:00\n", DIGITS_3, "document.limit.int-digits", "$.a");
        assertEquals(BigInteger.valueOf(999), integerAt(YAML.apply("a: 16:39\n", DIGITS_3), "a"));
    }

    @Test
    @DisplayName("D-28 at the default limit: a 3 600-digit hexadecimal literal (4 335 decimal digits) is refused, a 4 301-digit binary one (1 295) is accepted")
    void defaultLimitCountsTheValueOfLongLiterals() {
        String hex3600 = "0x" + "F".repeat(3_600);
        String bin4301 = "0b" + "1".repeat(4_301);
        assertRefused(TOML, "a = " + hex3600 + "\n", Limits.DEFAULT, "document.limit.int-digits", "$.a");
        assertRefused(YAML, "a: " + hex3600 + "\n", Limits.DEFAULT, "document.limit.int-digits", "$.a");
        BigInteger expected = BigInteger.ONE.shiftLeft(4_301).subtract(BigInteger.ONE);
        assertEquals(expected, integerAt(TOML.apply("a = " + bin4301 + "\n", Limits.DEFAULT), "a"));
        assertEquals(expected, integerAt(YAML.apply("a: " + bin4301 + "\n", Limits.DEFAULT), "a"));
    }

    // ---- omnist-j#127: a long YAML integer never silently becomes a string ----

    @Test
    @DisplayName("a YAML integer between 1 000 and 4 300 digits is an integer, never a string")
    void longYamlIntegersStayIntegers() {
        for (int digits : new int[]{999, 1_000, 1_001, 1_100, 1_500, 3_000, 4_300}) {
            String literal = "9".repeat(digits);
            Document doc = YAML.apply("a: " + literal + "\n", Limits.DEFAULT);
            assertEquals(new BigInteger(literal), integerAt(doc, "a"), digits + " digits");
            Document negative = YAML.apply("a: -" + literal + "\n", Limits.DEFAULT);
            assertEquals(new BigInteger("-" + literal), integerAt(negative, "a"), digits + " digits");
        }
    }

    @Test
    @DisplayName("a YAML integer past the default limit is document.limit.int-digits at its path, however long")
    void overLongYamlIntegersAreRefused() {
        assertRefused(YAML, "a: " + "9".repeat(4_301) + "\n", Limits.DEFAULT, "document.limit.int-digits", "$.a");
        assertRefused(YAML, "o:\n  a: " + "9".repeat(50_000) + "\n", Limits.DEFAULT, "document.limit.int-digits", "$.o.a");
    }

    @Test
    @DisplayName("a long YAML float stays a number and a quoted run of digits stays a string")
    void longYamlFloatsAndQuotedDigitsKeepTheirType() {
        Document floating = YAML.apply("a: 1." + "0".repeat(1_500) + "\n", Limits.DEFAULT);
        Node root = (Node) floating;
        assertEquals(new NumberScalar(1.0), root.edges().get(0).target());
        Document quoted = YAML.apply("a: \"" + "9".repeat(1_500) + "\"\n", Limits.DEFAULT);
        assertEquals(new StringScalar("9".repeat(1_500)), ((Node) quoted).edges().get(0).target());
    }

    @Test
    @DisplayName("a configured digit limit above Jackson's own number-length cap is honoured for JSON")
    void jsonDigitLimitAboveTheParserCap() {
        Limits wide = new Limits(200, 1_000_000, 20_000);
        String literal = "7".repeat(12_000);
        assertEquals(new BigInteger(literal), integerAt(JSON.apply("{\"a\": " + literal + "}", wide), "a"));
        // past the configured limit the parser's own cap (set to the limit) refuses first
        assertEquals("parse.codec-syntax", refused(JSON, "{\"a\": " + "7".repeat(20_001) + "}", wide).getCode());
    }

    // ---- XML: schema-directed pretyping of an integer leaf (E-4a, D-28) ----

    @Test
    @DisplayName("the pretyping of an XML integer leaf uses the configured digit limit, at the leaf's Document path")
    void xmlPretypedIntegersAreBounded() {
        Schema schema = OsdReader.read("record R {\n  \"n\": integer,\n  \"m\": integer,\n}\nroot R\n");
        Document ok = XmlCodec.readWithLimits("<R><n>999</n><m>-999</m></R>", schema, null, DIGITS_3);
        Node r = (Node) ((Node) ok).edges().get(0).target();
        assertEquals(new IntegerScalar(BigInteger.valueOf(999)), r.edges().get(0).target());
        assertEquals(new IntegerScalar(BigInteger.valueOf(-999)), r.edges().get(1).target());
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> XmlCodec.readWithLimits("<R><n>1</n><m>-1000</m></R>", schema, null, DIGITS_3));
        assertEquals("document.limit.int-digits", e.getCode());
        assertEquals("$.R.m", e.getPath());
    }

    @Test
    @DisplayName("the path of a pretyped XML integer in a repeated element is indexed")
    void xmlPretypedRepeatedLeafPathIsIndexed() {
        Schema schema = OsdReader.read("record R {\n  \"n\" [1,]: integer,\n}\nroot R\n");
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> XmlCodec.readWithLimits("<R><n>1</n><n>2000</n></R>", schema, null, DIGITS_3));
        assertEquals("$.R.n[1]", e.getPath());
    }

    private static Node root(Document d) {
        return (Node) d;
    }

    @Test
    @DisplayName("a JSON integer that does not fit an int (a Long) is bounded by the digit limit too")
    void jsonLongIsBounded() {
        Limits three = new Limits(200, 1_000_000, 3);
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> JsonCodec.readWithLimits("{\"a\": 12345678901}", three));
        assertEquals("document.limit.int-digits", e.getCode());
        assertEquals("$.a", e.getPath());
    }

    @Test
    @DisplayName("YAML: a long plain scalar that is neither an integer nor a float stays a string; small integers still read")
    void yamlLongStringsAndSmallIntegers() {
        String text = "x".repeat(300);
        Node r = root(YamlCodec.read("a: " + text + "\nb: 0\nc: -0\nd: 00\ne: 0755\n"));
        assertEquals(new StringScalar(text), r.edges().get(0).target());
        assertEquals(new IntegerScalar(BigInteger.ZERO), r.edges().get(1).target());
        assertEquals(new IntegerScalar(BigInteger.ZERO), r.edges().get(2).target());
        assertEquals(new IntegerScalar(BigInteger.ZERO), r.edges().get(3).target());
        assertEquals(new IntegerScalar(BigInteger.valueOf(493)), r.edges().get(4).target());
    }
}
