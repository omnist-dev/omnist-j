package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.IntegerScalar;
import dev.omnist.document.Scalar.StringScalar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TOML reader: the scan that leaves keys, table headers and strings alone, long integers, and
 * edges in source order (D-1).
 */
class TomlReaderV034Test {

    private static Node root(Document d) {
        return (Node) d;
    }

    @Test
    @DisplayName("a number-shaped TOML key or table name is a name, not a value: it is never rewritten")
    void tomlNumberShapedNamesAreNotRewritten() {
        Node r = root(TomlCodec.read("123456789012345678901 = 1\n1e999 = 2\n[7e400]\nx = 3\n"));
        assertEquals(List.of("123456789012345678901", "1e999", "7e400"),
                r.edges().stream().map(Edge::label).toList());
    }

    @Test
    @DisplayName("a 25-digit integer in an inline table, and in an array of tables, is read as an integer")
    void tomlLongIntegersInsideTablesAreRead() {
        Node t = root(TomlCodec.read("t = {a = 1, b = 123456789012345678901234567}\n"));
        Node inline = (Node) t.edges().get(0).target();
        assertEquals(new IntegerScalar(new BigInteger("123456789012345678901234567")), inline.edges().get(1).target());
        // a nested array on a line of its own is not a table header: it is refused as an array of arrays
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> TomlCodec.read("a = [\n  [123456789012345678901234567],\n  [2]\n]\n"));
        assertEquals("document.unlabeled-element", e.getCode());
    }

    @Test
    @DisplayName("a TOML string that looks like the reader's own wrapper for a long integer stays a string")
    void tomlStringsStayStrings() {
        Node r = root(TomlCodec.read("a = \"__omnist_int__12345678901234567890123\"\nb = 12345678901234567890123\n"));
        assertEquals(new StringScalar("__omnist_int__12345678901234567890123"), r.edges().get(0).target());
        assertEquals(new IntegerScalar(new BigInteger("12345678901234567890123")), r.edges().get(1).target());
    }

    @Test
    @DisplayName("a signed long TOML integer keeps its sign; a hex literal with many leading zeros is its value")
    void tomlSignedLongIntegers() {
        Node r = root(TomlCodec.read("b = -12345678901234567890123\nc = +12345678901234567890123\nd = 0x0000000000000000000000000ff\n"));
        assertEquals(new IntegerScalar(new BigInteger("-12345678901234567890123")), r.edges().get(0).target());
        assertEquals(new IntegerScalar(new BigInteger("12345678901234567890123")), r.edges().get(1).target());
        assertEquals(new IntegerScalar(BigInteger.valueOf(255)), r.edges().get(2).target());
    }

    @Test
    @DisplayName("a TOML key containing a dot is one label (D-1: edges are read in source order, fetched by key)")
    void tomlDottedQuotedKeyIsOneLabel() {
        Node r = root(TomlCodec.read("\"b.c\" = 1\na = 2\n"));
        assertEquals(List.of("b.c", "a"), r.edges().stream().map(Edge::label).toList());
    }

    @Test
    @DisplayName("TOML edges keep the order the keys were written in, native temporals included")
    void tomlKeepsSourceOrder() {
        Node r = root(TomlCodec.read("z = 1\nm = 2\na = 3\n[t]\nq = 1\nb = 2\n"));
        assertEquals(List.of("z", "m", "a", "t"), r.edges().stream().map(Edge::label).toList());
        Node t = (Node) r.edges().get(3).target();
        assertEquals(List.of("q", "b"), t.edges().stream().map(Edge::label).toList());
        // two labels that differ only in Unicode normalization form stay distinct and in order (D-16)
        Node nf = root(TomlCodec.read("\"café\" = 1\n\"café\" = 2\n"));
        assertEquals(List.of("café", "café"), nf.edges().stream().map(Edge::label).toList());
    }

    @Test
    @DisplayName("an edge sits where it was written: every codec reads edges in source order")
    void edgeOrderIsSourceOrder() {
        Node json = (Node) JsonCodec.read("{\"b\": 1, \"a\": 2}");
        assertEquals("b", json.edges().get(0).label());
        Node yaml = (Node) YamlCodec.read("b: 1\na: 2\n");
        assertEquals("b", yaml.edges().get(0).label());
        Node toml = (Node) TomlCodec.read("b = 1\na = 2\nd = 2024-01-01\nt = 12:00:00\ndt = 2024-01-01T12:30:00\n");
        assertEquals(List.of("b", "a", "d", "t", "dt"), toml.edges().stream().map(Edge::label).toList());
    }
}
