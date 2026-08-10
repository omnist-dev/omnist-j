package dev.omnist.schema;

public enum ScalarKind {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    TIME("time"),
    DATETIME("datetime");

    private final String keyword;

    ScalarKind(String keyword) {
        this.keyword = keyword;
    }

    public String keyword() {
        return keyword;
    }

    public static ScalarKind fromKeyword(String name) {
        for (ScalarKind k : values()) {
            if (k.keyword.equals(name)) {
                return k;
            }
        }
        return null;
    }
}
