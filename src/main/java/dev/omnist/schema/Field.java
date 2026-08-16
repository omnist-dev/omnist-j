package dev.omnist.schema;

import java.util.Objects;

/**
 * An immutable field declaration within a {@link Record} definition (omnist-spec §5.4).
 *
 * <p>A field captures:
 * <ul>
 *   <li>a {@code label} — the JSON/OML key name as a quoted string in OSD</li>
 *   <li>a {@code type} — a scalar type, a {@link Type.Ref} to another record, or {@link Type.Any}</li>
 *   <li>cardinality bounds {@code [min, max]} — minimum and maximum occurrence count per node;
 *       {@code max == null} means unbounded</li>
 * </ul>
 * The default cardinality when omitted in OSD is {@code [1, 1]} (exactly one occurrence).
 *
 * @param label the field label; must not be {@code null}
 * @param type  the field type; must not be {@code null}
 * @param min   the minimum occurrence count; must be &gt;= 0
 * @param max   the maximum occurrence count; {@code null} means unbounded;
 *              if non-{@code null}, must be &gt;= {@code min}
 */
public record Field(String label, Type type, int min, Integer max) {
    /**
     * Compact constructor that enforces label/type non-null and cardinality invariants.
     *
     * @throws NullPointerException     if {@code label} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code min} is negative, or if {@code max} is non-{@code null}
     *                                  and less than {@code min}
     */
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
