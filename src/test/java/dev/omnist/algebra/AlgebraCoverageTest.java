package dev.omnist.algebra;

import dev.omnist.algebra.LintFinding;
import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import dev.omnist.schema.Field;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Record;
import dev.omnist.schema.Schema;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Type;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AlgebraCoverageTest {

    @Test
    void testAlgebraExtractEdgeCases() {
        Schema schema = OsdReader.read("""
            record Sub {
                "val": string,
            }
            record Root {
                "id": integer,
                "sub" [0,1]: Sub,
            }
            root Root
            """);

        // Extracting optional field "sub" without mandatory "id" throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> SchemaAlgebra.extract(schema, Set.of("sub")));

        // Extracting all mandatory fields succeeds
        Schema sExt = SchemaAlgebra.extract(schema, Set.of("id", "sub", "val"));
        assertNotNull(sExt);
    }

    @Test
    void testInferWithReportAllowAny() {
        Node doc1 = new Node(List.of(
            new Edge("id", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("mixed", new Scalar.StringScalar("hello"))
        ));
        Node doc2 = new Node(List.of(
            new Edge("id", new Scalar.IntegerScalar(BigInteger.valueOf(2))),
            new Edge("mixed", new Scalar.IntegerScalar(BigInteger.valueOf(100)))
        ));

        InferResult res = SchemaAlgebra.inferWithReport(List.of(doc1, doc2), "R", true);
        assertNotNull(res);
        assertNotNull(res.schema());
        assertNotNull(res.fallbacks());
        assertFalse(res.fallbacks().isEmpty());
    }

    @Test
    void testInferGeneratesIdentifiersFromUnusualFieldLabels() {
        // A nested Node field's label becomes a generated record name via
        // identifier(): exercises the non-alnum/underscore sanitization branch
        // ("my label" has a space), the leading-digit-strip branch ("123field"),
        // the leading-underscore-strip branch ("_field"), and the immediate-break
        // branch for a label that's already a valid leading char ("plain").
        Node doc = new Node(List.of(
            new Edge("my label", new Node(List.of(new Edge("x", new Scalar.IntegerScalar(BigInteger.ONE))))),
            new Edge("123field", new Node(List.of(new Edge("x", new Scalar.IntegerScalar(BigInteger.ONE))))),
            new Edge("_field", new Node(List.of(new Edge("x", new Scalar.IntegerScalar(BigInteger.ONE))))),
            new Edge("plain", new Node(List.of(new Edge("x", new Scalar.IntegerScalar(BigInteger.ONE)))))
        ));
        Schema schema = SchemaAlgebra.infer(List.of(doc), "R", false);
        assertNotNull(schema);
        assertTrue(schema.records().size() >= 5);
    }

    @Test
    void testLintEdgeCases() {
        Schema s = OsdReader.read("""
            record Root {
                "id": integer,
            }
            record Unused {
                "x": string,
            }
            root Root
            """);
        List<LintFinding> findings = SchemaAlgebra.lint(s);
        assertNotNull(findings);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.code().equals("lint.unreachable-record")));
    }

    @Test
    void testLintWithUndefinedRootDoesNotThrow() {
        // A schema whose root name isn't a key in records() at all (not constructible
        // via OsdReader, which validates this, but Schema's own constructor doesn't) --
        // exercises reachablePlain's schema.records().containsKey(name) false branch.
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("Other", new Record("Other", List.of()));
        Schema schema = new Schema("Missing", records);

        List<LintFinding> findings = SchemaAlgebra.lint(schema);
        assertNotNull(findings, "lint() should not throw on an undefined root");
    }

    @Test
    void testSchemaAlgebraSelfCycleLintsAndPrunes() {
        // Self-reference cycle should not cause infinite loop in lint() or prune()
        // Tests dedupe-on-revisit in both reachablePlain() and reachable() methods
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", new Record("R", List.of(
            new Field("name", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("self", new Type.Ref("R"), 0, 1)  // Optional self-reference
        )));
        Schema schema = new Schema("R", records);

        // Both lint and prune should complete without hanging or throwing
        List<LintFinding> lintFindings = SchemaAlgebra.lint(schema);
        assertNotNull(lintFindings, "lint() should not throw or hang");

        Schema prunedSchema = SchemaAlgebra.prune(schema);
        assertNotNull(prunedSchema, "prune() should not throw or hang");
        assertFalse(prunedSchema.records().isEmpty(), "prune() should preserve the record");
    }
}
