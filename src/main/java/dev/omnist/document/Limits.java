package dev.omnist.document;

/**
 * Safety limits bounding Document nesting depth, node count, integer literal digits and input size
 * (omnist-spec section 2.4 and 2.4.2).
 *
 * <p>Enforced by every untrusted-input entry point: {@link dev.omnist.oml.OmlLexer},
 * {@link dev.omnist.schema.OsdReader}, and each format codec's reader. Not by
 * {@link Node}/{@link Edge} construction itself, which stays unconditionally trusting
 * (a document already built in memory has no "input" left to bound).
 *
 * <p>{@code maxInputBytes} (D-23) is applied by every reader that accepts a {@code Limits}: the OML
 * reader through {@link dev.omnist.oml.OmlReader#read(String, Limits)}, and the JSON, TOML and XML
 * codecs through their {@code readWithLimits} methods (YAML through
 * {@link dev.omnist.codec.YamlCodec#readWithLimits(String, dev.omnist.codec.YamlLimits, Limits)}).
 * The depth, node-count and integer-digit bounds of the codecs stay at the reference defaults.
 *
 * <p>A value that is not positive is refused with {@link IllegalArgumentException}, never quietly
 * replaced by the default or read as "no limit" (D-10: every bound is finite). {@code maxInputBytes}
 * is also refused above {@link #MAX_INPUT_BYTES_CEILING}.
 *
 * @param maxDepth         maximum nesting depth (reference default: 200)
 * @param maxNodeCount     maximum materialized node count (reference default: 1 000 000)
 * @param maxIntegerDigits maximum decimal digits in an integer literal (reference default: 4 300)
 * @param maxInputBytes    maximum input size in bytes, counted before a byte-order mark is stripped
 *                         and before decoding (default: {@link #DEFAULT_MAX_INPUT_BYTES}, 64 MiB)
 */
public record Limits(int maxDepth, int maxNodeCount, int maxIntegerDigits, int maxInputBytes) {

    /**
     * This implementation's default maximum input size (D-23, D-24): 64 MiB. The spec names no
     * reference number; see {@code docs/limitations.md} for what was measured behind this one.
     */
    public static final int DEFAULT_MAX_INPUT_BYTES = 64 * 1024 * 1024;

    /** Largest accepted {@code maxInputBytes}: 1 GiB, well inside a Java {@code String} and a heap. */
    public static final int MAX_INPUT_BYTES_CEILING = 1024 * 1024 * 1024;

    /**
     * Normative reference default limits per omnist-spec §2.4, plus this port's input-size default.
     */
    public static final Limits DEFAULT = new Limits(200, 1_000_000, 4_300, DEFAULT_MAX_INPUT_BYTES);

    /**
     * The limits of releases before the input-size bound existed: the given three, and the default
     * {@link #DEFAULT_MAX_INPUT_BYTES} input size.
     *
     * @param maxDepth         maximum nesting depth
     * @param maxNodeCount     maximum materialized node count
     * @param maxIntegerDigits maximum decimal digits in an integer literal
     * @throws IllegalArgumentException if any limit is not positive
     */
    public Limits(int maxDepth, int maxNodeCount, int maxIntegerDigits) {
        this(maxDepth, maxNodeCount, maxIntegerDigits, DEFAULT_MAX_INPUT_BYTES);
    }

    /**
     * @throws IllegalArgumentException if any limit is not positive, or {@code maxInputBytes} is above
     *                                  {@link #MAX_INPUT_BYTES_CEILING}
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
        if (maxInputBytes <= 0) {
            throw new IllegalArgumentException("maxInputBytes must be positive");
        }
        if (maxInputBytes > MAX_INPUT_BYTES_CEILING) {
            throw new IllegalArgumentException("maxInputBytes must be at most " + MAX_INPUT_BYTES_CEILING);
        }
    }

    /**
     * Returns these limits with another input-size maximum.
     *
     * @param bytes the new {@code maxInputBytes}
     * @return a copy with {@code maxInputBytes} replaced
     * @throws IllegalArgumentException if {@code bytes} is not positive or is above the ceiling
     */
    public Limits withMaxInputBytes(int bytes) {
        return new Limits(maxDepth, maxNodeCount, maxIntegerDigits, bytes);
    }
}
