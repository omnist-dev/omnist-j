package dev.omnist.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record Schema(String root, Map<String, Record> records) {
    public Schema {
        Objects.requireNonNull(root, "root must not be null");
        records = records != null ? Collections.unmodifiableMap(new LinkedHashMap<>(records)) : Map.of();
    }
}
