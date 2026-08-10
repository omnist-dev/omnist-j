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
}
