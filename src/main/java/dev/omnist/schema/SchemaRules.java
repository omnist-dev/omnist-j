package dev.omnist.schema;

import java.util.List;

/** Construction-time well-formedness rules S-8 and S-22 (omnist-spec section 3.3). */
final class SchemaRules {
    private SchemaRules() {}

    /**
     * S-8: a Name must match {@code [A-Za-z_][A-Za-z0-9_]*}.
     *
     * @param what a description of the role of the name, for the message
     * @param name the name to check
     * @throws SchemaException {@code schema.invalid-name} at {@code $}
     */
    static void checkName(String what, String name) {
        boolean ok = !name.isEmpty() && isStart(name.charAt(0));
        for (int i = 1; ok && i < name.length(); i++) {
            ok = isStart(name.charAt(i)) || (name.charAt(i) >= '0' && name.charAt(i) <= '9');
        }
        if (!ok) {
            throw new SchemaException("schema.invalid-name", "$",
                    what + " is not a valid name (must match [A-Za-z_][A-Za-z0-9_]*): '" + escape(name) + "'");
        }
    }

    /** Escapes non-printable and non-ASCII characters so a malformed name cannot break the message. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.toString();
    }

    private static boolean isStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    /**
     * S-22: every field label must encode to valid UTF-8, i.e. contain no unpaired surrogate.
     *
     * @param recordName the (already S-8-valid) name of the record holding the fields: the path
     * @param fields     the fields to check
     * @throws SchemaException {@code schema.invalid-label} at the record path
     */
    static void checkLabels(String recordName, List<Field> fields) {
        for (Field f : fields) {
            String label = f.label();
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < label.length()
                        && Character.isLowSurrogate(label.charAt(i + 1))) {
                    i++;
                } else if (Character.isSurrogate(c)) {
                    throw new SchemaException("schema.invalid-label", recordName,
                            "a field label contains an unpaired UTF-16 surrogate (U+"
                                    + String.format("%04X", (int) c) + ") and does not encode to UTF-8");
                }
            }
        }
    }
}
