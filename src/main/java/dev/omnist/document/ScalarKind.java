package dev.omnist.document;

/**
 * The seven scalar kinds defined by omnist-spec §2.2.1.
 * Implementations MUST NOT add or collapse kinds.
 */
public enum ScalarKind {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    DATE,
    TIME,
    DATE_TIME
}
