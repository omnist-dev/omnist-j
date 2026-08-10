package dev.omnist.schema;

import java.util.Objects;

public sealed interface Type {
    record Scalar(ScalarKind kind, boolean nullable) implements Type {
        public Scalar {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    record Ref(String name) implements Type {
        public Ref {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    record Any() implements Type {
        public static final Any INSTANCE = new Any();
    }
}
