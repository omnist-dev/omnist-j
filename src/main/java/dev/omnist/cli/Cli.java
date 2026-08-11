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

public final class Cli {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cli() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err, System.in);
        System.exit(code);
    }

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

            String cmd = positionals.get(0);

            if (cmd.equals("format")) {
                if (positionals.size() < 2) {
                    err.println("Missing format input file");
                    return 2;
                }
                String content = readInput(positionals.get(1), in);
                Document doc = readDocument(fromFormat, content, null);
                String formatted = writeDocument(toFormat, doc, compact);
                writeOutput(outputPath, formatted, out);
                return 0;
            }

            if (cmd.equals("validate")) {
                if (positionals.size() < 2) {
                    err.println("Missing validate input file");
                    return 2;
                }
                if (schemaPath == null) {
                    err.println("Missing --schema parameter");
                    return 2;
                }
                Schema schema = OsdReader.read(readInput(schemaPath, in));
                Document doc = readDocument(fromFormat, readInput(positionals.get(1), in), schema);
                ValidationResult res = Validator.validate(doc, schema);

                if (json) {
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

            if (cmd.equals("convert")) {
                if (positionals.size() < 2) {
                    err.println("Missing convert input file");
                    return 2;
                }
                if (schemaPath == null) {
                    err.println("Missing --schema parameter");
                    return 2;
                }
                Schema schema = OsdReader.read(readInput(schemaPath, in));
                Document doc = readDocument(fromFormat, readInput(positionals.get(1), in), schema);

                try {
                    Document materialized = Materializer.materialize(doc, schema);
                    String result = writeDocument(toFormat, materialized, compact);
                    writeOutput(outputPath, result, out);
                    return 0;
                } catch (ValidationException ex) {
                    if (json) {
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

            if (cmd.equals("schema")) {
                if (positionals.size() < 2) {
                    err.println("Missing schema subcommand");
                    return 2;
                }
                String subcmd = positionals.get(1);

                if (subcmd.equals("normalize")) {
                    if (positionals.size() < 3) {
                        err.println("Missing schema input file");
                        return 2;
                    }
                    Schema s = OsdReader.read(readInput(positionals.get(2), in));
                    Schema normalized = SchemaAlgebra.normalize(s);
                    writeOutput(outputPath, writeOsd(normalized, compact), out);
                    return 0;
                }

                if (subcmd.equals("prune")) {
                    if (positionals.size() < 3) {
                        err.println("Missing schema input file");
                        return 2;
                    }
                    Schema s = OsdReader.read(readInput(positionals.get(2), in));
                    Schema pruned = SchemaAlgebra.prune(s);
                    writeOutput(outputPath, writeOsd(pruned, compact), out);
                    return 0;
                }

                if (subcmd.equals("extract")) {
                    if (positionals.size() < 3) {
                        err.println("Missing schema input file");
                        return 2;
                    }
                    if (keepLabels == null) {
                        err.println("Missing --keep labels parameter");
                        return 2;
                    }
                    Schema s = OsdReader.read(readInput(positionals.get(2), in));
                    Set<String> keep = new LinkedHashSet<>(Arrays.asList(keepLabels.split(",")));
                    try {
                        Schema extracted = SchemaAlgebra.extract(s, keep);
                        writeOutput(outputPath, writeOsd(extracted, compact), out);
                        return 0;
                    } catch (IllegalArgumentException ex) {
                        if (json) {
                            out.println(MAPPER.writeValueAsString(new JsonResponse(false, ex.getMessage(), List.of())));
                        } else {
                            err.println(ex.getMessage());
                        }
                        return 1;
                    }
                }

                if (subcmd.equals("is-empty")) {
                    if (positionals.size() < 3) {
                        err.println("Missing schema input file");
                        return 2;
                    }
                    Schema s = OsdReader.read(readInput(positionals.get(2), in));
                    boolean empty = SchemaAlgebra.isEmpty(s);
                    if ("json".equals(resultFormat)) {
                        out.println("{\"empty\":" + empty + "}");
                    }
                    return empty ? 0 : 1;
                }

                if (subcmd.equals("compatible-with")) {
                    if (positionals.size() < 4) {
                        err.println("Missing schema input files");
                        return 2;
                    }
                    Schema a = OsdReader.read(readInput(positionals.get(2), in));
                    Schema b = OsdReader.read(readInput(positionals.get(3), in));
                    boolean comp = SchemaAlgebra.compatibleWith(a, b);
                    if ("json".equals(resultFormat)) {
                        out.println("{\"compatible\":" + comp + "}");
                    }
                    return comp ? 0 : 1;
                }

                if (subcmd.equals("equivalent")) {
                    if (positionals.size() < 4) {
                        err.println("Missing schema input files");
                        return 2;
                    }
                    Schema a = OsdReader.read(readInput(positionals.get(2), in));
                    Schema b = OsdReader.read(readInput(positionals.get(3), in));
                    boolean equiv = SchemaAlgebra.equivalent(a, b);
                    if ("json".equals(resultFormat)) {
                        out.println("{\"equivalent\":" + equiv + "}");
                    }
                    return equiv ? 0 : 1;
                }

                if (subcmd.equals("lint")) {
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

                    if (json) {
                        out.println(MAPPER.writeValueAsString(new LintResponse(ok, findings)));
                    } else {
                        for (LintFinding lf : findings) {
                            err.println(lf.severity().toUpperCase() + " [" + lf.code() + "] at " + lf.location() + ": " + lf.message());
                        }
                    }

                    return ok ? 0 : 1;
                }
            }

            if (cmd.equals("infer")) {
                if (positionals.size() < 2) {
                    err.println("Missing input samples");
                    return 2;
                }

                List<Document> samples = new ArrayList<>();
                for (int idx = 1; idx < positionals.size(); idx++) {
                    samples.add(readDocument(fromFormat, readInput(positionals.get(idx), in), null));
                }

                try {
                    InferResult res = SchemaAlgebra.inferWithReport(samples, "R", allowAny);
                    if (allowAny && res.fallbacks() != null && !res.fallbacks().isEmpty()) {
                        err.println("opened " + res.fallbacks().size() + " field(s) as `any`:");
                        for (AnyFallback fb : res.fallbacks()) {
                            err.println("  " + fb.location() + " — " + fb.reason());
                        }
                    }
                    writeOutput(outputPath, writeOsd(res.schema(), compact), out);
                    return 0;
                } catch (Exception ex) {
                    if (json) {
                        out.println(MAPPER.writeValueAsString(new JsonResponse(false, ex.getMessage(), List.of())));
                    } else {
                        err.println(ex.getMessage());
                    }
                    return 2;
                }
            }

            err.println("Unknown command: " + cmd);
            return 2;

        } catch (Exception ex) {
            err.println("Error: " + ex.getMessage());
            ex.printStackTrace(err);
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
}
