package dev.omnist.algebra;

import dev.omnist.schema.*;
import dev.omnist.schema.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaAlgebraTest {

    @Test
    @DisplayName("satisfiableSet correctly identifies satisfiable vs unsatisfiable record cycles (§6.4)")
    void testSatisfiableSet() {
        // UnsatNode: mandatory self-reference -> unsatisfiable
        Record unsatNode = new Record("UnsatNode", List.of(
                new Field("child", new Type.Ref("UnsatNode"), 1, 1)
        ));

        // OptNode: optional self-reference -> satisfiable
        Record optNode = new Record("OptNode", List.of(
                new Field("child", new Type.Ref("OptNode"), 0, 1)
        ));

        // Child & Parent: valid scalar & reference -> satisfiable
        Record child = new Record("Child", List.of(
                new Field("val", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Record parent = new Record("Parent", List.of(
                new Field("child", new Type.Ref("Child"), 1, 1)
        ));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("UnsatNode", unsatNode);
        records.put("OptNode", optNode);
        records.put("Child", child);
        records.put("Parent", parent);

        Schema schema = new Schema("Parent", records);

        Set<String> sat = SchemaAlgebra.satisfiableSet(schema);

        assertFalse(sat.contains("UnsatNode"), "Required self-referential cycle must not be in satisfiableSet");
        assertTrue(sat.contains("OptNode"), "Optional self-referential cycle must be in satisfiableSet");
        assertTrue(sat.contains("Child"), "Scalar record must be in satisfiableSet");
        assertTrue(sat.contains("Parent"), "Valid parent record must be in satisfiableSet");
    }

    @Test
    @DisplayName("isEmpty correctly detects empty schemas with unsatisfiable root (§6.4)")
    void testIsEmpty() {
        Record unsatRoot = new Record("UnsatRoot", List.of(
                new Field("child", new Type.Ref("UnsatRoot"), 1, 1)
        ));
        Schema unsatSchema = new Schema("UnsatRoot", Map.of("UnsatRoot", unsatRoot));
        assertTrue(SchemaAlgebra.isEmpty(unsatSchema), "Schema with unsatisfiable root must be empty");

        Record satRoot = new Record("SatRoot", List.of(
                new Field("val", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)
        ));
        Schema satSchema = new Schema("SatRoot", Map.of("SatRoot", satRoot));
        assertFalse(SchemaAlgebra.isEmpty(satSchema), "Schema with satisfiable root must not be empty");
    }

    @Test
    @DisplayName("prune removes max=0 fields, optional unsatisfiable Ref fields, and unreachable records (§6.5)")
    void testPruneHappyPath() {
        Record badRec = new Record("BadRec", List.of(
                new Field("loop", new Type.Ref("BadRec"), 1, 1)
        ));

        Record unreachable = new Record("Unreachable", List.of(
                new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));

        Record root = new Record("Root", List.of(
                new Field("f1", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("f2", new Type.Scalar(ScalarKind.STRING, false), 0, 0),             // max == 0 -> prune
                new Field("f3", new Type.Ref("BadRec"), 0, 1)                                   // min==0 & BadRec unsat -> prune
        ));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("Root", root);
        records.put("BadRec", badRec);
        records.put("Unreachable", unreachable);

        Schema schema = new Schema("Root", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        assertEquals(Set.of("Root"), pruned.records().keySet(), "Unreachable & unsat-only records must be pruned");

        Record prunedRoot = pruned.records().get("Root");
        assertEquals(1, prunedRoot.fields().size());
        assertEquals("f1", prunedRoot.fields().get(0).label());
    }

    @Test
    @DisplayName("prune normative root-unsatisfiable special case (§6.5): root fields preserved intact")
    void testPruneRootUnsatisfiableSpecialCase() {
        Record child = new Record("Child", List.of(
                new Field("val", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));

        Record unsatRoot = new Record("UnsatRoot", List.of(
                new Field("loop", new Type.Ref("UnsatRoot"), 1, 1),                           // Mandatory unsat field
                new Field("dead", new Type.Scalar(ScalarKind.STRING, false), 0, 0),            // max == 0 field
                new Field("ref", new Type.Ref("Child"), 1, 1)                                  // Ref to Child
        ));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("UnsatRoot", unsatRoot);
        records.put("Child", child);

        Schema schema = new Schema("UnsatRoot", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        // Root fields must be kept intact (§6.5)
        Record prunedRoot = pruned.records().get("UnsatRoot");
        assertEquals(3, prunedRoot.fields().size(), "Root-unsatisfiable fields must NOT be pruned");
        assertEquals(unsatRoot.fields(), prunedRoot.fields());

        // Child must be kept because reachable from root
        assertTrue(pruned.records().containsKey("Child"));
    }

    @Test
    @DisplayName("prune retains original declaration order in output environment map (§6.5)")
    void testPruneDeclarationOrderDeterminism() {
        Record c = new Record("C", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Record b = new Record("B", List.of(new Field("c", new Type.Ref("C"), 1, 1)));
        Record a = new Record("A", List.of(new Field("b", new Type.Ref("B"), 1, 1)));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("A", a);
        records.put("B", b);
        records.put("C", c);

        Schema schema = new Schema("A", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        List<String> keyOrder = List.copyOf(pruned.records().keySet());
        assertEquals(List.of("A", "B", "C"), keyOrder, "Output map must preserve declaration order");
    }

    @Test
    @DisplayName("prune retains declaration order across a large 12-record schema (regression check against Map.copyOf nondeterminism)")
    void testPruneDeclarationOrderDeterminismLarge() {
        Map<String, Record> records = new LinkedHashMap<>();
        List<String> expectedNames = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            String name = "Rec_" + (char) ('A' + i); // Rec_A, Rec_B, ..., Rec_L
            expectedNames.add(name);
        }

        for (int i = 0; i < 12; i++) {
            String currentName = expectedNames.get(i);
            List<Field> fields = new ArrayList<>();
            fields.add(new Field("val", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1));
            if (i + 1 < 12) {
                fields.add(new Field("next", new Type.Ref(expectedNames.get(i + 1)), 1, 1));
            }
            records.put(currentName, new Record(currentName, fields));
        }

        Schema schema = new Schema("Rec_A", records);
        Schema pruned = SchemaAlgebra.prune(schema);

        List<String> actualOrder = List.copyOf(pruned.records().keySet());
        assertEquals(expectedNames, actualOrder, "Large schema must strictly preserve 12-record declaration order");
    }

    @Test
    @DisplayName("compatibleWith worked example (§6.6): adding optional field makes B -> A compatible, A -> B incompatible")
    void testCompatibleWithWorkedExample() {
        Record userA = new Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("name", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("nick", new Type.Scalar(ScalarKind.STRING, false), 0, 1)
        ));
        Schema schemaA = new Schema("User", Map.of("User", userA));

        Record userB = new Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("name", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Schema schemaB = new Schema("User", Map.of("User", userB));

        assertFalse(SchemaAlgebra.compatibleWith(schemaA, schemaB), "A may emit nick, B is closed -> incompatible");
        assertTrue(SchemaAlgebra.compatibleWith(schemaB, schemaA), "Everything B emits, A accepts -> compatible");
    }

    @Test
    @DisplayName("compatibleWith pre-filters unsatisfiable optional Ref fields in A (§6.6)")
    void testCompatibleWithUnsatisfiableRefPrefilter() {
        Record badRec = new Record("BadRec", List.of(
                new Field("loop", new Type.Ref("BadRec"), 1, 1)
        ));
        Record userA = new Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("opt_bad", new Type.Ref("BadRec"), 0, 1)
        ));
        Map<String, Record> mapA = new LinkedHashMap<>();
        mapA.put("User", userA);
        mapA.put("BadRec", badRec);
        Schema schemaA = new Schema("User", mapA);

        Record userB = new Record("User", List.of(
                new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1)
        ));
        Schema schemaB = new Schema("User", Map.of("User", userB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB), "A can never emit opt_bad -> compatible with B");
    }

    @Test
    @DisplayName("compatibleWith enforces scalar subtyping (§6.3): integer <: number")
    void testCompatibleWithScalarSubtyping() {
        Record rA = new Record("R", List.of(new Field("f", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)));
        Schema schemaA = new Schema("R", Map.of("R", rA));

        Record rB = new Record("R", List.of(new Field("f", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1)));
        Schema schemaB = new Schema("R", Map.of("R", rB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB), "INTEGER in A satisfies NUMBER in B");
        assertFalse(SchemaAlgebra.compatibleWith(schemaB, schemaA), "NUMBER in A cannot satisfy INTEGER in B");
    }

    @Test
    @DisplayName("compatibleWith handles self-referential coinductive cycles without stack overflow (§6.6)")
    void testCompatibleWithCoinduction() {
        Record nodeA = new Record("Node", List.of(new Field("child", new Type.Ref("Node"), 0, 1)));
        Schema schemaA = new Schema("Node", Map.of("Node", nodeA));

        Record nodeB = new Record("Node", List.of(new Field("child", new Type.Ref("Node"), 0, 1)));
        Schema schemaB = new Schema("Node", Map.of("Node", nodeB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB));
        assertTrue(SchemaAlgebra.equivalent(schemaA, schemaB));
    }

    @Test
    @DisplayName("equivalent evaluates language equivalence regardless of record names (§6.7)")
    void testEquivalentDifferentRecordNames() {
        Record recA = new Record("UserA", List.of(new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schemaA = new Schema("UserA", Map.of("UserA", recA));

        Record recB = new Record("UserB", List.of(new Field("id", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schemaB = new Schema("UserB", Map.of("UserB", recB));

        assertTrue(SchemaAlgebra.equivalent(schemaA, schemaB), "Differently named records with identical language are equivalent");
    }

    @Test
    @DisplayName("compatibleWith Type.Any behavior (§6.6): RHS Any absorbs all, LHS Any requires RHS Any")
    void testCompatibleWithAny() {
        Record rA = new Record("R", List.of(new Field("f", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schemaA = new Schema("R", Map.of("R", rA));

        Record rB = new Record("R", List.of(new Field("f", Type.Any.INSTANCE, 1, 1)));
        Schema schemaB = new Schema("R", Map.of("R", rB));

        assertTrue(SchemaAlgebra.compatibleWith(schemaA, schemaB), "RHS Any absorbs String");
        assertFalse(SchemaAlgebra.compatibleWith(schemaB, schemaA), "LHS Any requires RHS Any");
    }
}
