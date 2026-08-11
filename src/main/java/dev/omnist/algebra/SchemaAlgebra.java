package dev.omnist.algebra;

import dev.omnist.schema.*;
import dev.omnist.schema.Record;

import java.util.*;

/**
 * Normative Schema Algebra operations (§6 of 06-schema-algebra.md).
 */
public final class SchemaAlgebra {
    private SchemaAlgebra() {}

    /**
     * Computes the set of record names in schema S that admit at least one finite document (§6.4).
     * Implements a least fixpoint computation.
     */
    public static Set<String> satisfiableSet(Schema schema) {
        Set<String> sat = new HashSet<>();
        boolean changed = true;

        while (changed) {
            changed = false;
            for (Map.Entry<String, Record> entry : schema.records().entrySet()) {
                String name = entry.getKey();
                if (sat.contains(name)) {
                    continue;
                }
                if (recordSatisfiable(entry.getValue(), sat)) {
                    sat.add(name);
                    changed = true;
                }
            }
        }

        return Set.copyOf(sat);
    }

    /**
     * Checks if a record can be satisfied given the current set of known-satisfiable record names (§6.4).
     */
    private static boolean recordSatisfiable(Record rec, Set<String> sat) {
        for (Field f : rec.fields()) {
            if (f.min() < 1) {
                continue; // Optional field (min == 0) never blocks satisfiability
            }
            if (f.type() instanceof Type.Scalar || f.type() instanceof Type.Any) {
                continue; // Scalars and Any are always satisfiable
            }
            if (f.type() instanceof Type.Ref ref) {
                if (!sat.contains(ref.name())) {
                    return false; // Blocked by an unsatisfied required reference target
                }
            }
        }
        return true;
    }

    /**
     * Checks if a Schema accepts zero finite documents (§6.4).
     * Returns true if the root record name is not in the satisfiable set.
     */
    public static boolean isEmpty(Schema schema) {
        return !satisfiableSet(schema).contains(schema.root());
    }

    /**
     * Prunes a Schema into an equivalent schema with unreachable records and impossible fields removed (§6.5).
     * Preserves language equivalence (S and prune(S) accept identical Documents).
     */
    public static Schema prune(Schema schema) {
        Set<String> sat = satisfiableSet(schema);
        boolean rootOk = sat.contains(schema.root());
        Set<String> keep = reachable(schema, sat, rootOk);

        Map<String, Record> newEnv = new LinkedHashMap<>();
        for (Map.Entry<String, Record> entry : schema.records().entrySet()) {
            String name = entry.getKey();
            if (!keep.contains(name)) {
                continue;
            }
            if (!rootOk && name.equals(schema.root())) {
                // Normative §6.5: Root-unsatisfiable special case! Keep root fields intact as written.
                newEnv.put(name, entry.getValue());
            } else {
                newEnv.put(name, pruneRecord(entry.getValue(), sat));
            }
        }

        return new Schema(schema.root(), newEnv);
    }

    /**
     * Checks if every Document accepted by Schema A is also accepted by Schema B (§6.6).
     * Implements coinductive memoized subschema comparison.
     */
    public static boolean compatibleWith(Schema a, Schema b) {
        Set<String> satA = satisfiableSet(a);
        Map<DefPair, Boolean> memo = new HashMap<>();
        return sub(a, new Type.Ref(a.root()), b, new Type.Ref(b.root()), satA, memo);
    }

    /**
     * Checks if two Schemas accept the exact same set of Documents (§6.7).
     * Evaluates compatibleWith(A, B) && compatibleWith(B, A).
     */
    public static boolean equivalent(Schema a, Schema b) {
        return compatibleWith(a, b) && compatibleWith(b, a);
    }

    /**
     * Group record names of schema into structural equivalence classes using partition refinement (§6.8).
     * Operates directly on S.records().
     */
    public static List<List<String>> equivalenceClasses(Schema schema) {
        List<String> names = new ArrayList<>(schema.records().keySet());
        Collections.sort(names);

        // Initial partition by target-blind local_signature
        Map<LocalSigKey, List<String>> initialMap = new LinkedHashMap<>();
        for (String name : names) {
            Record rec = schema.records().get(name);
            LocalSigKey sig = localSignature(rec);
            initialMap.computeIfAbsent(sig, k -> new ArrayList<>()).add(name);
        }

        List<List<String>> blocks = new ArrayList<>(initialMap.values());

        // Map name to block index
        Map<String, Integer> blockOf = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            for (String n : blocks.get(i)) {
                blockOf.put(n, i);
            }
        }

        // Refinement loop
        while (true) {
            List<List<String>> newBlocks = new ArrayList<>();
            for (List<String> block : blocks) {
                Map<RefineKey, List<String>> subMap = new LinkedHashMap<>();
                for (String name : block) {
                    Record rec = schema.records().get(name);
                    RefineKey key = refineKey(rec, blockOf);
                    subMap.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
                }
                newBlocks.addAll(subMap.values());
            }

            if (newBlocks.size() == blocks.size()) {
                break;
            }

            blocks = newBlocks;
            blockOf.clear();
            for (int i = 0; i < blocks.size(); i++) {
                for (String n : blocks.get(i)) {
                    blockOf.put(n, i);
                }
            }
        }

        return blocks;
    }

    /**
     * normalize(S) - canonical minimal schema equivalent to S (§6.8).
     */
    public static Schema normalize(Schema schema) {
        Schema pruned = prune(schema);
        if (isEmpty(pruned)) {
            return pruned;
        }

        List<List<String>> blocks = equivalenceClasses(pruned);

        Map<String, String> rep = new HashMap<>();
        for (List<String> block : blocks) {
            String keep = Collections.min(block);
            for (String n : block) {
                rep.put(n, keep);
            }
        }

        List<String> sortedNames = new ArrayList<>(pruned.records().keySet());
        Collections.sort(sortedNames);

        Map<String, Record> newEnv = new LinkedHashMap<>();
        for (String name : sortedNames) {
            if (rep.get(name).equals(name)) {
                Record rec = pruned.records().get(name);
                newEnv.put(name, remap(rec, rep));
            }
        }

        String newRoot = rep.get(pruned.root());
        return new Schema(newRoot, newEnv);
    }

    private static Record remap(Record record, Map<String, String> rep) {
        List<Field> newFields = new ArrayList<>();
        for (Field f : record.fields()) {
            Type newType = f.type();
            if (f.type() instanceof Type.Ref ref) {
                newType = new Type.Ref(rep.get(ref.name()));
            }
            newFields.add(new Field(f.label(), newType, f.min(), f.max()));
        }
        return new Record(record.name(), newFields);
    }

    private record FieldSigKey(String label, int min, Integer max, Object shapeKey) {}

    private record LocalSigKey(List<FieldSigKey> fields) {}

    private static LocalSigKey localSignature(Record rec) {
        List<FieldSigKey> fields = new ArrayList<>();
        for (Field f : rec.fields()) {
            Object shapeKey;
            if (f.type() instanceof Type.Any) {
                shapeKey = "any";
            } else if (f.type() instanceof Type.Ref) {
                shapeKey = "ref";
            } else if (f.type() instanceof Type.Scalar sc) {
                shapeKey = List.of("scalar", sc.kind().keyword(), sc.nullable());
            } else {
                shapeKey = f.type().toString();
            }
            fields.add(new FieldSigKey(f.label(), f.min(), f.max(), shapeKey));
        }
        fields.sort(Comparator.comparing(FieldSigKey::label));
        return new LocalSigKey(fields);
    }

    private record RefineKey(LocalSigKey localSig, List<Object> refBlockIndices) {}

    private static RefineKey refineKey(Record rec, Map<String, Integer> blockOf) {
        LocalSigKey localSig = localSignature(rec);
        List<Field> sortedFields = new ArrayList<>(rec.fields());
        sortedFields.sort(Comparator.comparing(Field::label));

        List<Object> refBlockIndices = new ArrayList<>();
        for (Field f : sortedFields) {
            if (f.type() instanceof Type.Ref ref) {
                refBlockIndices.add(blockOf.get(ref.name()));
            } else {
                refBlockIndices.add(null);
            }
        }
        return new RefineKey(localSig, refBlockIndices);
    }

    private static boolean sub(Schema sa, Type ta, Schema sb, Type tb, Set<String> satA, Map<DefPair, Boolean> memo) {
        if (ta instanceof Type.Ref ref && !satA.contains(ref.name())) {
            return true; // Vacuously compatible: A emits no documents at this branch (§6.6)
        }

        Object da = resolveDefinition(sa, ta);
        Object db = resolveDefinition(sb, tb);

        DefPair key = new DefPair(da, db);
        if (memo.containsKey(key)) {
            return memo.get(key); // Coinductive hypothesis return (§6.6)
        }
        memo.put(key, true); // Coinductive assumption (§6.6)

        boolean result;
        if (db instanceof Type.Any) {
            result = true; // Any absorbs all inputs on RHS
        } else if (da instanceof Type.Any) {
            result = false; // Only Any on RHS can hold Any on LHS
        } else if (da instanceof Type.Scalar scalarA && db instanceof Type.Scalar scalarB) {
            result = scalarSub(scalarA, scalarB);
        } else if (da instanceof Record recA && db instanceof Record recB) {
            result = recordSub(sa, recA, sb, recB, satA, memo);
        } else {
            result = false; // Value vs object mismatch
        }

        memo.put(key, result);
        return result;
    }

    private static Object resolveDefinition(Schema schema, Type type) {
        if (type instanceof Type.Ref ref) {
            return schema.records().get(ref.name());
        }
        return type;
    }

    private static boolean scalarSub(Type.Scalar a, Type.Scalar b) {
        if (a.nullable() && !b.nullable()) {
            return false;
        }
        if (a.kind() == b.kind()) {
            return true;
        }
        // Subtyping §6.3: INTEGER is subtype of NUMBER
        return a.kind() == ScalarKind.INTEGER && b.kind() == ScalarKind.NUMBER;
    }

    private static boolean recordSub(Schema sa, Record a, Schema sb, Record b, Set<String> satA, Map<DefPair, Boolean> memo) {
        // 1. Every label A may emit must be allowed by B.
        for (Field fa : a.fields()) {
            if (fa.max() != null && fa.max() == 0) {
                continue; // A never emits it (§6.6)
            }
            if (fa.min() == 0 && fa.type() instanceof Type.Ref ref && !satA.contains(ref.name())) {
                continue; // A never emits it either (pre-filter §6.6)
            }
            Field fb = b.field(fa.label());
            if (fb == null) {
                return false; // B is closed (§6.6)
            }
            if (!cardinalitySub(fa.min(), fa.max(), fb.min(), fb.max())) {
                return false;
            }
            if (!sub(sa, fa.type(), sb, fb.type(), satA, memo)) {
                return false;
            }
        }

        // 2. Every label B requires must be guaranteed by A.
        for (Field fb : b.fields()) {
            if (fb.min() >= 1) {
                Field fa = a.field(fb.label());
                if (fa == null || fa.min() < fb.min()) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean cardinalitySub(int minA, Integer maxA, int minB, Integer maxB) {
        if (minB > minA) {
            return false;
        }
        return le(maxA, maxB);
    }

    private static boolean le(Integer x, Integer y) {
        if (y == null) {
            return true; // y is unbounded (+infinity)
        }
        if (x == null) {
            return false; // x is unbounded, y is bounded
        }
        return x <= y;
    }

    private static Record pruneRecord(Record rec, Set<String> sat) {
        List<Field> kept = new ArrayList<>();
        for (Field f : rec.fields()) {
            if (f.max() != null && f.max() == 0) {
                continue; // max == 0 can never be emitted
            }
            if (f.min() == 0 && f.type() instanceof Type.Ref ref && !sat.contains(ref.name())) {
                continue; // Optional field typed to an unsatisfiable record can never be emitted
            }
            kept.add(f);
        }
        return new Record(rec.name(), List.copyOf(kept));
    }

    private static Set<String> reachable(Schema schema, Set<String> sat, boolean rootOk) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(schema.root());

        while (!stack.isEmpty()) {
            String name = stack.pop();
            if (seen.contains(name) || !schema.records().containsKey(name)) {
                continue;
            }
            seen.add(name);
            Record rec = schema.records().get(name);

            List<Field> fieldsToFollow;
            if (!rootOk && name.equals(schema.root())) {
                fieldsToFollow = rec.fields();
            } else {
                fieldsToFollow = pruneRecord(rec, sat).fields();
            }

            for (Field f : fieldsToFollow) {
                if (f.type() instanceof Type.Ref ref) {
                    stack.push(ref.name());
                }
            }
        }

        return seen;
    }

    private record DefPair(Object da, Object db) {}
}
