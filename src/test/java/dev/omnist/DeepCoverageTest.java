package dev.omnist;

import dev.omnist.algebra.*;
import dev.omnist.cli.Cli;
import dev.omnist.codec.*;
import dev.omnist.document.*;
import dev.omnist.oml.*;
import dev.omnist.schema.*;
import dev.omnist.validation.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepCoverageTest {

    @Test
    void testLimitsAndDocumentRecords() {
        Limits l1 = Limits.DEFAULT;
        assertEquals(200, l1.maxDepth());
        assertEquals(1_000_000, l1.maxNodeCount());
        assertEquals(4300, l1.maxIntegerDigits());

        Limits l2 = new Limits(10, 20, 30);
        assertEquals(10, l2.maxDepth());
        assertEquals(20, l2.maxNodeCount());
        assertEquals(30, l2.maxIntegerDigits());

        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new Limits(10, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Limits(10, 10, 0));

        // TimeValue & DateTimeValue
        TimeValue tv = TimeValue.of(LocalTime.of(12, 30, 0));
        assertEquals("12:30", tv.format());
        assertNotNull(tv.toString());
        assertEquals(tv, TimeValue.of(LocalTime.of(12, 30, 0)));

        DateTimeValue dtv = DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 12, 30, 0));
        assertEquals("2024-01-01T12:30", dtv.format());
        assertNotNull(dtv.toString());
        assertEquals(dtv, DateTimeValue.of(LocalDateTime.of(2024, 1, 1, 12, 30, 0)));
    }

    @Test
    void testOmlReaderAndLexerLimitsAndEdgeCases() {
        // Exceeding depth limit
        String nestedOml = "a: { b: { c: { d: 1 } } }";
        Limits d2Limits = new Limits(2, 100, 100);
        assertThrows(OmlParseException.class, () -> OmlReader.read(nestedOml, d2Limits));

        // Exceeding node limit
        String multiNodeOml = "a: { b: { c: 1 } }\nd: { e: { f: 2 } }\ng: { h: { i: 3 } }\n";
        Limits n3Limits = new Limits(100, 3, 100);
        assertThrows(OmlParseException.class, () -> OmlReader.read(multiNodeOml, n3Limits));

        // Multiline strings and escapes
        String tripleQuote = "str: \"\"\"\nLine1\nLine2\n\"\"\"";
        Document doc1 = OmlReader.read(tripleQuote);
        assertNotNull(doc1);

        String singleQuote = "str: 'C:\\\\Path\\\\NoEscapes'";
        Document doc2 = OmlReader.read(singleQuote);
        assertNotNull(doc2);
    }

    @Test
    void testJsonCodecEdgeCases() {
        // Large integer digit check in preprocessJson
        String hugeInt = "{\"num\": " + "9".repeat(4301) + "}";
        assertThrows(RuntimeException.class, () -> JsonCodec.read(hugeInt));

        // Document write with WriteReport
        Node docNode = new Node(List.of(
            new Edge("a", new Scalar.StringScalar("hello")),
            new Edge("b", new Scalar.IntegerScalar(BigInteger.valueOf(42)))
        ));
        WriteReport rep = new WriteReport();
        String jsonOut = JsonCodec.write(docNode, 2, false, rep);
        assertNotNull(jsonOut);
    }

    @Test
    void testTomlCodecEdgeCases() {
        assertThrows(WriteException.class, () -> TomlCodec.write(null));

        // Exceeding length limit
        String longText = "a = 1\n" + " ".repeat(TomlCodec.MAX_INPUT_LENGTH + 1);
        assertThrows(RuntimeException.class, () -> TomlCodec.read(longText));

        // Toml write with table array and dates
        Node docNode = new Node(List.of(
            new Edge("title", new Scalar.StringScalar("Test")),
            new Edge("date", new Scalar.DateScalar(LocalDate.of(2024, 1, 1))),
            new Edge("items", new Node(List.of(new Edge("name", new Scalar.StringScalar("item1")))))
        ));
        WriteReport rep = new WriteReport();
        String tomlOut = TomlCodec.write(docNode, false, rep);
        assertNotNull(tomlOut);
    }

    @Test
    void testYamlCodecEdgeCases() {
        String yamlText = """
            name: "Test"
            count: 42
            active: true
            date: 2024-01-01
            time: 12:00:00
            dt: 2024-01-01T12:00:00
            list:
              - a
              - b
            """;
        Document doc = YamlCodec.read(yamlText);
        assertNotNull(doc);

        WriteReport rep = new WriteReport();
        String yamlOut = YamlCodec.write(doc, false, rep);
        assertNotNull(yamlOut);
    }

    @Test
    void testXmlCodecEdgeCases() {
        assertThrows(WriteException.class, () -> XmlCodec.write(null));

        String xmlText = """
            <root attr="value">
                <child>text</child>
                <item>1</item>
                <item>2</item>
            </root>
            """;
        Document doc = XmlCodec.read(xmlText);
        assertNotNull(doc);

        WriteReport rep = new WriteReport();
        String xmlOut = XmlCodec.write(doc, false, rep);
        assertNotNull(xmlOut);
    }

    @Test
    void testMaterializerEdgeCases() {
        Schema schema = OsdReader.read("""
            record R {
                "d": date,
                "dt": datetime,
                "opt" [0,1]: string,
            }
            root R
            """);

        // Materialize string scalar into date/datetime
        Node docNode = new Node(List.of(
            new Edge("d", new Scalar.StringScalar("2024-01-01")),
            new Edge("dt", new Scalar.StringScalar("2024-01-01T12:00:00")),
            new Edge("opt", new Scalar.StringScalar("val"))
        ));

        Document matDoc = Materializer.materialize(docNode, schema);
        assertNotNull(matDoc);

        // Incompatible scalar upgrade attempt (number to date) -> failure
        Node invalidDoc = new Node(List.of(
            new Edge("d", new Scalar.NumberScalar(3.14)),
            new Edge("dt", new Scalar.StringScalar("invalid-datetime"))
        ));
        assertThrows(ValidationException.class, () -> Materializer.materialize(invalidDoc, schema));
    }

    @Test
    void testSchemaAlgebraAndLintEdgeCases() {
        Schema s1 = OsdReader.read("""
            record A { "x": string }
            record B { "a": A }
            root B
            """);
        Schema s2 = OsdReader.read("""
            record X { "x": string }
            record Y { "a": X }
            root Y
            """);

        // Isomorphic schemas
        assertTrue(SchemaAlgebra.equivalent(s1, s2));
        assertTrue(SchemaAlgebra.compatibleWith(s1, s2));
        assertFalse(SchemaAlgebra.isEmpty(s1));

        // Normalize
        Schema sNorm = SchemaAlgebra.normalize(s1);
        assertNotNull(sNorm);

        // Prune
        Schema sPrune = SchemaAlgebra.prune(s1);
        assertNotNull(sPrune);
    }

    private int runCli(String stdinText, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream psOut = new PrintStream(out);
        PrintStream psErr = new PrintStream(err);
        byte[] inputBytes = stdinText != null ? stdinText.getBytes() : new byte[0];
        return Cli.run(args, psOut, psErr, new ByteArrayInputStream(inputBytes));
    }

    @Test
    void testCliSubcommandsAndErrorHandling(@TempDir Path tempDir) throws Exception {
        Path workDir = Files.createDirectories(tempDir.resolve("work"));
        Path omlFile = workDir.resolve("input.oml");
        Files.writeString(omlFile, "a: 1\n");

        Path osdFile = workDir.resolve("schema.osd");
        Files.writeString(osdFile, "record R { \"a\": integer }\nroot R\n");

        // CLI help & version & unknown
        assertEquals(2, runCli(null, "--help"));
        assertEquals(2, runCli(null, "--version"));
        assertEquals(2, runCli(null, "invalid-subcommand"));

        // CLI format
        assertEquals(0, runCli(null, "format", "--to", "json", omlFile.toString()));

        // CLI validate
        assertEquals(0, runCli(null, "validate", "--schema", osdFile.toString(), omlFile.toString()));

        // CLI convert
        Path jsonOut = workDir.resolve("out.json");
        assertEquals(0, runCli(null, "convert", "--schema", osdFile.toString(), "--to", "json", "-o", jsonOut.toString(), omlFile.toString()));

        // CLI schema normalize
        assertEquals(0, runCli(null, "schema", "normalize", osdFile.toString()));

        // CLI schema prune
        assertEquals(0, runCli(null, "schema", "prune", osdFile.toString()));

        // CLI schema lint
        assertEquals(0, runCli(null, "schema", "lint", osdFile.toString()));

        // CLI schema equivalent
        assertEquals(0, runCli(null, "schema", "equivalent", osdFile.toString(), osdFile.toString()));

        // CLI schema compatible-with
        assertEquals(0, runCli(null, "schema", "compatible-with", osdFile.toString(), osdFile.toString()));

        // CLI infer
        assertEquals(0, runCli(null, "infer", omlFile.toString()));
    }
}
