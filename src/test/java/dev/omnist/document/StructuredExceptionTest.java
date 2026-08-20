package dev.omnist.document;

import dev.omnist.algebra.AlgebraException;
import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.codec.JsonCodec;
import dev.omnist.codec.TomlCodec;
import dev.omnist.codec.XmlCodec;
import dev.omnist.codec.YamlCodec;
import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StructuredExceptionTest {

    @Test
    @DisplayName("DocumentParseException properties and constructors")
    void testDocumentParseExceptionProperties() {
        DocumentParseException ex1 = new DocumentParseException("$.foo", "document.limit.depth", "depth error");
        assertEquals("$.foo", ex1.getPath());
        assertEquals("document.limit.depth", ex1.getCode());
        assertEquals("depth error", ex1.getMessage());

        DocumentParseException ex1b = new DocumentParseException(null, null, "null path and code");
        assertEquals("$", ex1b.getPath());
        assertEquals("document.parse-error", ex1b.getCode());

        Throwable cause = new RuntimeException("cause");
        DocumentParseException ex2 = new DocumentParseException(null, null, "error with cause", cause);
        assertEquals("$", ex2.getPath());
        assertEquals("document.parse-error", ex2.getCode());
        assertSame(cause, ex2.getCause());

        DocumentParseException ex2b = new DocumentParseException("$.bar", "document.limit.nodes", "nodes with cause", cause);
        assertEquals("$.bar", ex2b.getPath());
        assertEquals("document.limit.nodes", ex2b.getCode());
        assertSame(cause, ex2b.getCause());
    }

    @Test
    @DisplayName("AlgebraException properties and constructors")
    void testAlgebraExceptionProperties() {
        AlgebraException ex1 = new AlgebraException("MyRecord.field", "algebra.infer-mixed-shape", "mixed shape");
        assertEquals("MyRecord.field", ex1.getPath());
        assertEquals("algebra.infer-mixed-shape", ex1.getCode());
        assertEquals("mixed shape", ex1.getMessage());

        AlgebraException ex1b = new AlgebraException(null, null, "null path and code");
        assertEquals("$", ex1b.getPath());
        assertEquals("algebra.error", ex1b.getCode());

        Throwable cause = new RuntimeException("cause");
        AlgebraException ex2 = new AlgebraException(null, null, "error with cause", cause);
        assertEquals("$", ex2.getPath());
        assertEquals("algebra.error", ex2.getCode());
        assertSame(cause, ex2.getCause());

        AlgebraException ex2b = new AlgebraException("MyRecord", "algebra.extract-invalidates-root", "extract with cause", cause);
        assertEquals("MyRecord", ex2b.getPath());
        assertEquals("algebra.extract-invalidates-root", ex2b.getCode());
        assertSame(cause, ex2b.getCause());
    }

    @Test
    @DisplayName("JsonCodec throws DocumentParseException with correct codes and paths")
    void testJsonCodecStructuredExceptions() {
        DocumentParseException exArray = assertThrows(DocumentParseException.class, () -> JsonCodec.read("[1, 2]"));
        assertEquals("document.unlabeled-element", exArray.getCode());
        assertEquals("$", exArray.getPath());

        DocumentParseException exNested = assertThrows(DocumentParseException.class, () -> JsonCodec.read("{\"a\": [[1]]}"));
        assertEquals("document.unlabeled-element", exNested.getCode());
        assertEquals("$.a[0]", exNested.getPath());

        DocumentParseException exParse = assertThrows(DocumentParseException.class, () -> JsonCodec.read("{invalid json"));
        assertEquals("document.parse-error", exParse.getCode());
    }

    @Test
    @DisplayName("YamlCodec throws DocumentParseException with correct codes and paths")
    void testYamlCodecStructuredExceptions() {
        DocumentParseException exArray = assertThrows(DocumentParseException.class, () -> YamlCodec.read("- 1\n- 2\n"));
        assertEquals("document.unlabeled-element", exArray.getCode());
        assertEquals("$", exArray.getPath());

        DocumentParseException exNested = assertThrows(DocumentParseException.class, () -> YamlCodec.read("a:\n  - [1, 2]\n"));
        assertEquals("document.unlabeled-element", exNested.getCode());
        assertEquals("$.a[0]", exNested.getPath());

        DocumentParseException exParse = assertThrows(DocumentParseException.class, () -> YamlCodec.read("a: [1, 2"));
        assertEquals("document.parse-error", exParse.getCode());
    }

    @Test
    @DisplayName("TomlCodec throws DocumentParseException with correct codes and paths")
    void testTomlCodecStructuredExceptions() {
        DocumentParseException exNested = assertThrows(DocumentParseException.class, () -> TomlCodec.read("a = [[1]]\n"));
        assertEquals("document.unlabeled-element", exNested.getCode());
        assertEquals("$.a[0]", exNested.getPath());

        DocumentParseException exParse = assertThrows(DocumentParseException.class, () -> TomlCodec.read("a = \n"));
        assertEquals("document.parse-error", exParse.getCode());
    }

    @Test
    @DisplayName("XmlCodec throws DocumentParseException with correct codes and paths")
    void testXmlCodecStructuredExceptions() {
        DocumentParseException exParse = assertThrows(DocumentParseException.class, () -> XmlCodec.read("<root><unclosed>"));
        assertEquals("document.parse-error", exParse.getCode());

        DocumentParseException exMixed = assertThrows(DocumentParseException.class, () -> XmlCodec.read("<root>text<child>1</child></root>"));
        assertEquals("document.unlabeled-element", exMixed.getCode());
    }

    @Test
    @DisplayName("SchemaAlgebra throws AlgebraException with correct codes and paths")
    void testSchemaAlgebraStructuredExceptions() {
        // Zero samples
        AlgebraException exZero = assertThrows(AlgebraException.class, () -> SchemaAlgebra.infer(List.of()));
        assertEquals("algebra.infer-no-samples", exZero.getCode());
        assertEquals("$", exZero.getPath());

        // Scalar root
        AlgebraException exScalar = assertThrows(AlgebraException.class, () -> SchemaAlgebra.infer(List.of(new Scalar.IntegerScalar(java.math.BigInteger.ONE))));
        assertEquals("algebra.infer-scalar-root", exScalar.getCode());
        assertEquals("$", exScalar.getPath());

        // Mixed shape
        Document d1 = new Node(List.of(new Edge("a", new Scalar.IntegerScalar(java.math.BigInteger.ONE))));
        Document d2 = new Node(List.of(new Edge("a", new Node(List.of(new Edge("b", new Scalar.StringScalar("s")))))));
        AlgebraException exMixed = assertThrows(AlgebraException.class, () -> SchemaAlgebra.infer(List.of(d1, d2)));
        assertEquals("algebra.infer-mixed-shape", exMixed.getCode());

        // Conflicting scalars
        Document d3 = new Node(List.of(new Edge("a", new Scalar.IntegerScalar(java.math.BigInteger.ONE))));
        Document d4 = new Node(List.of(new Edge("a", new Scalar.StringScalar("s"))));
        AlgebraException exConf = assertThrows(AlgebraException.class, () -> SchemaAlgebra.infer(List.of(d3, d4)));
        assertEquals("algebra.infer-conflicting-scalars", exConf.getCode());

        // Extract invalidates root
        Record rec = new Record("Root", List.of(new Field("req", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schema = new Schema("Root", Map.of("Root", rec));
        AlgebraException exExt = assertThrows(AlgebraException.class, () -> SchemaAlgebra.extract(schema, Set.of("opt")));
        assertEquals("algebra.extract-invalidates-root", exExt.getCode());
    }
}
