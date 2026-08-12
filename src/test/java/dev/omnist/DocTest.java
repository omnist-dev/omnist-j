package dev.omnist;

import dev.omnist.algebra.*;
import dev.omnist.codec.*;
import dev.omnist.document.*;
import dev.omnist.oml.*;
import dev.omnist.schema.*;
import dev.omnist.validation.*;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class DocTest {

    private static final String PERSON_SCHEMA = """
        record Person {
            "name": string,
            "age": integer,
        }
        root Person
        """;

    private static final String ROOT_SCHEMA = """
        record Root {
            "id": integer,
            "secret" [0,1]: string,
        }
        root Root
        """;

    private static final String ROOT_DEAD_SCHEMA = """
        record Root {
            "id": integer,
        }
        record Dead {
            "x": string,
        }
        root Root
        """;

    @Test
    void testOmlReaderExample() {
        String oml = "name: \"Alice\"\nage: 30\n";
        Document doc = OmlReader.read(oml);
        assertTrue(doc instanceof Node);
        Node root = (Node) doc;
        assertEquals("name", root.edges().get(0).label());
    }

    @Test
    void testOmlWriterExample() {
        Document doc = OmlReader.read("name: \"Alice\"\nage: 30\n");
        String oml = OmlWriter.write(doc);
        assertTrue(oml.contains("name: \"Alice\""));
    }

    @Test
    void testOsdReaderExample() {
        Schema schema = OsdReader.read(PERSON_SCHEMA);
        assertNotNull(schema);
        assertEquals("Person", schema.root());
        assertTrue(schema.records().containsKey("Person"));
    }

    @Test
    void testOsdWriterExample() {
        Schema schema = OsdReader.read(PERSON_SCHEMA);
        String written = OsdWriter.write(schema);
        assertTrue(written.contains("record Person"));
    }

    @Test
    void testDocumentConstructionExample() {
        Node node = new Node(List.of(
            new Edge("title", new Scalar.StringScalar("Project"))
        ));
        assertEquals("title", node.edges().get(0).label());
    }

    @Test
    void testLimitsExample() {
        Limits limits = new Limits(2, 50, 100);
        assertEquals(2, limits.maxDepth());
        assertEquals(50, limits.maxNodeCount());
        assertEquals(100, limits.maxIntegerDigits());
        assertThrows(OmlParseException.class, () -> {
            OmlReader.read("a: { b: { c: 1 } }\n", limits);
        });
    }

    @Test
    void testValidatorExample() {
        Schema schema = OsdReader.read(PERSON_SCHEMA);
        Document validDoc = OmlReader.read("name: \"Bob\"\nage: 25\n");
        ValidationResult res = Validator.validate(validDoc, schema);
        assertTrue(res.isValid());
        assertTrue(res.diagnostics().isEmpty());

        Document invalidDoc = OmlReader.read("name: \"Bob\"\nage: \"not-an-int\"\n");
        ValidationResult invalidRes = Validator.validate(invalidDoc, schema);
        assertFalse(invalidRes.isValid());
    }

    @Test
    void testMaterializerExample() {
        Schema schema = OsdReader.read("""
            record Item {
                "created": date,
            }
            root Item
            """);
        Document doc = OmlReader.read("created: \"2024-01-01\"\n");
        Document materialized = Materializer.materialize(doc, schema);
        assertNotNull(materialized);
    }

    @Test
    void testSchemaAlgebraSatisfiableSet() {
        Schema schema = OsdReader.read(ROOT_SCHEMA);
        Set<String> set = SchemaAlgebra.satisfiableSet(schema);
        assertTrue(set.contains("Root"));
    }

    @Test
    void testSchemaAlgebraIsEmpty() {
        Schema schema = OsdReader.read(ROOT_SCHEMA);
        assertFalse(SchemaAlgebra.isEmpty(schema));
    }

    @Test
    void testSchemaAlgebraPrune() {
        Schema schema = OsdReader.read(ROOT_DEAD_SCHEMA);
        Schema pruned = SchemaAlgebra.prune(schema);
        assertTrue(pruned.records().containsKey("Root"));
        assertFalse(pruned.records().containsKey("Dead"));
    }

    @Test
    void testSchemaAlgebraCompatibleWith() {
        Schema s1 = OsdReader.read(ROOT_SCHEMA);
        Schema s2 = OsdReader.read(ROOT_SCHEMA);
        assertTrue(SchemaAlgebra.compatibleWith(s1, s2));
    }

    @Test
    void testSchemaAlgebraEquivalent() {
        Schema s1 = OsdReader.read(ROOT_SCHEMA);
        Schema s2 = OsdReader.read(ROOT_SCHEMA);
        assertTrue(SchemaAlgebra.equivalent(s1, s2));
    }

    @Test
    void testSchemaAlgebraNormalize() {
        Schema schema = OsdReader.read(ROOT_SCHEMA);
        Schema norm = SchemaAlgebra.normalize(schema);
        assertNotNull(norm);
    }

    @Test
    void testSchemaAlgebraEquivalenceClasses() {
        Schema schema = OsdReader.read(ROOT_SCHEMA);
        List<List<String>> classes = SchemaAlgebra.equivalenceClasses(schema);
        assertFalse(classes.isEmpty());
    }

    @Test
    void testSchemaAlgebraExtract() {
        Schema schema = OsdReader.read(ROOT_SCHEMA);
        Schema extracted = SchemaAlgebra.extract(schema, Set.of("id"));
        assertNotNull(extracted);
    }

    @Test
    void testSchemaAlgebraLint() {
        Schema schema = OsdReader.read(ROOT_DEAD_SCHEMA);
        List<LintFinding> findings = SchemaAlgebra.lint(schema);
        assertFalse(findings.isEmpty());
        assertEquals("lint.unreachable-record", findings.get(0).code());
    }

    @Test
    void testSchemaAlgebraInfer() {
        Document doc1 = OmlReader.read("id: 1\nname: \"A\"\n");
        Document doc2 = OmlReader.read("id: 2\nname: \"B\"\n");
        Schema inferred = SchemaAlgebra.infer(List.of(doc1, doc2));
        assertNotNull(inferred);
        assertTrue(inferred.records().containsKey("Root"));

        InferResult res = SchemaAlgebra.inferWithReport(List.of(doc1, doc2), "Root", false);
        assertNotNull(res.schema());
    }

    @Test
    void testJsonCodecExample() {
        String json = "{\"name\":\"Alice\",\"age\":30}";
        Document doc = JsonCodec.read(json);
        assertTrue(doc instanceof Node);
        String jsonOut = JsonCodec.write(doc);
        assertTrue(jsonOut.contains("\"name\""));
    }

    @Test
    void testYamlCodecExample() {
        String yaml = "name: Alice\nage: 30\n";
        Document doc = YamlCodec.read(yaml);
        assertTrue(doc instanceof Node);
        String yamlOut = YamlCodec.write(doc);
        assertTrue(yamlOut.contains("name:"));
    }

    @Test
    void testTomlCodecExample() {
        String toml = "name = \"Alice\"\nage = 30\n";
        Document doc = TomlCodec.read(toml);
        assertTrue(doc instanceof Node);
        String tomlOut = TomlCodec.write(doc);
        assertTrue(tomlOut.contains("name ="));
    }

    @Test
    void testXmlCodecExample() {
        String xml = "<Person><name>Alice</name><age>30</age></Person>";
        Document doc = XmlCodec.read(xml);
        assertNotNull(doc);
        String xmlOut = XmlCodec.write(doc);
        assertTrue(xmlOut.contains("<Person>"));
    }

    @Test
    void testReflectionSafeguardDocReferences() throws Exception {
        File docFile = new File("docs/01-api-reference.md");
        assertTrue(docFile.exists(), "docs/01-api-reference.md must exist");
        String content = Files.readString(docFile.toPath());

        Class<?>[] classesToVerify = new Class<?>[] {
            OmlReader.class, OmlWriter.class,
            OsdReader.class, OsdWriter.class,
            Document.class, Node.class, Edge.class, Value.class, Scalar.class, Limits.class,
            Validator.class, Materializer.class,
            SchemaAlgebra.class,
            JsonCodec.class, YamlCodec.class, TomlCodec.class, XmlCodec.class
        };

        for (Class<?> clazz : classesToVerify) {
            assertTrue(content.contains(clazz.getSimpleName()),
                "Documentation must reference class: " + clazz.getSimpleName());
        }
    }
}
