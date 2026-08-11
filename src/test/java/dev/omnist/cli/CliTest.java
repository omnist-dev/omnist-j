package dev.omnist.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class CliTest {

    private static class CliResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private CliResult runCli(String[] args, String inputData) {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        InputStream inStream = new ByteArrayInputStream(inputData.getBytes(StandardCharsets.UTF_8));

        PrintStream out = new PrintStream(outStream);
        PrintStream err = new PrintStream(errStream);

        int code = Cli.run(args, out, err, inStream);

        out.flush();
        err.flush();

        return new CliResult(
            code,
            outStream.toString(StandardCharsets.UTF_8),
            errStream.toString(StandardCharsets.UTF_8)
        );
    }

    @Test
    @DisplayName("cli format command formats OML correctly")
    void testFormat() {
        CliResult res = runCli(new String[]{"format", "-"}, "a: 1\n");
        assertEquals(0, res.exitCode);
        assertTrue(res.stdout.contains("a: 1"));
    }

    @Test
    @DisplayName("cli validate command succeeds on valid OML")
    void testValidateSuccess() throws IOException {
        // Create temporary schema and document files
        File schemaFile = File.createTempFile("schema", ".osd");
        schemaFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(schemaFile)) {
            fw.write("record R {\n  \"a\": integer,\n}\nroot R\n");
        }

        File docFile = File.createTempFile("doc", ".oml");
        docFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(docFile)) {
            fw.write("a: 42\n");
        }

        CliResult res = runCli(new String[]{"validate", docFile.getAbsolutePath(), "--from", "oml", "--schema", schemaFile.getAbsolutePath(), "--json"}, "");
        assertEquals(0, res.exitCode);
        assertTrue(res.stdout.contains("\"ok\":true"));
    }

    @Test
    @DisplayName("cli validate command fails on invalid OML")
    void testValidateFailure() throws IOException {
        File schemaFile = File.createTempFile("schema", ".osd");
        schemaFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(schemaFile)) {
            fw.write("record R {\n  \"a\": integer,\n}\nroot R\n");
        }

        File docFile = File.createTempFile("doc", ".oml");
        docFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(docFile)) {
            fw.write("a: \"not-an-int\"\n");
        }

        CliResult res = runCli(new String[]{"validate", docFile.getAbsolutePath(), "--from", "oml", "--schema", schemaFile.getAbsolutePath(), "--json"}, "");
        assertEquals(1, res.exitCode);
        assertTrue(res.stdout.contains("\"ok\":false"));
        assertTrue(res.stdout.contains("validate.type-mismatch"));
    }

    @Test
    @DisplayName("cli schema normalize command works")
    void testSchemaNormalize() throws IOException {
        File schemaFile = File.createTempFile("schema", ".osd");
        schemaFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(schemaFile)) {
            fw.write("record R {\n  \"a\": integer,\n}\nroot R\n");
        }

        CliResult res = runCli(new String[]{"schema", "normalize", schemaFile.getAbsolutePath()}, "");
        assertEquals(0, res.exitCode);
        assertTrue(res.stdout.contains("record R"));
    }

    @Test
    @DisplayName("cli schema is-empty command works")
    void testSchemaIsEmpty() throws IOException {
        File schemaFile = File.createTempFile("schema", ".osd");
        schemaFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(schemaFile)) {
            fw.write("record R {\n  \"a\": integer,\n}\nroot R\n");
        }

        CliResult res = runCli(new String[]{"schema", "is-empty", schemaFile.getAbsolutePath(), "--result-format", "json"}, "");
        assertEquals(1, res.exitCode); // not empty -> exit code 1
        assertTrue(res.stdout.contains("\"empty\":false"));
    }
}
