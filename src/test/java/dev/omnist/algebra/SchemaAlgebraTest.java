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
}

