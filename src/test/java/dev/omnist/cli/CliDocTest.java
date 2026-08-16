package dev.omnist.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class CliDocTest {

    private static final String PERSON_SCHEMA = """
        record Person {
            "name": string,
            "age": integer,
        }
        root Person
        """;

    private static final String ITEM_SCHEMA = """
        record Item {
            "created": date,
        }
        root Item
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

    private static final String ROOT_DEAD_ANY_SCHEMA = """
        record Root {
            "id": integer,
            "x": any,
        }
        record Dead {
            "x": string,
        }
        root Root
        """;

    private File tempFile(String prefix, String suffix, String content) throws IOException {
        File file = File.createTempFile(prefix, suffix);
        file.deleteOnExit();
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8)) {
            fw.write(content);
        }
        return file;
    }

    private int runCli(String[] args, String stdin, StringBuilder outBuf, StringBuilder errBuf) {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();

        PrintStream out = new PrintStream(outStream, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errStream, true, StandardCharsets.UTF_8);
        InputStream in = (stdin != null)
            ? new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8))
            : new ByteArrayInputStream(new byte[0]);

        int code = Cli.run(args, out, err, in);
        out.flush();
        err.flush();

        if (outBuf != null) outBuf.append(outStream.toString(StandardCharsets.UTF_8));
        if (errBuf != null) errBuf.append(errStream.toString(StandardCharsets.UTF_8));
        return code;
    }

    @Test
    void testCliFormatExample() {
        StringBuilder stdout = new StringBuilder();
        String oml = "name: \"Alice\"\nage: 30\n";
        int code = runCli(new String[]{"format", "-", "--to", "json"}, oml, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("Alice"));
    }

    @Test
    void testCliValidateExample() throws IOException {
        StringBuilder stdout = new StringBuilder();
        File schemaFile = tempFile("schema", ".osd", PERSON_SCHEMA);
        File omlFile = tempFile("doc", ".oml", "name: \"Alice\"\nage: 30\n");

        int code = runCli(new String[]{"validate", omlFile.getAbsolutePath(), "--schema", schemaFile.getAbsolutePath(), "--json"}, null, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("{\"ok\":true}"));
    }

    @Test
    void testCliConvertExample() throws IOException {
        StringBuilder stdout = new StringBuilder();
        File schemaFile = tempFile("schema", ".osd", ITEM_SCHEMA);
        File omlFile = tempFile("doc", ".oml", "created: \"2024-01-01\"\n");

        int code = runCli(new String[]{"convert", omlFile.getAbsolutePath(), "--schema", schemaFile.getAbsolutePath()}, null, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("2024-01-01"));
    }

    @Test
    void testCliSchemaNormalizeExample() {
        StringBuilder stdout = new StringBuilder();
        int code = runCli(new String[]{"schema", "normalize", "-"}, PERSON_SCHEMA, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("record Person"));
    }

    @Test
    void testCliSchemaPruneExample() {
        StringBuilder stdout = new StringBuilder();
        int code = runCli(new String[]{"schema", "prune", "-"}, ROOT_DEAD_SCHEMA, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("Root"));
        assertFalse(stdout.toString().contains("Dead"));
    }

    @Test
    void testCliSchemaExtractExample() {
        StringBuilder stdout = new StringBuilder();
        int code = runCli(new String[]{"schema", "extract", "-", "--keep", "id"}, ROOT_SCHEMA, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("record Root"));
    }

    @Test
    void testCliSchemaIsEmptyExample() {
        StringBuilder stdout = new StringBuilder();
        int code = runCli(new String[]{"schema", "is-empty", "-", "--result-format", "json"}, ROOT_SCHEMA, stdout, null);
        assertEquals(1, code);
        assertTrue(stdout.toString().contains("{\"empty\":false}"));
    }

    @Test
    void testCliSchemaCompatibleWithExample() throws IOException {
        StringBuilder stdout = new StringBuilder();
        File s1 = tempFile("schema1", ".osd", ROOT_SCHEMA);
        File s2 = tempFile("schema2", ".osd", ROOT_SCHEMA);

        int code = runCli(new String[]{"schema", "compatible-with", s1.getAbsolutePath(), s2.getAbsolutePath(), "--result-format", "json"}, null, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("{\"compatible\":true}"));
    }

    @Test
    void testCliSchemaEquivalentExample() throws IOException {
        StringBuilder stdout = new StringBuilder();
        File s1 = tempFile("schema1", ".osd", ROOT_SCHEMA);
        File s2 = tempFile("schema2", ".osd", ROOT_SCHEMA);

        int code = runCli(new String[]{"schema", "equivalent", s1.getAbsolutePath(), s2.getAbsolutePath(), "--result-format", "json"}, null, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("{\"equivalent\":true}"));
    }

    @Test
    void testCliSchemaLintExample() {
        StringBuilder stderr = new StringBuilder();
        int code = runCli(new String[]{"schema", "lint", "-"}, ROOT_DEAD_SCHEMA, null, stderr);
        assertTrue(code == 0 || code == 1);
        assertTrue(stderr.toString().contains("lint.unreachable-record"));
    }

    @Test
    void testCliSchemaLintSeverityExample() {
        StringBuilder stderr = new StringBuilder();
        int code = runCli(new String[]{"schema", "lint", "-", "--severity", "warning"}, ROOT_DEAD_ANY_SCHEMA, null, stderr);
        assertEquals(1, code);
        assertTrue(stderr.toString().contains("WARNING"));
        assertFalse(stderr.toString().contains("INFO"));
    }

    @Test
    void testCliInferExample() {
        StringBuilder stdout = new StringBuilder();
        String oml = "id: 1\nname: \"Alice\"\n";
        int code = runCli(new String[]{"infer", "-"}, oml, stdout, null);
        assertEquals(0, code);
        assertTrue(stdout.toString().contains("record R"));
    }
}
