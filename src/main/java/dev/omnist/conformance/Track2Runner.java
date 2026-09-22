package dev.omnist.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omnist.algebra.AnyFallback;
import dev.omnist.algebra.InferResult;
import dev.omnist.algebra.LintFinding;
import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.codec.*;
import dev.omnist.document.*;
import dev.omnist.oml.OmlParseException;
import dev.omnist.oml.OmlReader;
import dev.omnist.oml.OmlWriter;
import dev.omnist.schema.Field;
import dev.omnist.schema.OsdParseException;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.OsdWriter;
import dev.omnist.schema.Record;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;
import dev.omnist.validation.Materializer;
import dev.omnist.validation.ValidationDiagnostic;
import dev.omnist.validation.ValidationException;
import dev.omnist.validation.ValidationResult;
import dev.omnist.validation.Validator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Track 2 of the conformance harness: runs the omnist-spec's JSON-vector test suite
 * (omnist-spec §8.5) directly in-process against this port's public API (no CLI
 * subprocess), dispatching each vector's operation and comparing the result or
 * diagnostics against the vector's expected outcome.
 */
public final class Track2Runner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int passCount = 0;
    private static int failCount = 0;
    private static int skipCount = 0;

    /** Skip tally by reason: a skip is only ever reported with a reason (omnist-spec E-20). */
    private static final Map<String, Integer> skipReasons = new TreeMap<>();

    /**
     * The {@code declared_max_*} keys this runner can honour by configuring the OML reader.
     * Every other {@code declared_max_*} key is skipped, never run against this port's defaults
     * (test-suite/README.md, "Declared-limit keys").
     */
    private static final Set<String> HANDLED_LIMIT_KEYS = Set.of(
        "declared_max_depth", "declared_max_nodes", "declared_max_int_digits");

    private Track2Runner() {}

    /** Returns a copy of the skip tally by reason, for the harness summary. */
    public static Map<String, Integer> skipReasons() {
        return new TreeMap<>(skipReasons);
    }

    private static void skip(String name, String reason) {
        System.out.println("    [SKIP] " + name + " (" + reason + ")");
        skipCount++;
        skipReasons.merge(reason, 1, Integer::sum);
    }

    /**
     * Returns the reason a vector must be skipped because of a declared-limit key this runner
     * cannot honour, or {@code null} if it can run.
     */
    private static String unhonouredLimitKey(JsonNode input) {
        String format = input.has("format") ? input.get("format").asText() : "oml";
        for (Iterator<String> it = input.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!key.startsWith("declared_max_")) {
                continue;
            }
            if ("declared_max_alias_expansion".equals(key)) {
                return "E-20 not yet implemented: YAML alias expansion limit, D-18/D-19/D-20 (omnist-spec section 9.4, DIV-3); "
                    + "declared_max_alias_expansion is allowlisted so the vector is not run against the default";
            }
            if (!HANDLED_LIMIT_KEYS.contains(key)) {
                return "unrecognised limit key " + key + ": not run against this port's default";
            }
            if (!"oml".equalsIgnoreCase(format)) {
                return "no configuration surface for " + key + " on " + format + " (only the OML reader's limits are configurable): skipped per test-suite/README 'Declared-limit keys', never run against the default";
            }
        }
        return null;
    }

    /**
     * Runs every JSON vector under {@code testSuiteDir} and returns the tally.
     *
     * @param testSuiteDir directory containing the Track 2 JSON vector files
     * @return a 3-element array {@code {pass, fail, skip}}
     * @throws Exception if a vector cannot be read or parsed
     */
    public static int[] runTrack2(Path testSuiteDir) throws Exception {
        passCount = 0;
        failCount = 0;
        skipCount = 0;
        skipReasons.clear();
        doRunTrack2(testSuiteDir);
        return new int[]{passCount, failCount, skipCount};
    }

    private static void doRunTrack2(Path testSuiteDir) throws Exception {
        try (Stream<Path> stream = Files.walk(testSuiteDir)) {
            List<Path> jsonFiles = stream
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();

            for (Path jsonFile : jsonFiles) {
                String relPath = testSuiteDir.relativize(jsonFile).toString().replace('\\', '/');
                System.out.println("  Processing vectors in: " + relPath);
                
                JsonNode root = MAPPER.readTree(Files.readString(jsonFile, StandardCharsets.UTF_8));
                JsonNode vectors = root.get("vectors");
                if (vectors == null) continue;

                for (JsonNode vector : vectors) {
                    runVector(vector);
                }
            }
        }
    }

    private static void runVector(JsonNode vector) {
        String name = vector.get("name").asText();
        String op = vector.get("operation").asText();
        JsonNode input = vector.get("input");
        JsonNode expect = vector.get("expect");

        String limitSkip = unhonouredLimitKey(input);
        if (limitSkip != null) {
            skip(name, limitSkip);
            return;
        }

        try {
            switch (op) {
                case "parse" -> runParseVector(input, expect);
                case "parse_schema" -> runParseSchemaVector(input, expect);
                case "validate" -> runValidateVector(input, expect);
                case "materialize" -> runMaterializeVector(input, expect);
                case "write" -> runWrite(input, expect);
                case "compatible_with" -> runCompatibleWithVector(input, expect);
                case "equivalent" -> runEquivalentVector(input, expect);
                case "is_empty" -> runIsEmptyVector(input, expect);
                case "normalize" -> runNormalizeVector(input, expect);
                case "prune" -> runPruneVector(input, expect);
                case "extract" -> runExtractVector(input, expect);
                case "infer" -> runInferVector(input, expect, false);
                case "infer_with_report" -> runInferVector(input, expect, true);
                case "lint" -> runLintVector(input, expect);
                case "parse_schema_oml", "write_schema_oml", "schema_from_document", "schema_to_document" ->
                    skip(name, "E-20 not yet implemented: OSD-OML extension, operation " + op + " (omnist-j#105)");
                // An operation this runner has never heard of is a failure to notice, not a skip.
                default -> throw new IllegalArgumentException("unknown operation " + op);
            }
        } catch (Throwable t) {
            System.err.println("    [FAIL] " + name + " failed: " + t.getMessage());
            t.printStackTrace();
            failCount++;
        }
    }

    private static void runParseVector(JsonNode input, JsonNode expect) throws Exception {
        if (input.has("bytes_hex")) {
            runParseVectorBytesHex(input, expect);
            return;
        }
        String text = input.get("text").asText();
        String format = input.has("format") ? input.get("format").asText() : "oml";
        boolean expectedOk = expect.get("ok").asBoolean();

        dev.omnist.document.Limits limits = dev.omnist.document.Limits.DEFAULT;
        if (input.has("declared_max_depth")) {
            int d = input.get("declared_max_depth").asInt();
            limits = new dev.omnist.document.Limits(d, limits.maxNodeCount(), limits.maxIntegerDigits());
        }
        if (input.has("declared_max_nodes")) {
            int n = input.get("declared_max_nodes").asInt();
            limits = new dev.omnist.document.Limits(limits.maxDepth(), n, limits.maxIntegerDigits());
        }
        if (input.has("declared_max_int_digits")) {
            int dig = input.get("declared_max_int_digits").asInt();
            limits = new dev.omnist.document.Limits(limits.maxDepth(), limits.maxNodeCount(), dig);
        }

        Document actualDoc = null;
        Throwable thrown = null;
        WriteReport readReport = new WriteReport();
        try {
            actualDoc = parseFormat(text, format, limits, readReport);
        } catch (Throwable ex) {
            thrown = ex;
        }

        if (expectedOk) {
            if (thrown != null) {
                throw new RuntimeException("Expected parse success, but got exception: " + thrown.getMessage(), thrown);
            }
            Document expectedDoc = decodeJsonDoc(expect.get("document"));
            if (!actualDoc.equals(expectedDoc) && !isEquivalentDoc(actualDoc, expectedDoc)) {
                throw new RuntimeException("Parsed document does not match expected document");
            }
            if (expect.has("diagnostics")) {
                compareDiagnostics(readReport.adjustments(), expect.get("diagnostics"));
            }
            passCount++;
            System.out.println("    [PASS] parse:" + input.get("text").asText().replace("\n", "\\n"));
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected parse failure, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags = extractParserDiagnostics(thrown);
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] parse error:" + input.get("text").asText().replace("\n", "\\n"));
        }
    }

    private static void runParseSchemaVector(JsonNode input, JsonNode expect) throws Exception {
        if (input.has("bytes_hex")) {
            runParseSchemaVectorBytesHex(input, expect);
            return;
        }
        String text = input.get("text").asText();
        boolean expectedOk = expect.get("ok").asBoolean();

        Schema actualSchema = null;
        Throwable thrown = null;
        try {
            actualSchema = OsdReader.read(text);
        } catch (Throwable ex) {
            thrown = ex;
        }

        if (expectedOk) {
            if (thrown != null) {
                throw new RuntimeException("Expected parse_schema success, but got: " + thrown.getMessage(), thrown);
            }
            if (expect.has("schema")) {
                // omnist-spec Sec3.3/Sec5.9: canonical OSD output is byte-for-byte
                // deterministic, so a determinism-regression vector's expect.schema is
                // compared as exact text, not merely re-parsed for structural equality
                // (the gap the TypeScript sweep review found: a structural comparison
                // would pass even if OsdWriter mis-orders records, mis-escapes a label
                // (OSD-15), or otherwise deviates from the one canonical spelling).
                String expectedSchemaText = normalizeNewlines(expect.get("schema").asText());
                String actualSchemaText = normalizeNewlines(OsdWriter.write(actualSchema));
                if (!actualSchemaText.equals(expectedSchemaText)) {
                    throw new RuntimeException("parse_schema canonical output mismatch. Expected: ["
                            + expectedSchemaText + "] Actual: [" + actualSchemaText + "]");
                }
            }
            passCount++;
            System.out.println("    [PASS] parse_schema success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected parse_schema failure, but it succeeded");
            }
            compareJsonDiagnostics(extractParserDiagnostics(thrown), expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] parse_schema error");
        }
    }

    private static String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n");
    }

    /**
     * D-14 / E-27: presents a {@code bytes_hex} {@code parse} vector's raw bytes to the CLI's
     * byte-oriented entry point ({@code omnist format - --from &lt;format&gt; --json}, fed via
     * stdin) rather than decoding them in this runner with a lossy repair. On success, the CLI's
     * stdout is the reformatted document (OML by default) which is re-parsed and compared to
     * {@code expect.document}; on failure, the CLI's {@code --json} error payload's
     * {@code (path, code)} is compared to {@code expect.diagnostics}, exactly as the text-input
     * path does via {@link #compareJsonDiagnostics}.
     */
    private static void runParseVectorBytesHex(JsonNode input, JsonNode expect) throws Exception {
        byte[] bytes = hexToBytes(input.get("bytes_hex").asText());
        String format = input.has("format") ? input.get("format").asText() : "oml";
        boolean expectedOk = expect.get("ok").asBoolean();

        CliResult result = runCli(new String[] {"format", "-", "--from", format, "--json"}, bytes);

        if (expectedOk) {
            if (result.exitCode() != 0) {
                throw new RuntimeException("Expected parse success (bytes_hex), but CLI exited " + result.exitCode()
                        + ": " + result.stdout() + result.stderr());
            }
            Document actualDoc = OmlReader.read(result.stdout());
            Document expectedDoc = decodeJsonDoc(expect.get("document"));
            if (!actualDoc.equals(expectedDoc) && !isEquivalentDoc(actualDoc, expectedDoc)) {
                throw new RuntimeException("Parsed document (bytes_hex, via CLI) does not match expected document");
            }
            passCount++;
            System.out.println("    [PASS] parse (bytes_hex):" + input.get("bytes_hex").asText());
        } else {
            if (result.exitCode() == 0) {
                throw new RuntimeException("Expected parse failure (bytes_hex), but CLI succeeded: " + result.stdout());
            }
            List<JsonDiagnostic> actualDiags = extractCliJsonDiagnostics(result.stdout());
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] parse error (bytes_hex):" + input.get("bytes_hex").asText());
        }
    }

    /**
     * D-14 / E-27, the {@code parse_schema} counterpart of {@link #runParseVectorBytesHex}:
     * presents the bytes to {@code omnist schema normalize - --json} (any command that reads an
     * OSD schema from stdin exercises the same byte-oriented decode path).
     */
    private static void runParseSchemaVectorBytesHex(JsonNode input, JsonNode expect) throws Exception {
        byte[] bytes = hexToBytes(input.get("bytes_hex").asText());
        boolean expectedOk = expect.get("ok").asBoolean();

        CliResult result = runCli(new String[] {"schema", "normalize", "-", "--json"}, bytes);

        if (expectedOk) {
            if (result.exitCode() != 0) {
                throw new RuntimeException("Expected parse_schema success (bytes_hex), but CLI exited "
                        + result.exitCode() + ": " + result.stdout() + result.stderr());
            }
            passCount++;
            System.out.println("    [PASS] parse_schema success (bytes_hex)");
        } else {
            if (result.exitCode() == 0) {
                throw new RuntimeException("Expected parse_schema failure (bytes_hex), but CLI succeeded: " + result.stdout());
            }
            List<JsonDiagnostic> actualDiags = extractCliJsonDiagnostics(result.stdout());
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] parse_schema error (bytes_hex)");
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}

    /**
     * Runs the CLI's {@code dev.omnist.cli.Cli#run} entry point in-process, feeding {@code bytes}
     * raw (undecoded) on stdin — the CLI's own byte-oriented entry point, per E-27 — and capturing
     * its exit code and streams.
     */
    private static CliResult runCli(String[] args, byte[] bytes) throws Exception {
        java.io.ByteArrayOutputStream outBaos = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream errBaos = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream outPs = new java.io.PrintStream(outBaos, true, StandardCharsets.UTF_8);
             java.io.PrintStream errPs = new java.io.PrintStream(errBaos, true, StandardCharsets.UTF_8);
             java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes)) {
            int code = dev.omnist.cli.Cli.run(args, outPs, errPs, in);
            return new CliResult(code, outBaos.toString(StandardCharsets.UTF_8), errBaos.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * Parses a {@code --json} error payload the CLI printed on stdout (the same
     * {@code {"ok":false,"errors":[{"path":...,"code":...}]}} shape {@link #extractParserDiagnostics}
     * builds from an in-process exception) into the same {@link JsonDiagnostic} list.
     */
    private static List<JsonDiagnostic> extractCliJsonDiagnostics(String stdout) throws Exception {
        JsonNode root = MAPPER.readTree(stdout);
        List<JsonDiagnostic> diags = new ArrayList<>();
        for (JsonNode err : root.get("errors")) {
            diags.add(new JsonDiagnostic(err.get("path").asText(), err.get("code").asText()));
        }
        return diags;
    }

    private static void runValidateVector(JsonNode input, JsonNode expect) {
        Schema schema = OsdReader.read(input.get("schema").asText());
        Document doc = decodeJsonDoc(input.get("document"));
        boolean expectedOk = expect.get("ok").asBoolean();

        ValidationResult res = Validator.validate(doc, schema);

        if (expectedOk) {
            if (!res.isValid()) {
                throw new RuntimeException("Expected document to be valid, but got: " + res.diagnostics());
            }
            passCount++;
            System.out.println("    [PASS] validate success");
        } else {
            if (res.isValid()) {
                throw new RuntimeException("Expected document to be invalid, but it was valid");
            }
            List<JsonDiagnostic> actualDiags = res.diagnostics().stream()
                .map(d -> new JsonDiagnostic(d.path(), d.code()))
                .toList();
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] validate error");
        }
    }

    private static void runMaterializeVector(JsonNode input, JsonNode expect) {
        Schema schema = OsdReader.read(input.get("schema").asText());
        Document doc = decodeJsonDoc(input.get("document"));
        boolean expectedOk = expect.get("ok").asBoolean();

        Document actualDoc = null;
        Throwable thrown = null;
        try {
            actualDoc = Materializer.materialize(doc, schema);
        } catch (Throwable ex) {
            thrown = ex;
        }

        if (expectedOk) {
            if (thrown != null) {
                throw new RuntimeException("Expected materialize success, but got: " + thrown.getMessage(), thrown);
            }
            Document expectedDoc = decodeJsonDoc(expect.get("document"));
            if (!actualDoc.equals(expectedDoc)) {
                throw new RuntimeException("Materialized document mismatch");
            }
            passCount++;
            System.out.println("    [PASS] materialize success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected materialize to fail, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags;
            if (thrown instanceof ValidationException ve) {
                actualDiags = ve.getResult().diagnostics().stream()
                    .map(d -> new JsonDiagnostic(d.path(), d.code()))
                    .toList();
            } else {
                actualDiags = extractParserDiagnostics(thrown);
            }
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] materialize error");
        }
    }

    private static void runWrite(JsonNode input, JsonNode expect) throws Exception {
        Document doc = decodeJsonDoc(input.get("document"));
        String format = input.get("format").asText();
        boolean strict = input.has("strict") && input.get("strict").asBoolean();
        boolean expectedOk = expect.get("ok").asBoolean();
        
        WriteReport report = new WriteReport();
        String actualText = null;
        Throwable thrown = null;
        
        try {
            if ("oml".equalsIgnoreCase(format)) {
                actualText = dev.omnist.oml.OmlWriter.write(doc);
            } else if ("json".equalsIgnoreCase(format)) {
                actualText = dev.omnist.codec.JsonCodec.write(doc, null, strict, report);
            } else if ("yaml".equalsIgnoreCase(format)) {
                actualText = dev.omnist.codec.YamlCodec.write(doc, strict, report);
            } else if ("toml".equalsIgnoreCase(format)) {
                actualText = dev.omnist.codec.TomlCodec.write(doc, strict, report);
            } else if ("xml".equalsIgnoreCase(format)) {
                actualText = dev.omnist.codec.XmlCodec.write(doc, strict, report);
            } else {
                throw new IllegalArgumentException("Unknown write format: " + format);
            }
        } catch (Throwable ex) {
            thrown = ex;
        }
        
        if (expectedOk) {
            if (thrown != null) {
                throw new RuntimeException("Expected write to succeed, but it threw: " + thrown.getMessage(), thrown);
            }
            if (expect.has("text")) {
                String expectedText = expect.get("text").asText();
                Document expectedDoc = parseFormat(expectedText, format);
                Document actualDoc = parseFormat(actualText, format);
                if (!actualDoc.equals(expectedDoc)) {
                    throw new RuntimeException("Actual document parsed from written text does not equal expected document");
                }
            } else if (expect.has("document")) {
                Document expectedDoc = decodeJsonDoc(expect.get("document"));
                Document actualDoc = parseFormat(actualText, format);
                if (!actualDoc.equals(expectedDoc)) {
                    throw new RuntimeException("Actual document parsed from written text does not equal expected document");
                }
            }
            if (expect.has("diagnostics")) {
                compareDiagnostics(report.adjustments(), expect.get("diagnostics"));
            }
            passCount++;
            System.out.println("    [PASS] write success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected write to fail, but it succeeded");
            }
            WriteReport errReport = null;
            if (thrown instanceof WriteException we) {
                errReport = we.report();
            }
            if (errReport == null) {
                errReport = report;
            }
            if (expect.has("diagnostics")) {
                compareDiagnostics(errReport.adjustments(), expect.get("diagnostics"));
            }
            passCount++;
            System.out.println("    [PASS] write error");
        }
    }

    private static void compareDiagnostics(List<WriteAdjustment> actual, JsonNode expectedDiagNode) {
        Set<String> actualSet = new HashSet<>();
        for (WriteAdjustment adj : actual) {
            actualSet.add(adj.path() + "|" + adj.code());
        }
        Set<String> expectedSet = new HashSet<>();
        for (JsonNode d : expectedDiagNode) {
            expectedSet.add(d.get("path").asText() + "|" + d.get("code").asText());
        }
        if (!actualSet.equals(expectedSet)) {
            throw new RuntimeException("Diagnostics mismatch. Expected: " + expectedSet + ", Actual: " + actualSet);
        }
    }

    private static void runCompatibleWithVector(JsonNode input, JsonNode expect) {
        Schema a = OsdReader.read(input.get("a").asText());
        Schema b = OsdReader.read(input.get("b").asText());
        boolean expected = expect.get("result").asBoolean();

        boolean actual = SchemaAlgebra.compatibleWith(a, b);
        if (actual == expected) {
            passCount++;
            System.out.println("    [PASS] compatible_with");
        } else {
            throw new RuntimeException("compatible_with result mismatch. Expected: " + expected + ", Got: " + actual);
        }
    }

    private static void runEquivalentVector(JsonNode input, JsonNode expect) {
        Schema a = OsdReader.read(input.get("a").asText());
        Schema b = OsdReader.read(input.get("b").asText());
        boolean expected = expect.get("result").asBoolean();

        boolean actual = SchemaAlgebra.equivalent(a, b);
        if (actual == expected) {
            passCount++;
            System.out.println("    [PASS] equivalent");
        } else {
            throw new RuntimeException("equivalent result mismatch. Expected: " + expected + ", Got: " + actual);
        }
    }

    private static void runIsEmptyVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        boolean expected = expect.get("empty").asBoolean();

        boolean actual = SchemaAlgebra.isEmpty(s);
        if (actual == expected) {
            passCount++;
            System.out.println("    [PASS] is_empty");
        } else {
            throw new RuntimeException("is_empty result mismatch. Expected: " + expected + ", Got: " + actual);
        }
    }

    private static void runNormalizeVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        String expectedText = expect.get("schema").asText().replace("\r\n", "\n");

        Schema normalized = SchemaAlgebra.normalize(s);
        String actualText = OsdWriter.write(normalized).replace("\r\n", "\n");
        if (actualText.equals(expectedText)) {
            passCount++;
            System.out.println("    [PASS] normalize");
        } else {
            throw new RuntimeException("normalize output mismatch. Expected:\n" + expectedText + "\nActual:\n" + actualText);
        }
    }

    private static void runPruneVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        String expectedText = expect.get("schema").asText().replace("\r\n", "\n");

        Schema pruned = SchemaAlgebra.prune(s);
        String actualText = OsdWriter.write(pruned).replace("\r\n", "\n");
        if (actualText.equals(expectedText)) {
            passCount++;
            System.out.println("    [PASS] prune");
        } else {
            throw new RuntimeException("prune output mismatch. Expected:\n" + expectedText + "\nActual:\n" + actualText);
        }
    }

    private static void runExtractVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        JsonNode keepNode = input.get("keep");
        Set<String> keep = new LinkedHashSet<>();
        for (JsonNode k : keepNode) {
            keep.add(k.asText());
        }
        boolean expectedOk = expect.get("ok").asBoolean();

        Schema actualSchema = null;
        Throwable thrown = null;
        try {
            actualSchema = SchemaAlgebra.extract(s, keep);
        } catch (Throwable ex) {
            thrown = ex;
        }

        if (expectedOk) {
            if (thrown != null) {
                throw new RuntimeException("Expected extract success, but got: " + thrown.getMessage(), thrown);
            }
            Schema expectedSchema = OsdReader.read(expect.get("schema").asText());
            if (!exactSchemaEqual(actualSchema, expectedSchema)) {
                throw new RuntimeException("Extracted schema mismatch");
            }
            passCount++;
            System.out.println("    [PASS] extract success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected extract to fail, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags = extractParserDiagnostics(thrown);
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] extract error");
        }
    }

    private static void runInferVector(JsonNode input, JsonNode expect, boolean withReport) {
        JsonNode samplesNode = input.get("samples");
        List<Document> samples = new ArrayList<>();
        for (JsonNode s : samplesNode) {
            if (s.isTextual()) {
                samples.add(OmlReader.read(s.asText()));
            } else {
                samples.add(decodeJsonDoc(s));
            }
        }
        boolean allowAny = input.has("allow_any") && input.get("allow_any").asBoolean();
        boolean expectedOk = expect.get("ok").asBoolean();

        Schema actualSchema = null;
        List<AnyFallback> fallbacks = null;
        Throwable thrown = null;
        try {
            if (withReport) {
                InferResult res = SchemaAlgebra.inferWithReport(samples, "Root", allowAny);
                actualSchema = res.schema();
                fallbacks = res.fallbacks();
            } else {
                actualSchema = SchemaAlgebra.infer(samples, "Root", allowAny);
            }
        } catch (Throwable ex) {
            thrown = ex;
        }

        if (expectedOk) {
            if (thrown != null) {
                throw new RuntimeException("Expected infer success, but got: " + thrown.getMessage(), thrown);
            }
            Schema expectedSchema = OsdReader.read(expect.get("schema").asText());
            if (!isomorphicSchemaEqual(actualSchema, expectedSchema)) {
                throw new RuntimeException("Inferred schema is not isomorphic to expected schema.\nExpected:\n" + OsdWriter.write(expectedSchema) + "\nGot:\n" + OsdWriter.write(actualSchema));
            }
            if (withReport && expect.has("fallbacks")) {
                compareFallbacks(fallbacks, expect.get("fallbacks"));
            }
            passCount++;
            System.out.println("    [PASS] infer success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected infer to fail, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags = extractParserDiagnostics(thrown);
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            passCount++;
            System.out.println("    [PASS] infer error");
        }
    }

    private static void compareFallbacks(List<AnyFallback> actual, JsonNode expectedNode) {
        Set<String> act = actual.stream()
            .map(f -> f.location() + "|" + f.reason())
            .collect(Collectors.toSet());
        Set<String> exp = new HashSet<>();
        for (JsonNode n : expectedNode) {
            exp.add(n.get("location").asText() + "|" + n.get("reason").asText());
        }
        if (!act.equals(exp)) {
            throw new RuntimeException("Fallbacks mismatch. Expected: " + exp + ", Got: " + act);
        }
    }

    private static void runLintVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        boolean expectedOk = expect.get("ok").asBoolean();

        List<LintFinding> findings = SchemaAlgebra.lint(s);
        boolean hasWarning = findings.stream().anyMatch(f -> "warning".equals(f.severity()));
        boolean actualOk = !hasWarning;

        if (actualOk == expectedOk) {
            compareFindings(findings, expect.get("findings"));
            passCount++;
            System.out.println("    [PASS] lint");
        } else {
            throw new RuntimeException("lint ok mismatch. Expected ok: " + expectedOk + ", Got findings: " + findings);
        }
    }

    private static void compareFindings(List<LintFinding> actual, JsonNode expectedNode) {
        Set<String> act = actual.stream()
            .map(f -> f.code() + "|" + f.severity() + "|" + f.location())
            .collect(Collectors.toSet());
        Set<String> exp = new HashSet<>();
        if (expectedNode != null) {
            for (JsonNode n : expectedNode) {
                exp.add(n.get("code").asText() + "|" + n.get("severity").asText() + "|" + n.get("location").asText());
            }
        }
        if (!act.equals(exp)) {
            throw new RuntimeException("Lint findings mismatch. Expected: " + exp + ", Got: " + act);
        }
    }

    private static record JsonDiagnostic(String path, String code) {}

    private static void compareJsonDiagnostics(List<JsonDiagnostic> actual, JsonNode expectedNode) {
        // omnist-spec section 8.5.2 (E-17): compare the diagnostics as a SET of (path, code);
        // no partial matching, no path or code loosening.
        Set<String> act = actual.stream()
            .map(d -> d.path() + "|" + d.code())
            .collect(Collectors.toSet());
        Set<String> exp = new HashSet<>();
        if (expectedNode != null) {
            for (JsonNode n : expectedNode) {
                exp.add(n.get("path").asText() + "|" + n.get("code").asText());
            }
        }
        if (!act.equals(exp)) {
            throw new RuntimeException("Diagnostics mismatch. Expected: " + sorted(exp) + ", Got: " + sorted(act));
        }
    }

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }

    private static boolean exactSchemaEqual(Schema s1, Schema s2) {
        if (!s1.root().equals(s2.root())) return false;
        if (!s1.records().keySet().equals(s2.records().keySet())) return false;
        for (String name : s1.records().keySet()) {
            dev.omnist.schema.Record r1 = s1.records().get(name);
            dev.omnist.schema.Record r2 = s2.records().get(name);
            if (!recordEqualOrderInsensitive(r1, r2)) return false;
        }
        return true;
    }

    private static boolean recordEqualOrderInsensitive(dev.omnist.schema.Record r1, dev.omnist.schema.Record r2) {
        if (!r1.name().equals(r2.name())) return false;
        if (r1.fields().size() != r2.fields().size()) return false;
        Map<String, Field> f1 = new HashMap<>();
        for (Field f : r1.fields()) f1.put(f.label(), f);
        Map<String, Field> f2 = new HashMap<>();
        for (Field f : r2.fields()) f2.put(f.label(), f);
        if (!f1.keySet().equals(f2.keySet())) return false;
        for (String label : f1.keySet()) {
            Field field1 = f1.get(label);
            Field field2 = f2.get(label);
            if (!fieldEqual(field1, field2)) return false;
        }
        return true;
    }

    private static boolean fieldEqual(Field f1, Field f2) {
        if (f1.min() != f2.min()) return false;
        if (!Objects.equals(f1.max(), f2.max())) return false;
        return typeEqual(f1.type(), f2.type());
    }

    private static boolean typeEqual(Type t1, Type t2) {
        if (t1 instanceof Type.Scalar s1 && t2 instanceof Type.Scalar s2) {
            return s1.kind() == s2.kind() && s1.nullable() == s2.nullable();
        }
        if (t1 instanceof Type.Ref r1 && t2 instanceof Type.Ref r2) {
            return r1.name().equals(r2.name());
        }
        if (t1 instanceof Type.Any && t2 instanceof Type.Any) {
            return true;
        }
        return false;
    }

    private static boolean isomorphicSchemaEqual(Schema s1, Schema s2) {
        if (s1.records().size() != s2.records().size()) return false;
        List<String> names1 = new ArrayList<>(s1.records().keySet());
        List<String> names2 = new ArrayList<>(s2.records().keySet());
        
        Map<String, String> f = new HashMap<>();
        f.put(s1.root(), s2.root());
        Set<String> used = new HashSet<>();
        used.add(s2.root());
        
        return backtrackIsomorphism(0, names1, names2, f, used, s1, s2);
    }

    private static boolean backtrackIsomorphism(int index, List<String> names1, List<String> names2, 
                                                Map<String, String> f, Set<String> used, Schema s1, Schema s2) {
        if (index == names1.size()) {
            return verifyIsomorphismBijection(f, s1, s2);
        }
        String n1 = names1.get(index);
        if (f.containsKey(n1)) {
            return backtrackIsomorphism(index + 1, names1, names2, f, used, s1, s2);
        }
        for (String n2 : names2) {
            if (!used.contains(n2)) {
                f.put(n1, n2);
                used.add(n2);
                if (backtrackIsomorphism(index + 1, names1, names2, f, used, s1, s2)) {
                    return true;
                }
                used.remove(n2);
                f.remove(n1);
            }
        }
        return false;
    }

    private static boolean verifyIsomorphismBijection(Map<String, String> f, Schema s1, Schema s2) {
        for (String n1 : s1.records().keySet()) {
            String n2 = f.get(n1);
            dev.omnist.schema.Record r1 = s1.records().get(n1);
            dev.omnist.schema.Record r2 = s2.records().get(n2);
            if (r2 == null) return false;
            if (r1.fields().size() != r2.fields().size()) return false;
            
            Map<String, Field> fields1 = new HashMap<>();
            for (Field field : r1.fields()) fields1.put(field.label(), field);
            Map<String, Field> fields2 = new HashMap<>();
            for (Field field : r2.fields()) fields2.put(field.label(), field);
            
            if (!fields1.keySet().equals(fields2.keySet())) return false;
            for (String label : fields1.keySet()) {
                Field f1 = fields1.get(label);
                Field f2 = fields2.get(label);
                if (f1.min() != f2.min()) return false;
                if (!Objects.equals(f1.max(), f2.max())) return false;
                
                Type t1 = f1.type();
                Type t2 = f2.type();
                if (t1 instanceof Type.Scalar sc1 && t2 instanceof Type.Scalar sc2) {
                    if (sc1.kind() != sc2.kind() || sc1.nullable() != sc2.nullable()) return false;
                } else if (t1 instanceof Type.Ref ref1 && t2 instanceof Type.Ref ref2) {
                    String mappedRefName = f.get(ref1.name());
                    if (mappedRefName == null || !mappedRefName.equals(ref2.name())) return false;
                } else if (t1 instanceof Type.Any && t2 instanceof Type.Any) {
                    // OK
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Turns a thrown exception into the (path, code) diagnostic it carries. Only the port's own
     * structured exceptions are accepted: a throwable that carries no {@code code}/{@code path}
     * is a conformance failure (the spec requires both, section 8.2), never something to guess a
     * code for by reading the message text.
     */
    private static List<JsonDiagnostic> extractParserDiagnostics(Throwable ex) {
        if (ex instanceof dev.omnist.document.DocumentParseException dpe) {
            return List.of(new JsonDiagnostic(dpe.getPath(), dpe.getCode()));
        }
        if (ex instanceof dev.omnist.algebra.AlgebraException ae) {
            return List.of(new JsonDiagnostic(ae.getPath(), ae.getCode()));
        }
        if (ex instanceof OmlParseException ope) {
            return List.of(new JsonDiagnostic(ope.getPath(), ope.getCode()));
        }
        if (ex instanceof OsdParseException osd) {
            return List.of(new JsonDiagnostic(osd.getPath(), osd.getCode()));
        }
        throw new RuntimeException("Unstructured failure (no code/path): " + ex.getClass().getName() + ": " + ex.getMessage(), ex);
    }

    private static Document parseFormat(String text, String format) throws Exception {
        return parseFormat(text, format, dev.omnist.document.Limits.DEFAULT);
    }

    private static Document parseFormat(String text, String format, dev.omnist.document.Limits limits) throws Exception {
        return parseFormat(text, format, limits, null);
    }

    private static Document parseFormat(String text, String format, dev.omnist.document.Limits limits, WriteReport report) throws Exception {
        if ("oml".equalsIgnoreCase(format)) {
            return dev.omnist.oml.OmlReader.read(text, limits);
        } else if ("json".equalsIgnoreCase(format)) {
            return dev.omnist.codec.JsonCodec.read(text);
        } else if ("yaml".equalsIgnoreCase(format)) {
            return dev.omnist.codec.YamlCodec.read(text);
        } else if ("toml".equalsIgnoreCase(format)) {
            return dev.omnist.codec.TomlCodec.read(text);
        } else if ("xml".equalsIgnoreCase(format)) {
            return dev.omnist.codec.XmlCodec.read(text, null, report);
        } else {
            throw new IllegalArgumentException("Unknown format: " + format);
        }
    }

    private static Document decodeJsonDoc(JsonNode node) {
        if (node == null || node.isNull()) {
            return Value.NULL;
        }
        if (node.has("scalar")) {
            JsonNode scalar = node.get("scalar");
            JsonNode kindNode = scalar.get("kind");
            JsonNode valNode = scalar.get("value");
            if (kindNode == null || kindNode.isNull()) {
                return Value.NULL;
            }
            String kind = kindNode.asText();
            if (valNode == null || valNode.isNull()) {
                return Value.NULL;
            }
            return switch (kind) {
                case "string" -> new Scalar.StringScalar(valNode.asText());
                case "boolean" -> new Scalar.BooleanScalar(valNode.asBoolean());
                case "integer" -> new Scalar.IntegerScalar(new java.math.BigInteger(valNode.asText()));
                case "number" -> {
                    String s = valNode.asText();
                    double d;
                    if ("nan".equals(s)) d = Double.NaN;
                    else if ("inf".equals(s)) d = Double.POSITIVE_INFINITY;
                    else if ("-inf".equals(s)) d = Double.NEGATIVE_INFINITY;
                    else d = valNode.asDouble();
                    yield new Scalar.NumberScalar(d);
                }
                case "date" -> new Scalar.DateScalar(java.time.LocalDate.parse(valNode.asText()));
                case "time" -> new Scalar.TimeScalar(parseTimeValue(valNode.asText()));
                case "datetime" -> new Scalar.DateTimeScalar(parseDateTimeValue(valNode.asText()));
                default -> throw new IllegalArgumentException("Unknown scalar kind: " + kind);
            };
        } else if (node.has("edges")) {
            JsonNode edgesNode = node.get("edges");
            List<Edge> edges = new ArrayList<>();
            for (JsonNode edgeNode : edgesNode) {
                String label = edgeNode.get(0).asText();
                Document target = decodeJsonDoc(edgeNode.get(1));
                edges.add(new Edge(label, (Target) target));
            }
            return new dev.omnist.document.Node(edges);
        } else {
            throw new IllegalArgumentException("Invalid canonical JSON document shape: " + node.toString());
        }
    }

    private static DateTimeValue parseDateTimeValue(String text) {
        return dev.omnist.document.DateTimeValue.parse(text);
    }

    private static dev.omnist.document.TimeValue parseTimeValue(String text) {
        return dev.omnist.document.TimeValue.parse(text);
    }

    private static boolean isEquivalentDoc(dev.omnist.document.Document a, dev.omnist.document.Document b) {
        if (a.equals(b)) return true;
        if (a instanceof dev.omnist.document.Node na && b instanceof dev.omnist.document.Node nb) {
            if (na.edges().size() != nb.edges().size()) return false;
            java.util.Map<String, java.util.List<dev.omnist.document.Target>> mapA = new java.util.HashMap<>();
            for (dev.omnist.document.Edge e : na.edges()) {
                mapA.computeIfAbsent(e.label(), k -> new java.util.ArrayList<>()).add(e.target());
            }
            java.util.Map<String, java.util.List<dev.omnist.document.Target>> mapB = new java.util.HashMap<>();
            for (dev.omnist.document.Edge e : nb.edges()) {
                mapB.computeIfAbsent(e.label(), k -> new java.util.ArrayList<>()).add(e.target());
            }
            if (!mapA.keySet().equals(mapB.keySet())) return false;
            for (String k : mapA.keySet()) {
                java.util.List<dev.omnist.document.Target> listA = mapA.get(k);
                java.util.List<dev.omnist.document.Target> listB = mapB.get(k);
                if (listA.size() != listB.size()) return false;
                for (int i = 0; i < listA.size(); i++) {
                    if (!isEquivalentDoc((dev.omnist.document.Document) listA.get(i), (dev.omnist.document.Document) listB.get(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

}
