package dev.omnist.validation;

import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;

import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationCoverageTest {

    @Test
    void testValidationEdgeCases() {
        Schema schema = OsdReader.read("""
            record Sub {
                "val": string,
            }
            record Root {
                "req": integer,
                "opt" [0,1]: string,
                "arr" [2,3]: string,
                "sub" [1,1]: Sub,
            }
            root Root
            """);

        // 1. Missing mandatory field "req"
        Node docMissingReq = new Node(List.of(
            new Edge("arr", new Scalar.StringScalar("a")),
            new Edge("arr", new Scalar.StringScalar("b")),
            new Edge("sub", new Node(List.of(new Edge("val", new Scalar.StringScalar("x")))))
        ));
        ValidationResult res1 = Validator.validate(docMissingReq, schema);
        assertFalse(res1.isValid());
        assertTrue(res1.diagnostics().stream().anyMatch(d -> d.code().equals("validate.cardinality")));

        // 2. Cardinality too small for "arr" (only 1 element, required 2)
        Node docTooFewArr = new Node(List.of(
            new Edge("req", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("arr", new Scalar.StringScalar("a")),
            new Edge("sub", new Node(List.of(new Edge("val", new Scalar.StringScalar("x")))))
        ));
        ValidationResult res2 = Validator.validate(docTooFewArr, schema);
        assertFalse(res2.isValid());
        assertTrue(res2.diagnostics().stream().anyMatch(d -> d.code().equals("validate.cardinality")));

        // 3. Cardinality too large for "arr" (4 elements, max 3)
        Node docTooManyArr = new Node(List.of(
            new Edge("req", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("arr", new Scalar.StringScalar("a")),
            new Edge("arr", new Scalar.StringScalar("b")),
            new Edge("arr", new Scalar.StringScalar("c")),
            new Edge("arr", new Scalar.StringScalar("d")),
            new Edge("sub", new Node(List.of(new Edge("val", new Scalar.StringScalar("x")))))
        ));
        ValidationResult res3 = Validator.validate(docTooManyArr, schema);
        assertFalse(res3.isValid());
        assertTrue(res3.diagnostics().stream().anyMatch(d -> d.code().equals("validate.cardinality")));

        // 4. Unexpected field in root
        Node docExtraField = new Node(List.of(
            new Edge("req", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("arr", new Scalar.StringScalar("a")),
            new Edge("arr", new Scalar.StringScalar("b")),
            new Edge("sub", new Node(List.of(new Edge("val", new Scalar.StringScalar("x"))))),
            new Edge("extraField", new Scalar.StringScalar("extra"))
        ));
        ValidationResult res4 = Validator.validate(docExtraField, schema);
        assertFalse(res4.isValid());
        assertTrue(res4.diagnostics().stream().anyMatch(d -> d.code().equals("validate.unexpected-field")));
    }
}
