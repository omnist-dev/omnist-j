package dev.omnist.schema;

import java.util.List;
import java.util.Objects;

/**
 * An immutable record definition in the OSD schema model (omnist-spec §5.5).
 * A record has a unique name and an ordered list of {@link Field} declarations.
 * The canonical ordering of fields within a record is preserved exactly as declared.
 *
 * @param name   the record type name; must not be {@code null} and must not be a reserved keyword
 * @param fields the ordered list of field declarations; {@code null} is normalized to an empty list
 */
public record Record(String name, List<Field> fields) {
    /** @throws NullPointerException if {@code name} is {@code null} */
    public Record {
        Objects.requireNonNull(name, "name must not be null");
        fields = fields != null ? List.copyOf(fields) : List.of();
    }

    /**
     * Looks up a field by its label, returning the first match in declaration order.
     *
     * @param label the field label to look up
     * @return the matching {@link Field}, or {@code null} if no field with that label exists
     */
    public Field field(String label) {
        for (Field f : fields) {
            if (f.label().equals(label)) {
                return f;
            }
        }
        return null;
    }
}
