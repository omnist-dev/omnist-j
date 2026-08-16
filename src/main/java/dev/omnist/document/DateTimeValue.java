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
    /** @throws NullPointerException if {@code dateTime} is {@code null} */
    public DateTimeValue {
        Objects.requireNonNull(dateTime, "dateTime must not be null");
    }

    /**
     * Creates an un-offset date-time (local date-time with no UTC offset).
     *
     * @param dateTime the local date and time; must not be {@code null}
     * @return a new {@link DateTimeValue} with {@code offset == null}
     */
    public static DateTimeValue of(LocalDateTime dateTime) {
        return new DateTimeValue(dateTime, null);
    }

    /**
     * Creates an offset date-time with an explicit UTC offset.
     *
     * @param dateTime the local date and time; must not be {@code null}
     * @param offset   the UTC zone offset (e.g. {@code ZoneOffset.UTC}); may be {@code null} to produce
     *                 an un-offset value
     * @return a new {@link DateTimeValue}
     */
    public static DateTimeValue of(LocalDateTime dateTime, ZoneOffset offset) {
        return new DateTimeValue(dateTime, offset);
    }

    /**
     * Formats this value as an ISO-8601 string suitable for OML and codec serialization.
     * The format is {@code YYYY-MM-DDTHH:MM:SS[.fractional]} optionally followed by
     * {@code Z} for UTC or a numeric offset such as {@code +05:30}.
     * Un-offset values omit the trailing timezone designator entirely.
     *
     * @return the ISO-8601 formatted string; never {@code null}
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(dateTime.toString());
        if (offset != null) {
            if (ZoneOffset.UTC.equals(offset)) {
                sb.append("Z");
            } else {
                sb.append(offset.getId());
            }
        }
        return sb.toString();
    }

}
