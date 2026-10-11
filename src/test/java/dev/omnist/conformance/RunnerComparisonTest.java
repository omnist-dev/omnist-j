package dev.omnist.conformance;

import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.IntegerScalar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the runners compare (omnist-spec 8.5.2, 8.5.3, DIV-33): a parse result with edge order
 * significant (D-1, omnist-j#126), a {@code write} vector's text byte for byte, a lint finding's
 * {@code code} exactly (omnist-j#125), and OML's {@code compact} key (E-35).
 */
class RunnerComparisonTest {

    private static Document doc(String... labels) {
        return new Node(java.util.Arrays.stream(labels)
                .map(l -> new Edge(l, new IntegerScalar(BigInteger.ONE))).toList());
    }

    private static int[] run(Path dir, String vectors) throws Exception {
        Files.writeString(dir.resolve("v.json"), "{\"vectors\": [" + vectors + "]}");
        return Track2Runner.runTrack2(dir);
    }

    private static String vector(String name, String op, String input, String expect) {
        return "{\"name\":\"" + name + "\",\"operation\":\"" + op + "\",\"input\":" + input + ",\"expect\":" + expect + "}";
    }

    private static String edges(String... labels) {
        StringBuilder sb = new StringBuilder("{\"edges\":[");
        for (int i = 0; i < labels.length; i++) {
            sb.append(i == 0 ? "" : ",").append("[\"").append(labels[i])
                    .append("\",{\"scalar\":{\"kind\":\"integer\",\"value\":1}}]");
        }
        return sb.append("]}").toString();
    }

    // ---- omnist-j#126: edge order is significant ----

    @Test
    @DisplayName("self-test: two Documents that differ only in edge order are different")
    void documentsDifferingOnlyInEdgeOrderAreNotEqual() {
        assertTrue(Track2Runner.documentsEqual(doc("a", "b"), doc("a", "b")));
        assertFalse(Track2Runner.documentsEqual(doc("a", "b"), doc("b", "a")));
        Document nestedAB = new Node(List.of(new Edge("o", (dev.omnist.document.Target) doc("a", "b"))));
        Document nestedBA = new Node(List.of(new Edge("o", (dev.omnist.document.Target) doc("b", "a"))));
        assertFalse(Track2Runner.documentsEqual(nestedAB, nestedBA));
        // repeated labels: the order of the repeats is order too
        assertFalse(Track2Runner.documentsEqual(
                new Node(List.of(new Edge("a", new IntegerScalar(BigInteger.ONE)), new Edge("a", new IntegerScalar(BigInteger.TWO)))),
                new Node(List.of(new Edge("a", new IntegerScalar(BigInteger.TWO)), new Edge("a", new IntegerScalar(BigInteger.ONE))))));
    }

    @Test
    @DisplayName("a parse vector whose expected Document has the edges in another order FAILS")
    void aParseVectorWithTheEdgesInAnotherOrderFails(@TempDir Path dir) throws Exception {
        String input = "{\"format\":\"json\",\"text\":\"{\\\"a\\\":1,\\\"b\\\":1}\"}";
        int[] results = run(dir,
                vector("same-order", "parse", input, "{\"ok\":true,\"document\":" + edges("a", "b") + "}") + ","
                + vector("other-order", "parse", input, "{\"ok\":true,\"document\":" + edges("b", "a") + "}"));
        assertEquals(1, results[0]);
        assertEquals(1, results[1]);
    }

    // ---- the written text is compared byte for byte ----

    @Test
    @DisplayName("a write vector's text is compared as written, not by re-parsing: a hex-case difference fails")
    void writeTextIsByteExact(@TempDir Path dir) throws Exception {
        String doc = "{\"document\":{\"edges\":[[\"a\",{\"scalar\":{\"kind\":\"string\",\"value\":\"a\\u001fb\"}}]]},\"format\":\"json\"}";
        int[] results = run(dir,
                vector("lowercase", "write", doc, "{\"ok\":true,\"text\":\"{\\\"a\\\":\\\"a\\\\u001fb\\\"}\"}") + ","
                + vector("uppercase", "write", doc, "{\"ok\":true,\"text\":\"{\\\"a\\\":\\\"a\\\\u001Fb\\\"}\"}"));
        assertEquals(1, results[0]);
        assertEquals(1, results[1]);
    }

    @Test
    @DisplayName("an OML write vector with a different layout fails; the outer whitespace is trimmed")
    void omlWriteLayoutIsByteExact(@TempDir Path dir) throws Exception {
        String doc = "{\"document\":" + edges("a") + ",\"format\":\"oml\"}";
        int[] results = run(dir,
                vector("exact", "write", doc, "{\"ok\":true,\"text\":\"a: 1\\n\"}") + ","
                + vector("outer-whitespace", "write", doc, "{\"ok\":true,\"text\":\"\\n a: 1  \"}") + ","
                + vector("inner-spacing", "write", doc, "{\"ok\":true,\"text\":\"a:  1\\n\"}"));
        assertEquals(2, results[0]);
        assertEquals(1, results[1]);
    }

    @Test
    @DisplayName("E-35: compact is passed to the OML writer, and a vector that asks for it is compared compact")
    void compactIsPassedToTheOmlWriter(@TempDir Path dir) throws Exception {
        String twoEdges = "{\"document\":" + edges("a", "b") + ",\"format\":\"oml\",\"compact\":true}";
        String compactText = dev.omnist.oml.OmlWriter.writeCompact(doc("a", "b"));
        assertFalse(compactText.contains("\n"), compactText);
        int[] results = run(dir,
                vector("compact", "write", twoEdges, "{\"ok\":true,\"text\":\"" + compactText + "\"}") + ","
                + vector("expanded-text-for-a-compact-request", "write", twoEdges, "{\"ok\":true,\"text\":\"a: 1\\nb: 1\\n\"}"));
        assertEquals(1, results[0]);
        assertEquals(1, results[1]);
    }

    @Test
    @DisplayName("E-18: XML inter-tag whitespace is ignored; JSON layout is the writer's choice but its string contents are not")
    void normalizationOfXmlAndJson() {
        assertEquals("<r><a>x y</a></r>", Track2Runner.normalizeWriteText("<r>\n  <a>x y</a>\n</r>\n", "xml"));
        assertEquals("{\"a\":[1,2],\"b\":\"x  y\"}",
                Track2Runner.normalizeWriteText("{\"a\": [1, 2],\n \"b\": \"x  y\"}", "json"));
        assertEquals("{\"a\":\"q\\\"  \\\\\",\"b\":1}",
                Track2Runner.normalizeWriteText("{ \"a\" : \"q\\\"  \\\\\" , \"b\" : 1 }", "json"));
        assertEquals("a:  1", Track2Runner.normalizeWriteText("\na:  1\n", "toml"));
    }

    // ---- omnist-j#125: lint codes are compared exactly ----

    @Test
    @DisplayName("Track 1 compares a lint finding's code exactly: the code with the lint. prefix passes, any other spelling fails")
    void track1LintCodeIsExact(@TempDir Path tmp) throws Exception {
        Path fixtures = tmp.resolve("fixtures");
        String schema = "record Orphan {\n    \"x\": string,\n}\nrecord R {\n    \"n\": integer,\n}\nroot R\n";
        String[][] cases = {
            {"exact", "lint.unreachable-record"},
            {"without-prefix", "unreachable-record"},
            {"wrong-code", "lint.something-else"},
        };
        for (String[] c : cases) {
            Path dir = fixtures.resolve("lint/" + c[0]);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("purpose.txt"), c[0] + "\n");
            Files.writeString(dir.resolve("input.osd"), schema);
            Files.writeString(dir.resolve("expected.json"), "{\"ok\": false, \"findings\": [{\"code\": \"" + c[1]
                    + "\", \"severity\": \"warning\", \"location\": \"Orphan\", \"message\": \"x\"}]}");
        }
        int[] results = Track1Runner.runTrack1(fixtures, tmp);
        assertEquals(1, results[0], "only the exact code passes");
        assertEquals(2, results[1]);
    }
}
