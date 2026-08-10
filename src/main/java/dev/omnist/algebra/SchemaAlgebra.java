package dev.omnist.algebra;

import dev.omnist.schema.*;
import dev.omnist.schema.Record;

import java.util.*;

/**
 * Omnist Schema Algebra Operations (§6.4, §6.5).
 */
public class SchemaAlgebra {

    /**
     * Computes the set of record names that admit at least one finite Document (§6.4).
     * Implements a least fixpoint over the record environment.
     */
    public static Set<String> satisfiableSet(Schema schema) {
        Set<String> sat = new LinkedHashSet<>();
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
}
