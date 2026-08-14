package dev.omnist.algebra;

import dev.omnist.schema.Field;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SchemaAlgebraTest {

    @Test
    @DisplayName("satisfiableSet identifies simple satisfiable record (§6.4)")
    void testSatisfiableSetBasic() {
        dev.omnist.schema.Record recA = new dev.omnist.schema.Record("A", List.of(
                new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Schema schema = new Schema("A", Map.of("A", recA));

        Set<String> sat = SchemaAlgebra.satisfiableSet(schema);
        assertEquals(Set.of("A"), sat);
        assertFalse(SchemaAlgebra.isEmpty(schema));
    }

    @Test
    @DisplayName("satisfiableSet identifies unsatisfiable cycle (§6.4)")
    void testSatisfiableSetUnsatisfiableCycle() {
        dev.omnist.schema.Record recA = new dev.omnist.schema.Record("A", List.of(
                new Field("b", new Type.Ref("B"), 1, 1)
        ));
        dev.omnist.schema.Record recB = new dev.omnist.schema.Record("B", List.of(
                new Field("a", new Type.Ref("A"), 1, 1)
        ));
        Schema schema = new Schema("A", Map.of("A", recA, "B", recB));

        Set<String> sat = SchemaAlgebra.satisfiableSet(schema);
        assertTrue(sat.isEmpty());
        assertTrue(SchemaAlgebra.isEmpty(schema));
    }

    @Test
    @DisplayName("satisfiableSet identifies optional self-cycle as satisfiable (§6.4)")
    void testSatisfiableSetOptionalCycleSatisfiable() {
        dev.omnist.schema.Record node = new dev.omnist.schema.Record("Node", List.of(
                new Field("next", new Type.Ref("Node"), 0, 1),
                new Field("val", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Schema schema = new Schema("Node", Map.of("Node", node));

        Set<String> sat = SchemaAlgebra.satisfiableSet(schema);
        assertEquals(Set.of("Node"), sat);
        assertFalse(SchemaAlgebra.isEmpty(schema));
    }

    @Test
    @DisplayName("prune drops unreachable and unsatisfiable records (§6.5)")
    void testPruneDropsUnreachableAndUnsatisfiable() {
        dev.omnist.schema.Record recA = new dev.omnist.schema.Record("A", List.of(
                new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record recB = new dev.omnist.schema.Record("B", List.of(
                new Field("y", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)
        ));
        dev.omnist.schema.Record recC = new dev.omnist.schema.Record("C", List.of(
                new Field("c", new Type.Ref("C"), 1, 1)
        ));
        Schema schema = new Schema("A", Map.of("A", recA, "B", recB, "C", recC));

        Schema pruned = SchemaAlgebra.prune(schema);
        assertEquals(1, pruned.records().size());
        assertTrue(pruned.records().containsKey("A"));
    }

    @Test
    @DisplayName("prune on root-unsatisfiable schema keeps root fields intact (§6.5)")
    void testPruneUnsatisfiableRootLeavesRecordsIntact() {
        dev.omnist.schema.Record child = new dev.omnist.schema.Record("Child", List.of(
                new Field("val", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));

        dev.omnist.schema.Record unsatRoot = new dev.omnist.schema.Record("UnsatRoot", List.of(
                new Field("loop", new Type.Ref("UnsatRoot"), 1, 1),
                new Field("dead", new Type.Scalar(ScalarKind.STRING, false), 0, 0),
                new Field("ref", new Type.Ref("Child"), 1, 1)
        ));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("UnsatRoot", unsatRoot);
        records.put("Child", child);

        Schema schema = new Schema("UnsatRoot", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        dev.omnist.schema.Record prunedRoot = pruned.records().get("UnsatRoot");
        assertEquals(3, prunedRoot.fields().size());
        assertEquals(unsatRoot.fields(), prunedRoot.fields());
        assertTrue(pruned.records().containsKey("Child"));
    }

    @Test
    @DisplayName("prune retains original declaration order in output environment map (§6.5)")
    void testPruneDeclarationOrderDeterminism() {
        dev.omnist.schema.Record c = new dev.omnist.schema.Record("C", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        dev.omnist.schema.Record b = new dev.omnist.schema.Record("B", List.of(new Field("c", new Type.Ref("C"), 1, 1)));
        dev.omnist.schema.Record a = new dev.omnist.schema.Record("A", List.of(new Field("b", new Type.Ref("B"), 1, 1)));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("A", a);
        records.put("B", b);
        records.put("C", c);

        Schema schema = new Schema("A", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        List<String> keyOrder = List.copyOf(pruned.records().keySet());
        assertEquals(List.of("A", "B", "C"), keyOrder);
    }

    @Test
    @DisplayName("prune retains declaration order across a large 12-record schema (regression check against Map.copyOf nondeterminism)")
    void testPruneDeclarationOrderDeterminismLarge() {
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        List<String> expectedNames = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            String name = "Rec_" + (char) ('A' + i);
            expectedNames.add(name);
        }

        for (int i = 0; i < 12; i++) {
            String currentName = expectedNames.get(i);
            List<Field> fields = new ArrayList<>();
            fields.add(new Field("val", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1));
            if (i + 1 < 12) {
                fields.add(new Field("next", new Type.Ref(expectedNames.get(i + 1)), 1, 1));
            }
            records.put(currentName, new dev.omnist.schema.Record(currentName, fields));
        }

        Schema schema = new Schema("Rec_A", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        List<String> actualOrder = List.copyOf(pruned.records().keySet());
        assertEquals(expectedNames, actualOrder);
    }

    @Test
    @DisplayName("compatibleWith worked example (§6.6)")
    void testCompatibleWithWorkedExample() {
        dev.omnist.schema.Record userA = new dev.omnist.schema.Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("name", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("nick", new Type.Scalar(ScalarKind.STRING, false), 0, 1)
        ));
        Schema schemaA = new Schema("User", Map.of("User", userA));

        dev.omnist.schema.Record userB = new dev.omnist.schema.Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("name", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Schema schemaB = new Schema("User", Map.of("User", userB));

        assertFalse(SchemaAlgebra.compatibleWith(schemaA, schemaB));
        assertTrue(SchemaAlgebra.compatibleWith(schemaB, schemaA));
    }

    @Test
    @DisplayName("compatibleWith pre-filters unsatisfiable optional Ref fields in A (§6.6)")
    void testCompatibleWithUnsatisfiableRefPrefilter() {
        dev.omnist.schema.Record badRec = new dev.omnist.schema.Record("BadRec", List.of(
                new Field("loop", new Type.Ref("BadRec"), 1, 1)
        ));
        dev.omnist.schema.Record userA = new dev.omnist.schema.Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("opt_bad", new Type.Ref("BadRec"), 0, 1)
        ));
        Map<String, dev.omnist.schema.Record> mapA = new LinkedHashMap<>();
        mapA.put("User", userA);
        mapA.put("BadRec", badRec);
        Schema schemaA = new Schema("User", mapA);

        dev.omnist.schema.Record userB = new dev.omnist.schema.Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Schema schemaB = new Schema("User", Map.of("User", userB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB));
    }

    @Test
    @DisplayName("compatibleWith enforces scalar subtyping (§6.3): integer <: number")
    void testCompatibleWithScalarSubtyping() {
        dev.omnist.schema.Record rA = new dev.omnist.schema.Record("R", List.of(new Field("f", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)));
        Schema schemaA = new Schema("R", Map.of("R", rA));

        dev.omnist.schema.Record rB = new dev.omnist.schema.Record("R", List.of(new Field("f", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1)));
        Schema schemaB = new Schema("R", Map.of("R", rB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB));
        assertFalse(SchemaAlgebra.compatibleWith(schemaB, schemaA));
    }

    @Test
    @DisplayName("compatibleWith handles self-referential coinductive cycles without stack overflow (§6.6)")
    void testCompatibleWithCoinduction() {
        dev.omnist.schema.Record nodeA = new dev.omnist.schema.Record("Node", List.of(new Field("child", new Type.Ref("Node"), 0, 1)));
        Schema schemaA = new Schema("Node", Map.of("Node", nodeA));

        dev.omnist.schema.Record nodeB = new dev.omnist.schema.Record("Node", List.of(new Field("child", new Type.Ref("Node"), 0, 1)));
        Schema schemaB = new Schema("Node", Map.of("Node", nodeB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB));
        assertTrue(SchemaAlgebra.equivalent(schemaA, schemaB));
    }

    @Test
    @DisplayName("equivalent evaluates language equivalence regardless of record names (§6.7)")
    void testEquivalentDifferentRecordNames() {
        dev.omnist.schema.Record recA = new dev.omnist.schema.Record("UserA", List.of(new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schemaA = new Schema("UserA", Map.of("UserA", recA));

        dev.omnist.schema.Record recB = new dev.omnist.schema.Record("UserB", List.of(new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schemaB = new Schema("UserB", Map.of("UserB", recB));

        assertTrue(SchemaAlgebra.equivalent(schemaA, schemaB));
    }

    @Test
    @DisplayName("compatibleWith Type.Any behavior (§6.6): RHS Any absorbs all, LHS Any requires RHS Any")
    void testCompatibleWithAny() {
        dev.omnist.schema.Record rA = new dev.omnist.schema.Record("R", List.of(new Field("f", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schemaA = new Schema("R", Map.of("R", rA));

        dev.omnist.schema.Record rB = new dev.omnist.schema.Record("R", List.of(new Field("f", Type.Any.INSTANCE, 1, 1)));
        Schema schemaB = new Schema("R", Map.of("R", rB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB));
        assertFalse(SchemaAlgebra.compatibleWith(schemaB, schemaA));
    }

    @Test
    @DisplayName("equivalenceClasses groups records into structural equivalence blocks (§6.8)")
    void testEquivalenceClassesWorkedExample() {
        dev.omnist.schema.Record recA = new dev.omnist.schema.Record("A", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        dev.omnist.schema.Record recB = new dev.omnist.schema.Record("B", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        dev.omnist.schema.Record recTop = new dev.omnist.schema.Record("Top", List.of(
            new Field("a", new Type.Ref("A"), 1, 1),
            new Field("b", new Type.Ref("B"), 1, 1)
        ));
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("A", recA);
        records.put("B", recB);
        records.put("Top", recTop);
        Schema schema = new Schema("Top", records);

        List<List<String>> eq = SchemaAlgebra.equivalenceClasses(schema);
        assertEquals(2, eq.size());
        assertEquals(List.of("A", "B"), eq.get(0));
        assertEquals(List.of("Top"), eq.get(1));
    }

    @Test
    @DisplayName("normalize replaces equivalent record targets with canonical representative (§6.8)")
    void testNormalizeWorkedExample() {
        dev.omnist.schema.Record recA = new dev.omnist.schema.Record("A", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        dev.omnist.schema.Record recB = new dev.omnist.schema.Record("B", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        dev.omnist.schema.Record recTop = new dev.omnist.schema.Record("Top", List.of(
            new Field("a", new Type.Ref("A"), 1, 1),
            new Field("b", new Type.Ref("B"), 1, 1)
        ));
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("A", recA);
        records.put("B", recB);
        records.put("Top", recTop);
        Schema schema = new Schema("Top", records);

        Schema norm = SchemaAlgebra.normalize(schema);
        assertEquals(2, norm.records().size());
        assertTrue(norm.records().containsKey("A"));
        assertFalse(norm.records().containsKey("B"));
        assertTrue(norm.records().containsKey("Top"));

        dev.omnist.schema.Record topNorm = norm.records().get("Top");
        assertEquals("A", ((Type.Ref) topNorm.field("a").type()).name());
        assertEquals("A", ((Type.Ref) topNorm.field("b").type()).name());
    }

    @Test
    @DisplayName("normalize returns empty schema unchanged (§6.8)")
    void testNormalizeEmptySchemaReturnsUnchanged() {
        dev.omnist.schema.Record recC = new dev.omnist.schema.Record("C", List.of(new Field("c", new Type.Ref("C"), 1, 1)));
        Schema schema = new Schema("C", Map.of("C", recC));

        Schema norm = SchemaAlgebra.normalize(schema);
        assertEquals(schema, norm);
    }

    @Test
    @DisplayName("extract worked example 1: optional coupon field drops successfully (§6.9)")
    void testExtractWorkedExample1() {
        dev.omnist.schema.Record address = new dev.omnist.schema.Record("Address", List.of(
            new Field("street", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("city", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record lineItem = new dev.omnist.schema.Record("LineItem", List.of(
            new Field("sku", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("qty", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1),
            new Field("price", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1)
        ));
        dev.omnist.schema.Record order = new dev.omnist.schema.Record("Order", List.of(
            new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("status", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("total", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1),
            new Field("address", new Type.Ref("Address"), 1, 1),
            new Field("items", new Type.Ref("LineItem"), 1, null),
            new Field("coupon", new Type.Scalar(ScalarKind.STRING, false), 0, 1)
        ));
        dev.omnist.schema.Record root = new dev.omnist.schema.Record("Root", List.of(
            new Field("order", new Type.Ref("Order"), 1, 1)
        ));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Address", address);
        records.put("LineItem", lineItem);
        records.put("Order", order);
        records.put("Root", root);

        Schema schema = new Schema("Root", records);

        Set<String> keep = Set.of("order", "id", "status", "total", "address", "street", "city", "items", "sku", "qty", "price");
        Schema extracted = SchemaAlgebra.extract(schema, keep);

        assertNotNull(extracted);
        dev.omnist.schema.Record extOrder = extracted.records().get("Order");
        assertNotNull(extOrder);
        assertNull(extOrder.field("coupon"));
    }

    @Test
    @DisplayName("extract worked example 2: removing mandatory fields causes failure and reports first_bad (§6.9)")
    void testExtractWorkedExample2() {
        dev.omnist.schema.Record address = new dev.omnist.schema.Record("Address", List.of(
            new Field("street", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("city", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record lineItem = new dev.omnist.schema.Record("LineItem", List.of(
            new Field("sku", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("qty", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1),
            new Field("price", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1)
        ));
        dev.omnist.schema.Record order = new dev.omnist.schema.Record("Order", List.of(
            new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("status", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("total", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1),
            new Field("address", new Type.Ref("Address"), 1, 1),
            new Field("items", new Type.Ref("LineItem"), 1, null),
            new Field("coupon", new Type.Scalar(ScalarKind.STRING, false), 0, 1)
        ));
        dev.omnist.schema.Record root = new dev.omnist.schema.Record("Root", List.of(
            new Field("order", new Type.Ref("Order"), 1, 1)
        ));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Address", address);
        records.put("LineItem", lineItem);
        records.put("Order", order);
        records.put("Root", root);

        Schema schema = new Schema("Root", records);

        Set<String> keep = Set.of("order", "id", "status", "street", "city", "sku", "qty", "price");
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            SchemaAlgebra.extract(schema, keep);
        });
        assertEquals("removing label total deletes a mandatory field of Order", ex.getMessage());
    }

    @Test
    @DisplayName("extract worked example 3: first_bad is declaration order based over the whole env (§6.9)")
    void testExtractWorkedExample3() {
        dev.omnist.schema.Record address = new dev.omnist.schema.Record("Address", List.of(
            new Field("street", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("city", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record lineItem = new dev.omnist.schema.Record("LineItem", List.of(
            new Field("sku", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("qty", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1),
            new Field("price", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1)
        ));
        dev.omnist.schema.Record order = new dev.omnist.schema.Record("Order", List.of(
            new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("status", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("total", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1),
            new Field("address", new Type.Ref("Address"), 1, 1),
            new Field("items", new Type.Ref("LineItem"), 1, null),
            new Field("coupon", new Type.Scalar(ScalarKind.STRING, false), 0, 1)
        ));
        dev.omnist.schema.Record root = new dev.omnist.schema.Record("Root", List.of(
            new Field("order", new Type.Ref("Order"), 1, 1)
        ));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Address", address);
        records.put("LineItem", lineItem);
        records.put("Order", order);
        records.put("Root", root);

        Schema schema = new Schema("Root", records);

        Set<String> keep = Set.of("order");
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            SchemaAlgebra.extract(schema, keep);
        });
        assertEquals("removing label street deletes a mandatory field of Address", ex.getMessage());
    }

    @Test
    @DisplayName("extract drops reference fields pointing to invalidated records (§6.9)")
    void testExtractDropReferenceToInvalidated() {
        dev.omnist.schema.Record coupon = new dev.omnist.schema.Record("Coupon", List.of(
            new Field("code", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            new Field("discount", new Type.Scalar(ScalarKind.NUMBER, false), 0, 1)
        ));
        dev.omnist.schema.Record order = new dev.omnist.schema.Record("Order", List.of(
            new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
            // optional reference to Coupon
            new Field("coupon", new Type.Ref("Coupon"), 0, 1)
        ));
        dev.omnist.schema.Record root = new dev.omnist.schema.Record("Root", List.of(
            new Field("order", new Type.Ref("Order"), 1, 1)
        ));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Coupon", coupon);
        records.put("Order", order);
        records.put("Root", root);

        Schema schema = new Schema("Root", records);

        // Keep order, id, coupon, discount (drops code, invalidating Coupon)
        Set<String> keep = Set.of("order", "id", "coupon", "discount");
        Schema extracted = SchemaAlgebra.extract(schema, keep);

        assertNotNull(extracted);
        // Coupon should be completely gone since it was invalidated
        assertFalse(extracted.records().containsKey("Coupon"));
        // Order should still exist but the coupon field (referencing Coupon) must be dropped
        dev.omnist.schema.Record extOrder = extracted.records().get("Order");
        assertNotNull(extOrder);
        assertNull(extOrder.field("coupon"));
    }

    @Test
    @DisplayName("lint detects unsatisfiable, unreachable, duplicate records and any-fields (§6.11)")
    void testLintDiagnostics() {
        dev.omnist.schema.Record cycle = new dev.omnist.schema.Record("Cycle", List.of(
            new Field("next", new Type.Ref("Cycle"), 1, 1)
        ));
        dev.omnist.schema.Record unused = new dev.omnist.schema.Record("Unused", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record dupA = new dev.omnist.schema.Record("DupA", List.of(
            new Field("val", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record dupB = new dev.omnist.schema.Record("DupB", List.of(
            new Field("val", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        dev.omnist.schema.Record root = new dev.omnist.schema.Record("Root", List.of(
            new Field("anyField", Type.Any.INSTANCE, 0, 1),
            new Field("toCycle", new Type.Ref("Cycle"), 1, 1),
            new Field("toDupA", new Type.Ref("DupA"), 0, 1),
            new Field("toDupB", new Type.Ref("DupB"), 0, 1)
        ));

        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Cycle", cycle);
        records.put("Unused", unused);
        records.put("DupA", dupA);
        records.put("DupB", dupB);
        records.put("Root", root);

        Schema schema = new Schema("Root", records);

        List<LintFinding> findings = SchemaAlgebra.lint(schema);
        assertNotNull(findings);
        assertEquals(5, findings.size());

        LintFinding f1 = findings.get(0);
        assertEquals("lint.any-field", f1.code());
        assertEquals("info", f1.severity());
        assertEquals("Root.anyField", f1.location());

        LintFinding f2 = findings.get(1);
        assertEquals("lint.duplicate-record", f2.code());
        assertEquals("warning", f2.severity());
        assertEquals("DupA, DupB", f2.location());

        LintFinding f3 = findings.get(2);
        assertEquals("lint.unreachable-record", f3.code());
        assertEquals("warning", f3.severity());
        assertEquals("Unused", f3.location());

        LintFinding f4 = findings.get(3);
        assertEquals("lint.unsatisfiable-record", f4.code());
        assertEquals("warning", f4.severity());
        assertEquals("Cycle", f4.location());

        LintFinding f5 = findings.get(4);
        assertEquals("lint.unsatisfiable-record", f5.code());
        assertEquals("warning", f5.severity());
        assertEquals("Root", f5.location());
    }

    @Test
    @DisplayName("infer validation check: zero samples and scalar root (§6.10)")
    void testInferValidation() {
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () -> {
            SchemaAlgebra.infer(List.of());
        });
        assertEquals("cannot infer a schema from zero samples", ex1.getMessage());

        List<dev.omnist.document.Document> samples = List.of(
            new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.valueOf(42))
        );
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () -> {
            SchemaAlgebra.infer(samples);
        });
        assertEquals("infer expects object (record) samples at the root", ex2.getMessage());
    }

    @Test
    @DisplayName("infer happy path: cardinalities, arrays, and subtyping (§6.10)")
    void testInferHappyPath() {
        dev.omnist.document.Node sample1 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("tag", new dev.omnist.document.Scalar.StringScalar("a")),
            new dev.omnist.document.Edge("tag", new dev.omnist.document.Scalar.StringScalar("b")),
            new dev.omnist.document.Edge("n", new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.valueOf(1)))
        ));

        dev.omnist.document.Node sample2 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("tag", new dev.omnist.document.Scalar.StringScalar("c")),
            new dev.omnist.document.Edge("n", new dev.omnist.document.Scalar.NumberScalar(2.5)),
            new dev.omnist.document.Edge("opt", new dev.omnist.document.Scalar.StringScalar("x"))
        ));

        List<dev.omnist.document.Document> samples = List.of(sample1, sample2);
        Schema schema = SchemaAlgebra.infer(samples);

        assertNotNull(schema);
        assertEquals("Root", schema.root());

        dev.omnist.schema.Record root = schema.records().get("Root");
        assertNotNull(root);

        Field tag = root.field("tag");
        assertNotNull(tag);
        assertEquals(0, tag.min());
        assertNull(tag.max());
        assertEquals(ScalarKind.STRING, ((Type.Scalar) tag.type()).kind());

        Field n = root.field("n");
        assertNotNull(n);
        assertEquals(1, n.min());
        assertEquals(1, n.max());
        assertEquals(ScalarKind.NUMBER, ((Type.Scalar) n.type()).kind());

        Field opt = root.field("opt");
        assertNotNull(opt);
        assertEquals(0, opt.min());
        assertEquals(1, opt.max());
        assertEquals(ScalarKind.STRING, ((Type.Scalar) opt.type()).kind());
    }

    @Test
    @DisplayName("infer type conflicts fail by default but open to any with allowAny=true (§6.10)")
    void testInferConflicts() {
        dev.omnist.document.Node sample1 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("id", new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.valueOf(7)))
        ));
        dev.omnist.document.Node sample2 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("id", new dev.omnist.document.Scalar.StringScalar("seven"))
        ));
        List<dev.omnist.document.Document> samples = List.of(sample1, sample2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            SchemaAlgebra.infer(samples);
        });
        assertTrue(ex.getMessage().contains("has values of more than one scalar kind") 
            || ex.getMessage().contains("has values of more than one scalar"));

        InferResult result = SchemaAlgebra.inferWithReport(samples, "Root", true);
        assertNotNull(result.schema());
        assertEquals(Type.Any.INSTANCE, result.schema().records().get("Root").field("id").type());
        assertEquals(1, result.fallbacks().size());
        AnyFallback fb = result.fallbacks().get(0);
        assertEquals("Root.id", fb.location());
        assertTrue(fb.reason().contains("values of more than one scalar kind"));
    }

    @Test
    @DisplayName("infer does not normalize output (§6.10)")
    void testInferDoesNotNormalize() {
        dev.omnist.document.Node sample = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("a", new dev.omnist.document.Node(List.of(
                new dev.omnist.document.Edge("x", new dev.omnist.document.Scalar.StringScalar("val"))
            ))),
            new dev.omnist.document.Edge("b", new dev.omnist.document.Node(List.of(
                new dev.omnist.document.Edge("x", new dev.omnist.document.Scalar.StringScalar("val"))
            )))
        ));

        Schema schema = SchemaAlgebra.infer(List.of(sample));
        assertNotNull(schema);

        assertEquals(3, schema.records().size());
        assertTrue(schema.records().containsKey("Root"));
        assertTrue(schema.records().containsKey("A"));
        assertTrue(schema.records().containsKey("B"));
    }

    // ==========================================================================
    // Batch: coverage-gap-driven tests
    // ==========================================================================

    @Test
    @DisplayName("lint: reachablePlain dedupes a cyclic self-reference")
    void testLintCyclicSelfReference() {
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("self", new Type.Ref("R"), 0, 1)
        )));
        Schema schema = new Schema("R", records);
        List<LintFinding> findings = SchemaAlgebra.lint(schema);
        assertNotNull(findings);
    }

    @Test
    @DisplayName("infer: nesting depth exceeding 100 throws")
    void testInferDepthLimitExceeded() {
        dev.omnist.document.Node deep = new dev.omnist.document.Node(List.of());
        for (int i = 0; i < 105; i++) {
            deep = new dev.omnist.document.Node(List.of(new dev.omnist.document.Edge("child", deep)));
        }
        List<dev.omnist.document.Document> samples = List.of(deep);
        assertThrows(IllegalArgumentException.class, () -> SchemaAlgebra.infer(samples));
    }

    @Test
    @DisplayName("infer: null value among samples sets nullable, boolean/date scalar kinds mapped")
    void testInferNullableAndBooleanDateKinds() {
        dev.omnist.document.Node s1 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("flag", new dev.omnist.document.Scalar.BooleanScalar(true)),
            new dev.omnist.document.Edge("d", new dev.omnist.document.Scalar.DateScalar(java.time.LocalDate.parse("2024-01-01")))
        ));
        dev.omnist.document.Node s2 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("flag", dev.omnist.document.Value.NULL),
            new dev.omnist.document.Edge("d", new dev.omnist.document.Scalar.DateScalar(java.time.LocalDate.parse("2024-01-02")))
        ));
        Schema schema = SchemaAlgebra.infer(List.of(s1, s2));
        Field flagField = schema.records().get("Root").field("flag");
        assertInstanceOf(Type.Scalar.class, flagField.type());
        Type.Scalar flagType = (Type.Scalar) flagField.type();
        assertEquals(ScalarKind.BOOLEAN, flagType.kind());
        assertTrue(flagType.nullable());
        Field dField = schema.records().get("Root").field("d");
        assertEquals(ScalarKind.DATE, ((Type.Scalar) dField.type()).kind());
    }

    @Test
    @DisplayName("infer: empty-label sanitization fallback to \"Rec\"")
    void testInferEmptyLabelFallsBackToRec() {
        // identifier() maps every char to [A-Za-z0-9_] and never shortens the
        // string, so it can only return "" when the label itself is "" --
        // unique()'s isEmpty() fallback to "Rec" is reachable only via that.
        dev.omnist.document.Node child = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("x", new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.ONE))
        ));
        dev.omnist.document.Node sample = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("", child)
        ));
        Schema schema = SchemaAlgebra.infer(List.of(sample));
        assertTrue(schema.records().containsKey("Rec"));
    }

    @Test
    @DisplayName("infer: two distinct labels sanitizing/capitalizing to the same name collide")
    void testInferCollidingRecordNames() {
        dev.omnist.document.Node child = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("x", new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.ONE))
        ));
        dev.omnist.document.Node sample = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("item", child),
            new dev.omnist.document.Edge("Item", child)
        ));
        Schema schema = SchemaAlgebra.infer(List.of(sample));
        assertTrue(schema.records().containsKey("Item"));
        assertTrue(schema.records().containsKey("Item2"));
    }

    @Test
    @DisplayName("infer: identifier sanitization strips leading digits/underscores, keeps a fully-numeric label as-is")
    void testInferIdentifierSanitization() {
        dev.omnist.document.Node child = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("x", new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.ONE))
        ));
        dev.omnist.document.Node sample1 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("123abc", child)
        ));
        Schema schema1 = SchemaAlgebra.infer(List.of(sample1));
        assertTrue(schema1.records().containsKey("Abc"));

        dev.omnist.document.Node sample2 = new dev.omnist.document.Node(List.of(
            new dev.omnist.document.Edge("123", child)
        ));
        Schema schema2 = SchemaAlgebra.infer(List.of(sample2));
        assertTrue(schema2.records().keySet().stream().anyMatch(k -> k.contains("123")));
    }

    @Test
    @DisplayName("normalize: Ref-typed field local signature groups correctly")
    void testNormalizeWithRefTypedField() {
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Leaf", new dev.omnist.schema.Record("Leaf", List.of(
            new Field("v", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        )));
        records.put("A", new dev.omnist.schema.Record("A", List.of(
            new Field("leaf", new Type.Ref("Leaf"), 1, 1)
        )));
        records.put("B", new dev.omnist.schema.Record("B", List.of(
            new Field("leaf", new Type.Ref("Leaf"), 1, 1)
        )));
        Schema schema = new Schema("A", records);
        Schema normalized = SchemaAlgebra.normalize(schema);
        assertNotNull(normalized);
    }

    @Test
    @DisplayName("compatibleWith: scalar subtyping, record field comparison, cardinality bound mismatches")
    void testCompatibleWithScalarAndRecordEdgeCases() {
        Map<String, dev.omnist.schema.Record> ra = new LinkedHashMap<>();
        ra.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, true), 1, 1)
        )));
        Schema a1 = new Schema("R", ra);
        Map<String, dev.omnist.schema.Record> rb = new LinkedHashMap<>();
        rb.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        )));
        Schema b1 = new Schema("R", rb);
        assertFalse(SchemaAlgebra.compatibleWith(a1, b1));

        Map<String, dev.omnist.schema.Record> ra2 = new LinkedHashMap<>();
        ra2.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 0, 0)
        )));
        Schema a2 = new Schema("R", ra2);
        Map<String, dev.omnist.schema.Record> rb2 = new LinkedHashMap<>();
        rb2.put("R", new dev.omnist.schema.Record("R", List.of()));
        Schema b2 = new Schema("R", rb2);
        assertTrue(SchemaAlgebra.compatibleWith(a2, b2));

        Map<String, dev.omnist.schema.Record> ra3 = new LinkedHashMap<>();
        ra3.put("R", new dev.omnist.schema.Record("R", List.of()));
        Schema a3 = new Schema("R", ra3);
        Map<String, dev.omnist.schema.Record> rb3 = new LinkedHashMap<>();
        rb3.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("y", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        )));
        Schema b3 = new Schema("R", rb3);
        assertFalse(SchemaAlgebra.compatibleWith(a3, b3));

        Map<String, dev.omnist.schema.Record> ra4 = new LinkedHashMap<>();
        ra4.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 0, null)
        )));
        Schema a4 = new Schema("R", ra4);
        Map<String, dev.omnist.schema.Record> rb4 = new LinkedHashMap<>();
        rb4.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 0, 5)
        )));
        Schema b4 = new Schema("R", rb4);
        assertFalse(SchemaAlgebra.compatibleWith(a4, b4));

        Map<String, dev.omnist.schema.Record> ra5 = new LinkedHashMap<>();
        ra5.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)
        )));
        Schema a5 = new Schema("R", ra5);
        Map<String, dev.omnist.schema.Record> rb5 = new LinkedHashMap<>();
        rb5.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1)
        )));
        Schema b5 = new Schema("R", rb5);
        assertTrue(SchemaAlgebra.compatibleWith(a5, b5));
    }

    @Test
    @DisplayName("sub: required field's Ref type to an unsatisfiable record is vacuously compatible; " +
                 "le: A's bounded max against B's unbounded (null) max is compatible")
    void testSubVacuousUnsatisfiableRefAndUnboundedMaxB() {
        // Verified empirically: compatibleWith(a, b) below returns true because Root.x is a
        // *required* (min=1) field typed as a Ref to "Unsat", a record that can never be
        // satisfied (its own field is a mandatory self-cycle). Per sub()'s first check
        // (ta instanceof Type.Ref && !satA.contains(ref.name())), A can never actually emit
        // a document containing this field at all, so the comparison is vacuously true --
        // this is distinct from the existing recordSub pre-filter, which only skips *optional*
        // (min == 0) unsatisfiable-ref fields.
        Map<String, dev.omnist.schema.Record> ra = new LinkedHashMap<>();
        ra.put("Root", new dev.omnist.schema.Record("Root", List.of(
            new Field("x", new Type.Ref("Unsat"), 1, 1)
        )));
        ra.put("Unsat", new dev.omnist.schema.Record("Unsat", List.of(
            new Field("self", new Type.Ref("Unsat"), 1, 1)
        )));
        Schema a = new Schema("Root", ra);
        Map<String, dev.omnist.schema.Record> rb = new LinkedHashMap<>();
        rb.put("Root", new dev.omnist.schema.Record("Root", List.of()));
        Schema b = new Schema("Root", rb);
        assertTrue(SchemaAlgebra.compatibleWith(a, b));

        // Verified empirically: compatibleWith(a2, b2) below returns true. A's field "x" has a
        // bounded max (3) and B's has an unbounded max (null), so cardinalitySub delegates to
        // le(maxA=3, maxB=null), which hits the y == null -> return true branch (B's cardinality
        // is unbounded, so any bounded A max satisfies it).
        Map<String, dev.omnist.schema.Record> ra2 = new LinkedHashMap<>();
        ra2.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 0, 3)
        )));
        Schema a2 = new Schema("R", ra2);
        Map<String, dev.omnist.schema.Record> rb2 = new LinkedHashMap<>();
        rb2.put("R", new dev.omnist.schema.Record("R", List.of(
            new Field("x", new Type.Scalar(ScalarKind.STRING, false), 0, null)
        )));
        Schema b2 = new Schema("R", rb2);
        assertTrue(SchemaAlgebra.compatibleWith(a2, b2));
    }

    @Test
    @DisplayName("prune: optional ref to an unsatisfiable record is dropped; cyclic reachability dedupes")
    void testPruneOptionalRefToUnsatisfiableAndCycles() {
        Map<String, dev.omnist.schema.Record> records = new LinkedHashMap<>();
        records.put("Root", new dev.omnist.schema.Record("Root", List.of(
            new Field("self", new Type.Ref("Root"), 0, 1),
            new Field("dead", new Type.Ref("Unsat"), 0, 1)
        )));
        records.put("Unsat", new dev.omnist.schema.Record("Unsat", List.of(
            new Field("mustSelf", new Type.Ref("Unsat"), 1, 1)
        )));
        Schema schema = new Schema("Root", records);
        Schema pruned = SchemaAlgebra.prune(schema);
        assertFalse(pruned.records().get("Root").fields().stream().anyMatch(f -> f.label().equals("dead")));
    }
}

