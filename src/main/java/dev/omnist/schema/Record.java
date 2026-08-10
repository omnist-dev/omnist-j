package dev.omnist.schema;

import java.util.List;
import java.util.Objects;

public record Record(String name, List<Field> fields) {
    public Record {
        Objects.requireNonNull(name, "name must not be null");
        fields = fields != null ? List.copyOf(fields) : List.of();
    }

    public Field field(String label) {
        for (Field f : fields) {
            if (f.label().equals(label)) {
                return f;
            }
        }
        return null;
    }
}
