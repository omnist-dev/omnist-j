package dev.omnist.document;

/**
 * Safety limits bounding Document nesting depth, node count, and integer literal digits (omnist-spec §2.4).
 *
 * NOTE: This record defines the safety limit parameters specified in omnist-spec §2.4.
 * Limit enforcement is not wired into Node/Edge construction in this step; it will be
 * enforced in later steps by input readers (e.g. OML/codec parsers) when constructing trees from untrusted input.
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
