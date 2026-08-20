package dev.omnist.validation;

import dev.omnist.document.*;
import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

    @Test
    @DisplayName("Happy path: valid document against schema passes with zero diagnostics (including integer -> number subtyping)")
    void testHappyPath() {
        Record dbRec = new Record("Database", List.of(
                new Field("type", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("port", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1) // NUMBER field
        ));

        Record serviceRec = new Record("Service", List.of(
                new Field("host", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("db", new Type.Ref("Database"), 1, 1),
                new Field("owner", new Type.Scalar(ScalarKind.STRING, true), 0, 1),
                new Field("payload", Type.Any.INSTANCE, 1, 1)
        ));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("Database", dbRec);
        records.put("Service", serviceRec);
        Schema schema = new Schema("Service", records);

        // Document has integer value 5432 for Database.port (integer -> number subtyping §6.3)
        Node dbNode = new Node(List.of(
                new Edge("type", new Scalar.StringScalar("postgres")),
                new Edge("port", new Scalar.IntegerScalar(BigInteger.valueOf(5432)))
        ));

        Node docNode = new Node(List.of(
                new Edge("host", new Scalar.StringScalar("localhost")),
                new Edge("db", dbNode),
                new Edge("owner", Value.NULL),
                new Edge("payload", new Node(List.of(new Edge("foo", new Scalar.StringScalar("bar")))))
        ));

        ValidationResult res = Validator.validate(docNode, schema);
        assertTrue(res.isValid(), "Document should be valid");
        assertTrue(res.diagnostics().isEmpty(), "Diagnostics should be empty");
    }

    @Test
    @DisplayName("Explicit 3+ repeated label path indexing check (§3.6.1): 1st $.item, 2nd $.item[1], 3rd $.item[2]")
    void testRepeatedLabelPathIndexing() {
        Record r = new Record("R", List.of(
                new Field("item", new Type.Scalar(ScalarKind.STRING, false), 0, null)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        // 3 'item' edges, all with invalid types for STRING
        Node docNode = new Node(List.of(
                new Edge("item", new Scalar.BooleanScalar(true)),                                      // 1st: $.item
                new Edge("item", new Scalar.IntegerScalar(BigInteger.valueOf(42))),                    // 2nd: $.item[1]
                new Edge("item", new Node(List.of(new Edge("x", new Scalar.StringScalar("y")))))       // 3rd: $.item[2]
        ));

        ValidationResult res = Validator.validate(docNode, schema);
        assertFalse(res.isValid());
        assertEquals(3, res.diagnostics().size());

        assertEquals("$.item[0]", res.diagnostics().get(0).path(), "1st occurrence of repeated label must have [0] index");
        assertEquals("validate.type-mismatch", res.diagnostics().get(0).code());

        assertEquals("$.item[1]", res.diagnostics().get(1).path(), "2nd occurrence must have index [1]");
        assertEquals("validate.type-mismatch", res.diagnostics().get(1).code());

        assertEquals("$.item[2]", res.diagnostics().get(2).path(), "3rd occurrence must have index [2]");
        assertEquals("validate.shape-mismatch", res.diagnostics().get(2).code());
    }

    @Test
    @DisplayName("Multi-error collection: validate collects ALL failures across the tree without stopping early")
    void testMultiErrorCollection() {
        Record r = new Record("R", List.of(
                new Field("req", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("non_null", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        // Document has missing req (cardinality error at $), non_null = null (null-not-allowed), and extra = 1 (unexpected-field)
        Node docNode = new Node(List.of(
                new Edge("non_null", Value.NULL),
                new Edge("extra", new Scalar.IntegerScalar(BigInteger.valueOf(1)))
        ));

        ValidationResult res = Validator.validate(docNode, schema);
        assertFalse(res.isValid());
        assertEquals(3, res.diagnostics().size());

        List<String> codes = res.diagnostics().stream().map(ValidationDiagnostic::code).toList();
        assertTrue(codes.contains("validate.cardinality"));
        assertTrue(codes.contains("validate.null-not-allowed"));
        assertTrue(codes.contains("validate.unexpected-field"));
    }

    @Test
    @DisplayName("Cardinality error path rule (§3.6): path is parent record node path, NOT field edge path")
    void testCardinalityErrorPathIsParentNode() {
        Record subRec = new Record("Sub", List.of(
                new Field("req_field", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Record rootRec = new Record("Root", List.of(
                new Field("sub", new Type.Ref("Sub"), 1, 1)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("Sub", subRec);
        records.put("Root", rootRec);
        Schema schema = new Schema("Root", records);

        // Sub node is empty (missing req_field)
        Node subNode = new Node(List.of());
        Node rootNode = new Node(List.of(new Edge("sub", subNode)));

        ValidationResult res = Validator.validate(rootNode, schema);
        assertFalse(res.isValid());
        assertEquals(1, res.diagnostics().size());

        ValidationDiagnostic d = res.diagnostics().get(0);
        assertEquals("validate.cardinality", d.code());
        assertEquals("$.sub", d.path(), "Cardinality failure path must be parent node path $.sub, not $.sub.req_field");
    }

    @Test
    @DisplayName("Closedness & unexpected-field no-descent rule (§3.6)")
    void testUnexpectedFieldNoDescent() {
        Record r = new Record("R", List.of(
                new Field("known", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        Node extraInner = new Node(List.of(
                new Edge("bad_inner", new Scalar.BooleanScalar(true))
        ));

        Node docNode = new Node(List.of(
                new Edge("known", new Scalar.StringScalar("ok")),
                new Edge("extra", extraInner)
        ));

        ValidationResult res = Validator.validate(docNode, schema);
        assertFalse(res.isValid());
        assertEquals(1, res.diagnostics().size(), "Only 1 unexpected-field diagnostic should be reported (no descent into extra)");
        assertEquals("$.extra", res.diagnostics().get(0).path());
        assertEquals("validate.unexpected-field", res.diagnostics().get(0).code());
    }

    @Test
    @DisplayName("Type.Any target accepts scalar, null, and nested object unchecked")
    void testAnyTargetAcceptsAllUnchecked() {
        Record r = new Record("R", List.of(
                new Field("a1", Type.Any.INSTANCE, 1, 1),
                new Field("a2", Type.Any.INSTANCE, 1, 1),
                new Field("a3", Type.Any.INSTANCE, 1, 1)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        Node docNode = new Node(List.of(
                new Edge("a1", new Scalar.IntegerScalar(BigInteger.valueOf(10))),
                new Edge("a2", Value.NULL),
                new Edge("a3", new Node(List.of(new Edge("x", new Scalar.BooleanScalar(false)))))
        ));

        ValidationResult res = Validator.validate(docNode, schema);
        assertTrue(res.isValid());
    }

    @Test
    @DisplayName("Bare-value document against record schema reports validate.shape-mismatch at $")
    void testBareValueDocumentAgainstRecordSchema() {
        Record r = new Record("R", List.of(
                new Field("f", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        Document bareVal = new Scalar.IntegerScalar(BigInteger.valueOf(42));

        ValidationResult res = Validator.validate(bareVal, schema);
        assertFalse(res.isValid());
        assertEquals(1, res.diagnostics().size());
        assertEquals("$", res.diagnostics().get(0).path());
        assertEquals("validate.shape-mismatch", res.diagnostics().get(0).code());
    }
}
