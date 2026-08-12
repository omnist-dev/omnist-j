package dev.omnist.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omnist.document.Document;
import dev.omnist.oml.OmlReader;
import dev.omnist.schema.Field;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Record;
import dev.omnist.schema.Schema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class Track1Runner {
    private static final boolean DELIBERATELY_FAIL_COMPARISON = false;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Track1Runner() {}

    public static int[] runTrack1(Path fixturesDir, Path repoDir) throws Exception {
        int passCount = 0;
        int failCount = 0;
        int skipCount = 0;

        try (Stream<Path> stream = Files.walk(fixturesDir)) {
            List<Path> purposeFiles = stream
                .filter(p -> p.getFileName().toString().equals("purpose.txt"))
                .sorted()
                .toList();

            for (Path purposeFile : purposeFiles) {
                Path fixtureDir = purposeFile.getParent();
                String relPath = fixturesDir.relativize(fixtureDir).toString().replace('\\', '/');

                int[] res;
                if (relPath.startsWith("_referee-self-test")) {
                    res = runRefereeSelfTest(fixtureDir, relPath);
                } else {
                    res = runCliFixture(fixtureDir, relPath, repoDir);
                }
                passCount += res[0];
                failCount += res[1];
                skipCount += res[2];
            }
        }
        return new int[]{passCount, failCount, skipCount};
    }

    private static int[] runRefereeSelfTest(Path dir, String relPath) {
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
                return new int[]{1, 0, 0};
            } else {
                System.err.println("  [FAIL] " + relPath + " (Expected " + expect + " but got " + (actualEqual ? "equal" : "not_equal") + ")");
                return new int[]{0, 1, 0};
            }
        } catch (Throwable t) {
            System.err.println("  [FAIL] " + relPath + " threw: " + t.getMessage());
            return new int[]{0, 1, 0};
        }
    }

    private static int[] runCliFixture(Path dir, String relPath, Path repoDir) {
        String op = relPath.split("/")[0];
        
        try {
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
                case "extract" -> {
                    cmd.add("schema");
                    cmd.add("extract");
                    cmd.add("input.osd");
                    if (Files.exists(dir.resolve("keep.txt"))) {
                        String keep = Files.readString(dir.resolve("keep.txt"), StandardCharsets.UTF_8).trim();
                        cmd.add("--keep");
                        cmd.add(keep);
                    }
                }
                case "infer" -> {
                    cmd.add("infer");
                    if (Files.exists(dir.resolve("allow-any.txt"))) {
                        cmd.add("--allow-any");
                    }
                    try (Stream<Path> s = Files.list(dir)) {
                        List<Path> samples = s.filter(p -> p.getFileName().toString().startsWith("sample"))
                            .sorted().toList();
                        for (Path sp : samples) {
                            cmd.add(sp.getFileName().toString());
                        }
                    }
                }
                case "lint" -> {
                    cmd.add("schema");
                    cmd.add("lint");
                    cmd.add("input.osd");
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
                    cmd.add("--schema");
                    cmd.add("schema.osd");
                    cmd.add("--json");
                }
                default -> throw new IllegalArgumentException("Unknown fixture operation: " + op);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            Process proc = pb.start();

            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();

            boolean actualOk = (exitCode == 0);

            if ("is_empty".equals(op)) {
                JsonNode res = MAPPER.readTree(stdout);
                actualOk = !res.get("empty").asBoolean();
            } else if ("compatible_with".equals(op)) {
                JsonNode res = MAPPER.readTree(stdout);
                actualOk = res.get("compatible").asBoolean();
            } else if ("equivalent".equals(op)) {
                JsonNode res = MAPPER.readTree(stdout);
                actualOk = res.get("equivalent").asBoolean();
            }

            if (actualOk == expectedOk) {
                if (actualOk && Files.exists(dir.resolve("expected.osd"))) {
                    Schema actualSchema = OsdReader.read(stdout);
                    Schema expectedSchema = OsdReader.read(Files.readString(dir.resolve("expected.osd"), StandardCharsets.UTF_8));
                    if ("normalize".equals(op) || "prune".equals(op)) {
                        if (!isomorphicSchemaEqual(actualSchema, expectedSchema)) {
                            System.err.println("  [FAIL] " + relPath + " (Output schema not isomorphic)");
                            return new int[]{0, 1, 0};
                        }
                    } else if (!exactSchemaEqual(actualSchema, expectedSchema)) {
                        System.err.println("  [FAIL] " + relPath + " (Output schema not equal)");
                        return new int[]{0, 1, 0};
                    }
                }

                if (actualOk && Files.exists(dir.resolve("expected.oml"))) {
                    Document actualDoc = OmlReader.read(stdout);
                    Document expectedDoc = OmlReader.read(Files.readString(dir.resolve("expected.oml"), StandardCharsets.UTF_8));
                    if (!actualDoc.equals(expectedDoc)) {
                        System.err.println("  [FAIL] " + relPath + " (Output document not equal)");
                        return new int[]{0, 1, 0};
                    }
                }

                if (Files.exists(dir.resolve("expected_findings.json"))) {
                    JsonNode actualJson = MAPPER.readTree(stdout);
                    JsonNode expectedJson = MAPPER.readTree(Files.readString(dir.resolve("expected_findings.json"), StandardCharsets.UTF_8));
                    compareFindings(actualJson.get("findings"), expectedJson);
                }

                System.out.println("  [PASS] " + relPath);
                return new int[]{1, 0, 0};
            } else {
                System.err.println("  [FAIL] " + relPath + " (Exit code " + exitCode + ", stdout: " + stdout.trim() + ", stderr: " + stderr.trim() + ")");
                return new int[]{0, 1, 0};
            }
        } catch (Throwable t) {
            System.err.println("  [FAIL] " + relPath + " threw: " + t.getMessage());
            t.printStackTrace();
            return new int[]{0, 1, 0};
        }
    }

    private static boolean exactSchemaEqual(Schema a, Schema b) {
        if (!a.root().equals(b.root())) return false;
        if (a.records().size() != b.records().size()) return false;
        for (Map.Entry<String, Record> entry : a.records().entrySet()) {
            Record bRec = b.records().get(entry.getKey());
            if (bRec == null) return false;
            if (!exactRecordEqual(entry.getValue(), bRec)) return false;
        }
        return true;
    }

    private static boolean exactRecordEqual(Record a, Record b) {
        if (!a.name().equals(b.name())) return false;
        if (a.fields().size() != b.fields().size()) return false;
        Set<Field> setA = new HashSet<>(a.fields());
        Set<Field> setB = new HashSet<>(b.fields());
        return setA.equals(setB);
    }

    private static boolean isomorphicSchemaEqual(Schema a, Schema b) {
        if (!a.root().equals(b.root())) return false;
        if (a.records().size() != b.records().size()) return false;
        List<Record> recsA = a.records().values().stream().sorted(Comparator.comparing(Record::name)).toList();
        List<Record> recsB = b.records().values().stream().sorted(Comparator.comparing(Record::name)).toList();
        for (int i = 0; i < recsA.size(); i++) {
            if (!exactRecordEqual(recsA.get(i), recsB.get(i))) return false;
        }
        return true;
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
}
