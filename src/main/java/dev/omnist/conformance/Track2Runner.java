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

public final class Track2Runner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Track2Runner() {}

    public static int[] runTrack2(Path testSuiteDir) throws Exception {
        int passCount = 0;
        int failCount = 0;
        int skipCount = 0;

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
                    int[] res = runVector(vector);
                    passCount += res[0];
                    failCount += res[1];
                    skipCount += res[2];
                }
            }
        }
        return new int[]{passCount, failCount, skipCount};
    }

    private static int[] runVector(JsonNode vector) {
        String name = vector.get("name").asText();
        String op = vector.get("operation").asText();
        JsonNode input = vector.get("input");
        JsonNode expect = vector.get("expect");

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
                default -> {
                    System.out.println("    [SKIP] " + name + " (Operation " + op + " not implemented in harness)");
                    return new int[]{0, 0, 1};
                }
            }
            return new int[]{1, 0, 0};
        } catch (Throwable t) {
            System.err.println("    [FAIL] " + name + " failed: " + t.getMessage());
            t.printStackTrace();
            return new int[]{0, 1, 0};
        }
    }

    private static void runParseVector(JsonNode input, JsonNode expect) throws Exception {
        String text = input.get("text").asText();
        String format = input.has("format") ? input.get("format").asText() : "oml";
        boolean expectedOk = expect.get("ok").asBoolean();

        Limits limits = Limits.DEFAULT;
        if (input.has("declared_max_depth")) {
            int d = input.get("declared_max_depth").asInt();
            limits = new Limits(d, limits.maxNodeCount(), limits.maxIntegerDigits());
        }
        if (input.has("declared_max_nodes")) {
            int n = input.get("declared_max_nodes").asInt();
            limits = new Limits(limits.maxDepth(), n, limits.maxIntegerDigits());
        }
        if (input.has("declared_max_int_digits")) {
            int dig = input.get("declared_max_int_digits").asInt();
            limits = new Limits(limits.maxDepth(), limits.maxNodeCount(), dig);
        }

        Document actualDoc = null;
        Throwable thrown = null;
        try {
            actualDoc = parseFormat(text, format, limits);
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
            System.out.println("    [PASS] parse:" + input.get("text").asText().replace("\n", "\\n"));
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected parse failure, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags = extractParserDiagnostics(thrown);
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            System.out.println("    [PASS] parse error:" + input.get("text").asText().replace("\n", "\\n"));
        }
    }

    private static Document parseFormat(String text, String format, Limits limits) throws Exception {
        return switch (format) {
            case "oml" -> OmlReader.read(text, limits);
            case "json" -> JsonCodec.read(text);
            case "yaml" -> YamlCodec.read(text);
            case "toml" -> TomlCodec.read(text);
            case "xml" -> XmlCodec.read(text);
            default -> throw new IllegalArgumentException("Unknown format: " + format);
        };
    }

    private static Document parseFormat(String text, String format) throws Exception {
        return parseFormat(text, format, Limits.DEFAULT);
    }

    private static void runParseSchemaVector(JsonNode input, JsonNode expect) {
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
            System.out.println("    [PASS] parse_schema success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected parse_schema failure, but it succeeded");
            }
            String code = "schema.parse-error";
            String msg = thrown.getMessage();
            if (msg.contains("Empty cardinality")) code = "schema.empty-cardinality";
            else if (msg.contains("cannot be negative") || msg.contains("Invalid cardinality") || msg.contains("must be a whole number")) code = "schema.invalid-cardinality";
            else if (msg.contains("Reserved type name")) code = "schema.reserved-record-name";
            else if (msg.contains("Unknown type")) code = "schema.type-not-found";
            else if (msg.contains("Duplicate record")) code = "schema.duplicate-record";
            else if (msg.contains("Duplicate root")) code = "schema.duplicate-root";
            else if (msg.contains("? cannot apply") || msg.contains("already includes null")) code = "schema.optional-any-ref";

            int line = 1;
            if (thrown instanceof OsdParseException ope) {
                line = ope.getLine();
            }
            String path = findOsdPath(text, line);

            List<JsonDiagnostic> actualDiags = List.of(new JsonDiagnostic(path, code));
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            System.out.println("    [PASS] parse_schema error");
        }
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
            System.out.println("    [PASS] validate success");
        } else {
            if (res.isValid()) {
                throw new RuntimeException("Expected document to be invalid, but it was valid");
            }
            List<JsonDiagnostic> actualDiags = res.diagnostics().stream()
                .map(d -> new JsonDiagnostic(d.path(), d.code()))
                .toList();
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
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
                actualText = OmlWriter.write(doc);
            } else if ("json".equalsIgnoreCase(format)) {
                actualText = JsonCodec.write(doc, null, strict, report);
            } else if ("yaml".equalsIgnoreCase(format)) {
                actualText = YamlCodec.write(doc, strict, report);
            } else if ("toml".equalsIgnoreCase(format)) {
                actualText = TomlCodec.write(doc, strict, report);
            } else if ("xml".equalsIgnoreCase(format)) {
                actualText = XmlCodec.write(doc, strict, report);
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
            String expectedText = expect.get("text").asText();
            Document expectedDoc = parseFormat(expectedText, format);
            Document actualDoc = parseFormat(actualText, format);
            if (!actualDoc.equals(expectedDoc)) {
                throw new RuntimeException("Actual document parsed from written text does not equal expected document");
            }
            if (expect.has("diagnostics")) {
                compareDiagnostics(report.adjustments(), expect.get("diagnostics"));
            }
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
        if (actual != expected) {
            throw new RuntimeException("compatibleWith mismatch. Expected: " + expected + ", Actual: " + actual);
        }
        System.out.println("    [PASS] compatible_with");
    }

    private static void runEquivalentVector(JsonNode input, JsonNode expect) {
        Schema a = OsdReader.read(input.get("a").asText());
        Schema b = OsdReader.read(input.get("b").asText());
        boolean expected = expect.get("result").asBoolean();

        boolean actual = SchemaAlgebra.equivalent(a, b);
        if (actual != expected) {
            throw new RuntimeException("equivalent mismatch. Expected: " + expected + ", Actual: " + actual);
        }
        System.out.println("    [PASS] equivalent");
    }

    private static void runIsEmptyVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        boolean expected = expect.get("empty").asBoolean();

        boolean actual = SchemaAlgebra.isEmpty(s);
        if (actual != expected) {
            throw new RuntimeException("isEmpty mismatch. Expected: " + expected + ", Actual: " + actual);
        }
        System.out.println("    [PASS] is_empty");
    }

    private static void runNormalizeVector(JsonNode input, JsonNode expect) {
        Schema s = OsdReader.read(input.get("schema").asText());
        String expectedText = expect.get("schema").asText().replace("\r\n", "\n");

        Schema normalized = SchemaAlgebra.normalize(s);
        String actualText = OsdWriter.write(normalized).replace("\r\n", "\n");
        if (actualText.equals(expectedText)) {
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
            System.out.println("    [PASS] extract success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected extract to fail, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags = extractParserDiagnostics(thrown);
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
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
            System.out.println("    [PASS] infer success");
        } else {
            if (thrown == null) {
                throw new RuntimeException("Expected infer to fail, but it succeeded");
            }
            List<JsonDiagnostic> actualDiags = extractParserDiagnostics(thrown);
            compareJsonDiagnostics(actualDiags, expect.get("diagnostics"));
            System.out.println("    [PASS] infer error");
        }
    }

    private static void runLintVector(JsonNode input, JsonNode expect) {
        Schema schema = OsdReader.read(input.get("schema").asText());
        List<LintFinding> findings = SchemaAlgebra.lint(schema);
        compareLintFindings(findings, expect.get("findings"));
        System.out.println("    [PASS] lint");
    }

    private static boolean exactSchemaEqual(Schema s1, Schema s2) {
        if (!s1.root().equals(s2.root())) return false;
        if (!s1.records().keySet().equals(s2.records().keySet())) return false;
        for (String name : s1.records().keySet()) {
            Record r1 = s1.records().get(name);
            Record r2 = s2.records().get(name);
            if (!recordEqualOrderInsensitive(r1, r2)) return false;
        }
        return true;
    }

    private static boolean recordEqualOrderInsensitive(Record r1, Record r2) {
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
            Record r1 = s1.records().get(n1);
            Record r2 = s2.records().get(n2);
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
                case "integer" -> new Scalar.IntegerScalar(new BigInteger(valNode.asText()));
                case "number" -> {
                    String s = valNode.asText();
                    double d;
                    if ("nan".equals(s)) d = Double.NaN;
                    else if ("inf".equals(s)) d = Double.POSITIVE_INFINITY;
                    else if ("-inf".equals(s)) d = Double.NEGATIVE_INFINITY;
                    else d = valNode.asDouble();
                    yield new Scalar.NumberScalar(d);
                }
                case "date" -> new Scalar.DateScalar(LocalDate.parse(valNode.asText()));
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
            return new Node(edges);
        } else {
            throw new IllegalArgumentException("Invalid canonical JSON document shape: " + node.toString());
        }
    }

    private static DateTimeValue parseDateTimeValue(String text) {
        if (text.endsWith("Z") || text.endsWith("z")) {
            LocalDateTime dt = LocalDateTime.parse(text.substring(0, text.length() - 1));
            return DateTimeValue.of(dt, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 10) {
            LocalDateTime dt = LocalDateTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return DateTimeValue.of(dt, offset);
        }
        return DateTimeValue.of(LocalDateTime.parse(text));
    }

    private static TimeValue parseTimeValue(String text) {
        if (text.endsWith("Z") || text.endsWith("z")) {
            LocalTime t = LocalTime.parse(text.substring(0, text.length() - 1));
            return TimeValue.of(t, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 0 && text.indexOf(':') < signPos) {
            LocalTime t = LocalTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return TimeValue.of(t, offset);
        }
        return TimeValue.of(LocalTime.parse(text));
    }

    private static boolean isEquivalentDoc(Document a, Document b) {
        if (a.equals(b)) return true;
        if (a instanceof Node na && b instanceof Node nb) {
            if (na.edges().size() != nb.edges().size()) return false;
            for (int i = 0; i < na.edges().size(); i++) {
                Edge ea = na.edges().get(i);
                Edge eb = nb.edges().get(i);
                if (!ea.label().equals(eb.label())) return false;
                if (!isEquivalentDoc((Document) ea.target(), (Document) eb.target())) return false;
            }
            return true;
        }
        return false;
    }

    private record JsonDiagnostic(String path, String code) {}

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
            code = "depth-limit";
        } else if (msg.contains("too many nodes") || msg.contains("materialized")) {
            code = "materialize-limit";
        } else if (msg.contains("array of arrays") || msg.contains("no labeled-edge form") || msg.contains("unlabeled")) {
            code = "document.unlabeled-element";
        } else if (msg.contains("mixed content")) {
            code = "document.xml-mixed-content";
        } else if (msg.contains("maximum digit limit") || msg.contains("digit limit")) {
            code = "integer.digit-limit";
        } else if (msg.contains("invalid JSON") || msg.contains("invalid TOML") || msg.contains("invalid XML")) {
            code = "document.parse-error";
        }
        
        return List.of(new JsonDiagnostic(path, code));
    }

    private static void compareJsonDiagnostics(List<JsonDiagnostic> actual, JsonNode expected) {
        if (expected == null) return;
        List<JsonDiagnostic> exp = new ArrayList<>();
        for (JsonNode n : expected) {
            exp.add(new JsonDiagnostic(n.get("path").asText(), n.get("code").asText()));
        }
        if (actual.size() != exp.size()) {
            throw new RuntimeException("Diagnostics count mismatch. Expected: " + exp + ", Got: " + actual);
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).code().equals(exp.get(i).code())) {
                throw new RuntimeException("Diagnostic code mismatch at index " + i + ". Expected: " + exp.get(i) + ", Got: " + actual.get(i));
            }
        }
    }

    private static void compareLintFindings(List<LintFinding> actual, JsonNode expected) {
        Set<String> act = actual.stream().map(d -> d.code() + "|" + d.severity() + "|" + d.location()).collect(Collectors.toSet());
        Set<String> exp = new HashSet<>();
        if (expected != null) {
            for (JsonNode n : expected) {
                String c = n.get("code").asText();
                if (c.startsWith("lint.")) c = c.substring(5);
                exp.add(c + "|" + n.get("severity").asText() + "|" + n.get("location").asText());
            }
        }
        if (!act.equals(exp)) {
            throw new RuntimeException("Lint findings mismatch. Expected: " + exp + ", Got: " + act);
        }
    }

    private static void compareFallbacks(List<AnyFallback> actual, JsonNode expected) {
        Set<String> act = actual.stream().map(AnyFallback::location).collect(Collectors.toSet());
        Set<String> exp = new HashSet<>();
        if (expected != null) {
            for (JsonNode n : expected) {
                exp.add(n.get("location").asText());
            }
        }
        if (!act.equals(exp)) {
            throw new RuntimeException("Fallbacks mismatch. Expected: " + exp + ", Got: " + act);
        }
    }
}
