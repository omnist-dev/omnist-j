package dev.omnist.oml;

import dev.omnist.document.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OmlWriterTest {

    @Test
    @DisplayName("Full round-trip fidelity: all 7 scalar kinds + null + nesting + repeated labels")
    void testFullRoundTripAllScalarKinds() {
        Node address = new Node(List.of(
                new Edge("city", new Scalar.StringScalar("Zurich")),
                new Edge("postcode", new Scalar.StringScalar("8001"))
        ));

        Node doc = new Node(List.of(
                new Edge("str", new Scalar.StringScalar("hello world")),
                new Edge("num_int", new Scalar.IntegerScalar(BigInteger.valueOf(42))),
                new Edge("num_float", new Scalar.NumberScalar(3.14159)),
                new Edge("flag", new Scalar.BooleanScalar(true)),
                new Edge("d", new Scalar.DateScalar(LocalDate.of(2026, 8, 10))),
                new Edge("t", new Scalar.TimeScalar(TimeValue.of(LocalTime.of(12, 30, 45), ZoneOffset.ofHours(2)))),
                new Edge("dt", new Scalar.DateTimeScalar(DateTimeValue.of(LocalDateTime.of(2026, 8, 10, 12, 30, 45), ZoneOffset.UTC))),
                new Edge("empty", Value.NULL),
                new Edge("address", address),
                new Edge("tag", new Scalar.StringScalar("x")),
                new Edge("tag", new Scalar.StringScalar("y"))
        ));

        String canonicalStr = OmlWriter.write(doc);
        Document readBackCanonical = OmlReader.read(canonicalStr);
        assertEquals(doc, readBackCanonical, "Canonical OML write round-trip failed");

        String compactStr = OmlWriter.writeCompact(doc);
        Document readBackCompact = OmlReader.read(compactStr);
        assertEquals(doc, readBackCompact, "Compact OML write round-trip failed");
    }

    @Test
    @DisplayName("Whole-number NumberScalar (3.0) round-trips as NumberScalar, not IntegerScalar")
    void testWholeNumberScalarRoundTrip() {
        Node doc = new Node(List.of(
                new Edge("val", new Scalar.NumberScalar(3.0))
        ));

        String written = OmlWriter.write(doc);
        assertTrue(written.contains("3.0"), "Formatted double 3.0 should contain .0");

        Document readBack = OmlReader.read(written);
        assertInstanceOf(Node.class, readBack);
        Node readNode = (Node) readBack;
        assertEquals(1, readNode.edges().size());

        Target target = readNode.edges().get(0).target();
        assertInstanceOf(Scalar.NumberScalar.class, target, "3.0 should re-tokenize as NumberScalar, not IntegerScalar");
        Scalar.NumberScalar ns = (Scalar.NumberScalar) target;
        assertEquals(3.0, ns.value());
    }

    @Test
    @DisplayName("Label-quoting asymmetry (§4.4): 'nan' writes quoted, ordinary label writes bare")
    void testLabelQuotingAsymmetry() {
        Node doc = new Node(List.of(
                new Edge("nan", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
                new Edge("null", new Scalar.IntegerScalar(BigInteger.valueOf(2))),
                new Edge("ordinary", new Scalar.IntegerScalar(BigInteger.valueOf(3)))
        ));

        String written = OmlWriter.write(doc);
        assertTrue(written.contains("\"nan\": 1"), "Label 'nan' must be quoted in output");
        assertTrue(written.contains("\"null\": 2"), "Label 'null' must be quoted in output");
        assertTrue(written.contains("ordinary: 3"), "Ordinary label must be written bare");

        Document readBack = OmlReader.read(written);
        assertEquals(doc, readBack, "Quoted label document round-trip failed");
    }

    @Test
    @DisplayName("Bare-value Document (scalar or null with no Node wrapper) writes and round-trips correctly")
    void testBareValueDocumentRoundTrip() {
        Document bareStr = new Scalar.StringScalar("hello");
        assertEquals(bareStr, OmlReader.read(OmlWriter.write(bareStr)));

        Document bareInt = new Scalar.IntegerScalar(BigInteger.valueOf(42));
        assertEquals(bareInt, OmlReader.read(OmlWriter.write(bareInt)));

        Document bareNull = Value.NULL;
        assertEquals(bareNull, OmlReader.read(OmlWriter.write(bareNull)));
    }

    @Test
    @DisplayName("String escaping canonical processing")
    void testStringEscaping() {
        Node doc = new Node(List.of(
                new Edge("text", new Scalar.StringScalar("line1\nline2\ttab\"quote\\slash"))
        ));

        String written = OmlWriter.write(doc);
        Document readBack = OmlReader.read(written);
        assertEquals(doc, readBack, "String escape round-trip failed");
    }

    @Test
    @DisplayName("A label with characters outside IDENT_PATTERN (not just a reserved word) writes quoted")
    void testNonIdentLabelWritesQuoted() {
        Node doc = new Node(List.of(new Edge("has space", new Scalar.IntegerScalar(BigInteger.ONE))));
        String written = OmlWriter.write(doc);
        assertTrue(written.contains("\"has space\": 1"));
        assertEquals(doc, OmlReader.read(written));
    }

    @Test
    @DisplayName("An empty nested node writes as {} at any nesting depth (writeCompact and canonical write)")
    void testEmptyNestedNodeAtDepth() {
        Document doc = new Node(List.of(
            new Edge("a", new Node(List.of(new Edge("empty", new Node(List.of())))))
        ));
        String compact = OmlWriter.writeCompact(doc);
        assertTrue(compact.contains("empty: {}"));
        assertEquals(doc, OmlReader.read(compact));

        String canonical = OmlWriter.write(doc);
        assertTrue(canonical.contains("{}"));
        assertEquals(doc, OmlReader.read(canonical));
    }

    @Test
    @DisplayName("Issue #42: labels starting with digits or hyphens write quoted and round-trip correctly")
    void testDigitAndHyphenFirstLabelsWriteQuoted() {
        List<String> labelsToTest = List.of("1abc", "-foo", "123", "-", "-1", "0", "0xyz", "_valid", "valid_1", "true", "nan", "inf", "null");
        for (String label : labelsToTest) {
            Node doc = new Node(List.of(new Edge(label, new Scalar.IntegerScalar(BigInteger.valueOf(42)))));
            String written = OmlWriter.write(doc);
            if (label.equals("_valid") || label.equals("valid_1")) {
                assertTrue(written.contains(label + ": 42"), "Expected bare label for " + label + ", got: " + written);
            } else {
                assertTrue(written.contains("\"" + label + "\": 42"), "Expected quoted label for " + label + ", got: " + written);
            }
            Document roundtripped = OmlReader.read(written);
            assertEquals(doc, roundtripped, "Round-trip failed for label: " + label);
        }
    }

    @Test
    @DisplayName("Property-style roundtrip test: read(write(doc)).equals(doc) over many label variations")
    void testPropertyStyleLabelRoundTrip() {
        String[] prefixes = {"", "a", "_", "1", "-", "Z", "9", "-a", "1_"};
        String[] middles = {"", "abc", "123", "-_-", "true", "null", "nan", "inf", "false"};
        String[] suffixes = {"", "x", "0", "_", "-", ":", " "};

        for (String p : prefixes) {
            for (String m : middles) {
                for (String s : suffixes) {
                    String label = p + m + s;
                    Node doc = new Node(List.of(new Edge(label, new Scalar.StringScalar("val"))));
                    String written = OmlWriter.write(doc);
                    Document readBack = OmlReader.read(written);
                    assertEquals(doc, readBack, "Round-trip failed for label: [" + label + "]");
                }
            }
        }
    }
}
