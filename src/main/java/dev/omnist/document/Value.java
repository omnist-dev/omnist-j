package dev.omnist.document;

/**
 * Represents a value in the Document model (omnist-spec §2.2).
 * <pre>
 * value = scalar-value | null
 * </pre>
 * A value is either a {@link Scalar} or {@link NullValue}.
 */
public sealed interface Value extends Target, Document permits Scalar, Value.NullValue {

    /**
     * Singleton instance representing the null value.
     */
    NullValue NULL = NullValue.INSTANCE;

    /**
     * Represents the null value in the Document model (omnist-spec §2.2.1).
     */
    record NullValue() implements Value {
        public static final NullValue INSTANCE = new NullValue();
    }
}
