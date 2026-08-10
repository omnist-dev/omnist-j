package dev.omnist.document;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Represents a date and time of day, joined, with optional UTC offset (omnist-spec §2.2.1).
 *
 * @param dateTime the local date and time (required)
 * @param offset   the UTC zone offset, or null if un-offset
 */
public record DateTimeValue(LocalDateTime dateTime, ZoneOffset offset) {
    public DateTimeValue {
        Objects.requireNonNull(dateTime, "dateTime must not be null");
    }

    public static DateTimeValue of(LocalDateTime dateTime) {
        return new DateTimeValue(dateTime, null);
    }

    public static DateTimeValue of(LocalDateTime dateTime, ZoneOffset offset) {
        return new DateTimeValue(dateTime, offset);
    }
}
