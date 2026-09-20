package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec read failures adopted from omnist-spec v0.19.0-beta: {@code parse.codec-syntax} with a
 * {@code line:col} path (section 8.3.1, E-11), and the data-XML profile refusals that must come
 * AFTER well-formedness (docs/formats/xml.md).
 */
class CodecSweepTest {

    private static DocumentParseException fail(java.util.function.Function<String, Document> read, String text) {
        return assertThrows(DocumentParseException.class, () -> read.apply(text), text);
    }

    private static void assertRefusal(String text, String code) {
        DocumentParseException ex = fail(XmlCodec::read, text);
        assertEquals(code, ex.getCode(), text);
        assertEquals("$", ex.getPath(), text);
    }

    private static void assertMalformed(String text, String path) {
        DocumentParseException ex = fail(XmlCodec::read, text);
        assertEquals("parse.codec-syntax", ex.getCode(), text);
        if (path != null) {
            assertEquals(path, ex.getPath(), text);
        } else {
            assertTrue(ex.getPath().matches("\\d+:\\d+"), ex.getPath());
        }
    }

    // ---------------------------------------------------------------- codec-syntax paths

    @Test
    void malformedInputIsCodecSyntaxWithATextPositionOnEveryCodec() {
        DocumentParseException json = fail(JsonCodec::read, "{\n  \"a\": ,\n}");
        assertEquals("parse.codec-syntax", json.getCode());
        assertEquals("2:8", json.getPath());

        DocumentParseException toml = fail(TomlCodec::read, "a = 1\nb = \n");
        assertEquals("parse.codec-syntax", toml.getCode());
        assertEquals("2:5", toml.getPath());

        DocumentParseException yaml = fail(YamlCodec::read, "a: 1\nb: [1, 2\n");
        assertEquals("parse.codec-syntax", yaml.getCode());
        assertTrue(yaml.getPath().matches("[3-9]:\\d+"), yaml.getPath());

        DocumentParseException xml = fail(XmlCodec::read, "<r>\n  <a>\n</r>");
        assertEquals("parse.codec-syntax", xml.getCode());
        assertEquals("3:3", xml.getPath());
    }

    @Test
    void yamlWithNoDocumentOrTwoDocumentsIsCodecSyntax() {
        assertEquals("parse.codec-syntax", fail(YamlCodec::read, "a: 1\n---\nb: 2\n").getCode());
        assertEquals("parse.codec-syntax", fail(YamlCodec::read, "").getCode());
    }

    @Test
    void anUnknownPositionFallsBackToOneOne() {
        DocumentParseException ex = CodecInput.syntax("JSON", "boom", 0, -1, null);
        assertEquals("1:1", ex.getPath());
        assertEquals("parse.codec-syntax", ex.getCode());
        assertNull(ex.getCause());
        DocumentParseException withCause = CodecInput.syntax("JSON", "boom", 4, 7, new RuntimeException("x"));
        assertEquals("4:7", withCause.getPath());
        assertNotNull(withCause.getCause());
    }

    @Test
    void oversizedAndNullInputAreRefusedBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.read(null));
        String huge = "x".repeat(JsonCodec.MAX_INPUT_LENGTH + 1);
        assertEquals("document.parse-error", fail(JsonCodec::read, huge).getCode());
    }

    // ---------------------------------------------------------------- data-XML profile

    @Test
    void doctypeIsRefusedOnSightWhenTheRestIsWellFormed() {
        assertRefusal("<?xml version=\"1.0\"?><!DOCTYPE d [<!ELEMENT d ANY>]><d><f>hi</f></d>", "format.dtd-forbidden");
        assertRefusal("<!DOCTYPE d><d/>", "format.dtd-forbidden");
        // A declaration that is never used is still refused; so is one whose entities ARE used.
        assertRefusal("<!DOCTYPE d [<!ENTITY e \"x\">]><d>&e;</d>", "format.dtd-forbidden");
        // Quoted '>' and brackets inside the declaration do not end it early.
        assertRefusal("<!DOCTYPE d [<!ENTITY e \"a>b]\"><!-- ] > --><?pi ] > ?>]><d/>", "format.dtd-forbidden");
        assertRefusal("<!DOCTYPE d SYSTEM \"a>b\"><d/>", "format.dtd-forbidden");
        assertRefusal("<!DOCTYPE d SYSTEM 'a>b'><d/>", "format.dtd-forbidden");
    }

    @Test
    void nonPredefinedEntityIsRefusedIncludingInAttributeValues() {
        assertRefusal("<d><f>&nbsp;</f></d>", "format.entity-forbidden");
        assertRefusal("<r a=\"&foo;\"/>", "format.entity-forbidden");
        assertRefusal("<r a='&foo;'><b/></r>", "format.entity-forbidden");
        assertRefusal("<r><a b=\"x &foo; y\">1</a></r>", "format.entity-forbidden");
        assertRefusal("<r>&café;</r>", "format.entity-forbidden");
        assertRefusal("<r>&a.b-c:d_e;</r>", "format.entity-forbidden");
    }

    @Test
    void theRefusalIsReportedOnlyAfterWellFormednessAndSyntaxErrorsWin() {
        assertMalformed("<r><a>&foo;</a>", "1:16");
        assertMalformed("<r><a>&foo;</a><b></r>", null);
        assertMalformed("<!DOCTYPE d [<!ENTITY e \"x\">]><d>", null);
        assertMalformed("<!DOCTYPE d [<!ENTITY a \"&b;\">]><d>&a;", null);
        assertMalformed("<r a=\"&foo;\"", null);
        // Positions are the ORIGINAL text's, even with a multi-line DOCTYPE blanked out before it.
        assertMalformed("<!DOCTYPE d [\n<!ENTITY e \"x\">\n]>\n<d></e>", "4:6");
        assertMalformed("<r>\n&foo;\n<a></b></r>", "3:6");
    }

    @Test
    void unterminatedOrMalformedDoctypesAreSyntaxErrorsNotRefusals() {
        assertMalformed("<!DOCTYPE d", null);
        assertMalformed("<!DOCTYPE d [", null);
        assertMalformed("<!DOCTYPE d \"unterminated><d/>", null);
        assertMalformed("<!DOCTYPE d [<!-- never closed ]><d/>", null);
        assertMalformed("<!DOCTYPE d [<?pi never closed ]><d/>", null);
        assertMalformed("<!doctype d><d/>", null);
    }

    @Test
    void commentsCdataAndProcessingInstructionsAreInert() {
        Document doc = XmlCodec.read(
            "<?xml version=\"1.0\"?><?pi &foo; <!DOCTYPE x> ?><!-- &foo; <!DOCTYPE x> --><r><!-- &bar; --><a>1</a>"
            + "<b><![CDATA[&foo; <!DOCTYPE x>]]></b></r>");
        assertEquals(new Node(List.of(new Edge("r", new Node(List.of(
            new Edge("a", new Scalar.StringScalar("1")),
            new Edge("b", new Scalar.StringScalar("&foo; <!DOCTYPE x>"))))))), doc);
        // Unterminated inert constructs are left for the parser to report.
        assertMalformed("<r><!-- &foo;", null);
        assertMalformed("<r><![CDATA[ &foo;", null);
        assertMalformed("<r><?pi &foo;", null);
    }

    @Test
    void numericCharacterReferencesAndTheFivePredefinedEntitiesStayLegal() {
        Document doc = XmlCodec.read("<r>&#65;&#x42;&amp;&lt;&gt;&quot;&apos;</r>");
        assertEquals(new Node(List.of(new Edge("r", new Scalar.StringScalar("AB&<>\"'")))), doc);
        Document attr = XmlCodec.read("<r a=\"&amp;&#65;\"><b>1</b></r>");
        assertNotNull(attr);
    }

    @Test
    void aBareAmpersandOrUnterminatedReferenceIsMalformedNotARefusal() {
        assertMalformed("<r>a & b</r>", null);
        assertMalformed("<r>&foo</r>", "1:8");
        assertMalformed("<r>&;</r>", null);
        assertMalformed("<r>&#;</r>", null);
        assertMalformed("<r a=\"&\"/>", null);
        assertMalformed("<r>&", null);
    }

    @Test
    void aStrayLessThanInsideATagIsLeftToTheParser() {
        assertMalformed("<r a=\"1\" <b/>", null);
        assertMalformed("<r a=\"1\"", null);
    }

    @Test
    void mixedContentIsRefusedWithPathDollar() {
        assertRefusal("<d>text<f>hi</f></d>", "format.mixed-content");
        assertRefusal("<d><f>hi</f>tail</d>", "format.mixed-content");
        assertRefusal("<d><g><f>hi</f>x</g></d>", "format.mixed-content");
        // Whitespace between child elements is not mixed content.
        assertNotNull(XmlCodec.read("<d>\n  <f>hi</f>\n  <g>ho</g>\n</d>"));
    }

    @Test
    void theScanReportsTheFirstRefusalInDocumentOrder() {
        assertEquals(XmlProfileScan.Kind.DTD, XmlProfileScan.scan("<!DOCTYPE d [<!ENTITY e \"x\">]><d>&e;</d>").kind);
        assertEquals(XmlProfileScan.Kind.ENTITY, XmlProfileScan.scan("<d>&e;</d>").kind);
        assertEquals(XmlProfileScan.Kind.NONE, XmlProfileScan.scan("<d>&amp;</d>").kind);
        assertEquals(XmlProfileScan.Kind.NONE, XmlProfileScan.scan("<d/>").kind);
        String sanitized = XmlProfileScan.scan("<!DOCTYPE d [\n]>\n<d a=\"&foo;\"/>").sanitized;
        assertEquals("<!DOCTYPE d [\n]>\n<d a=\"&foo;\"/>".length(), sanitized.length());
        assertFalse(sanitized.contains("DOCTYPE"));
        assertFalse(sanitized.contains("&foo;"));
        assertEquals(2, sanitized.chars().filter(c -> c == '\n').count());
    }

    // ---------------------------------------------------------------- YAML: cycles must not crash

    @Test
    void aYamlAliasToAnAnchorStillBeingBuiltFailsCleanlyAndQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            DocumentParseException ex = fail(YamlCodec::read, "a: &A\n  b: *A\n");
            assertTrue(ex.getCode().equals("document.limit.depth") || ex.getCode().equals("parse.codec-syntax"), ex.getCode());
            DocumentParseException top = fail(YamlCodec::read, "&A\nk: *A\n");
            assertTrue(top.getCode().equals("document.limit.depth") || top.getCode().equals("parse.codec-syntax"), top.getCode());
        });
    }

    @Test
    void aSelfReferentialMergeDoesNotHangOrThrowAnUncheckedException() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Accepted today (the reference accepts it too: D-20 is not enforced anywhere yet, DIV-3),
            // but it must terminate and must not materialise a cyclic structure.
            Document doc = YamlCodec.read("a: &A\n  <<: *A\n  x: 1\n");
            assertEquals(new Node(List.of(new Edge("a", new Node(List.of(new Edge("x", new Scalar.IntegerScalar(java.math.BigInteger.ONE))))))), doc);
        });
    }

    // ---------------------------------------------------------------- coverage of the mapping helpers

    @Test
    void xmlSyntaxErrorMapsSaxPositionsAndFallsBackToOneOne() {
        DocumentParseException sax = XmlCodec.syntaxError(new org.xml.sax.SAXParseException("bad", null, null, 3, 9));
        assertEquals("3:9", sax.getPath());
        assertEquals("parse.codec-syntax", sax.getCode());
        DocumentParseException other = XmlCodec.syntaxError(new java.io.IOException("io"));
        assertEquals("1:1", other.getPath());
        assertEquals("parse.codec-syntax", other.getCode());
    }

    @Test
    void jsonSyntaxErrorWithoutALocationFallsBackToOneOne() {
        DocumentParseException none = JsonCodec.syntaxError(
            new com.fasterxml.jackson.core.JsonParseException((com.fasterxml.jackson.core.JsonParser) null, "boom"));
        assertEquals("1:1", none.getPath());
        assertEquals("parse.codec-syntax", none.getCode());
    }

    @Test
    void anOverLongTomlIntegerKeepsItsDocumentLimitCode() {
        // The digit-limit pre-scan runs inside read(); its code must not be swallowed into a
        // generic parse failure.
        DocumentParseException ex = fail(TomlCodec::read, "a = " + "9".repeat(5000) + "\n");
        assertEquals("document.limit.int-digits", ex.getCode());
    }

    @Test
    void anAliasBombIsRefusedByTheLibraryWithoutAMarkAndReportedAtOneOne() {
        StringBuilder sb = new StringBuilder("l0: &a0\n");
        for (int i = 0; i < 10; i++) sb.append("  k").append(i).append(": x\n");
        for (int l = 1; l < 10; l++) {
            sb.append("l").append(l).append(": &a").append(l).append("\n");
            for (int i = 0; i < 10; i++) sb.append("  k").append(i).append(": *a").append(l - 1).append("\n");
        }
        // SnakeYAML's own cap (50 aliases to collections) refuses this before any expansion. The
        // spec's code for it is document.limit.alias-expansion (D-18), which this port does not
        // implement yet (DIV-3); until it does, the refusal is parse.codec-syntax and, crucially,
        // nothing is expanded.
        DocumentParseException ex = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> fail(YamlCodec::read, sb.toString()));
        assertEquals("parse.codec-syntax", ex.getCode());
        assertEquals("1:1", ex.getPath());
    }

    @Test
    void aDoctypeWithCrLfLineEndingsIsStillRefusedAndKeepsItsLines() {
        assertRefusal("<!DOCTYPE d [\r\n]>\r\n<d/>", "format.dtd-forbidden");
        assertMalformed("<!DOCTYPE d [\r\n]>\r\n<d></e>", "3:6");
    }

    // ---------------------------------------------------------------- depth: D-12 / D-13

    private static String yamlNested(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ".repeat(i)).append("k:\n");
        return sb.append("  ".repeat(n)).append("v: 1\n").toString();
    }

    @Test
    void yamlNestingBetweenTheLibraryDefaultAndThePortLimitIsAccepted() {
        // SnakeYAML's own default cap (50) used to refuse these as syntax errors.
        assertNotNull(YamlCodec.read(yamlNested(60)));
        assertNotNull(YamlCodec.read(yamlNested(150)));
        assertNotNull(YamlCodec.read(yamlNested(199)));
    }

    @Test
    void yamlNestingOverThePortLimitIsADepthLimitViolation() {
        DocumentParseException ex = fail(YamlCodec::read, yamlNested(250));
        assertEquals("document.limit.depth", ex.getCode());
    }

    @Test
    void hostileTomlNestingIsADepthLimitViolationNotAStackOverflow() {
        DocumentParseException ex = assertTimeoutPreemptively(Duration.ofSeconds(20),
            () -> fail(TomlCodec::read, "a = " + "[".repeat(3000) + "]".repeat(3000)));
        assertEquals("document.limit.depth", ex.getCode());
        assertEquals("$", ex.getPath());
    }

    @Test
    void yamlAndTomlFailureMappingHelpers() {
        DocumentParseException marked = YamlCodec.syntaxError(new org.yaml.snakeyaml.error.YAMLException("no mark"));
        assertEquals("1:1", marked.getPath());
        DocumentParseException noProblemMark = YamlCodec.syntaxError(
            new org.yaml.snakeyaml.scanner.ScannerException("context", null, "problem", null));
        assertEquals("1:1", noProblemMark.getPath());
        DocumentParseException located = fail(YamlCodec::read, "a: 1\nb: [1, 2\n");
        assertNotEquals("1:1", located.getPath());

        DocumentParseException overflow = TomlCodec.unexpectedFailure(new StackOverflowError());
        assertEquals("document.limit.depth", overflow.getCode());
        assertEquals("$", overflow.getPath());
        DocumentParseException other = TomlCodec.unexpectedFailure(new AssertionError("boom"));
        assertEquals("parse.codec-syntax", other.getCode());
        assertEquals("1:1", other.getPath());
    }
}
