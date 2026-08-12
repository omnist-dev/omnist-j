package dev.omnist.algebra;

import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
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
}
