package dev.omnist.algebra;

import dev.omnist.schema.*;
import dev.omnist.schema.Record;
import dev.omnist.document.Document;
import dev.omnist.document.Node;
import dev.omnist.document.Edge;
import dev.omnist.document.Value;
import dev.omnist.document.Scalar;
import dev.omnist.document.Target;

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
            // Type is sealed to exactly {Scalar, Ref, Any}; both other cases are
            // excluded above, so f.type() is exhaustively a Ref here.
            Type.Ref ref = (Type.Ref) f.type();
            if (!sat.contains(ref.name())) {
                return false; // Blocked by an unsatisfied required reference target
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
     * Evaluates {@code compatibleWith(A, B) && compatibleWith(B, A)}.
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

    /**
     * extract(S, keep) - returns the minimal subschema that recognizes only Documents built from keep labels (§6.9).
     */
    public static Schema extract(Schema schema, Set<String> keep) {
        Map<String, Record> trimmed = new LinkedHashMap<>();
        Set<String> invalidated = new LinkedHashSet<>();
        String firstBadLabel = null;
        String firstBadRecord = null;

        for (Map.Entry<String, Record> entry : schema.records().entrySet()) {
            String name = entry.getKey();
            Record rec = entry.getValue();
            List<Field> kept = new ArrayList<>();
            for (Field f : rec.fields()) {
                if (keep.contains(f.label())) {
                    kept.add(f);
                } else if (f.min() >= 1) {
                    if (firstBadLabel == null) {
                        firstBadLabel = f.label();
                        firstBadRecord = name;
                    }
                    invalidated.add(name);
                }
            }
            trimmed.put(name, new Record(name, kept));
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Record> entry : trimmed.entrySet()) {
                String name = entry.getKey();
                if (invalidated.contains(name)) {
                    continue;
                }
                Record rec = entry.getValue();
                for (Field f : rec.fields()) {
                    if (f.min() >= 1 && f.type() instanceof Type.Ref ref && invalidated.contains(ref.name())) {
                        invalidated.add(name);
                        changed = true;
                        break;
                    }
                }
            }
        }

        if (invalidated.contains(schema.root())) {
            throw new IllegalArgumentException("removing label " + firstBadLabel + " deletes a mandatory field of " + firstBadRecord);
        }

        Map<String, Record> newEnv = new LinkedHashMap<>();
        for (Map.Entry<String, Record> entry : trimmed.entrySet()) {
            String name = entry.getKey();
            if (invalidated.contains(name)) {
                continue;
            }
            Record rec = entry.getValue();
            List<Field> fields = new ArrayList<>();
            for (Field f : rec.fields()) {
                if (f.type() instanceof Type.Ref ref && invalidated.contains(ref.name())) {
                    continue;
                }
                fields.add(f);
            }
            newEnv.put(name, new Record(name, fields));
        }

        return normalize(prune(new Schema(schema.root(), newEnv)));
    }

    /**
     * lint(S) - diagnoses structural schema issues that compile fine but mean parts of the schema can never do anything (§6.11).
     */
    public static List<LintFinding> lint(Schema schema) {
        List<LintFinding> findings = new ArrayList<>();
        Set<String> reach = reachablePlain(schema);
        Set<String> sat = satisfiableSet(schema);

        for (String name : reach) {
            if (!sat.contains(name)) {
                findings.add(new LintFinding(
                    "lint.unsatisfiable-record", "warning", name,
                    "record '" + name + "' is reachable but unsatisfiable -- no finite document can match it (e.g. a mandatory ref cycle)"
                ));
            }
        }

        for (String name : schema.records().keySet()) {
            if (!reach.contains(name)) {
                findings.add(new LintFinding(
                    "lint.unreachable-record", "warning", name,
                    "record '" + name + "' is defined but never reachable from the root; drop it with `schema prune`"
                ));
            }
        }

        List<List<String>> blocks = equivalenceClasses(schema);
        for (List<String> block : blocks) {
            if (block.size() > 1) {
                List<String> group = new ArrayList<>(block);
                Collections.sort(group);
                String location = String.join(", ", group);
                String keep = group.get(0);
                
                List<String> otherQuotes = new ArrayList<>();
                for (int i = 1; i < group.size(); i++) {
                    otherQuotes.add("'" + group.get(i) + "'");
                }
                String others = String.join(", ", otherQuotes);
                
                findings.add(new LintFinding(
                    "lint.duplicate-record", "warning", location,
                    "records " + others + " are structurally identical to '" + keep + "'; merge them with `schema normalize`"
                ));
            }
        }

        for (Map.Entry<String, Record> entry : schema.records().entrySet()) {
            String name = entry.getKey();
            Record rec = entry.getValue();
            for (Field f : rec.fields()) {
                if (f.type() instanceof Type.Any) {
                    findings.add(new LintFinding(
                        "lint.any-field", "info", name + "." + f.label(),
                        "field '" + f.label() + "' of record '" + name + "' is typed `any` (accepts any value unchecked)"
                    ));
                }
            }
        }

        Collections.sort(findings);
        return findings;
    }

    private static Set<String> reachablePlain(Schema schema) {
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(schema.root());
        while (!stack.isEmpty()) {
            String name = stack.pop();
            if (seen.contains(name) || !schema.records().containsKey(name)) {
                // JaCoCo artifact: manually traced this exact algorithm against a
                // self-referencing schema and confirmed the continue is reached
                // (seenContains=true on the second pop); the enclosing if's own
                // compound-branch coverage also shows the true path taken. The
                // bare continue's GOTO bytecode just doesn't register a hit.
                continue;
            }
            seen.add(name);
            Record rec = schema.records().get(name);
            for (Field f : rec.fields()) {
                if (f.type() instanceof Type.Ref ref) {
                    stack.push(ref.name());
                }
            }
        }
        return seen;
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

    public static Schema infer(List<Document> samples) {
        return infer(samples, "Root", false);
    }

    public static Schema infer(List<Document> samples, String rootName, boolean allowAny) {
        return inferWithReport(samples, rootName, allowAny).schema();
    }

    public static InferResult inferWithReport(List<Document> samples, String rootName, boolean allowAny) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("cannot infer a schema from zero samples");
        }
        List<Node> nodes = new ArrayList<>();
        for (Document d : samples) {
            if (!(d instanceof Node n)) {
                throw new IllegalArgumentException("infer expects object (record) samples at the root");
            }
            nodes.add(n);
        }

        Map<String, Record> env = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        List<AnyFallback> fallbacks = new ArrayList<>();

        inferRecord(nodes, rootName, env, used, allowAny, fallbacks, 0);

        return new InferResult(new Schema(rootName, env), List.copyOf(fallbacks));
    }

    private static void inferRecord(List<Node> nodes, String name, Map<String, Record> env,
                                    Set<String> used, boolean allowAny, List<AnyFallback> fallbacks, int depth) {
        if (depth > 100) {
            throw new IllegalArgumentException("nesting exceeds the maximum depth (100)");
        }
        used.add(name);

        List<String> order = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();
        for (Node node : nodes) {
            for (Edge edge : node.edges()) {
                String label = edge.label();
                if (!seenLabels.contains(label)) {
                    seenLabels.add(label);
                    order.add(label);
                }
            }
        }

        Map<String, List<Target>> children = new LinkedHashMap<>();
        Map<String, List<Integer>> perSampleCounts = new LinkedHashMap<>();
        for (String label : order) {
            children.put(label, new ArrayList<>());
            perSampleCounts.put(label, new ArrayList<>());
        }

        for (Node node : nodes) {
            Map<String, Integer> countsHere = new HashMap<>();
            for (Edge edge : node.edges()) {
                children.get(edge.label()).add(edge.target());
                countsHere.put(edge.label(), countsHere.getOrDefault(edge.label(), 0) + 1);
            }
            for (String label : order) {
                perSampleCounts.get(label).add(countsHere.getOrDefault(label, 0));
            }
        }

        List<Field> fields = new ArrayList<>();
        for (String label : order) {
            List<Integer> counts = perSampleCounts.get(label);
            int maxVal = Collections.max(counts);
            int minVal = Collections.min(counts);
            int cmin;
            Integer cmax;
            if (maxVal > 1) {
                cmin = 0;
                cmax = null;
            } else {
                cmin = minVal;
                cmax = 1;
            }

            Type typ = inferType(children.get(label), label, name, env, used, allowAny, fallbacks, depth);
            fields.add(new Field(label, typ, cmin, cmax));
        }

        env.put(name, new Record(name, fields));
    }

    private static Type inferType(List<Target> childValues, String label, String recordName,
                                  Map<String, Record> env, Set<String> used, boolean allowAny,
                                  List<AnyFallback> fallbacks, int depth) {
        boolean allNodes = true;
        boolean someNodes = false;
        for (Target v : childValues) {
            if (v instanceof Node) {
                someNodes = true;
            } else {
                allNodes = false;
            }
        }

        if (allNodes) {
            List<Node> childNodes = new ArrayList<>();
            for (Target v : childValues) {
                childNodes.add((Node) v);
            }
            String recName = unique(label, used);
            inferRecord(childNodes, recName, env, used, allowAny, fallbacks, depth + 1);
            return new Type.Ref(recName);
        }

        if (someNodes) {
            if (allowAny) {
                fallbacks.add(new AnyFallback(recordName + "." + label, "mixes objects and values"));
                return Type.Any.INSTANCE;
            }
            throw new IllegalArgumentException(recordName + "." + label + ": mixes objects and values; cannot infer one type");
        }

        Set<ScalarKind> kinds = new LinkedHashSet<>();
        boolean nullSeen = false;
        for (Target t : childValues) {
            // someNodes is false at this point (checked above), so every t is
            // exhaustively a Value; Value is sealed to {Scalar, NullValue}, so
            // after excluding NullValue, t is exhaustively a Scalar.
            Value v = (Value) t;
            if (v instanceof Value.NullValue) {
                nullSeen = true;
            } else {
                Scalar s = (Scalar) v;
                kinds.add(mapScalarKind(s.kind()));
            }
        }

        if (kinds.contains(ScalarKind.NUMBER)) {
            kinds.remove(ScalarKind.INTEGER);
        }

        if (kinds.isEmpty()) {
            return new Type.Scalar(ScalarKind.STRING, nullSeen);
        }

        if (kinds.size() > 1) {
            List<String> sortedNames = kinds.stream()
                .map(ScalarKind::keyword)
                .sorted()
                .toList();
            String joined = String.join(", ", sortedNames);
            if (allowAny) {
                fallbacks.add(new AnyFallback(recordName + "." + label, "values of more than one scalar kind (" + joined + ")"));
                return Type.Any.INSTANCE;
            }
            throw new IllegalArgumentException(recordName + "." + label + ": has values of more than one scalar kind (" + joined + ")");
        }

        return new Type.Scalar(kinds.iterator().next(), nullSeen);
    }

    private static ScalarKind mapScalarKind(dev.omnist.document.ScalarKind k) {
        return switch (k) {
            case STRING -> ScalarKind.STRING;
            case INTEGER -> ScalarKind.INTEGER;
            case NUMBER -> ScalarKind.NUMBER;
            case BOOLEAN -> ScalarKind.BOOLEAN;
            case DATE -> ScalarKind.DATE;
            case TIME -> ScalarKind.TIME;
            case DATE_TIME -> ScalarKind.DATETIME;
        };
    }

    private static String unique(String base, Set<String> used) {
        String name = identifier(base);
        if (name.isEmpty()) {
            name = "Rec";
        }
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String cand = name;
        int i = 2;
        while (used.contains(cand)) {
            cand = name + i;
            i++;
        }
        used.add(cand);
        return cand;
    }

    private static String identifier(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        int start = 0;
        while (start < out.length()) {
            char c = out.charAt(start);
            if ((c >= '0' && c <= '9') || c == '_') {
                start++;
            } else {
                break;
            }
        }
        if (start == out.length()) {
            return out;
        }
        return out.substring(start);
    }



    private record FieldSigKey(String label, int min, Integer max, Object shapeKey) {}

    private record LocalSigKey(List<FieldSigKey> fields) {}

    private static LocalSigKey localSignature(Record rec) {
        List<FieldSigKey> fields = new ArrayList<>();
        for (Field f : rec.fields()) {
            // Type is sealed to exactly {Any, Ref, Scalar} -- this switch is
            // exhaustive, no fallback case is reachable.
            Object shapeKey = switch (f.type()) {
                case Type.Any ignored -> "any";
                case Type.Ref ignored -> "ref";
                case Type.Scalar sc -> List.of("scalar", sc.kind().keyword(), sc.nullable());
            };
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
                // JaCoCo artifact, same as reachablePlain's twin continue above: the
                // enclosing self-cycle test (testPruneOptionalRefToUnsatisfiableAndCycles)
                // pushes "Root" twice via the self-ref field, so this is genuinely reached
                // on the second pop; the bare continue's GOTO just doesn't register a hit.
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
