package dev.omnist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliCoverageTest {

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

    private int execute(String[] args, String inputStdin, ByteArrayOutputStream outBaos, ByteArrayOutputStream errBaos) {
        ByteArrayInputStream in = new ByteArrayInputStream(inputStdin != null ? inputStdin.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        PrintStream out = new PrintStream(outBaos, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBaos, true, StandardCharsets.UTF_8);
        return Cli.run(args, out, err, in);
    }

    @Test
    void testEmptyArgs() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[0], null, out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Usage: omnist"));
    }

    @Test
    void testUnknownCommandAndOptions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"foobar", "--unknown-flag"}, null, out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown command: foobar"));
    }

    @Test
    void testFormatCommandMissingInput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"format"}, null, out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing format input file"));
    }

    @Test
    void testFormatFormatsAll(@TempDir Path tempDir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        // json -> yaml
        int code = execute(new String[]{"format", "-", "--from", "json", "--to", "yaml"}, "{\"a\":1}\n", out, err);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("a:"));

        // oml -> toml
        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "oml", "--to", "toml"}, "a: 1\n", out, err);
        assertEquals(0, code);

        // oml -> xml
        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "oml", "--to", "xml"}, "a: 1\n", out, err);
        assertEquals(0, code);

        // invalid format
        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "invalid"}, "a: 1\n", out, err);
        assertEquals(2, code);
    }

    @Test
    void testValidateCommand(@TempDir Path tempDir) throws Exception {
        Path schemaFile = tempDir.resolve("s.osd");
        Path docFile = tempDir.resolve("d.oml");
        Files.writeString(schemaFile, PERSON_SCHEMA);
        Files.writeString(docFile, "name: \"Alice\"\nage: 30\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"validate", docFile.toString()}, null, out, err);
        assertEquals(2, code, "Missing --schema");

        out.reset(); err.reset();
        code = execute(new String[]{"validate", docFile.toString(), "--schema", schemaFile.toString(), "--json"}, null, out, err);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("{\"ok\":true}"));

        // Validation failure case
        Path docFileErr = tempDir.resolve("derr.oml");
        Files.writeString(docFileErr, "name: \"Alice\"\nage: \"not-an-int\"\n");
        out.reset(); err.reset();
        code = execute(new String[]{"validate", docFileErr.toString(), "--schema", schemaFile.toString(), "--json"}, null, out, err);
        assertEquals(1, code);

        // Non-JSON validation failure
        out.reset(); err.reset();
        code = execute(new String[]{"validate", docFileErr.toString(), "--schema", schemaFile.toString()}, null, out, err);
        assertEquals(1, code);
        assertFalse(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void testConvertCommand(@TempDir Path tempDir) throws Exception {
        Path schemaFile = tempDir.resolve("s.osd");
        Path docFile = tempDir.resolve("d.oml");
        Files.writeString(schemaFile, PERSON_SCHEMA);
        Files.writeString(docFile, "name: \"Alice\"\nage: 30\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"convert"}, null, out, err);
        assertEquals(2, code);

        out.reset(); err.reset();
        code = execute(new String[]{"convert", docFile.toString()}, null, out, err);
        assertEquals(2, code, "Missing --schema");

        out.reset(); err.reset();
        code = execute(new String[]{"convert", docFile.toString(), "--schema", schemaFile.toString()}, null, out, err);
        assertEquals(0, code);

        // Convert error case
        Path docFileErr = tempDir.resolve("derr.oml");
        Files.writeString(docFileErr, "name: \"Alice\"\nage: \"not-an-int\"\n");
        out.reset(); err.reset();
        code = execute(new String[]{"convert", docFileErr.toString(), "--schema", schemaFile.toString(), "--json"}, null, out, err);
        assertEquals(2, code);

        out.reset(); err.reset();
        code = execute(new String[]{"convert", docFileErr.toString(), "--schema", schemaFile.toString()}, null, out, err);
        assertEquals(2, code);
        assertFalse(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void testSchemaSubcommands(@TempDir Path tempDir) throws Exception {
        Path s1 = tempDir.resolve("s1.osd");
        Path s2 = tempDir.resolve("s2.osd");
        Files.writeString(s1, ROOT_SCHEMA);
        Files.writeString(s2, ROOT_SCHEMA);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"schema"}, null, out, err);
        assertEquals(2, code);

        // normalize missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "normalize"}, null, out, err);
        assertEquals(2, code);

        // prune missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "prune"}, null, out, err);
        assertEquals(2, code);

        // extract missing keep
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "extract", s1.toString()}, null, out, err);
        assertEquals(2, code);

        // extract missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "extract"}, null, out, err);
        assertEquals(2, code);

        // is-empty missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "is-empty"}, null, out, err);
        assertEquals(2, code);

        // compatible-with missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "compatible-with", s1.toString()}, null, out, err);
        assertEquals(2, code);

        // equivalent missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "equivalent", s1.toString()}, null, out, err);
        assertEquals(2, code);

        // lint missing file
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "lint"}, null, out, err);
        assertEquals(2, code);

        // normalize
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "normalize", s1.toString(), "--compact"}, null, out, err);
        assertEquals(0, code);

        // prune
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "prune", s1.toString()}, null, out, err);
        assertEquals(0, code);

        // extract
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "extract", s1.toString(), "--keep", "id"}, null, out, err);
        assertEquals(0, code);

        // extract invalidation error
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "extract", s1.toString(), "--keep", "secret", "--json"}, null, out, err);
        assertEquals(1, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("algebra.extract-invalidates-root"));

        // is-empty
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "is-empty", s1.toString(), "--result-format", "json"}, null, out, err);
        assertEquals(1, code, "s1 is not empty");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("{\"empty\":false}"));

        // compatible-with
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "compatible-with", s1.toString(), s2.toString(), "--result-format", "json"}, null, out, err);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("{\"compatible\":true}"));

        // equivalent
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "equivalent", s1.toString(), s2.toString(), "--result-format", "json"}, null, out, err);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("{\"equivalent\":true}"));

        // lint
        out.reset(); err.reset();
        Path sUnreachable = tempDir.resolve("sunreach.osd");
        Files.writeString(sUnreachable, ROOT_DEAD_SCHEMA);
        code = execute(new String[]{"schema", "lint", sUnreachable.toString(), "--severity", "warning", "--json"}, null, out, err);
        assertEquals(1, code);

        out.reset(); err.reset();
        code = execute(new String[]{"schema", "lint", sUnreachable.toString()}, null, out, err);
        assertEquals(1, code);
    }

    @Test
    void testInferCommand(@TempDir Path tempDir) throws Exception {
        Path doc1 = tempDir.resolve("d1.oml");
        Path doc2 = tempDir.resolve("d2.oml");
        Files.writeString(doc1, "id: 1\nname: \"Alice\"\n");
        Files.writeString(doc2, "id: \"not-an-int\"\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"infer"}, null, out, err);
        assertEquals(2, code);

        out.reset(); err.reset();
        code = execute(new String[]{"infer", doc1.toString()}, null, out, err);
        assertEquals(0, code);

        // infer error json
        out.reset(); err.reset();
        code = execute(new String[]{"infer", doc1.toString(), doc2.toString(), "--json"}, null, out, err);
        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("false"));

        // infer error non-json
        out.reset(); err.reset();
        code = execute(new String[]{"infer", doc1.toString(), doc2.toString()}, null, out, err);
        assertEquals(2, code);
    }
}
