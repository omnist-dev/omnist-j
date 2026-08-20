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

    /**
     * Parses an ISO-8601 date-time string into a {@link DateTimeValue}.
     * Supports un-offset local date-times (e.g. {@code "2024-01-01T12:00:00"}), UTC 'Z' (e.g. {@code "2024-01-01T12:00:00Z"}),
     * and numeric offsets (e.g. {@code "2024-01-01T12:00:00+02:00"}).
     *
     * @param text the ISO-8601 date-time string to parse; must not be {@code null}
     * @return the parsed {@link DateTimeValue}
     */
    public static DateTimeValue parse(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.endsWith("Z") || text.endsWith("z")) {
            LocalDateTime dt = LocalDateTime.parse(text.substring(0, text.length() - 1));
            return DateTimeValue.of(dt, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 10) {
            LocalDateTime dt = LocalDateTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return DateTimeValue.of(dt, offset);
        }
        return DateTimeValue.of(LocalDateTime.parse(text));
    }

}
