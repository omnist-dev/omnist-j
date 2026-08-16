package dev.omnist.schema;

/**
 * The seven scalar type keywords a field can name in OSD (omnist-spec §5,
 * §3.3). Each variant's {@link #keyword()} is the exact bareword that
 * appears in schema text, e.g. {@code field "x": string}.
 */
public enum ScalarKind {
    /** OSD keyword {@code string}. */
    STRING("string"),
    /** OSD keyword {@code integer}. */
    INTEGER("integer"),
    /** OSD keyword {@code number}. */
    NUMBER("number"),
    /** OSD keyword {@code boolean}. */
    BOOLEAN("boolean"),
    /** OSD keyword {@code date}. */
    DATE("date"),
    /** OSD keyword {@code time}. */
    TIME("time"),
    /** OSD keyword {@code datetime}. */
    DATETIME("datetime");

    private final String keyword;

    ScalarKind(String keyword) {
        this.keyword = keyword;
    }

    /**
     * Returns the exact bareword this kind is spelled as in OSD schema text.
     */
    public String keyword() {
        return keyword;
    }

    /**
     * Resolves an OSD bareword back to its {@link ScalarKind}.
     *
     * @param name the bareword as it appears in schema text (e.g. {@code "integer"})
     * @return the matching kind, or {@code null} if {@code name} does not match any keyword
     */
    public static ScalarKind fromKeyword(String name) {
        for (ScalarKind k : values()) {
            if (k.keyword.equals(name)) {
                return k;
            }
        }
        return null;
    }
}
