package dev.omnist.codec;

import dev.omnist.document.DocumentParseException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Enforces omnist-spec D-18, D-18a, D-19, D-20 and D-22 (section 2.4.1) on a composed SnakeYAML
 * node graph, before anything is constructed from it.
 *
 * <p>SnakeYAML's composer resolves an alias to the very {@link Node} the anchor defined, so the
 * composed graph is the raw anchor/reference graph: its size is linear in the input however large
 * the expansion it describes. This class walks that graph once, with each container's
 * {@code (W, S)} memoized, and never walks the expanded tree.
 *
 * <p>For every candidate node (every mapping, every sequence in an ordinary value position, the
 * root included, an inline merge source included; scalars never) it computes {@code W}, the value
 * slots materialized, and {@code S}, the value slots written, and refuses the input with
 * {@code document.limit.alias-expansion} when {@code W / S} is greater than the maximum. A
 * container and a scalar each count one slot, mapping keys none; an alias counts one slot in
 * {@code S} and the {@code W} of its target in {@code W}; a merge key counts one slot in {@code S}
 * and contributes {@code W - 1} per merged mapping (its container is flattened away); a sequence
 * in merge-value position is a syntactic carrier with no slot of its own (D-18a). A reference to a
 * node whose definition has not finished is a cycle, rejected under the same code (D-20).
 *
 * <p>Merge shapes are validated first, in a pass of their own: a merge value that is not a mapping
 * or a sequence of mappings is {@code parse.codec-syntax}, and wins over every limit code (D-18a).
 *
 * <p>For an input that contains an alias or a merge key, {@code W} of the root above the maximum
 * expanded size is refused with {@code document.limit.expanded-size} (D-22), after every candidate
 * has passed the ratio check, so an input that fails both reports {@code alias-expansion}.
 *
 * <p>The ratio is checked as each container completes, so no {@code W} ever held exceeds
 * {@code max * S} of the node in hand; every addition saturates at {@link Long#MAX_VALUE} as well,
 * so a count can never wrap under a maximum. The walk is iterative: a deeply nested input cannot
 * exhaust the Java stack.
 */
final class YamlAliasCheck {

    /** How a completed child folds into its parent. */
    private enum Fold {
        /** An ordinary value (or complex key): {@code w} and {@code s} in full. */
        VALUE,
        /** A mapping first defined in merge-value position: {@code w-1} and {@code s-1}. */
        MERGE_MAPPING,
        /** A sequence first defined in merge-value position: its members' flattened totals. */
        CARRIER,
        /** An item of a sequence. */
        SEQ_ITEM,
        /** The document root. */
        ROOT
    }

    /** A container's memoized counts; {@code done} is false while its definition is being walked. */
    private static final class Slots {
        boolean done;
        boolean sequence;
        long w;
        long s;
        /** Sequence only: sum over mapping members of {@code W - 1}, what {@code <<: *seq} contributes. */
        long mergeW;
        /** Sequence only: sum over first-defined mapping members of {@code S - 1}. */
        long inlineS;
        /** Container levels in the materialized tree below and including this node. */
        int height;
    }

    private static final class Frame {
        final Node node;
        final Fold fold;
        final Slots slots;
        final List<NodeTuple> tuples;
        final List<Node> items;
        int pos;
        boolean keyDone;
        long w = 1;
        long s = 1;
        long mergeW;
        long inlineS;
        int height = 1;

        Frame(Node node, Fold fold, Slots slots) {
            this.node = node;
            this.fold = fold;
            this.slots = slots;
            if (node instanceof MappingNode m) {
                tuples = m.getValue();
                items = null;
            } else {
                tuples = null;
                items = ((SequenceNode) node).getValue();
            }
        }
    }

    private final YamlLimits limits;
    private final int maxDepth;
    private final IdentityHashMap<Node, Slots> memo = new IdentityHashMap<>();
    private final Set<Node> seenScalars = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<Frame> stack = new ArrayDeque<>();
    private boolean sawAlias;
    private boolean sawMerge;

    private YamlAliasCheck(YamlLimits limits, int maxDepth) {
        this.limits = limits;
        this.maxDepth = maxDepth;
    }

    /**
     * Checks a composed document.
     *
     * @param root     the root node of the composed document
     * @param limits   the alias limits
     * @param maxDepth the deepest container nesting the walk accepts; only an alias chain can
     *                 exceed it, as the library's own nesting cap bounds what is written
     * @throws DocumentParseException {@code parse.codec-syntax} for a malformed merge,
     *         {@code document.limit.alias-expansion} (D-18, D-20), {@code document.limit.expanded-size}
     *         (D-22) or {@code document.limit.depth} (a chain of aliases deeper than {@code maxDepth})
     */
    static void check(Node root, YamlLimits limits, int maxDepth) {
        validateMergeShapes(root);
        new YamlAliasCheck(limits, maxDepth).run(root);
    }

    // ---- pass 1: merge shapes (D-18a) --------------------------------------------------------

    private static boolean isMergeKey(Node key) {
        return key instanceof ScalarNode && Tag.MERGE.equals(key.getTag());
    }

    private static DocumentParseException malformedMerge(Node at, String what) {
        // A composed node always has a start mark; the marks are 0-based.
        int line = at.getStartMark().getLine() + 1;
        int column = at.getStartMark().getColumn() + 1;
        return CodecInput.syntax("YAML", "a merge key's value must be a mapping or a sequence of mappings, not " + what,
                line, column, null);
    }

    /** What a malformed merge value or member is: never a mapping, which is the one thing allowed. */
    private static String kind(Node n) {
        return n instanceof ScalarNode ? "a scalar" : "a sequence";
    }

    private static void validateMergeShapes(Node root) {
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            Node n = todo.pop();
            if (n instanceof ScalarNode || !seen.add(n)) {
                continue;
            }
            List<Node> children = new ArrayList<>();
            if (n instanceof MappingNode m) {
                for (NodeTuple t : m.getValue()) {
                    children.add(t.getKeyNode());
                    Node v = t.getValueNode();
                    if (isMergeKey(t.getKeyNode())) {
                        if (v instanceof SequenceNode seq) {
                            for (Node member : seq.getValue()) {
                                if (!(member instanceof MappingNode)) {
                                    throw malformedMerge(member, "a sequence member that is " + kind(member));
                                }
                            }
                        } else if (!(v instanceof MappingNode)) {
                            throw malformedMerge(v, kind(v));
                        }
                    }
                    children.add(v);
                }
            } else {
                children.addAll(((SequenceNode) n).getValue());
            }
            // Pushed in reverse so the first malformed merge in document order is the one reported.
            for (int i = children.size() - 1; i >= 0; i--) {
                todo.push(children.get(i));
            }
        }
    }

    // ---- pass 2: counting (D-18, D-19, D-20, D-22) -------------------------------------------

    static long add(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    static long mul(long a, long b) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        return hi != 0 || lo < 0 ? Long.MAX_VALUE : lo;
    }

    /**
     * {@code E = w / s} is above {@code max}. Exact, no division: {@code w > max * s} for {@code s >= 1}.
     * A saturated {@code w} is over every maximum (D-19), also when the product saturates too.
     */
    static boolean overRatio(long w, long s, long max) {
        return w == Long.MAX_VALUE || w > mul(max, s);
    }

    /** {@code W(root)} is above the expanded-size maximum; a saturated {@code w} is over every maximum. */
    static boolean overSize(long w, long max) {
        return w == Long.MAX_VALUE || w > max;
    }

    private static DocumentParseException aliasLimit(String message) {
        return new DocumentParseException("$", "document.limit.alias-expansion", "$: " + message);
    }

    private void run(Node root) {
        if (root instanceof ScalarNode) {
            return;
        }
        stack.push(newFrame(root, Fold.ROOT));
        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            Frame child = f.tuples != null ? stepMapping(f) : stepSequence(f);
            if (child != null) {
                stack.push(child);
            } else {
                stack.pop();
                finish(f);
            }
        }
    }

    private Frame newFrame(Node node, Fold fold) {
        Slots slots = new Slots();
        slots.sequence = node instanceof SequenceNode;
        memo.put(node, slots);
        return new Frame(node, fold, slots);
    }

    /** Counts a scalar item or value; tracks whether it is a second sight of an anchored scalar. */
    private void scalar(Frame f, Node n) {
        if (n.getAnchor() != null && !seenScalars.add(n)) {
            sawAlias = true;
        }
        f.w = add(f.w, 1);
        f.s = add(f.s, 1);
    }

    private Frame stepMapping(Frame f) {
        while (f.pos < f.tuples.size()) {
            NodeTuple t = f.tuples.get(f.pos);
            Node key = t.getKeyNode();
            if (!f.keyDone) {
                f.keyDone = true;
                // A complex key is materialized like any value; counted so it cannot hide a bomb.
                if (!(key instanceof ScalarNode)) {
                    Frame c = reference(f, key, Fold.VALUE);
                    if (c != null) {
                        return c;
                    }
                } else if (key.getAnchor() != null && !seenScalars.add(key)) {
                    sawAlias = true;
                }
            }
            f.pos++;
            f.keyDone = false;
            Node v = t.getValueNode();
            Frame c;
            if (isMergeKey(key)) {
                sawMerge = true;
                f.s = add(f.s, 1);
                c = reference(f, v, v instanceof SequenceNode ? Fold.CARRIER : Fold.MERGE_MAPPING);
            } else if (v instanceof ScalarNode) {
                scalar(f, v);
                c = null;
            } else {
                c = reference(f, v, Fold.VALUE);
            }
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private Frame stepSequence(Frame f) {
        while (f.pos < f.items.size()) {
            Node item = f.items.get(f.pos++);
            if (item instanceof ScalarNode) {
                scalar(f, item);
                continue;
            }
            Frame c = reference(f, item, Fold.SEQ_ITEM);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /**
     * A container (or a complex key) met as a child of {@code parent}. First sight: returns the
     * frame to walk. A node already completed is an alias: folded at once from its memo. A node
     * whose definition is still being walked is a cycle (D-20).
     */
    private Frame reference(Frame parent, Node n, Fold fold) {
        Slots m = memo.get(n);
        if (m == null) {
            return newFrame(n, fold);
        }
        if (!m.done) {
            throw aliasLimit("an anchored definition refers to itself, so its expansion is unbounded (D-20)");
        }
        sawAlias = true;
        fold(parent, fold, m, true);
        return null;
    }

    private void fold(Frame p, Fold fold, Slots c, boolean alias) {
        switch (fold) {
            case VALUE -> {
                p.w = add(p.w, c.w);
                p.s = add(p.s, alias ? 1 : c.s);
                p.height = Math.max(p.height, 1 + c.height);
            }
            case MERGE_MAPPING -> {
                p.w = add(p.w, (c.w - 1));
                p.s = add(p.s, alias ? 0 : (c.s - 1));
                p.height = Math.max(p.height, c.height);
            }
            case CARRIER -> {
                p.w = add(p.w, c.mergeW);
                p.s = add(p.s, alias ? 0 : c.inlineS);
                p.height = Math.max(p.height, c.height - 1);
            }
            default -> { // SEQ_ITEM: the root never folds into a parent
                p.w = add(p.w, c.w);
                p.s = add(p.s, alias ? 1 : c.s);
                p.height = Math.max(p.height, 1 + c.height);
                if (!c.sequence) {
                    p.mergeW = add(p.mergeW, (c.w - 1));
                    p.inlineS = add(p.inlineS, alias ? 0 : (c.s - 1));
                }
            }
        }
    }

    private void finish(Frame f) {
        Slots slots = f.slots;
        slots.w = f.w;
        slots.s = f.s;
        slots.mergeW = f.mergeW;
        slots.inlineS = f.inlineS;
        slots.height = f.height;
        slots.done = true;

        boolean carrier = f.fold == Fold.CARRIER;
        if (!carrier && overRatio(f.w, f.s, limits.maxAliasExpansion())) {
            throw aliasLimit("the expansion factor of a mapping or sequence exceeds the maximum ("
                    + limits.maxAliasExpansion() + "): it materializes " + f.w + " value slots from " + f.s + " written");
        }
        if (f.height > maxDepth) {
            throw new DocumentParseException("$", "document.limit.depth",
                    "$: aliases expand to nesting deeper than " + maxDepth + " levels");
        }
        if (f.fold == Fold.ROOT) {
            if ((sawAlias || sawMerge) && overSize(f.w, limits.maxExpandedSlots())) {
                throw new DocumentParseException("$", "document.limit.expanded-size",
                        "$: the input expands to " + f.w + " value slots, over the maximum (" + limits.maxExpandedSlots() + ")");
            }
            return;
        }
        fold(stack.peek(), f.fold, slots, false);
    }
}
