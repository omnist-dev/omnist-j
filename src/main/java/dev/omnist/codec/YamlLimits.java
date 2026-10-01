package dev.omnist.codec;

/**
 * The two YAML alias limits of omnist-spec section 2.4.1: the maximum alias expansion factor
 * (D-18) and the maximum expanded size (D-22).
 *
 * <p>Both are finite, as D-10 requires, and both are enforced by {@link YamlCodec#readWithLimits(String,
 * YamlLimits)} on the composed node graph before anything is materialized (D-19).
 *
 * <p>{@code maxAliasExpansion} is compared against {@code E = W / S} of every candidate node: an
 * input is rejected with {@code document.limit.alias-expansion} when some {@code E} is greater than
 * it, and accepted when it is equal. {@code maxExpandedSlots} is compared against {@code W} of the
 * document root, only for an input that contains an alias or a merge key: an input is rejected with
 * {@code document.limit.expanded-size} when {@code W(root)} is greater than it, and accepted when
 * it is equal.
 *
 * <p>Like {@link dev.omnist.document.Limits}, a value that is not positive is refused with
 * {@link IllegalArgumentException} rather than quietly replaced by the default. A value above the
 * ceiling is refused too: {@code maxAliasExpansion} at most {@value #MAX_ALIAS_EXPANSION_CEILING},
 * {@code maxExpandedSlots} at most {@value #MAX_EXPANDED_SLOTS_CEILING} (the ceiling the spec
 * recommends for D-22; it measured about 780 bytes per slot in Go, so past it the cap no longer
 * bounds memory on a typical heap).
 *
 * @param maxAliasExpansion maximum expansion factor of any candidate node (reference default: 50)
 * @param maxExpandedSlots  maximum value slots a document containing an alias or merge key
 *                          materializes (reference default: 1 000 000)
 */
public record YamlLimits(int maxAliasExpansion, long maxExpandedSlots) {

    /** Reference default for the maximum alias expansion factor (D-18). */
    public static final int DEFAULT_MAX_ALIAS_EXPANSION = 50;

    /** Reference default for the maximum expanded size in value slots (D-22). */
    public static final long DEFAULT_MAX_EXPANDED_SLOTS = 1_000_000L;

    /** Largest accepted {@code maxAliasExpansion}. */
    public static final int MAX_ALIAS_EXPANSION_CEILING = 10_000;

    /** Largest accepted {@code maxExpandedSlots}: the ceiling omnist-spec D-22 recommends. */
    public static final long MAX_EXPANDED_SLOTS_CEILING = 10_000_000L;

    /** The reference defaults. */
    public static final YamlLimits DEFAULT =
            new YamlLimits(DEFAULT_MAX_ALIAS_EXPANSION, DEFAULT_MAX_EXPANDED_SLOTS);

    /**
     * @throws IllegalArgumentException if a limit is not positive or is above its ceiling
     */
    public YamlLimits {
        if (maxAliasExpansion <= 0) {
            throw new IllegalArgumentException("maxAliasExpansion must be positive");
        }
        if (maxAliasExpansion > MAX_ALIAS_EXPANSION_CEILING) {
            throw new IllegalArgumentException(
                    "maxAliasExpansion must be at most " + MAX_ALIAS_EXPANSION_CEILING);
        }
        if (maxExpandedSlots <= 0) {
            throw new IllegalArgumentException("maxExpandedSlots must be positive");
        }
        if (maxExpandedSlots > MAX_EXPANDED_SLOTS_CEILING) {
            throw new IllegalArgumentException(
                    "maxExpandedSlots must be at most " + MAX_EXPANDED_SLOTS_CEILING);
        }
    }
}
