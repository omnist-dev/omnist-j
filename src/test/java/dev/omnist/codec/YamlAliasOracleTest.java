package dev.omnist.codec;

import dev.omnist.document.DocumentParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential test: random small documents of anchors, aliases, merge keys and merge carriers are
 * checked by the reader and, independently, by a naive oracle that applies the spec's W and S
 * rules by re-expanding every reference (no memo, exponential in general, fine for small
 * documents). The reader must reach the same verdict at every maximum and at the exact W(root)
 * boundary of the expanded-size cap.
 */
class YamlAliasOracleTest {

    // ---- the oracle -------------------------------------------------------------------------

    private static boolean isMerge(Node key) {
        return key instanceof ScalarNode && Tag.MERGE.equals(key.getTag());
    }

    /** W by re-expansion: no memo. */
    private static long w(Node n) {
        if (n instanceof ScalarNode) {
            return 1;
        }
        long total = 1;
        if (n instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                total += isMerge(t.getKeyNode()) ? mergeW(t.getValueNode()) : w(t.getValueNode());
            }
        } else {
            for (Node item : ((SequenceNode) n).getValue()) {
                total += w(item);
            }
        }
        return total;
    }

    private static long mergeW(Node v) {
        if (v instanceof MappingNode) {
            return w(v) - 1;
        }
        long total = 0;
        for (Node member : ((SequenceNode) v).getValue()) {
            total += w(member) - 1;
        }
        return total;
    }

    private final Set<Node> defined = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Node, Long> candidateS = new IdentityHashMap<>();
    private boolean sawAliasOrMerge;

    /** S of a container at the place it is first written (a later sight is an alias, one slot). */
    private long s(Node n) {
        if (n instanceof ScalarNode) {
            return 1;
        }
        defined.add(n);
        long total = 1;
        if (n instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                Node v = t.getValueNode();
                if (isMerge(t.getKeyNode())) {
                    sawAliasOrMerge = true;
                    total += 1;
                    if (v instanceof MappingNode) {
                        total += first(v) ? s(v) - 1 : 0;
                    } else if (first(v)) {
                        // a carrier: no slot, never a candidate; its mapping members add their written slots
                        defined.add(v);
                        for (Node member : ((SequenceNode) v).getValue()) {
                            total += first(member) ? s(member) - 1 : 0;
                        }
                    }
                } else {
                    total += value(v);
                }
            }
        } else {
            for (Node item : ((SequenceNode) n).getValue()) {
                total += value(item);
            }
        }
        candidateS.put(n, total);
        return total;
    }

    private boolean first(Node n) {
        if (defined.contains(n)) {
            sawAliasOrMerge = true;
            return false;
        }
        return true;
    }

    private long value(Node v) {
        return v instanceof ScalarNode ? 1 : first(v) ? s(v) : 1;
    }

    // ---- the generator ------------------------------------------------------------------------

    private static final class Gen {
        final Random r;
        int names;
        int keys;
        final List<String> maps = new ArrayList<>();
        /** Sequences whose members are all mappings: usable after a merge key. */
        final List<String> mapSeqs = new ArrayList<>();
        final List<String> mixedSeqs = new ArrayList<>();

        Gen(long seed) {
            r = new Random(seed);
        }

        String key() {
            return "k" + keys++;
        }

        String pick(List<String> l) {
            return "*" + l.get(r.nextInt(l.size()));
        }

        String mappingMember(int depth) {
            int c = r.nextInt(3);
            if (c == 0 && !maps.isEmpty()) {
                return pick(maps);
            }
            if (c == 1) {
                String name = "n" + names++;
                String body = map(depth + 1);
                maps.add(name);
                return "&" + name + " " + body;
            }
            return map(depth + 1);
        }

        String carrier(int depth) {
            int n = 1 + r.nextInt(3);
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                b.append(i > 0 ? ", " : "").append(mappingMember(depth));
            }
            return b.append("]").toString();
        }

        String mergeEntry(int depth) {
            switch (r.nextInt(6)) {
                case 0:
                    if (!maps.isEmpty()) {
                        return "<<: " + pick(maps);
                    }
                    return "<<: " + map(depth + 1);
                case 1:
                    if (!mapSeqs.isEmpty()) {
                        return "<<: " + pick(mapSeqs);
                    }
                    return "<<: " + carrier(depth);
                case 2:
                    return "<<: " + carrier(depth);
                case 3:
                    return "<<: " + map(depth + 1);
                case 4: {
                    String name = "n" + names++;
                    String body = carrier(depth);
                    mapSeqs.add(name);
                    return "<<: &" + name + " " + body;
                }
                default: {
                    String name = "n" + names++;
                    String body = map(depth + 1);
                    maps.add(name);
                    return "<<: &" + name + " " + body;
                }
            }
        }

        String map(int depth) {
            int n = 1 + r.nextInt(3);
            StringBuilder b = new StringBuilder("{");
            for (int i = 0; i < n; i++) {
                b.append(i > 0 ? ", " : "");
                if (r.nextInt(4) == 0) {
                    b.append(mergeEntry(depth));
                } else {
                    b.append(key()).append(": ").append(value(depth + 1));
                }
            }
            return b.append("}").toString();
        }

        String seq(int depth) {
            int n = 1 + r.nextInt(4);
            boolean allMaps = true;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                b.append(i > 0 ? ", " : "");
                if (r.nextInt(3) == 0) {
                    b.append(r.nextInt(100));
                    allMaps = false;
                } else {
                    b.append(mappingMember(depth));
                }
            }
            lastSeqAllMaps = allMaps;
            return b.append("]").toString();
        }

        boolean lastSeqAllMaps;

        String value(int depth) {
            int c = r.nextInt(depth > 2 ? 3 : 7);
            switch (c) {
                case 0:
                case 1:
                    return Integer.toString(r.nextInt(100));
                case 2: {
                    List<String> all = new ArrayList<>(maps);
                    all.addAll(mapSeqs);
                    all.addAll(mixedSeqs);
                    return all.isEmpty() ? "0" : pick(all);
                }
                case 3:
                case 4: {
                    String name = "n" + names++;
                    String body = map(depth + 1);
                    if (r.nextBoolean()) {
                        maps.add(name);
                        return "&" + name + " " + body;
                    }
                    return body;
                }
                default: {
                    boolean anchor = r.nextBoolean();
                    String name = "n" + names++;
                    String body = seq(depth + 1);
                    if (anchor) {
                        (lastSeqAllMaps ? mapSeqs : mixedSeqs).add(name);
                        return "&" + name + " " + body;
                    }
                    return body;
                }
            }
        }

        String document() {
            StringBuilder b = new StringBuilder();
            int n = 2 + r.nextInt(6);
            for (int i = 0; i < n; i++) {
                if (r.nextInt(5) == 0) {
                    b.append(mergeEntry(0));
                } else {
                    b.append(key()).append(": ").append(value(0));
                }
                b.append("\n");
            }
            return b.toString();
        }
    }

    // ---- the test -------------------------------------------------------------------------------

    @Test
    @DisplayName("the reader agrees with a naive re-expanding oracle on random aliased documents")
    void agreesWithTheOracle() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        int checked = 0;
        int rejectedRatio = 0;
        int rejectedSize = 0;
        for (long seed = 0; seed < 600; seed++) {
            String yaml = new Gen(seed).document();
            Node root = new Yaml(options).compose(new StringReader(yaml));
            YamlAliasOracleTest o = new YamlAliasOracleTest();
            o.s(root);
            long wRoot = w(root);
            if (wRoot > 200_000) {
                continue;
            }
            for (int max : new int[]{1, 2, 3, 5, 8, 50}) {
                boolean ratioFails = false;
                for (Map.Entry<Node, Long> c : o.candidateS.entrySet()) {
                    ratioFails |= w(c.getKey()) > (long) max * c.getValue();
                }
                for (long slotsMax : new long[]{Math.max(1, wRoot - 1), wRoot, 10_000_000L}) {
                    String expected = ratioFails ? "document.limit.alias-expansion"
                            : o.sawAliasOrMerge && wRoot > slotsMax ? "document.limit.expanded-size" : null;
                    String actual = null;
                    try {
                        YamlCodec.readWithLimits(yaml, new YamlLimits(max, slotsMax));
                    } catch (DocumentParseException e) {
                        actual = e.getCode();
                        assertEquals("$", e.getPath(), yaml);
                    }
                    assertEquals(expected, actual, "seed " + seed + " max " + max + " slots " + slotsMax
                            + " W(root)=" + wRoot + "\n" + yaml);
                    checked++;
                    rejectedRatio += "document.limit.alias-expansion".equals(actual) ? 1 : 0;
                    rejectedSize += "document.limit.expanded-size".equals(actual) ? 1 : 0;
                }
            }
        }
        // the generator reaches every verdict, at both sides of the boundaries
        assertTrue(checked > 5000, "checked " + checked);
        assertTrue(rejectedRatio > 500, "ratio rejections " + rejectedRatio);
        assertTrue(rejectedSize > 100, "size rejections " + rejectedSize);
    }
}
