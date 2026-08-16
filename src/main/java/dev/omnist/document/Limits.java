package dev.omnist.document;

/**
 * Safety limits bounding Document nesting depth, node count, and integer literal digits (omnist-spec §2.4).
 *
 * <p>Enforced by every untrusted-input entry point — {@link dev.omnist.oml.OmlLexer},
 * {@link dev.omnist.schema.OsdReader}, and each format codec's reader — not by
 * {@link Node}/{@link Edge} construction itself, which stays unconditionally trusting
 * (a document already built in memory has no "input" left to bound).
 *
 * @param maxDepth         maximum nesting depth (reference default: 200)
 * @param maxNodeCount     maximum materialized node count (reference default: 1 000 000)
 * @param maxIntegerDigits maximum decimal digits in an integer literal (reference default: 4 300)
 */
public record Limits(int maxDepth, int maxNodeCount, int maxIntegerDigits) {

    /**
     * Normative reference default limits per omnist-spec §2.4.
     */
    public static final Limits DEFAULT = new Limits(200, 1_000_000, 4_300);

    /**
     * @throws IllegalArgumentException if any limit is not positive
     */
    public Limits {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive");
        }
        if (maxNodeCount <= 0) {
            throw new IllegalArgumentException("maxNodeCount must be positive");
        }
        if (maxIntegerDigits <= 0) {
            throw new IllegalArgumentException("maxIntegerDigits must be positive");
        }
    }
}
