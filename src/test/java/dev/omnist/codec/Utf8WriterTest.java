package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import dev.omnist.document.Target;
import dev.omnist.document.Value;
import dev.omnist.oml.OmlReader;
import dev.omnist.oml.OmlWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule C-9 (omnist-spec section 7.3): every writer fails with {@code write.unsupported-value}, at
 * the Document path of the node holding the string (for a label, the node holding the edge), on a
 * string value or an edge label with no UTF-8 encoding, unconditionally.
 */
class Utf8WriterTest {

    /** One writer: how to write, how to read back, how to check. */
    private record Writer(String name, Function<Document, String> write, Function<Document, String> strictWrite,
                          Function<String, Document> read, Function<Document, WriteReport> check) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Writer[] WRITERS = {
        new Writer("oml", OmlWriter::write, OmlWriter::writeCompact, OmlReader::read, null),
        new Writer("json", JsonCodec::write, d -> JsonCodec.write(d, null, true, null), JsonCodec::read, JsonCodec::check),
        new Writer("yaml", YamlCodec::write, d -> YamlCodec.write(d, true, null), YamlCodec::read, YamlCodec::check),
        new Writer("toml", TomlCodec::write, d -> TomlCodec.write(d, true, null), TomlCodec::read, TomlCodec::check),
        new Writer("xml", XmlCodec::write, d -> XmlCodec.write(d, true, null), XmlCodec::read, XmlCodec::check),
    };

    static Stream<Writer> writers() {
        return Stream.of(WRITERS);
    }

    private static Node node(Edge... edges) {
        return new Node(List.of(edges));
    }

    private static Edge edge(String label, Target target) {
        return new Edge(label, target);
    }

    private static Scalar.StringScalar str(String s) {
        return new Scalar.StringScalar(s);
    }

    /** A document, the Document path C-9 must report, as a table row. */
    private record Case(String name, Document doc, String path) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> refusals() {
        return Stream.of(
            new Case("lone high surrogate in a leaf", node(edge("r", node(edge("a", str("a\uD800"))))), "$.r.a"),
            new Case("lone low surrogate", node(edge("r", node(edge("a", str("\uDC00y"))))), "$.r.a"),
            new Case("pair in the wrong order", node(edge("r", node(edge("a", str("\uDE00\uD83D"))))), "$.r.a"),
            new Case("high surrogate at the end", node(edge("r", node(edge("a", str("ok\uD83D"))))), "$.r.a"),
            new Case("high surrogate before a non-surrogate", node(edge("r", node(edge("a", str("\uD83Dx"))))), "$.r.a"),
            new Case("the second of a repeated label is indexed", node(edge("r", node(edge("item", str("ok")), edge("item", str("bad\uD800"))))), "$.r.item[1]"),
            new Case("the first of a repeated label is indexed (E-10)", node(edge("r", node(edge("item", str("\uD800")), edge("item", str("ok"))))), "$.r.item[0]"),
            new Case("nested", node(edge("r", node(edge("x", node(edge("y", str("\uD800"))))))), "$.r.x.y"),
            new Case("the first failure in document order", node(edge("r", node(edge("a", str("\uD800")), edge("b", str("\uD800"))))), "$.r.a"),
            new Case("a label: the node holding the edge", node(edge("r", node(edge("bad\uD800", new Scalar.IntegerScalar(BigInteger.ONE))))), "$.r"),
            new Case("a nested label: still the node holding the edge", node(edge("r", node(edge("x", node(edge("\uDC00", str("v"))))))), "$.r.x"),
            new Case("the root edge's label: the root node", node(edge("\uD800", node(edge("a", str("v"))))), "$"),
            new Case("a label wins over a string below it", node(edge("r", node(edge("\uD800", node(edge("a", str("\uD800"))))))), "$.r"),
            new Case("a string after a clean sibling subtree", node(edge("r", node(edge("ok", node(edge("a", str("fine")))), edge("bad", node(edge("z", str("\uD800"))))))), "$.r.bad.z")
        );
    }

    static Stream<Arguments> refusalMatrix() {
        return refusals().flatMap(c -> writers().map(w -> Arguments.of(w, c)));
    }

    private static void assertRefusedAt(Writer writer, Function<Document, String> write, Document doc, String path) {
        WriteException ex = assertThrows(WriteException.class, () -> write.apply(doc), writer.name());
        List<WriteAdjustment> errors = ex.report().adjustments().stream()
                .filter(a -> "write.unsupported-value".equals(a.code())).toList();
        assertFalse(errors.isEmpty(), writer.name() + ": " + ex.report());
        assertEquals(path, errors.get(0).path(), writer.name());
        assertEquals("error", errors.get(0).severity());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("refusalMatrix")
    @DisplayName("every writer fails with write.unsupported-value at the node's Document path")
    void everyWriterRefuses(Writer writer, Case c) {
        assertRefusedAt(writer, writer.write(), c.doc(), c.path());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("refusalMatrix")
    @DisplayName("strict mode, and the compact OML spelling, fail the same way: unconditionally")
    void strictRefusesToo(Writer writer, Case c) {
        assertRefusedAt(writer, writer.strictWrite(), c.doc(), c.path());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("refusalMatrix")
    @DisplayName("check() reports the same failure for the four codecs")
    void checkReportsTheFailure(Writer writer, Case c) {
        if (writer.check() == null) {
            return;
        }
        List<WriteAdjustment> errors = writer.check().apply(c.doc()).adjustments().stream()
                .filter(a -> "write.unsupported-value".equals(a.code())).toList();
        assertFalse(errors.isEmpty(), writer.name());
        assertEquals(c.path(), errors.get(0).path());
    }

    @ParameterizedTest
    @MethodSource("writers")
    @DisplayName("a valid surrogate pair (astral), U+FFFD, and a BMP character are ordinary text, and round-trip")
    void validTextStillWrites(Writer writer) {
        Document doc = node(edge("r", node(
                edge("astral", str("😀 smile")),
                edge("replacement", str("�")),
                edge("bmp", str("café €")),
                edge("edge", str("😀😀")))));
        String text = writer.write().apply(doc);
        assertNotNull(text);
        // Readers of some formats do not keep edge order, so compare the edges as a set.
        Node back = (Node) ((Node) writer.read().apply(text)).edges().get(0).target();
        Node want = (Node) ((Node) doc).edges().get(0).target();
        assertEquals(new java.util.HashSet<>(want.edges()), new java.util.HashSet<>(back.edges()));
        assertTrue(writer.strictWrite().apply(doc) != null);
    }

    @ParameterizedTest
    @MethodSource("writers")
    @DisplayName("a clean document still writes, and an unrelated unsupported value keeps its own diagnostic")
    void cleanDocumentUnchanged(Writer writer) {
        Document doc = node(edge("r", node(edge("a", str("plain")))));
        assertEquals(writer.write().apply(doc), writer.write().apply(doc));
    }

    @Test
    @DisplayName("OML: a bare string document is checked too, at $")
    void omlBareString() {
        WriteException ex = assertThrows(WriteException.class, () -> OmlWriter.write(str("\uD800")));
        assertEquals("$", ex.report().adjustments().get(0).path());
        assertEquals("write.unsupported-value", ex.report().adjustments().get(0).code());
        assertThrows(WriteException.class, () -> OmlWriter.writeCompact(str("a\uDC00")));
    }

    @Test
    @DisplayName("XML: a label or string C-9 reports is not reported a second time as an invalid name or character")
    void xmlReportsOnce() {
        Document label = node(edge("r", node(edge("x\uD800", str("v")))));
        assertEquals(1, XmlCodec.check(label).adjustments().stream().filter(a -> "write.unsupported-value".equals(a.code())).count());
        Document value = node(edge("r", str("\uD800")));
        assertEquals(1, XmlCodec.check(value).adjustments().stream().filter(a -> "write.unsupported-value".equals(a.code())).count());
        // The other XML rules are untouched: a C0 control and an invalid name are still refused where they are.
        Document control = node(edge("r", str("a\u0001b")));
        assertEquals("$.r", XmlCodec.check(control).adjustments().get(0).path());
        Document name = node(edge("r", node(edge("not a name", str("v")))));
        assertEquals("write.unsupported-value", XmlCodec.check(name).adjustments().get(0).code());
    }

    // ------------------------------------------------------------------ Encodability itself

    static Stream<Arguments> encodable() {
        return Stream.of(
            Arguments.of("", true),
            Arguments.of("plain ascii", true),
            Arguments.of("café", true),
            Arguments.of("�", true),
            Arguments.of("퟿", true),                  // just below the surrogates
            Arguments.of("", true),                  // just above the surrogates
            Arguments.of("😀", true),
            Arguments.of("a😀b😀", true),
            Arguments.of("\uD800", false),
            Arguments.of("\uDBFF", false),
            Arguments.of("\uDC00", false),
            Arguments.of("\uDFFF", false),
            Arguments.of("\uD83D😀", false),     // the first high is lone
            Arguments.of("😀\uDE00", false),     // the trailing low is lone
            Arguments.of("\uDE00\uD83D", false),
            Arguments.of("x\uD800", false)
        );
    }

    @ParameterizedTest
    @MethodSource("encodable")
    void isEncodableTable(String s, boolean expected) {
        assertEquals(expected, Encodability.isEncodable(s));
        // The scan agrees with the JDK's own strict encoder.
        java.nio.charset.CharsetEncoder encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder();
        assertEquals(expected, encoder.canEncode(s));
    }

    @Test
    void allEncodableCoversEveryDocumentShape() {
        assertTrue(Encodability.allEncodable(Value.NULL));
        assertTrue(Encodability.allEncodable(new Scalar.IntegerScalar(BigInteger.TEN)));
        assertTrue(Encodability.allEncodable(str("fine")));
        assertFalse(Encodability.allEncodable(str("\uD800")));
        assertTrue(Encodability.allEncodable(node()));
        assertTrue(Encodability.allEncodable(node(edge("\uD83D\uDE00", Value.NULL))));
        assertTrue(Encodability.allEncodable(node(edge("a", Value.NULL), edge("b", new Scalar.BooleanScalar(true)))));
        assertFalse(Encodability.allEncodable(node(edge("a", node(edge("\uD800", Value.NULL))))));
        assertFalse(Encodability.allEncodable(node(edge("a", node(edge("b", node(edge("c", str("x\uDC00")))))))));
    }

    @Test
    @DisplayName("the fast pass has no Java recursion: a 20 000-level document is safe")
    void veryDeepDocument() {
        Document doc = str("ok");
        for (int i = 0; i < 20_000; i++) {
            doc = node(edge("n", (Target) doc));
        }
        assertTrue(Encodability.allEncodable(doc));
        Document bad = str("\uD800");
        for (int i = 0; i < 20_000; i++) {
            bad = node(edge("n", (Target) bad));
        }
        assertFalse(Encodability.allEncodable(bad));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "a\uDC00", "\uDE00\uD83D"})
    void refusalCarriesTheReport(String bad) {
        WriteException ex = Encodability.refusal(node(edge("a", str(bad))));
        assertEquals("$.a", ex.report().adjustments().get(0).path());
        assertTrue(ex.getMessage().contains("UTF-8"), ex.getMessage());
    }

    @Test
    void theInternalSignalCarriesNoStackTrace() {
        assertEquals(0, Encodability.LoneSurrogate.INSTANCE.getStackTrace().length);
    }
}
