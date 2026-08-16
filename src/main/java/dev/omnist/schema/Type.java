package dev.omnist.schema;

import java.util.Objects;

/**
 * The three field-type forms an OSD schema field can declare (omnist-spec §5.3, §3.3):
 * a scalar kind, a reference to another record, or the open {@code any} type.
 */
public sealed interface Type {
    /**
     * A field typed as one of the seven scalar kinds, optionally nullable.
     *
     * @param kind     which scalar kind this field holds
     * @param nullable whether {@code null} is an accepted value for this field (OSD's {@code ?} suffix)
     */
    record Scalar(ScalarKind kind, boolean nullable) implements Type {
        /** @throws NullPointerException if {@code kind} is {@code null} */
        public Scalar {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    /**
     * A field typed as a reference to another record in the same schema (omnist-spec §5.3.2).
     *
     * @param name the referenced record's name; resolved against {@link Schema#records()}
     */
    record Ref(String name) implements Type {
        /** @throws NullPointerException if {@code name} is {@code null} */
        public Ref {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * The open {@code any} type: accepts any value unchecked (omnist-spec §5.3.3).
     * A singleton — always use {@link #INSTANCE} rather than constructing a new one.
     */
    record Any() implements Type {
        /** The single {@link Any} instance. */
        public static final Any INSTANCE = new Any();
    }
}
