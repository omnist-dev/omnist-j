package dev.omnist.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed OSD schema (omnist-spec §5, §3): a root record name plus the graph of
 * named records it (transitively) reaches. Records may reference each other and
 * themselves; nothing here requires acyclicity.
 *
 * @param root    the name of the root record; validation and materialization start here
 * @param records every record in this schema, keyed by name, in declaration order;
 *                defensively copied and made unmodifiable by the constructor
 */
public record Schema(String root, Map<String, Record> records) {
    /**
     * @throws NullPointerException if {@code root} is {@code null}
     * @throws SchemaException      {@code schema.invalid-name} at {@code $} if {@code root} or a key of
     *                              {@code records} is not a valid name (S-8)
     */
    public Schema {
        Objects.requireNonNull(root, "root must not be null");
        SchemaRules.checkName("root name", root);
        if (records != null) {
            for (String key : records.keySet()) {
                SchemaRules.checkName("record name", key);
            }
        }
        records = records != null ? Collections.unmodifiableMap(new LinkedHashMap<>(records)) : Map.of();
    }
}
