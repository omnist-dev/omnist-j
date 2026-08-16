package dev.omnist.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omnist.algebra.AnyFallback;
import dev.omnist.algebra.InferResult;
import dev.omnist.algebra.LintFinding;
import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.document.Document;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.OsdWriter;
import dev.omnist.schema.Schema;
import dev.omnist.validation.Materializer;
import dev.omnist.validation.ValidationDiagnostic;
import dev.omnist.validation.ValidationException;
import dev.omnist.validation.ValidationResult;
import dev.omnist.validation.Validator;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Implements every {@code omnist} CLI command: {@code format}, {@code validate},
 * {@code convert}, {@code schema} (with its {@code normalize}/{@code prune}/{@code extract}/
 * {@code compatible-with}/{@code equivalent}/{@code is-empty}/{@code lint} subcommands),
 * and {@code infer}. See {@code docs/02-cli-reference.md} for the full command reference
 * with worked examples. This class carries all testable CLI logic; {@link CliMain} is
 * just the JVM entry point that calls {@link #run} and exits with its status code.
 */
public final class Cli {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cli() {}

    /**
     * Parsed flags shared across every subcommand. Not every command reads every
     * field — each command's own {@code run*} method documents which ones it uses.
     *
     * @param compact      {@code --compact}: write in compact form where the target format supports it
     * @param fromFormat   {@code --from}: input format name; {@code null} defaults to {@code oml}
     * @param toFormat     {@code --to}: output format name; {@code null} defaults to {@code oml}
     * @param schemaPath   {@code --schema}: path (or {@code -} for stdin) to an OSD schema file
     * @param keepLabels   {@code --keep}: comma-separated record names, for {@code schema extract}
     * @param resultFormat {@code --result-format}: when {@code "json"}, boolean-result subcommands
     *                     print a JSON object instead of relying solely on the exit code
     * @param json         {@code --json}: emit machine-readable JSON error/result payloads on stdout
     * @param allowAny     {@code --allow-any}: for {@code infer}, fall back to {@code any} on
     *                     conflicting scalar kinds instead of failing
     * @param outputPath   {@code -o}: write output to this path instead of stdout
     * @param severity     {@code --severity}: parsed for forward compatibility but not yet
     *                     read by any command
     */
    private record Options(
        boolean compact,
        String fromFormat,
        String toFormat,
        String schemaPath,
        String keepLabels,
        String resultFormat,
        boolean json,
        boolean allowAny,
        String outputPath,
        String severity
    ) {}

    /**
     * Parses and executes a single CLI invocation.
     *
     * @param args command-line arguments, exactly as passed to {@code main}
     * @param out  stream for normal command output
     * @param err  stream for error and usage messages
     * @param in   stream read for {@code -} (stdin) input arguments
     * @return the process exit code: {@code 0} on success, {@code 1} for a usage or
     *         processing error, {@code 2} for specific commands' documented "failed
     *         check" outcome (e.g. {@code validate} on a non-conforming document)
     */
    public static int run(String[] args, PrintStream out, PrintStream err, InputStream in) {
        try {
            List<String> positionals = new ArrayList<>();
            boolean compact = false;
            String fromFormat = null;
            String toFormat = null;
            String schemaPath = null;
            String keepLabels = null;
            String resultFormat = null;
            boolean json = false;
            boolean allowAny = false;
            String outputPath = null;
            String severity = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--compact")) {
                    compact = true;
                } else if (arg.equals("-o")) {
                    if (i + 1 < args.length) {
                        outputPath = args[++i];
                    }
                } else if (arg.equals("--from")) {
                    if (i + 1 < args.length) {
                        fromFormat = args[++i];
                    }
                } else if (arg.equals("--to")) {
                    if (i + 1 < args.length) {
                        toFormat = args[++i];
                    }
                } else if (arg.equals("--schema")) {
                    if (i + 1 < args.length) {
                        schemaPath = args[++i];
                    }
                } else if (arg.equals("--keep")) {
                    if (i + 1 < args.length) {
                        keepLabels = args[++i];
                    }
                } else if (arg.equals("--result-format")) {
                    if (i + 1 < args.length) {
                        resultFormat = args[++i];
                    }
                } else if (arg.equals("--json")) {
                    json = true;
                } else if (arg.equals("--allow-any")) {
                    allowAny = true;
                } else if (arg.equals("--severity")) {
                    if (i + 1 < args.length) {
                        severity = args[++i];
                    }
                } else if (arg.startsWith("-") && !arg.equals("-")) {
                    // Ignore or skip unrecognized options
                } else {
                    positionals.add(arg);
                }
            }

            if (positionals.isEmpty()) {
                err.println("Usage: omnist [command] [args]");
                return 2;
            }

            Options opts = new Options(compact, fromFormat, toFormat, schemaPath, keepLabels,
                resultFormat, json, allowAny, outputPath, severity);
            String cmd = positionals.get(0);

            return switch (cmd) {
                case "format" -> runFormat(positionals, opts, out, err, in);
                case "validate" -> runValidate(positionals, opts, out, err, in);
                case "convert" -> runConvert(positionals, opts, out, err, in);
                case "schema" -> runSchema(positionals, opts, out, err, in);
                case "infer" -> runInfer(positionals, opts, out, err, in);
                default -> {
                    err.println("Unknown command: " + cmd);
                    yield 2;
                }
            };

        } catch (Exception ex) {
            err.println("Error: " + ex.getMessage());
            ex.printStackTrace(err);
            return 2;
        }
    }

    /**
     * {@code omnist format <input> [--from FORMAT] [--to FORMAT] [--compact] [-o OUTPUT]}:
     * reads a document in one format and re-emits it in another with no validation.
     */
    private static int runFormat(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 2) {
            err.println("Missing format input file");
            return 2;
        }
        String content = readInput(positionals.get(1), in);
        Document doc = readDocument(opts.fromFormat(), content, null);
        String formatted = writeDocument(opts.toFormat(), doc, opts.compact());
        writeOutput(opts.outputPath(), formatted, out);
        return 0;
    }

    /**
     * {@code omnist validate <input> --schema SCHEMA [--json]}: checks a document
     * against a schema and reports every diagnostic on failure.
     */
    private static int runValidate(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 2) {
            err.println("Missing validate input file");
            return 2;
        }
        if (opts.schemaPath() == null) {
            err.println("Missing --schema parameter");
            return 2;
        }
        Schema schema = OsdReader.read(readInput(opts.schemaPath(), in));
        Document doc = readDocument(opts.fromFormat(), readInput(positionals.get(1), in), schema);
        ValidationResult res = Validator.validate(doc, schema);

        if (opts.json()) {
            if (res.isValid()) {
                out.println("{\"ok\":true}");
                return 0;
            } else {
                List<JsonError> errors = new ArrayList<>();
                for (ValidationDiagnostic d : res.diagnostics()) {
                    errors.add(new JsonError(d.path(), d.code(), d.message()));
                }
                out.println(MAPPER.writeValueAsString(new JsonResponse(false, "Validation failed", errors)));
                return 1;
            }
        } else {
            if (res.isValid()) {
                return 0;
            } else {
                for (ValidationDiagnostic d : res.diagnostics()) {
                    err.println(d.path() + " [" + d.code() + "]: " + d.message());
                }
                return 1;
            }
        }
    }

    /**
     * {@code omnist convert <input> --schema SCHEMA [--to FORMAT] [--json]}: validates
     * and materializes a document against a schema, then re-emits it in another format.
     */
    private static int runConvert(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 2) {
            err.println("Missing convert input file");
            return 2;
        }
        if (opts.schemaPath() == null) {
            err.println("Missing --schema parameter");
            return 2;
        }
        Schema schema = OsdReader.read(readInput(opts.schemaPath(), in));
        Document doc = readDocument(opts.fromFormat(), readInput(positionals.get(1), in), schema);

        try {
            Document materialized = Materializer.materialize(doc, schema);
            String result = writeDocument(opts.toFormat(), materialized, opts.compact());
            writeOutput(opts.outputPath(), result, out);
            return 0;
        } catch (ValidationException ex) {
            if (opts.json()) {
                List<JsonError> errors = new ArrayList<>();
                for (ValidationDiagnostic d : ex.getResult().diagnostics()) {
                    errors.add(new JsonError(d.path(), d.code(), d.message()));
                }
                out.println(MAPPER.writeValueAsString(new JsonResponse(false, "Materialization failed", errors)));
            } else {
                for (ValidationDiagnostic d : ex.getResult().diagnostics()) {
                    err.println(d.path() + " [" + d.code() + "]: " + d.message());
                }
            }
            return 2;
        }
    }

    /**
     * {@code omnist schema <subcommand> ...}: dispatches to one of the seven
     * Schema Algebra subcommands (§6). Unknown subcommands and missing arguments
     * fall through to the same "Unknown command"/{@code 2} handling as an
     * unrecognized top-level command.
     */
    private static int runSchema(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 2) {
            err.println("Missing schema subcommand");
            return 2;
        }
        String subcmd = positionals.get(1);

        return switch (subcmd) {
            case "normalize" -> runSchemaNormalize(positionals, opts, out, err, in);
            case "prune" -> runSchemaPrune(positionals, opts, out, err, in);
            case "extract" -> runSchemaExtract(positionals, opts, out, err, in);
            case "is-empty" -> runSchemaIsEmpty(positionals, opts, out, err, in);
            case "compatible-with" -> runSchemaCompatibleWith(positionals, opts, out, err, in);
            case "equivalent" -> runSchemaEquivalent(positionals, opts, out, err, in);
            case "lint" -> runSchemaLint(positionals, opts, out, err, in);
            default -> {
                err.println("Unknown command: schema " + subcmd);
                yield 2;
            }
        };
    }

    /** {@code omnist schema normalize <schema>}: writes the schema's {@code normalize()} result (§6.8). */
    private static int runSchemaNormalize(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 3) {
            err.println("Missing schema input file");
            return 2;
        }
        Schema s = OsdReader.read(readInput(positionals.get(2), in));
        Schema normalized = SchemaAlgebra.normalize(s);
        writeOutput(opts.outputPath(), writeOsd(normalized, opts.compact()), out);
        return 0;
    }

    /** {@code omnist schema prune <schema>}: writes the schema's {@code prune()} result (§6.5). */
    private static int runSchemaPrune(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 3) {
            err.println("Missing schema input file");
            return 2;
        }
        Schema s = OsdReader.read(readInput(positionals.get(2), in));
        Schema pruned = SchemaAlgebra.prune(s);
        writeOutput(opts.outputPath(), writeOsd(pruned, opts.compact()), out);
        return 0;
    }

    /** {@code omnist schema extract <schema> --keep A,B,C}: writes the schema's {@code extract()} result (§6.9). */
    private static int runSchemaExtract(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 3) {
            err.println("Missing schema input file");
            return 2;
        }
        if (opts.keepLabels() == null) {
            err.println("Missing --keep labels parameter");
            return 2;
        }
        Schema s = OsdReader.read(readInput(positionals.get(2), in));
        Set<String> keep = new LinkedHashSet<>(Arrays.asList(opts.keepLabels().split(",")));
        try {
            Schema extracted = SchemaAlgebra.extract(s, keep);
            writeOutput(opts.outputPath(), writeOsd(extracted, opts.compact()), out);
            return 0;
        } catch (IllegalArgumentException ex) {
            if (opts.json()) {
                out.println(MAPPER.writeValueAsString(new JsonResponse(false, ex.getMessage(), List.of(new JsonError("$", "algebra.extract-invalidates-root", ex.getMessage())))));
            } else {
                err.println(ex.getMessage());
            }
            return 1;
        }
    }

    /** {@code omnist schema is-empty <schema>}: exits {@code 0} iff {@code is_empty()} is true (§6.4). */
    private static int runSchemaIsEmpty(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 3) {
            err.println("Missing schema input file");
            return 2;
        }
        Schema s = OsdReader.read(readInput(positionals.get(2), in));
        boolean empty = SchemaAlgebra.isEmpty(s);
        if ("json".equals(opts.resultFormat())) {
            out.println("{\"empty\":" + empty + "}");
        }
        return empty ? 0 : 1;
    }

    /** {@code omnist schema compatible-with <a> <b>}: exits {@code 0} iff {@code compatible_with(a, b)} is true (§6.6). */
    private static int runSchemaCompatibleWith(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 4) {
            err.println("Missing schema input files");
            return 2;
        }
        Schema a = OsdReader.read(readInput(positionals.get(2), in));
        Schema b = OsdReader.read(readInput(positionals.get(3), in));
        boolean comp = SchemaAlgebra.compatibleWith(a, b);
        if ("json".equals(opts.resultFormat())) {
            out.println("{\"compatible\":" + comp + "}");
        }
        return comp ? 0 : 1;
    }

    /** {@code omnist schema equivalent <a> <b>}: exits {@code 0} iff {@code equivalent(a, b)} is true (§6.7). */
    private static int runSchemaEquivalent(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 4) {
            err.println("Missing schema input files");
            return 2;
        }
        Schema a = OsdReader.read(readInput(positionals.get(2), in));
        Schema b = OsdReader.read(readInput(positionals.get(3), in));
        boolean equiv = SchemaAlgebra.equivalent(a, b);
        if ("json".equals(opts.resultFormat())) {
            out.println("{\"equivalent\":" + equiv + "}");
        }
        return equiv ? 0 : 1;
    }

    /** {@code omnist schema lint <schema> [--json]}: prints every {@code lint()} finding (§6.11). */
    private static int runSchemaLint(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 3) {
            err.println("Missing schema input file");
            return 2;
        }
        Schema s = OsdReader.read(readInput(positionals.get(2), in));
        List<LintFinding> findings = SchemaAlgebra.lint(s);

        boolean ok = true;
        for (LintFinding lf : findings) {
            if (lf.severity().equals("warning")) {
                ok = false;
            }
        }

        if (opts.json()) {
            out.println(MAPPER.writeValueAsString(new LintResponse(ok, findings)));
        } else {
            for (LintFinding lf : findings) {
                err.println(lf.severity().toUpperCase() + " [" + lf.code() + "] at " + lf.location() + ": " + lf.message());
            }
        }

        return ok ? 0 : 1;
    }

    /**
     * {@code omnist infer <sample>... [--allow-any] [--json]}: writes the OSD schema
     * inferred from one or more sample documents (§6.10).
     */
    private static int runInfer(List<String> positionals, Options opts, PrintStream out, PrintStream err, InputStream in) throws Exception {
        if (positionals.size() < 2) {
            err.println("Missing input samples");
            return 2;
        }

        List<Document> samples = new ArrayList<>();
        for (int idx = 1; idx < positionals.size(); idx++) {
            samples.add(readDocument(opts.fromFormat(), readInput(positionals.get(idx), in), null));
        }

        try {
            InferResult res = SchemaAlgebra.inferWithReport(samples, "R", opts.allowAny());
            // res.fallbacks() is always non-null (inferWithReport builds it via
            // List.copyOf), so only allowAny and emptiness are real conditions.
            if (opts.allowAny() && !res.fallbacks().isEmpty()) {
                err.println("opened " + res.fallbacks().size() + " field(s) as `any`:");
                for (AnyFallback fb : res.fallbacks()) {
                    err.println("  " + fb.location() + " — " + fb.reason());
                }
            }
            writeOutput(opts.outputPath(), writeOsd(res.schema(), opts.compact()), out);
            return 0;
        } catch (Exception ex) {
            if (opts.json()) {
                String code = getInferErrorCode(ex.getMessage());
                out.println(MAPPER.writeValueAsString(new JsonResponse(false, ex.getMessage(), List.of(new JsonError("$", code, ex.getMessage())))));
            } else {
                err.println(ex.getMessage());
            }
            return 2;
        }
    }

    private static String readInput(String path, InputStream in) throws Exception {
        if ("-".equals(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
    }

    private static void writeOutput(String path, String data, PrintStream out) throws Exception {
        if (path == null) {
            out.print(data);
        } else {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), data, StandardCharsets.UTF_8);
        }
    }

    private static Document readDocument(String format, String text, Schema schema) throws Exception {
        if (format == null) {
            format = "oml";
        }
        return switch (format.toLowerCase()) {
            case "oml" -> dev.omnist.oml.OmlReader.read(text);
            case "json" -> dev.omnist.codec.JsonCodec.read(text);
            case "yaml" -> dev.omnist.codec.YamlCodec.read(text);
            case "toml" -> dev.omnist.codec.TomlCodec.read(text);
            case "xml" -> schema != null ? dev.omnist.codec.XmlCodec.read(text, schema) : dev.omnist.codec.XmlCodec.read(text);
            default -> throw new IllegalArgumentException("Unsupported input format: " + format);
        };
    }

    private static String writeOsd(Schema s, boolean compact) {
        return compact ? OsdWriter.writeCompact(s) : OsdWriter.write(s);
    }

    private static String writeDocument(String format, Document doc, boolean compact) throws Exception {
        if (format == null) {
            format = "oml";
        }
        return switch (format.toLowerCase()) {
            case "oml" -> compact ? dev.omnist.oml.OmlWriter.writeCompact(doc) : dev.omnist.oml.OmlWriter.write(doc);
            case "json" -> dev.omnist.codec.JsonCodec.write(doc);
            case "yaml" -> dev.omnist.codec.YamlCodec.write(doc);
            case "toml" -> dev.omnist.codec.TomlCodec.write(doc);
            case "xml" -> dev.omnist.codec.XmlCodec.write(doc);
            default -> throw new IllegalArgumentException("Unsupported output format: " + format);
        };
    }

    private static class JsonError {
        public final String path;
        public final String code;
        public final String message;

        public JsonError(String path, String code, String message) {
            this.path = path;
            this.code = code;
            this.message = message;
        }
    }

    private static class JsonResponse {
        public final boolean ok;
        public final String message;
        public final List<JsonError> errors;

        public JsonResponse(boolean ok, String message, List<JsonError> errors) {
            this.ok = ok;
            this.message = message;
            this.errors = errors;
        }
    }

    private static class LintResponse {
        public final boolean ok;
        public final List<LintFinding> findings;

        public LintResponse(boolean ok, List<LintFinding> findings) {
            this.ok = ok;
            this.findings = findings;
        }
    }

    // Package-private (not private): the "zero samples" mapping is
    // unreachable through Cli.run itself -- the infer command's own
    // positionals.size() < 2 check guarantees at least one sample before
    // SchemaAlgebra.inferWithReport is ever called -- so it needs a direct
    // unit test rather than a CLI-driven one.
    static String getInferErrorCode(String msg) {
        // Patterns matched against the exact messages SchemaAlgebra.inferWithReport
        // and inferType actually throw (see SchemaAlgebra.java) -- kept in sync with
        // that source rather than guessed, since a mismatch here silently falls
        // through to the generic document.parse-error code below.
        if (msg == null) msg = "";
        if (msg.contains("expects object (record) samples at the root")) {
            return "algebra.infer-scalar-root";
        } else if (msg.contains("zero samples")) {
            return "algebra.infer-no-samples";
        } else if (msg.contains("mixes objects and values")) {
            return "algebra.infer-mixed-shape";
        } else if (msg.contains("more than one scalar kind")) {
            return "algebra.infer-conflicting-scalars";
        }
        return "document.parse-error";
    }
}
