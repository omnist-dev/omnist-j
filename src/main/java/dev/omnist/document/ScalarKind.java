package dev.omnist.document;

/**
 * The seven scalar kinds defined by omnist-spec §2.2.1.
 * Implementations MUST NOT add or collapse kinds.
 */
public enum ScalarKind {
    /** Unicode text (omnist-spec §2.2.1, type {@code string}). */
    STRING,
    /** An arbitrary-precision integer (omnist-spec §2.2.1, type {@code integer}). */
    INTEGER,
    /** An IEEE-754 64-bit floating-point number, including NaN and the infinities (omnist-spec §2.2.1, type {@code number}). */
    NUMBER,
    /** A boolean value (omnist-spec §2.2.1, type {@code boolean}). */
    BOOLEAN,
    /** A calendar date with no time component (omnist-spec §2.2.1, type {@code date}). */
    DATE,
    /** A time-of-day with an optional UTC offset (omnist-spec §2.2.1, type {@code time}). */
    TIME,
    /** A date-time with an optional UTC offset (omnist-spec §2.2.1, type {@code datetime}). */
    DATE_TIME
}
