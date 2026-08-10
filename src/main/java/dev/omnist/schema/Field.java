package dev.omnist.schema;

import java.util.Objects;

public record Field(String label, Type type, int min, Integer max) {
    public Field {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (min < 0) {
            throw new IllegalArgumentException("min cardinality cannot be negative");
        }
        if (max != null && max < min) {
            throw new IllegalArgumentException("max cardinality cannot be less than min");
        }
    }
}
