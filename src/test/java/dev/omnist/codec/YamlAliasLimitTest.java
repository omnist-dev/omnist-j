package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * omnist-spec D-18, D-18a, D-19, D-20, D-22 (section 2.4.1) on the YAML reader: what is a
 * candidate, how W and S count, both limits, the order they are reported in, and that nothing is
 * materialized before the check. The conformance vectors pin the same rules from the spec side.
 */
class YamlAliasLimitTest {

    static final String ALIAS = "document.limit.alias-expansion";
    static final String SIZE = "document.limit.expanded-size";
    static final String SYNTAX = "parse.codec-syntax";

    /** Alias maximum high enough that only the expanded-size limit can fire. */
    static YamlLimits slots(long max) {
        return new YamlLimits(YamlLimits.MAX_ALIAS_EXPANSION_CEILING, max);
    }

    /** Expanded-size maximum high enough that only the ratio can fire. */
    static YamlLimits ratio(int max) {
        return new YamlLimits(max, YamlLimits.MAX_EXPANDED_SLOTS_CEILING);
    }

    static DocumentParseException reject(String yaml, YamlLimits limits) {
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> YamlCodec.readWithLimits(yaml, limits), "expected a rejection of: " + yaml);
        return e;
    }

    static void assertRejected(String yaml, YamlLimits limits, String code) {
        DocumentParseException e = reject(yaml, limits);
        assertEquals(code, e.getCode(), e.getMessage());
        if (!SYNTAX.equals(code)) {
            assertEquals("$", e.getPath());
        } else {
            assertTrue(Pattern.matches("[1-9][0-9]*:[1-9][0-9]*", e.getPath()), e.getPath());
        }
    }

    static void assertAccepted(String yaml, YamlLimits limits) {
        assertNotNull(YamlCodec.readWithLimits(yaml, limits), yaml);
    }

    /** Accepted at {@code max}, rejected one below it: pins the largest E of the document to (max-1, max]. */
    static void pinFactor(String yaml, int max) {
        assertAccepted(yaml, ratio(max));
        assertRejected(yaml, ratio(max - 1), ALIAS);
    }

    /** Accepted at {@code w}, rejected at {@code w - 1}: pins W of the root exactly. */
    static void pinSlots(String yaml, long w) {
        assertAccepted(yaml, slots(w));
        assertRejected(yaml, slots(w - 1), SIZE);
    }

    static String keys(String prefix, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            b.append(i > 1 ? ", " : "").append(prefix).append(i).append(": ").append(i);
        }
        return b.toString();
    }

    // ---- the options --------------------------------------------------------------------------

    @Test
    @DisplayName("the reference defaults are 50 and 1 000 000")
    void defaults() {
        assertEquals(50, YamlLimits.DEFAULT.maxAliasExpansion());
        assertEquals(1_000_000L, YamlLimits.DEFAULT.maxExpandedSlots());
        assertEquals(YamlLimits.DEFAULT, new YamlLimits(YamlLimits.DEFAULT_MAX_ALIAS_EXPANSION,
                YamlLimits.DEFAULT_MAX_EXPANDED_SLOTS));
    }

    @Test
    @DisplayName("YamlLimits refuses a non-positive value and a value above its ceiling")
    void optionValidation() {
        assertThrows(IllegalArgumentException.class, () -> new YamlLimits(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new YamlLimits(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new YamlLimits(10_001, 10));
        assertThrows(IllegalArgumentException.class, () -> new YamlLimits(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new YamlLimits(10, -5));
        assertThrows(IllegalArgumentException.class, () -> new YamlLimits(10, 10_000_001L));
        assertEquals(10_000, new YamlLimits(10_000, 10_000_000L).maxAliasExpansion());
        assertEquals(10_000_000L, new YamlLimits(1, 10_000_000L).maxExpandedSlots());
        assertEquals(1, new YamlLimits(1, 1).maxAliasExpansion());
        assertThrows(NullPointerException.class, () -> YamlCodec.readWithLimits("a: 1", null));
    }

    @Test
    @DisplayName("saturating arithmetic: a saturated W is over every maximum")
    void saturation() {
        long max = Long.MAX_VALUE;
        assertEquals(max, YamlAliasCheck.add(max, 1));
        assertEquals(max, YamlAliasCheck.add(max - 1, 5));
        assertEquals(7, YamlAliasCheck.add(3, 4));
        assertEquals(max, YamlAliasCheck.mul(max, 2));
        assertEquals(max, YamlAliasCheck.mul(1L << 40, 1L << 40));
        assertEquals(12, YamlAliasCheck.mul(3, 4));
        // the product saturates too, so a plain comparison would call it equal and accept it
        assertTrue(YamlAliasCheck.overRatio(max, max, 10_000));
        assertTrue(YamlAliasCheck.overSize(max, 10_000_000L));
        assertFalse(YamlAliasCheck.overRatio(100, 2, 50));
        assertTrue(YamlAliasCheck.overRatio(101, 2, 50));
        assertFalse(YamlAliasCheck.overSize(5, 5));
        assertTrue(YamlAliasCheck.overSize(6, 5));
    }

    // ---- boundaries: accepted at the limit, rejected one past ---------------------------------

    @ParameterizedTest(name = "default maximum 50: a {0}-key block merged once, E = {0}+2 over 3")
    @CsvSource({"148,true", "149,false"})
    void defaultBoundary(int keys, boolean accepted) {
        String yaml = "base: &base {" + keys("k", keys) + "}\njob: {<<: *base, script: x}\n";
        if (accepted) {
            assertAccepted(yaml, YamlLimits.DEFAULT);
            assertNotNull(YamlCodec.read(yaml));
        } else {
            assertRejected(yaml, YamlLimits.DEFAULT, ALIAS);
            assertEquals(ALIAS, assertThrows(DocumentParseException.class, () -> YamlCodec.read(yaml)).getCode());
        }
    }

    @Test
    @DisplayName("the spec's nested example: W(r)=4, S(r)=2, E(r)=2.00; the merge of p contributes 1, not 2")
    void specNestedExample() {
        String yaml = "p: &p {k: 1}\nq: &q {<<: *p, m: 2}\nr: &r {n: *q}\n";
        pinFactor(yaml, 2);
        // W(root) = 1 + W(p) + W(q) + W(r) = 1 + 2 + 3 + 4
        pinSlots(yaml, 10);
    }

    @Test
    @DisplayName("the spec's unanchored fan-in: E(t) = 13 / 2 = 6.50, rejected at a maximum of 6")
    void specUnanchoredFanIn() {
        String yaml = "b: &b {k1: 1, k2: 2, k3: 3}\nt: {<<: [*b, *b, *b, *b]}\n";
        pinFactor(yaml, 7);
        assertRejected(yaml, ratio(6), ALIAS);
        // 1 + 4 + 13
        pinSlots(yaml, 18);
    }

    @Test
    @DisplayName("inline merge source: W(t) = 3, S(t) = 4; the inline container is one slot, not two")
    void specInlineMergeSource() {
        String yaml = "t: {<<: {a: 1}, z: 1}\n";
        // W(root) = 1 + W(t) = 4 (a merged `a`, `z`, t, root)
        pinSlots(yaml, 4);
        // with an alias inside the inline source the inline mapping can exceed the maximum on its own
        String bomb = "base: &b {k1: 1, k2: 2, k3: 3, k4: 4}\nt: {<<: {x1: *b, x2: *b}, z: 1}\n";
        // inline {x1: *b, x2: *b}: W = 1 + 5 + 5 = 11, S = 3, E = 3.67
        pinFactor(bomb, 4);
    }

    @Test
    @DisplayName("a carrier with inline and anchored members: S(z) = 5, E(z) = 1.00 (spec worked example)")
    void specCarrierMembers() {
        String yaml = "z: {<<: [{x: 1}, &m {y: 2}, *m], k: 3}\n";
        assertAccepted(yaml, ratio(1));
        // z, the three flattened members, k, then the root
        pinSlots(yaml, 6);
    }

    @Test
    @DisplayName("D-18a: the carrier holds no slot whether or not it is anchored")
    void carrierIsACarrierAnchoredOrNot() {
        String plain = "p: &p {a1: 1, a2: 2, a3: 3}\nq: &q {b1: 4, b2: 5, b3: 6}\n"
                + "z: {<<: [*p, *q], m1: 7, m2: 8, m3: 9}\n";
        String anchored = plain.replace("<<: [*p, *q]", "<<: &s [*p, *q]");
        // W(z) = 1 + 3 + 3 + 3 = 10, S(z) = 1 + 1 + 3 = 5, E(z) = 2.00
        pinFactor(plain, 2);
        pinFactor(anchored, 2);
        pinSlots(plain, 1 + 4 + 4 + 10);
        pinSlots(anchored, 1 + 4 + 4 + 10);
        // the anchored carrier is not a candidate: its own would-be E = W/S = (1+4+4)/3 = 3 is not checked
        assertAccepted(anchored, ratio(2));
    }

    @Test
    @DisplayName("D-18a: an alias to a sequence in merge position contributes the sum of W(member)-1")
    void mergeAliasToSequence() {
        String yaml = "p: &p {a: 1}\nq: &q {b: 2}\ns: &s [*p, *q]\ny: {<<: *s, m: 3}\n";
        // W(s) = 1 + 2 + 2 = 5, S(s) = 3, E(s) = 1.67; W(y) = 4, S(y) = 3, E(y) = 1.33
        pinFactor(yaml, 2);
        // not W(s) - 1 = 4: W(y) would be 6; root = 1 + 2 + 2 + 5 + 4
        pinSlots(yaml, 14);
        // a plain alias to a carrier materializes the list: 1 + W(p) + W(q)
        String carrierAlias = "p: &p {a: 1}\nq: &q {b: 2}\nz: {<<: &s [*p, *q]}\nt: *s\n";
        pinSlots(carrierAlias, 1 + 2 + 2 + 3 + 5);
    }

    @Test
    @DisplayName("a sequence written as a value and anchored is an ordinary candidate")
    void anchoredValueSequenceIsACandidate() {
        String yaml = "b: &b {k1: 1, k2: 2, k3: 3, k4: 4, k5: 5}\ns: &s [*b, *b, *b, *b]\n";
        // W(s) = 1 + 4 * 6 = 25, S(s) = 5, E(s) = 5.00
        pinFactor(yaml, 5);
    }

    @Test
    @DisplayName("an unanchored sequence in value position is a candidate")
    void unanchoredSequenceIsACandidate() {
        String yaml = "b: &b {k1: 1, k2: 2, k3: 3, k4: 4, k5: 5}\nl:\n  - *b\n  - *b\n  - *b\n  - *b\n";
        pinFactor(yaml, 5);
    }

    @Test
    @DisplayName("an unanchored mapping that merges a large block is a candidate; E is about (keys + 2) / 3")
    void unanchoredMergeIsACandidate() {
        String yaml = "base: &base {" + keys("k", 10) + "}\njob: {<<: *base, script: x}\n";
        // W(job) = 12, S(job) = 3, E = 4
        pinFactor(yaml, 4);
    }

    @Test
    @DisplayName("the document root is a candidate: aliases written at the root")
    void rootIsACandidate() {
        StringBuilder b = new StringBuilder("base: &b {" + keys("k", 10) + "}\n");
        for (int i = 1; i <= 10; i++) {
            b.append("r").append(i).append(": *b\n");
        }
        // W(root) = 1 + 11 + 10 * 11 = 122, S(root) = 1 + 11 + 10 = 22, E = 5.55; no other candidate is near
        pinFactor(b.toString(), 6);
    }

    @Test
    @DisplayName("a root sequence is a candidate too")
    void rootSequenceIsACandidate() {
        StringBuilder b = new StringBuilder("- &b [1, 2, 3, 4, 5, 6, 7, 8, 9]\n");
        for (int i = 0; i < 20; i++) {
            b.append("- *b\n");
        }
        // W(root) = 1 + 10 + 20 * 10 = 211, S(root) = 1 + 10 + 20 = 31; E(root) = 6.8; the document model
        // refuses a bare array afterwards, so past the limit only that refusal is observed
        assertRejected(b.toString(), ratio(6), ALIAS);
        DocumentParseException e = reject(b.toString(), ratio(7));
        assertEquals("document.unlabeled-element", e.getCode());
    }

    @Test
    @DisplayName("a scalar root has nothing to check")
    void scalarRoot() {
        assertEquals(dev.omnist.document.Value.NULL, YamlCodec.read("~\n"));
        assertNotNull(YamlCodec.read("5\n"));
    }

    // ---- exemptions, false-positive guards -----------------------------------------------------

    @Test
    @DisplayName("a scalar aliased 500 times is E = 1.00 and accepted")
    void scalarAliasedManyTimes() {
        StringBuilder b = new StringBuilder("c: &c value\n");
        for (int i = 0; i < 500; i++) {
            b.append("k").append(i).append(": *c\n");
        }
        assertAccepted(b.toString(), YamlLimits.DEFAULT);
        assertAccepted(b.toString(), ratio(1));
    }

    @Test
    @DisplayName("100 services merging a 20-key defaults anchor parse at the default (SnakeYAML's own cap is 50)")
    void hundredServicesMergingTwentyKeys() {
        StringBuilder b = new StringBuilder("x-defaults: &d\n");
        for (int i = 1; i <= 20; i++) {
            b.append("  key").append(i).append(": v").append(i).append("\n");
        }
        b.append("services:\n");
        for (int i = 0; i < 100; i++) {
            b.append("  svc").append(i).append(":\n    <<: *d\n    image: img").append(i).append("\n");
        }
        Document doc = YamlCodec.read(b.toString());
        dev.omnist.document.Node services = (dev.omnist.document.Node) ((dev.omnist.document.Node) doc)
                .edges().get(1).target();
        assertEquals(100, services.edges().size());
        dev.omnist.document.Node svc99 = (dev.omnist.document.Node) services.edges().get(99).target();
        assertEquals(21, svc99.edges().size());
        // worst E is 22 / 3 = 7.33 (the spec measures 7.33)
        pinFactor(b.toString(), 8);
        // W(root) = 2223: the spec's figure for this shape
        pinSlots(b.toString(), 2223);
    }

    @Test
    @DisplayName("a 100-key block aliased 60 times at the root is accepted, 100 times rejected")
    void blockAliasedAtTheRoot() {
        for (int n : new int[]{60, 100}) {
            StringBuilder b = new StringBuilder("base: &b {" + keys("k", 100) + "}\n");
            for (int i = 0; i < n; i++) {
                b.append("r").append(i).append(": *b\n");
            }
            if (n == 60) {
                assertAccepted(b.toString(), YamlLimits.DEFAULT);
            } else {
                // E = 10202 / 202 = 50.50
                assertRejected(b.toString(), YamlLimits.DEFAULT, ALIAS);
            }
        }
    }

    @Test
    @DisplayName("merge semantics are unchanged: a local key overrides a merged one, order and values intact")
    void mergeStillMerges() {
        dev.omnist.document.Node root = (dev.omnist.document.Node)
                YamlCodec.read("base: &b {k: 1, j: 2}\nt: {<<: *b, k: 9, z: 3}\n");
        dev.omnist.document.Node t = (dev.omnist.document.Node) root.edges().get(1).target();
        java.util.Map<String, String> got = new java.util.TreeMap<>();
        t.edges().forEach(e -> got.put(e.label(), e.target().toString()));
        assertEquals(3, t.edges().size());
        assertEquals(java.util.Map.of("j", "IntegerScalar[value=2]", "k", "IntegerScalar[value=9]",
                "z", "IntegerScalar[value=3]"), got);
    }

    // ---- D-22 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("D-22: accepted at the cap, rejected one past; the same input at the same W under D-18")
    void expandedSizeBoundary() {
        String yaml = "base: &base {k1: 1, k2: 2, k3: 3}\nt: {a: *base, b: *base, c: *base, d: *base}\n";
        pinSlots(yaml, 22);
    }

    @Test
    @DisplayName("D-22: passes where the ratio fails, fails where the ratio passes; both fail reports D-18")
    void twoLimitsAndTheirOrder() {
        String yaml = "base: &base {k1: 1, k2: 2, k3: 3}\nt: {a: *base, b: *base, c: *base, d: *base}\n";
        assertRejected(yaml, new YamlLimits(4, 21), SIZE);
        assertRejected(yaml, new YamlLimits(3, 22), ALIAS);
        assertRejected(yaml, new YamlLimits(3, 21), ALIAS);
        assertAccepted(yaml, new YamlLimits(4, 22));
    }

    @Test
    @DisplayName("D-22 applies only to an input with an alias or a merge key")
    void expandedSizeExemption() {
        String plain = "k1: 1\nk2: 2\nk3: 3\nk4: 4\nk5: 5\nk6: 6\nk7: 7\n";
        assertAccepted(plain, slots(3));
        // an anchor nobody refers to is not an alias
        assertAccepted("k1: &x 1\nk2: 2\nk3: 3\nk4: 4\nk5: 5\nk6: 6\nk7: 7\n", slots(3));
        // one harmless alias to a scalar subjects the whole document
        assertRejected("k1: &x 1\nk2: 2\nk3: 3\nk4: 4\nk5: 5\nk6: 6\nk7: 7\nk8: *x\n", slots(3), SIZE);
        // a merge key with no alias in sight
        assertRejected("t: {<<: {a: 1}}\n", slots(2), SIZE);
        pinSlots("t: {<<: {a: 1}}\n", 3);
        // an alias in key position
        assertAccepted("&k a: 1\nb: 2\n", slots(2));
        assertRejected("&k a: 1\n*k : 2\n", slots(2), SIZE);
        // a quoted << is a plain key, not a merge key
        assertAccepted("\"<<\": {a: 1}\nb: 2\n", slots(2));
    }

    @Test
    @DisplayName("D-22: the default, 1 000 000 slots, refuses a large aliased input")
    void defaultExpandedSize() {
        // 25 distinct containers of 50 keys each, aliased 1000 times in all: well under the ratio of 50
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            b.append("b").append(i).append(": &b").append(i).append(" {").append(keys("k", 40)).append("}\n");
        }
        b.append("l:\n");
        for (int i = 0; i < 1000; i++) {
            b.append("  - {x: *b").append(i % 25).append("}\n");
        }
        // W = 1 + 25 * 41 + (1 + 1000 * (1 + 1 + 41)), about 44 000: fine at the default
        assertAccepted(b.toString(), YamlLimits.DEFAULT);
        assertRejected(b.toString(), new YamlLimits(50, 40_000), SIZE);
    }

    // ---- merge shapes: parse.codec-syntax wins ---------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "a:\n  <<: 1\n",
            "a:\n  <<: [1]\n",
            "a:\n  <<: [[{a: 1}]]\n",
            "s: &s [1, 2]\nz:\n  <<: *s\n",
            "a:\n  <<:\n",
            "p: &p {x: 1}\nz: {<<: [*p, 2]}\n",
            "s: &s [{x: 1}]\nz: {<<: [*s]}\n",
            "z: {<<: &c [1, 2]}\n",
    })
    @DisplayName("a merge value that is not a mapping or a sequence of mappings is parse.codec-syntax")
    void malformedMerge(String yaml) {
        assertRejected(yaml, YamlLimits.DEFAULT, SYNTAX);
        assertRejected(yaml, new YamlLimits(1, 1), SYNTAX);
    }

    @Test
    @DisplayName("a malformed merge wins over a limit code wherever it sits in the document")
    void malformedMergeWinsOverEveryLimit() {
        String bomb = "p: &p {a: 1, b: 2, c: 3}\nt: {<<: [*p, *p, *p, *p]}\n";
        String bad = "bad:\n  <<: 1\n";
        assertRejected(bomb + bad, ratio(2), SYNTAX);
        assertRejected(bad + bomb, ratio(2), SYNTAX);
        assertRejected(bomb + bad, slots(2), SYNTAX);
        assertRejected(bomb, ratio(2), ALIAS);
        assertEquals("4:7", reject(bomb + bad, ratio(2)).getPath());
        // the first malformed merge in document order is reported
        assertEquals("2:7", reject("a:\n  <<: 1\nb:\n  <<: 2\n", YamlLimits.DEFAULT).getPath());
    }

    // ---- D-20 --------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "a: &a {b: *a}\n",
            "a: &a {<<: *a, k: 1}\n",
            "a: &a {b: &b {c: *a}}\n",
            "x: &x {y: &y {z: *x}}\n",
            "a: &a [*a]\n",
            "a: &a {<<: [*a]}\n",
            "a: &a {k: [1, *a]}\n",
            "a: &a {<<: &c [*a]}\n",
            "a: &a {? *a : 1}\n",
    })
    @DisplayName("D-20: a self-referential anchor is document.limit.alias-expansion")
    void cycles(String yaml) {
        assertRejected(yaml, YamlLimits.DEFAULT, ALIAS);
        assertRejected(yaml, new YamlLimits(10_000, 10_000_000L), ALIAS);
    }

    @Test
    @DisplayName("an alias to a sibling anchor is not a cycle")
    void siblingIsNotACycle() {
        assertAccepted("a: &a {k: 1}\nb: &b {x: *a, y: *a}\nc: {<<: [*a, *b]}\n", YamlLimits.DEFAULT);
    }

    // ---- complex keys, depth ---------------------------------------------------------------------

    @Test
    @DisplayName("a bomb in a complex key is counted, not constructed")
    void complexKey() {
        String bomb = "b: &b {" + keys("k", 100) + "}\n? {x: *b, y: *b, z: *b}\n: v\n";
        assertRejected(bomb, ratio(5), ALIAS);
        // a complex key is accepted by the check and refused by the document model afterwards
        DocumentParseException e = reject("k: &k {a: 1}\n? *k\n: v\n", YamlLimits.DEFAULT);
        assertNotEquals(ALIAS, e.getCode());
        assertNotEquals(SIZE, e.getCode());
        assertEquals(SIZE, reject("k: &k {a: 1}\n? *k\n: v\n", slots(3)).getCode());
        // a complex key written in place (not an alias) is walked as a child frame and then resumed
        DocumentParseException inPlace = reject("? {a: 1}\n: v\n", YamlLimits.DEFAULT);
        assertNotEquals(ALIAS, inPlace.getCode());
        assertNotEquals(SYNTAX, inPlace.getCode());
    }

    static String chain(int n) {
        StringBuilder b = new StringBuilder("a0: &a0 {leaf: 1}\n");
        for (int i = 1; i <= n; i++) {
            b.append("a").append(i).append(": &a").append(i).append(" {n: *a").append(i - 1).append("}\n");
        }
        return b.toString();
    }

    @Test
    @DisplayName("a chain of aliases deeper than anything that can be written is document.limit.depth, not a stack overflow")
    void aliasChainDepth() {
        // E(a_k) = (k + 2) / 2: a high ratio maximum lets a 2 500-deep chain through D-18
        String deep = chain(2500);
        DocumentParseException e = reject(deep, new YamlLimits(10_000, 10_000_000L));
        assertEquals("document.limit.depth", e.getCode());
        assertEquals("$", e.getPath());
        // within what the library's own nesting cap allows to be written, the document model's limit decides
        DocumentParseException shallow = reject(chain(300), new YamlLimits(10_000, 10_000_000L));
        assertEquals("document.limit.depth", shallow.getCode());
        assertNotEquals("$", shallow.getPath());
        // and a chain within the model's limit parses
        assertAccepted(chain(150), new YamlLimits(10_000, 10_000_000L));
        // at the default the ratio refuses a long chain long before depth matters
        assertRejected(chain(150), YamlLimits.DEFAULT, ALIAS);
    }

    @Test
    @DisplayName("a deeply nested written document is neither a stack overflow nor misreported")
    void deepWrittenNesting() {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            open.append("{a: ");
            close.append("}");
        }
        String yaml = "base: &b " + open + "1" + close + "\nr: *b\n";
        assertEquals("document.limit.depth", reject(yaml, YamlLimits.DEFAULT).getCode());
    }

    // ---- bombs: check before materialization, in linear time --------------------------------------

    static String fanOut(int levels, int fan) {
        StringBuilder b = new StringBuilder("a0: &a0 leaf\n");
        for (int i = 1; i <= levels; i++) {
            b.append("a").append(i).append(": &a").append(i).append(" {");
            for (int j = 0; j < fan; j++) {
                b.append(j > 0 ? ", " : "").append("p").append(j).append(": *a").append(i - 1);
            }
            b.append("}\n");
        }
        return b.toString();
    }

    static void assertFast(String yaml, YamlLimits limits, String code) {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertRejected(yaml, limits, code));
    }

    @Test
    @DisplayName("fan-out bombs 4^10, 2^30 and 50^4 are refused without being expanded")
    void fanOutBombs() {
        assertFast(fanOut(10, 4), YamlLimits.DEFAULT, ALIAS);
        assertFast(fanOut(30, 2), YamlLimits.DEFAULT, ALIAS);
        assertFast(fanOut(4, 50), YamlLimits.DEFAULT, ALIAS);
        // with the ratio raised to its ceiling the size cap catches them, still before materialization
        assertFast(fanOut(10, 4), new YamlLimits(10_000, 1_000_000L), ALIAS);
        assertFast(fanOut(30, 2), new YamlLimits(10_000, 1_000_000L), ALIAS);
    }

    @Test
    @DisplayName("the unanchored merge fan-in N = M = 2000 and a 100 000-item root list over 1 000 scalars")
    void fanInBombs() {
        StringBuilder b = new StringBuilder("b: &b {" + keys("k", 2000) + "}\nt: {<<: [");
        for (int i = 0; i < 2000; i++) {
            b.append(i > 0 ? ", " : "").append("*b");
        }
        b.append("]}\n");
        assertFast(b.toString(), YamlLimits.DEFAULT, ALIAS);

        StringBuilder list = new StringBuilder("base: &b [");
        for (int i = 0; i < 1000; i++) {
            list.append(i > 0 ? ", " : "").append(i);
        }
        list.append("]\nl:\n");
        for (int i = 0; i < 100_000; i++) {
            list.append("  - {k: *b}\n");
        }
        assertFast(list.toString(), YamlLimits.DEFAULT, ALIAS);
    }

    @Test
    @DisplayName("nothing is materialized before the check: a bomb is alias-expansion, never document.limit.nodes")
    void checkRunsBeforeMaterialization() {
        // 20 levels of 2 would build 2^20 nodes (past the 1 000 000 node limit) if it were expanded first
        String yaml = fanOut(20, 2).replace("leaf", "{x: 1}");
        assertRejected(yaml, new YamlLimits(10_000, 10_000_000L), ALIAS);
        // the same graph under a ratio that admits it is stopped by D-22, again not by the node limit
        String wide = "b: &b {" + keys("k", 100) + "}\n" + "l:\n" + "  - {x: *b}\n".repeat(30_000);
        assertRejected(wide, new YamlLimits(10_000, 1_000_000L), SIZE);
    }

    @Test
    @DisplayName("the check alone is linear: time it on the composed graph, apart from the library's own parse")
    void checkIsLinear() {
        String yaml = fanOut(30, 2);
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        org.yaml.snakeyaml.nodes.Node root = new Yaml(options).compose(new StringReader(yaml));
        assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> assertThrows(DocumentParseException.class,
                        () -> YamlAliasCheck.check(root, YamlLimits.DEFAULT, 1000)));
        // accepted at the ceiling, a graph whose shared nodes are walked once: 3 000 chained merges
        StringBuilder b = new StringBuilder("a0: &a0 {k: 1}\n");
        for (int i = 1; i <= 3000; i++) {
            b.append("a").append(i).append(": &a").append(i).append(" {<<: *a").append(i - 1).append("}\n");
        }
        org.yaml.snakeyaml.nodes.Node chainRoot = new Yaml(options).compose(new StringReader(b.toString()));
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> YamlAliasCheck.check(chainRoot, YamlLimits.DEFAULT, 1000));
    }

    @Test
    @DisplayName("a wide input with no alias is untouched: no cap applies to plain YAML")
    void plainYamlIsNotCapped() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            b.append("k").append(i).append(": ").append(i).append("\n");
        }
        assertAccepted(b.toString(), slots(1));
    }

    // omnist-spec v0.27.0-beta, D-18a: an empty merge sequence is a carrier that merges nothing
    // (W 0, S one slot for the `<<` entry); an empty sequence elsewhere is an ordinary node.

    @Test
    @DisplayName("`config: {<<: []}` is an empty mapping")
    void emptyMergeSequenceLeavesAnEmptyMapping() {
        assertEquals(YamlCodec.read("config: {}\n"), YamlCodec.read("config: {<<: []}\n"));
    }

    @Test
    @DisplayName("`t: {<<: [], c: 3}` keeps only its own key")
    void emptyMergeSequenceKeepsOwnKeys() {
        assertEquals(YamlCodec.read("t: {c: 3}\n"), YamlCodec.read("t: {<<: [], c: 3}\n"));
    }

    @Test
    @DisplayName("an aliased empty sequence in merge position merges nothing")
    void aliasedEmptyCarrierMergesNothing() {
        assertEquals(YamlCodec.read("t: {c: 3}\n"), YamlCodec.read("s: &s []\nt: {<<: *s, c: 3}\n"));
    }

    @Test
    @DisplayName("an anchored empty carrier in merge position merges nothing")
    void anchoredEmptyCarrierMergesNothing() {
        assertEquals(YamlCodec.read("t: {c: 3}\n"), YamlCodec.read("t: {<<: &s [], c: 3}\n"));
    }

    @Test
    @DisplayName("an empty sequence outside merge position yields no edge")
    void emptySequenceOutsideMergeYieldsNoEdge() {
        assertEquals(YamlCodec.read("a: 1\n"), YamlCodec.read("a: 1\nk: []\n"));
        assertEquals(YamlCodec.read("t: {}\n"), YamlCodec.read("t: {<<: [], k: []}\n"));
    }

    @Test
    @DisplayName("an empty carrier counts W 0 and one slot: `t: {<<: []}` has W(root) 2, S(root) 3")
    void emptyCarrierSizeCapBoundary() {
        String yaml = "t: {<<: []}\n";
        pinSlots(yaml, 2);
    }
}
