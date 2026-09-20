package dev.omnist;

import dev.omnist.codec.JsonCodec;
import dev.omnist.codec.TomlCodec;
import dev.omnist.codec.XmlCodec;
import dev.omnist.codec.YamlCodec;
import dev.omnist.document.Bom;
import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import dev.omnist.oml.OmlParseException;
import dev.omnist.oml.OmlReader;
import dev.omnist.oml.OmlWriter;
import dev.omnist.schema.OsdParseException;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.OsdWriter;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * omnist-spec section 2.5: D-15 (one leading U+FEFF is stripped on every read surface), D-21 (a
 * second one is rejected at 1:1 on every surface, whatever the parsing library would have done),
 * and "a U+FEFF anywhere else is ordinary content". The mark is built from a char code here, never
 * written as a raw character, so this file passes {@link NoRawByteOrderMarkTest}.
 */
class BomTest {

    private static final String BOM = String.valueOf(Bom.MARK);

    private static final String OML = "a: 1\n";
    private static final String OSD = "record R {\n    \"a\": string,\n}\nroot R\n";
    private static final String JSON = "{\"a\":1}";
    private static final String YAML = "a: 1\n";
    private static final String TOML = "a = 1\n";
    private static final String XML = "<root><a>1</a></root>";

    // ---------------------------------------------------------------- D-15: one mark is stripped

    @Test
    void oneLeadingMarkIsStrippedOnEveryReadSurface() {
        assertEquals(OmlReader.read(OML), OmlReader.read(BOM + OML));
        assertEquals(JsonCodec.read(JSON), JsonCodec.read(BOM + JSON));
        assertEquals(YamlCodec.read(YAML), YamlCodec.read(BOM + YAML));
        assertEquals(TomlCodec.read(TOML), TomlCodec.read(BOM + TOML));
        assertEquals(XmlCodec.read(XML), XmlCodec.read(BOM + XML));
        assertEquals(OsdWriter.write(OsdReader.read(OSD)), OsdWriter.write(OsdReader.read(BOM + OSD)));
    }

    @Test
    void aLoneMarkIsAnEmptyDocumentWhereEmptyIsLegal() {
        assertEquals(OmlReader.read(""), OmlReader.read(BOM));
    }

    // ---------------------------------------------------------------- D-21: a second is rejected

    @Test
    void secondLeadingMarkIsRejectedAtOneOneOnOmlAndOsdAsUnexpectedToken() {
        OmlParseException oml = assertThrows(OmlParseException.class, () -> OmlReader.read(BOM + BOM + OML));
        assertEquals("parse.unexpected-token", oml.getCode());
        assertEquals("1:1", oml.getPath());

        OsdParseException osd = assertThrows(OsdParseException.class, () -> OsdReader.read(BOM + BOM + OSD));
        assertEquals("parse.unexpected-token", osd.getCode());
        assertEquals("1:1", osd.getPath());
    }

    @Test
    void secondLeadingMarkIsRejectedAtOneOneOnEveryCodecAsCodecSyntax() {
        assertDoubledRejected(JsonCodec::read, JSON);
        assertDoubledRejected(YamlCodec::read, YAML);
        assertDoubledRejected(TomlCodec::read, TOML);
        assertDoubledRejected(XmlCodec::read, XML);
    }

    private static void assertDoubledRejected(Function<String, Document> read, String text) {
        DocumentParseException ex = assertThrows(DocumentParseException.class, () -> read.apply(BOM + BOM + text));
        assertEquals("parse.codec-syntax", ex.getCode());
        assertEquals("1:1", ex.getPath());
        // Three marks: still rejected (the strip consumes one, the second is still at offset zero).
        DocumentParseException three = assertThrows(DocumentParseException.class, () -> read.apply(BOM + BOM + BOM + text));
        assertEquals("parse.codec-syntax", three.getCode());
        assertEquals("1:1", three.getPath());
    }

    @Test
    void onlyTheMarkAtOffsetZeroOfTheRemainingTextCounts() {
        // A mark after other characters is not "a second leading mark": it is content or, where the
        // grammar has no place for it, an ordinary syntax error at its own position.
        OmlParseException oml = assertThrows(OmlParseException.class, () -> OmlReader.read(" " + BOM + OML));
        assertEquals("1:2", oml.getPath());
        DocumentParseException json = assertThrows(DocumentParseException.class, () -> JsonCodec.read(" " + BOM + JSON));
        assertEquals("parse.codec-syntax", json.getCode());
        assertEquals("1:2", json.getPath());
    }

    // ---------------------------------------------------------------- interior marks are content

    @Test
    void interiorMarkIsOrdinaryContentOnEverySurface() {
        String label = "a" + BOM + "b";
        String value = "x" + BOM + "y";
        Document expected = new Node(List.of(new Edge(label, new Scalar.StringScalar(value))));

        assertEquals(expected, OmlReader.read("\"" + label + "\": \"" + value + "\"\n"));
        assertEquals(expected, JsonCodec.read("{\"" + label + "\": \"" + value + "\"}"));
        assertEquals(expected, YamlCodec.read(label + ": " + value + "\n"));
        assertEquals(expected, TomlCodec.read("\"" + label + "\" = \"" + value + "\"\n"));
        Schema schema = OsdReader.read("record R {\n \"" + label + "\": string,\n}\nroot R\n");
        assertEquals(label, schema.records().get("R").fields().get(0).label());
    }

    @Test
    void interiorMarkInXmlTextIsPreserved() {
        Document doc = XmlCodec.read("<r><a>x" + BOM + "y</a></r>");
        String written = XmlCodec.write(doc);
        assertEquals(doc, XmlCodec.read(written));
        assertTrue(written.contains("x" + BOM + "y"));
    }

    @Test
    void theMarkIsNotWhitespaceToAnyScanner() {
        assertFalse(Character.isWhitespace(Bom.MARK));
        assertFalse(Character.isSpaceChar(Bom.MARK));
        assertFalse(BOM.matches("\\s"));
        assertEquals(1, BOM.trim().length());
        assertEquals(1, BOM.strip().length());
        assertFalse(BOM.isBlank());
        // ... so a bare label containing one is an error in OML, not an identifier.
        assertThrows(OmlParseException.class, () -> OmlReader.read("a" + BOM + "b: 1\n"));
    }

    // ---------------------------------------------------------------- writers never emit a mark

    @Test
    void noWriterEmitsALeadingMark() {
        Document doc = OmlReader.read("a: 1\nb: \"two\"\n");
        assertFalse(OmlWriter.write(doc).startsWith(BOM));
        assertFalse(JsonCodec.write(doc).startsWith(BOM));
        assertFalse(YamlCodec.write(doc).startsWith(BOM));
        assertFalse(TomlCodec.write(doc).startsWith(BOM));
        assertFalse(XmlCodec.write(OmlReader.read("root: { a: 1 }\n")).startsWith(BOM));
        assertFalse(OsdWriter.write(OsdReader.read(OSD)).startsWith(BOM));
    }

    @Test
    void aLabelBeginningWithTheMarkRoundTripsThroughEveryWriter() {
        Document doc = new Node(List.of(new Edge(BOM + "k", new Scalar.StringScalar(BOM + "v"))));

        assertEquals(doc, OmlReader.read(OmlWriter.write(doc)));
        assertEquals(doc, JsonCodec.read(JsonCodec.write(doc)));
        assertEquals(doc, TomlCodec.read(TomlCodec.write(doc)));

        // YAML: unquoted, this first key would be the second leading mark D-21 rejects, so the
        // writer must quote it, and the text it writes must not itself start with a mark.
        String yaml = YamlCodec.write(doc);
        assertFalse(yaml.startsWith(BOM));
        assertTrue(yaml.startsWith("\""), yaml);
        assertEquals(doc, YamlCodec.read(yaml));
    }

    @Test
    void aLabelBeginningWithTheMarkRoundTripsThroughTheOsdWriter() {
        Schema schema = OsdReader.read("record R {\n    \"" + BOM + "a\": string,\n}\nroot R\n");
        String written = OsdWriter.write(schema);
        assertFalse(written.startsWith(BOM));
        assertEquals(written, OsdWriter.write(OsdReader.read(written)));
    }

    // ---------------------------------------------------------------- the helper itself

    @Test
    void bomHelperStripsExactlyOne() {
        assertNull(Bom.strip(null, () -> new IllegalStateException()));
        assertEquals("", Bom.strip("", () -> new IllegalStateException()));
        assertEquals("abc", Bom.strip("abc", () -> new IllegalStateException()));
        assertEquals("abc", Bom.strip(BOM + "abc", () -> new IllegalStateException()));
        assertEquals("", Bom.strip(BOM, () -> new IllegalStateException()));
        assertEquals("a" + BOM, Bom.strip(BOM + "a" + BOM, () -> new IllegalStateException()));
        assertThrows(IllegalStateException.class, () -> Bom.strip(BOM + BOM, () -> new IllegalStateException()));
        assertThrows(IllegalStateException.class, () -> Bom.strip(BOM + BOM + "x", () -> new IllegalStateException()));
    }

    @Test
    void yamlWritesEmptyAndLineBreakStringsWithoutTreatingThemAsMarks() {
        Document doc = new Node(List.of(
            new Edge("empty", new Scalar.StringScalar("")),
            new Edge("nel", new Scalar.StringScalar("a\u0085b")),
            new Edge("plain", new Scalar.StringScalar("x"))));
        assertEquals(doc, YamlCodec.read(YamlCodec.write(doc)));
    }
}
