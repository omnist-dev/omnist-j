package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.IntegerScalar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * C-12 (a repeated YAML key is rejected), C-13 (the YAML collection tags are refused) and D-27 (a tagged
 * merge key), with the neighbouring JSON (C-11) and TOML duplicate-key behaviour.
 */
class YamlRejectionsTest {

    private static Node root(Document d) {
        return (Node) d;
    }

    private static DocumentParseException yamlRefusal(String text) {
        return assertThrows(DocumentParseException.class, () -> YamlCodec.read(text), text);
    }

    // ---- C-12: a repeated key is rejected ----

    @ParameterizedTest
    @ValueSource(strings = {
        "a: 1\nb: 2\na: 3\n",
        "{a: 1, a: 2}\n",
        "o:\n  k: 1\n  k: 2\n",
        "m:\n  - {a: 1, a: 2}\n",
        "a: 1\n\"a\": 2\n",
        "a: 1\na: [2, 3]\n"
    })
    @DisplayName("C-12: a YAML mapping that repeats a key is parse.codec-syntax at a text position")
    void yamlRepeatedKeyIsRejected(String text) {
        DocumentParseException e = yamlRefusal(text);
        assertEquals("parse.codec-syntax", e.getCode());
        assertTrue(e.getPath().matches("[1-9][0-9]*:[1-9][0-9]*"), e.getPath());
    }

    @Test
    @DisplayName("C-12: the same key in two mappings, and keys differing in case, are not repeats")
    void yamlDistinctKeysAreAccepted() {
        assertEquals(2, root(YamlCodec.read("x: {a: 1}\ny: {a: 2}\n")).edges().size());
        assertEquals(2, root(YamlCodec.read("a: 1\nA: 2\n")).edges().size());
    }

    @Test
    @DisplayName("C-12: a merge key's own override is not a repeated key")
    void yamlMergeOverrideIsNotARepeat() {
        Node r = root(YamlCodec.read("p: &p {x: 1, y: 2}\nq: {<<: *p, x: 9}\n"));
        Node q = (Node) r.edges().get(1).target();
        assertEquals(new IntegerScalar(BigInteger.valueOf(9)),
                q.edges().stream().filter(e -> e.label().equals("x")).findFirst().orElseThrow().target());
    }

    @Test
    @DisplayName("C-11: a repeated JSON key keeps the first position and the last value")
    void jsonRepeatedKeyKeepsFirstPositionLastValue() {
        Node r = root(JsonCodec.read("{\"a\": 1, \"b\": 2, \"a\": 3}"));
        assertEquals(List.of("a", "b"), r.edges().stream().map(Edge::label).toList());
        assertEquals(new IntegerScalar(BigInteger.valueOf(3)), r.edges().get(0).target());
    }

    @Test
    @DisplayName("a repeated TOML key or table is rejected")
    void tomlRepeatedKeyIsRejected() {
        DocumentParseException e = assertThrows(DocumentParseException.class, () -> TomlCodec.read("a = 1\na = 2\n"));
        assertEquals("parse.codec-syntax", e.getCode());
    }

    // ---- C-13: !!omap, !!pairs and !!set are refused ----

    @ParameterizedTest
    @ValueSource(strings = {
        "t: !!omap [{k: 1}, {j: 2}]\n",
        "t: !!pairs [{k: 1}, {k: 2}]\n",
        "t: !!set {x, y}\n",
        "t: !!set\n  ? x\n  ? y\n",
        "t: !!omap []\n",
        "!!omap [{k: 1}]\n",
        "a: &s !!set {x}\nb: *s\n",
        "a:\n  - b:\n      - !!pairs [{k: 1}]\n"
    })
    @DisplayName("C-13: a YAML !!omap, !!pairs or !!set is format.tag-forbidden at $, never an uncoded exception")
    void yamlCollectionTagsAreForbidden(String text) {
        DocumentParseException e = yamlRefusal(text);
        assertEquals("format.tag-forbidden", e.getCode());
        assertEquals("$", e.getPath());
    }

    @Test
    @DisplayName("C-13: an untagged sequence of single-entry mappings still reads as repeated edges")
    void untaggedSequenceOfMappingsStillReads() {
        Node r = root(YamlCodec.read("t:\n  - {k: 1}\n  - {j: 2}\n"));
        assertEquals(2, r.edges().size());
    }

    // ---- D-27: a tagged merge key ----

    @Test
    @DisplayName("D-27: !!str << is an ordinary key; !!merge << is a merge key")
    void taggedMergeKeys() {
        Node plain = (Node) root(YamlCodec.read("q: {!!str <<: 2}\n")).edges().get(0).target();
        assertEquals("<<", plain.edges().get(0).label());
        Node merged = (Node) root(YamlCodec.read("p: &p {x: 1}\nq: {!!merge <<: *p, y: 2}\n")).edges().get(1).target();
        assertEquals(List.of("x", "y"), merged.edges().stream().map(Edge::label).toList());
        assertEquals("parse.codec-syntax", yamlRefusal("q: {!!merge <<: 1}\n").getCode());
    }
}
