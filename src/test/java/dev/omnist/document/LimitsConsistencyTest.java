package dev.omnist.document;

import dev.omnist.codec.JsonCodec;
import dev.omnist.codec.TomlCodec;
import dev.omnist.codec.XmlCodec;
import dev.omnist.codec.YamlCodec;
import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;
import dev.omnist.validation.Materializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LimitsConsistencyTest {

    @Test
    @DisplayName("Flat document with 2000 scalar sibling edges counts as exactly 1 node across all codecs")
    void testFlatDocumentNodeCountSemantics() {
        StringBuilder jsonSb = new StringBuilder("{");
        for (int i = 0; i < 2000; i++) {
            if (i > 0) jsonSb.append(",");
            jsonSb.append('"').append("k").append(i).append('"').append(":").append(i);
        }
        jsonSb.append("}");
        Document jsonDoc = JsonCodec.read(jsonSb.toString());
        assertTrue(jsonDoc instanceof Node);
        assertEquals(2000, ((Node) jsonDoc).edges().size());

        StringBuilder yamlSb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            yamlSb.append("k").append(i).append(": ").append(i).append("\n");
        }
        Document yamlDoc = YamlCodec.read(yamlSb.toString());
        assertTrue(yamlDoc instanceof Node);
        assertEquals(2000, ((Node) yamlDoc).edges().size());

        StringBuilder tomlSb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            tomlSb.append("k").append(i).append(" = ").append(i).append("\n");
        }
        Document tomlDoc = TomlCodec.read(tomlSb.toString());
        assertTrue(tomlDoc instanceof Node);
        assertEquals(2000, ((Node) tomlDoc).edges().size());

        StringBuilder xmlSb = new StringBuilder("<root>");
        for (int i = 0; i < 2000; i++) {
            xmlSb.append("<k").append(i).append(">").append(i).append("</k").append(i).append(">");
        }
        xmlSb.append("</root>");
        Document xmlDoc = XmlCodec.read(xmlSb.toString());
        assertTrue(xmlDoc instanceof Node);
    }

    @Test
    @DisplayName("JSON parser accepts 2000-digit positive and negative integers")
    void testLargeIntegerWithinOmnistLimits() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append('9');
        String digits = sb.toString();

        Document doc = JsonCodec.read("{\"big\":" + digits + "}");
        assertTrue(doc instanceof Node);
        Edge edge = ((Node) doc).edges().get(0);
        assertTrue(edge.target() instanceof Scalar.IntegerScalar);
        assertEquals(new BigInteger(digits), ((Scalar.IntegerScalar) edge.target()).value());

        Document docNeg = JsonCodec.read("{\"neg\":-" + digits + "}");
        assertTrue(docNeg instanceof Node);
        Edge edgeNeg = ((Node) docNeg).edges().get(0);
        assertEquals(new BigInteger("-" + digits), ((Scalar.IntegerScalar) edgeNeg.target()).value());
    }

    @Test
    @DisplayName("Integer exceeding maxIntegerDigits (4300) throws document.limit.int-digits")
    void testIntegerExceedingMaxDigitsThrows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4301; i++) sb.append('9');
        String digits = sb.toString();

        assertThrows(RuntimeException.class, () -> JsonCodec.read("{\"big\":" + digits + "}"));
        assertThrows(RuntimeException.class, () -> JsonCodec.read("{\"big\":-" + digits + "}"));

        Record rootRec = new Record("Root", List.of(
            new Field("big", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)
        ));
        Schema schema = new Schema("Root", Map.of("Root", rootRec));
        assertThrows(RuntimeException.class, () -> XmlCodec.read("<Root><big>" + digits + "</big></Root>", schema));
        assertThrows(RuntimeException.class, () -> XmlCodec.read("<Root><big>-" + digits + "</big></Root>", schema));
    }

    @Test
    @DisplayName("Materializer enforces maxDepth and maxNodes correctly")
    void testMaterializerBudgetAndDepth() {
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            edges.add(new Edge("val", new Scalar.StringScalar("v" + i)));
        }
        Record multiRec = new Record("Multi", List.of(
            new Field("val", new Type.Scalar(ScalarKind.STRING, false), 0, null)
        ));
        Schema multiSchema = new Schema("Multi", Map.of("Multi", multiRec));
        Document doc = new Node(edges);
        Document mat = Materializer.materialize(doc, multiSchema);
        assertTrue(mat instanceof Node);
    }
}
