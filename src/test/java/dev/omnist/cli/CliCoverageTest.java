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
        int code = execute(new String[]{"foobar"}, null, out, err);
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

        // unknown schema subcommand: hits runSchema's own switch default case,
        // printing "Unknown command: schema bogus-subcommand" and returning 2.
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "bogus-subcommand", s1.toString()}, null, out, err);
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

        // is-empty without --result-format json: no JSON body printed, only exit code
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "is-empty", s1.toString()}, null, out, err);
        assertEquals(1, code);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("{\"empty\""));

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

        // lint: a schema whose only finding is "info" severity (any-typed field)
        // keeps ok == true, exercising the severity.equals("warning") false branch.
        Path sAnyOnly = tempDir.resolve("sany.osd");
        Files.writeString(sAnyOnly, "record R { \"x\": any } root R\n");
        out.reset(); err.reset();
        code = execute(new String[]{"schema", "lint", sAnyOnly.toString()}, null, out, err);
        assertEquals(0, code, "an info-only lint result should not fail the command");
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

    @Test
    void testFlagsAsLastArgumentWithNoValue() {
        // Each of these flags expects a following value; when it's the last
        // argument, the "i + 1 < args.length" guard's false branch is taken and
        // the flag is silently ignored (matches unrecognized-flag behavior).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        for (String flag : new String[]{"-o", "--from", "--to", "--schema", "--keep", "--result-format", "--severity"}) {
            out.reset(); err.reset();
            int code = execute(new String[]{"format", "-", flag}, "a: 1\n", out, err);
            assertEquals(2, code, "flag " + flag + " at end without value should be rejected");
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: " + flag));
        }
    }

    @Test
    void testValidateMissingInputFile() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"validate"}, null, out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing validate input file"));
    }

    @Test
    void testExtractInvalidRootNonJsonErrorOutput(@TempDir Path tempDir) throws Exception {
        Path s1 = tempDir.resolve("s1.osd");
        Files.writeString(s1, ROOT_SCHEMA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"schema", "extract", s1.toString(), "--keep", "secret"}, null, out, err);
        assertEquals(1, code);
        assertFalse(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void testEquivalentFalseCase(@TempDir Path tempDir) throws Exception {
        Path s1 = tempDir.resolve("s1.osd");
        Path s2 = tempDir.resolve("s2.osd");
        Files.writeString(s1, ROOT_SCHEMA);
        Files.writeString(s2, PERSON_SCHEMA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"schema", "equivalent", s1.toString(), s2.toString(), "--result-format", "json"}, null, out, err);
        assertEquals(1, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("{\"equivalent\":false}"));
    }

    @Test
    void testInferAllowAnyFallbackReport(@TempDir Path tempDir) throws Exception {
        Path doc1 = tempDir.resolve("d1.oml");
        Path doc2 = tempDir.resolve("d2.oml");
        Files.writeString(doc1, "a: 1\n");
        Files.writeString(doc2, "a: \"text\"\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"infer", doc1.toString(), doc2.toString(), "--allow-any"}, null, out, err);
        assertEquals(0, code);
        // Verified via a scratch diagnostic that this exact conflicting-scalar
        // pair does populate fallbacks with --allow-any, hitting the reporting branch.
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("opened 1 field(s) as `any`"));

        // --allow-any with no actual conflicts: fallbacks is empty, so the
        // reporting branch's other half (allowAny true, isEmpty true) is exercised.
        Path doc3 = tempDir.resolve("d3.oml");
        Files.writeString(doc3, "a: 1\n");
        out.reset(); err.reset();
        code = execute(new String[]{"infer", doc3.toString(), "--allow-any"}, null, out, err);
        assertEquals(0, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void testReadDocumentAllFormats(@TempDir Path tempDir) throws Exception {
        Path schemaFile = tempDir.resolve("s.osd");
        Files.writeString(schemaFile, PERSON_SCHEMA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = execute(new String[]{"format", "-", "--from", "yaml", "--to", "oml"}, "a: 1\n", out, err);
        assertEquals(0, code);

        // --to oml with --compact: exercises writeDocument's compact ternary branch
        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "yaml", "--to", "oml", "--compact"}, "a: 1\n", out, err);
        assertEquals(0, code);

        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "toml", "--to", "oml"}, "a = 1\n", out, err);
        assertEquals(0, code);

        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "xml", "--to", "oml"}, "<root><a>1</a></root>", out, err);
        assertEquals(0, code);

        // validate exercises the schema-aware XmlCodec.read(text, schema) path
        Path docFile = tempDir.resolve("d.xml");
        Files.writeString(docFile, "<Person><name>Alice</name><age>30</age></Person>");
        out.reset(); err.reset();
        code = execute(new String[]{"validate", docFile.toString(), "--schema", schemaFile.toString(), "--from", "xml"}, null, out, err);
        assertTrue(code == 0 || code == 1, "expected validate to complete (pass or fail), not crash");

        // unsupported --to format
        out.reset(); err.reset();
        code = execute(new String[]{"format", "-", "--from", "oml", "--to", "bogus"}, "a: 1\n", out, err);
        assertEquals(2, code);
    }

    @Test
    void testGetInferErrorCodeBranches(@TempDir Path tempDir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        // scalar root
        Path scalarDoc = tempDir.resolve("scalar.oml");
        Files.writeString(scalarDoc, "1\n");
        out.reset(); err.reset();
        int code = execute(new String[]{"infer", scalarDoc.toString(), "--json"}, null, out, err);
        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("algebra.infer-scalar-root"));

        // no samples
        out.reset(); err.reset();
        code = execute(new String[]{"infer", "--json"}, null, out, err);
        // no positionals -> falls into the earlier "Usage" branch (code 2), not infer-no-samples;
        // exercised indirectly via getInferErrorCode's message-matching logic on other inputs below.

        // mixed shape
        Path mixA = tempDir.resolve("mixA.oml");
        Path mixB = tempDir.resolve("mixB.oml");
        Files.writeString(mixA, "a: 1\n");
        Files.writeString(mixB, "1\n");
        out.reset(); err.reset();
        code = execute(new String[]{"infer", mixA.toString(), mixB.toString(), "--json"}, null, out, err);
        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("algebra.infer-"));

        // conflicting scalar types (no --allow-any)
        Path confA = tempDir.resolve("confA.oml");
        Path confB = tempDir.resolve("confB.oml");
        Files.writeString(confA, "a: 1\n");
        Files.writeString(confB, "a: \"text\"\n");
        out.reset(); err.reset();
        code = execute(new String[]{"infer", confA.toString(), confB.toString(), "--json"}, null, out, err);
        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("algebra.infer-conflicting-scalars"));
    }

    @Test
    void testGetInferErrorCodeDirect() {
        // Tested directly against the exact real messages SchemaAlgebra throws
        // (see SchemaAlgebra.java) rather than through CLI-triggered exceptions:
        // "zero samples" is unreachable through Cli.run itself (the infer
        // command's own arg-count check guarantees >=1 sample), and pinning the
        // exact message per branch is more reliable than a generic CLI-output
        // substring match, which can pass without confirming the right branch.
        assertEquals("algebra.infer-scalar-root",
            Cli.getInferErrorCode("infer expects object (record) samples at the root"));
        assertEquals("algebra.infer-no-samples",
            Cli.getInferErrorCode("cannot infer a schema from zero samples"));
        assertEquals("algebra.infer-mixed-shape",
            Cli.getInferErrorCode("Root.x: mixes objects and values; cannot infer one type"));
        assertEquals("algebra.infer-conflicting-scalars",
            Cli.getInferErrorCode("Root.x: has values of more than one scalar kind (integer, string)"));
        assertEquals("document.parse-error", Cli.getInferErrorCode(null));
        assertEquals("document.parse-error", Cli.getInferErrorCode("some unrelated message"));
    }

    @Test
    void testUnrecognizedOption() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"format", "-", "--unknown-flag"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unrecognized option: --unknown-flag"));
    }

    @Test
    void testMissingOptionValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(new String[]{"format", "-", "--to"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: --to"));

        err.reset();
        code = execute(new String[]{"format", "-", "-o"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: -o"));

        err.reset();
        code = execute(new String[]{"format", "-", "--from"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: --from"));

        err.reset();
        code = execute(new String[]{"validate", "-", "--schema"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: --schema"));

        err.reset();
        code = execute(new String[]{"schema", "extract", "-", "--keep"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: --keep"));

        err.reset();
        code = execute(new String[]{"schema", "is-empty", "-", "--result-format"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: --result-format"));

        err.reset();
        code = execute(new String[]{"schema", "lint", "-", "--severity"}, "", out, err);
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Missing value for option: --severity"));
    }

    @Test
    void testDebugFlagGateStackTrace() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        // Default: No debug flag -> only error message, no Java stack trace
        int code = execute(new String[]{"format", "non_existent_file.oml"}, "", out, err);
        assertEquals(2, code);
        String errStr = err.toString(StandardCharsets.UTF_8);
        assertTrue(errStr.contains("Error:"));
        assertFalse(errStr.contains("	at dev.omnist.cli.Cli"));

        // With --debug -> prints stack trace
        err.reset();
        code = execute(new String[]{"format", "non_existent_file.oml", "--debug"}, "", out, err);
        assertEquals(2, code);
        String errDebug = err.toString(StandardCharsets.UTF_8);
        assertTrue(errDebug.contains("Error:"));
        assertTrue(errDebug.contains("	at dev.omnist.cli.Cli"));

        // With -v -> prints stack trace
        err.reset();
        code = execute(new String[]{"format", "non_existent_file.oml", "-v"}, "", out, err);
        assertEquals(2, code);
        String errV = err.toString(StandardCharsets.UTF_8);
        assertTrue(errV.contains("Error:"));
        assertTrue(errV.contains("	at dev.omnist.cli.Cli"));
    }

}
