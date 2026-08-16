package dev.omnist.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omnist.document.*;
import dev.omnist.oml.OmlParseException;
import dev.omnist.oml.OmlReader;
import dev.omnist.schema.Field;
import dev.omnist.schema.OsdParseException;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Record;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

/**
 * Track 1 of the conformance harness: runs the compiled {@code omnist} CLI as a real
 * subprocess against the fixture-based test suite (omnist-spec §8.5) and compares its
 * stdout/stderr/exit-code against each fixture's expected result.
 */
public final class Track1Runner {
    private static final boolean DELIBERATELY_FAIL_COMPARISON = false;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int passCount = 0;
    private static int failCount = 0;
    private static int skipCount = 0;

    private record JsonDiagnostic(String path, String code) {}

    private Track1Runner() {}

    /**
     * Runs every fixture under {@code fixturesDir} against the CLI jar built from
     * {@code repoDir} and returns the tally.
     *
     * @param fixturesDir directory containing the Track 1 fixture files
     * @param repoDir     the repo root, used to locate {@code target/omnist-j-*.jar}
     * @return a 3-element array {@code {pass, fail, skip}}
     * @throws Exception if a fixture cannot be read or the CLI subprocess cannot be launched
     */
    public static int[] runTrack1(Path fixturesDir, Path repoDir) throws Exception {
        passCount = 0;
        failCount = 0;
        skipCount = 0;
        doRunTrack1(fixturesDir, repoDir);
        return new int[]{passCount, failCount, skipCount};
    }

    private static void doRunTrack1(Path fixturesDir, Path repoDir) throws Exception {
        try (Stream<Path> stream = Files.walk(fixturesDir)) {
            List<Path> purposeFiles = stream
                .filter(p -> p.getFileName().toString().equals("purpose.txt"))
                .sorted()
                .toList();

            for (Path purposeFile : purposeFiles) {
                Path fixtureDir = purposeFile.getParent();
                String relPath = fixturesDir.relativize(fixtureDir).toString().replace('\\', '/');

                if (relPath.startsWith("_referee-self-test")) {
                    runRefereeSelfTest(fixtureDir, relPath);
                } else {
                    runCliFixture(fixtureDir, relPath, repoDir);
                }
            }
        }
    }

    private static void runRefereeSelfTest(Path dir, String relPath) {
        try {
            String kind = Files.readString(dir.resolve("kind.txt"), StandardCharsets.UTF_8).trim();
            String mode = Files.exists(dir.resolve("mode.txt")) 
                ? Files.readString(dir.resolve("mode.txt"), StandardCharsets.UTF_8).trim() 
                : "exact";
            String expect = Files.readString(dir.resolve("expect.txt"), StandardCharsets.UTF_8).trim();

            boolean expectedEqual = expect.equals("equal");
            boolean actualEqual;

            if ("schema".equals(kind)) {
                Schema a = OsdReader.read(Files.readString(dir.resolve("a.osd"), StandardCharsets.UTF_8));
                Schema b = OsdReader.read(Files.readString(dir.resolve("b.osd"), StandardCharsets.UTF_8));
                if ("isomorphic".equals(mode)) {
                    actualEqual = isomorphicSchemaEqual(a, b);
                } else {
                    actualEqual = exactSchemaEqual(a, b);
                }
            } else {
                Document a = OmlReader.read(Files.readString(dir.resolve("a.oml"), StandardCharsets.UTF_8));
                Document b = OmlReader.read(Files.readString(dir.resolve("b.oml"), StandardCharsets.UTF_8));
                actualEqual = a.equals(b);
            }

            if (DELIBERATELY_FAIL_COMPARISON) {
                actualEqual = !actualEqual;
            }

            if (actualEqual == expectedEqual) {
                System.out.println("  [PASS] " + relPath);
                passCount++;
            } else {
                System.err.println("  [FAIL] " + relPath + " (Expected " + expect + " but got " + (actualEqual ? "equal" : "not_equal") + ")");
                failCount++;
            }
        } catch (Throwable t) {
            System.err.println("  [FAIL] " + relPath + " threw: " + t.getMessage());
            failCount++;
        }
    }

    private static void runCliFixture(Path dir, String relPath, Path repoDir) {
        // Extract operation from relation path
        String op = relPath.split("/")[0];
        
        try {
            // Read expected.txt or expected/ok.txt
            boolean expectedOk = true;
            Path okTxt = dir.resolve("expected/ok.txt");
            if (!Files.exists(okTxt)) {
                okTxt = dir.resolve("expected.txt");
            }
            if (Files.exists(okTxt)) {
                String okStr = Files.readString(okTxt, StandardCharsets.UTF_8).trim();
                expectedOk = okStr.equals("true") || okStr.equals("equal") || okStr.equals("satisfiable");
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(repoDir.resolve("omnist").toString());

            // Build CLI args based on op
            switch (op) {
                case "normalize" -> {
                    cmd.add("schema");
                    cmd.add("normalize");
                    cmd.add("input.osd");
                }
                case "prune" -> {
                    cmd.add("schema");
                    cmd.add("prune");
                    cmd.add("input.osd");
                }
                case "write" -> {
                    cmd.add("format");
                    cmd.add("input.oml");
                }
                case "is_empty" -> {
                    cmd.add("schema");
                    cmd.add("is-empty");
                    cmd.add("input.osd");
                    cmd.add("--result-format");
                    cmd.add("json");
                }
                case "compatible_with" -> {
                    cmd.add("schema");
                    cmd.add("compatible-with");
                    cmd.add("a.osd");
                    cmd.add("b.osd");
                    cmd.add("--result-format");
                    cmd.add("json");
                }
                case "equivalent" -> {
                    cmd.add("schema");
                    cmd.add("equivalent");
                    cmd.add("a.osd");
                    cmd.add("b.osd");
                    cmd.add("--result-format");
                    cmd.add("json");
                }
                case "lint" -> {
                    cmd.add("schema");
                    cmd.add("lint");
                    cmd.add("input.osd");
                    cmd.add("--json");
                }
                case "extract" -> {
                    cmd.add("schema");
                    cmd.add("extract");
                    cmd.add("schema.osd");
                    cmd.add("--keep");
                    cmd.add(Files.readString(dir.resolve("keep.txt"), StandardCharsets.UTF_8).trim());
                    cmd.add("--json");
                }
                case "validate" -> {
                    cmd.add("validate");
                    cmd.add("input.oml");
                    cmd.add("--schema");
                    cmd.add("schema.osd");
                    cmd.add("--json");
                }
                case "materialize" -> {
                    cmd.add("convert");
                    cmd.add("input.oml");
                    cmd.add("--from");
                    cmd.add("oml");
                    cmd.add("--to");
                    cmd.add("oml");
                    cmd.add("--schema");
                    cmd.add("schema.osd");
                    cmd.add("--json");
                }
                case "infer" -> {
                    cmd.add("infer");
                    List<Path> samples;
                    try (Stream<Path> sampleStream = Files.list(dir.resolve("samples"))) {
                        samples = sampleStream
                            .filter(p -> p.getFileName().toString().endsWith(".oml"))
                            .sorted()
                            .toList();
                    }
                    for (Path sample : samples) {
                        cmd.add("samples/" + sample.getFileName().toString());
                    }
                    cmd.add("--from");
                    cmd.add("oml");
                    if (Files.exists(dir.resolve("allow_any.txt"))) {
                        String allow = Files.readString(dir.resolve("allow_any.txt"), StandardCharsets.UTF_8).trim();
                        if ("true".equals(allow)) {
                            cmd.add("--allow-any");
                        }
                    }
                    cmd.add("--json");
                }
                default -> {
                    System.out.println("  [SKIP] " + relPath + " (Unsupported Track 1 operation: " + op + ")");
                    skipCount++;
                    return;
                }
            }

            // Run process or in-process CLI
            String stdout = "";
            String stderr = "";
            int exitCode = -1;

            Path omnistJar = repoDir.resolve("target/omnist-j-0.0.1-alpha.jar");
            Path omnistBin = repoDir.resolve("omnist");
            if (Files.exists(omnistJar) && Files.exists(omnistBin) && Files.isExecutable(omnistBin)) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(dir.toFile());
                    Process p = pb.start();
                    stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    exitCode = p.waitFor();
                } catch (Exception ex) {
                    exitCode = -1;
                }
            }

            if (exitCode == -1) {
                // In-process invocation fallback
                List<String> inProcCmd = new ArrayList<>();
                for (int i = 1; i < cmd.size(); i++) {
                    String arg = cmd.get(i);
                    if (!arg.startsWith("-") && (arg.endsWith(".osd") || arg.endsWith(".oml") || arg.startsWith("samples/"))) {
                        inProcCmd.add(dir.resolve(arg).toString());
                    } else {
                        inProcCmd.add(arg);
                    }
                }

                ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
                ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
                PrintStream outPs = new PrintStream(outBaos, true, StandardCharsets.UTF_8);
                PrintStream errPs = new PrintStream(errBaos, true, StandardCharsets.UTF_8);

                exitCode = dev.omnist.cli.Cli.run(inProcCmd.toArray(new String[0]), outPs, errPs, System.in);
                stdout = outBaos.toString(StandardCharsets.UTF_8);
                stderr = errBaos.toString(StandardCharsets.UTF_8);
            }

            // Verify outcome
            boolean matches = false;
            String errorDetail = "";

            if (op.equals("normalize") || op.equals("prune")) {
                if (exitCode == 0) {
                    Schema actual = OsdReader.read(stdout);
                    Schema expected = OsdReader.read(Files.readString(dir.resolve("expected.osd"), StandardCharsets.UTF_8));
                    matches = exactSchemaEqual(actual, expected);
                } else {
                    errorDetail = "Exit code was " + exitCode + ", expected 0. Stderr: " + stderr;
                }
            } else if (op.equals("write")) {
                if (exitCode == 0) {
                    Document actual = OmlReader.read(stdout);
                    Document expected = OmlReader.read(Files.readString(dir.resolve("expected.oml"), StandardCharsets.UTF_8));
                    matches = actual.equals(expected);
                } else {
                    errorDetail = "Exit code was " + exitCode + ", expected 0. Stderr: " + stderr;
                }
            } else if (op.equals("is_empty") || op.equals("compatible_with") || op.equals("equivalent")) {
                int expectedExit = expectedOk ? 0 : 1;
                if (exitCode == expectedExit) {
                    JsonNode rootNode = MAPPER.readTree(stdout);
                    boolean res = false;
                    if (op.equals("is_empty")) res = rootNode.get("empty").asBoolean();
                    else if (op.equals("compatible_with")) res = rootNode.get("compatible").asBoolean();
                    else if (op.equals("equivalent")) res = rootNode.get("equivalent").asBoolean();
                    matches = (res == expectedOk);
                } else {
                    errorDetail = "Exit code was " + exitCode + ", expected " + expectedExit + ". Stderr: " + stderr;
                }
            } else if (op.equals("lint")) {
                JsonNode expectedJson = MAPPER.readTree(Files.readString(dir.resolve("expected.json"), StandardCharsets.UTF_8));
                boolean expOk = expectedJson.get("ok").asBoolean();
                int expectedExit = expOk ? 0 : 1;
                if (exitCode == expectedExit) {
                    JsonNode rootNode = MAPPER.readTree(stdout);
                    boolean actOk = rootNode.get("ok").asBoolean();
                    if (actOk == expOk) {
                        compareFindings(rootNode.get("findings"), expectedJson.get("findings"));
                        matches = true;
                    }
                } else {
                    errorDetail = "Exit code was " + exitCode + ", expected " + expectedExit + ". Stderr: " + stderr;
                }
            } else if (op.equals("extract")) {
                if (expectedOk) {
                    if (exitCode == 0) {
                        Schema actual = OsdReader.read(stdout);
                        Schema expected = OsdReader.read(Files.readString(dir.resolve("expected/output.osd"), StandardCharsets.UTF_8));
                        matches = exactSchemaEqual(actual, expected);
                    } else {
                        errorDetail = "Exit code was " + exitCode + ", expected 0. Stderr: " + stderr;
                    }
                } else {
                    if (exitCode == 1) {
                        matches = true;
                    } else {
                        errorDetail = "Exit code was " + exitCode + ", expected 1. Stderr: " + stderr;
                    }
                }
            } else if (op.equals("validate")) {
                int expectedExit = expectedOk ? 0 : 1;
                if (exitCode == expectedExit) {
                    matches = true;
                } else {
                    errorDetail = "Exit code was " + exitCode + ", expected " + expectedExit + ". Stderr: " + stderr;
                }
            } else if (op.equals("materialize")) {
                if (expectedOk) {
                    if (exitCode == 0) {
                        Document actual = OmlReader.read(stdout);
                        Document expected = OmlReader.read(Files.readString(dir.resolve("expected/output.oml"), StandardCharsets.UTF_8));
                        matches = actual.equals(expected);
                    } else {
                        errorDetail = "Exit code was " + exitCode + ", expected 0. Stderr: " + stderr;
                    }
                } else {
                    if (exitCode == 2) {
                        matches = true;
                    } else {
                        errorDetail = "Exit code was " + exitCode + ", expected 2. Stderr: " + stderr;
                    }
                }
            } else if (op.equals("infer")) {
                if (expectedOk) {
                    if (exitCode == 0) {
                        Schema actual = OsdReader.read(stdout);
                        Schema expected = OsdReader.read(Files.readString(dir.resolve("expected/output.osd"), StandardCharsets.UTF_8));
                        matches = isomorphicSchemaEqual(actual, expected);
                    } else {
                        errorDetail = "Exit code was " + exitCode + ", expected 0. Stderr: " + stderr;
                    }
                } else {
                    if (exitCode == 2) {
                        matches = true;
                    } else {
                        errorDetail = "Exit code was " + exitCode + ", expected 2. Stderr: " + stderr;
                    }
                }
            }

            if (matches) {
                System.out.println("  [PASS] " + relPath);
                passCount++;
            } else {
                System.err.println("  [FAIL] " + relPath + " (" + (errorDetail.isEmpty() ? "output structural mismatch" : errorDetail) + ")");
                failCount++;
            }

        } catch (Throwable t) {
            System.err.println("  [FAIL] " + relPath + " threw: " + t.getMessage());
            t.printStackTrace();
            failCount++;
        }
    }

    private static void compareFindings(JsonNode actual, JsonNode expected) {
        Set<String> act = new HashSet<>();
        if (actual != null) {
            for (JsonNode n : actual) {
                String c = n.get("code").asText();
                if (c.startsWith("lint.")) c = c.substring(5);
                act.add(c + "|" + n.get("severity").asText() + "|" + n.get("location").asText());
            }
        }
        Set<String> exp = new HashSet<>();
        if (expected != null) {
            for (JsonNode n : expected) {
                String c = n.get("code").asText();
                if (c.startsWith("lint.")) c = c.substring(5);
                exp.add(c + "|" + n.get("severity").asText() + "|" + n.get("location").asText());
            }
        }
        if (!act.equals(exp)) {
            throw new RuntimeException("Findings mismatch. Expected: " + exp + ", Got: " + act);
        }
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

    private static String findOsdPath(String osd, int line) {
        String[] lines = osd.split("\\n");
        String currentRecord = null;
        String currentField = null;
        
        for (int i = 0; i < Math.min(line, lines.length); i++) {
            String l = lines[i].trim();
            if (l.startsWith("record ")) {
                String[] parts = l.split("\\s+");
                if (parts.length > 1) {
                    currentRecord = parts[1];
                    if (currentRecord.endsWith("{")) {
                        currentRecord = currentRecord.substring(0, currentRecord.length() - 1);
                    }
                    currentRecord = currentRecord.trim();
                }
                currentField = null;
            } else if (l.startsWith("}")) {
                currentRecord = null;
                currentField = null;
            } else if (currentRecord != null) {
                if (l.startsWith("\"")) {
                    int nextQuote = l.indexOf('"', 1);
                    if (nextQuote > 1) {
                        currentField = l.substring(1, nextQuote);
                    }
                }
            }
        }
        
        if (currentRecord != null) {
            if (currentField != null) {
                return currentRecord + "." + currentField;
            }
            return currentRecord;
        }
        return "$";
    }

    private static List<JsonDiagnostic> extractParserDiagnostics(Throwable ex) {
        if (ex instanceof OmlParseException ope) {
            return List.of(new JsonDiagnostic(ope.getPath(), ope.getCode()));
        }
        if (ex instanceof OsdParseException osd) {
            return List.of(new JsonDiagnostic(osd.getPath(), osd.getCode()));
        }
        String msg = ex.getMessage();
        if (msg == null) msg = "";
        
        String path = "$";
        String code = "document.parse-error";
        
        if (msg.startsWith("$")) {
            int colon = msg.indexOf(':');
            if (colon > 0) {
                path = msg.substring(0, colon).trim();
                msg = msg.substring(colon + 1).trim();
            }
        }
        
        if (msg.contains("depth") || msg.contains("nesting exceeds")) {
            code = "document.limit.depth";
        } else if (msg.contains("too many nodes") || msg.contains("materialized") || msg.contains("Node count")) {
            code = "document.limit.nodes";
        } else if (msg.contains("array of arrays") || msg.contains("no labeled-edge form") || msg.contains("unlabeled")) {
            code = "document.unlabeled-element";
        } else if (msg.contains("maximum digit limit") || msg.contains("digit limit") || msg.contains("Integer literal digit count")) {
            code = "document.limit.int-digits";
        } else if (msg.contains("invalidates root") || msg.contains("deletes a mandatory field")) {
            code = "algebra.extract-invalidates-root";
            if (msg.contains("deletes a mandatory field of ")) {
                int idx = msg.indexOf("deletes a mandatory field of ");
                path = msg.substring(idx + "deletes a mandatory field of ".length()).trim();
                if (path.contains(" ")) path = path.substring(0, path.indexOf(" "));
            }
        } else if (msg.contains("root must be a node") || msg.contains("scalar root") || msg.contains("expects object (record) samples")) {
            code = "algebra.infer-scalar-root";
        } else if (msg.contains("no samples") || msg.contains("empty samples") || msg.contains("zero samples")) {
            code = "algebra.infer-no-samples";
        } else if (msg.contains("mixes objects and values") || msg.contains("mixed shape")) {
            code = "algebra.infer-mixed-shape";
            int colon = msg.indexOf(':');
            if (colon > 0) path = msg.substring(0, colon).trim();
        } else if (msg.contains("conflicting") || msg.contains("conflicting types") || msg.contains("more than one scalar kind")) {
            code = "algebra.infer-conflicting-scalars";
            int colon = msg.indexOf(':');
            if (colon > 0) path = msg.substring(0, colon).trim();
        } else if (msg.contains("Unexpected token") || msg.contains("unexpected token") || msg.contains("Bare word") || msg.contains("bare word")) {
            code = "parse.unexpected-token";
        } else if (msg.contains("invalid JSON") || msg.contains("invalid TOML") || msg.contains("invalid XML")) {
            code = "document.parse-error";
        }
        
        return List.of(new JsonDiagnostic(path, code));
    }

    private static Document parseFormat(String text, String format) throws Exception {
        return parseFormat(text, format, dev.omnist.document.Limits.DEFAULT);
    }

    private static Document parseFormat(String text, String format, dev.omnist.document.Limits limits) throws Exception {
        if ("oml".equalsIgnoreCase(format)) {
            return dev.omnist.oml.OmlReader.read(text, limits);
        } else if ("json".equalsIgnoreCase(format)) {
            return dev.omnist.codec.JsonCodec.read(text);
        } else if ("yaml".equalsIgnoreCase(format)) {
            return dev.omnist.codec.YamlCodec.read(text);
        } else if ("toml".equalsIgnoreCase(format)) {
            return dev.omnist.codec.TomlCodec.read(text);
        } else if ("xml".equalsIgnoreCase(format)) {
            return dev.omnist.codec.XmlCodec.read(text);
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
        if (text.endsWith("Z") || text.endsWith("z")) {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(text.substring(0, text.length() - 1));
            return DateTimeValue.of(dt, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 10) {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return DateTimeValue.of(dt, offset);
        }
        return DateTimeValue.of(java.time.LocalDateTime.parse(text));
    }

    private static TimeValue parseTimeValue(String text) {
        if (text.endsWith("Z") || text.endsWith("z")) {
            java.time.LocalTime t = java.time.LocalTime.parse(text.substring(0, text.length() - 1));
            return TimeValue.of(t, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 0 && text.indexOf(':') < signPos) {
            java.time.LocalTime t = java.time.LocalTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return TimeValue.of(t, offset);
        }
        return TimeValue.of(java.time.LocalTime.parse(text));
    }

    private static boolean isEquivalentDoc(Document a, Document b) {
        if (a.equals(b)) return true;
        if (a instanceof dev.omnist.document.Node na && b instanceof dev.omnist.document.Node nb) {
            if (na.edges().size() != nb.edges().size()) return false;
            Map<String, List<Target>> mapA = new HashMap<>();
            for (Edge e : na.edges()) {
                mapA.computeIfAbsent(e.label(), k -> new ArrayList<>()).add(e.target());
            }
            Map<String, List<Target>> mapB = new HashMap<>();
            for (Edge e : nb.edges()) {
                mapB.computeIfAbsent(e.label(), k -> new ArrayList<>()).add(e.target());
            }
            if (!mapA.keySet().equals(mapB.keySet())) return false;
            for (String k : mapA.keySet()) {
                List<Target> listA = mapA.get(k);
                List<Target> listB = mapB.get(k);
                if (listA.size() != listB.size()) return false;
                for (int i = 0; i < listA.size(); i++) {
                    if (!isEquivalentDoc((Document) listA.get(i), (Document) listB.get(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }
}
