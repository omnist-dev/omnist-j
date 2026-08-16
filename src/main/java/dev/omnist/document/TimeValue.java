package dev.omnist.document;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Represents a time of day with optional UTC offset (omnist-spec §2.2.1).
 *
 * @param time   the time of day (required)
 * @param offset the UTC zone offset, or null if un-offset
 */
public record TimeValue(LocalTime time, ZoneOffset offset) {
    /** @throws NullPointerException if {@code time} is {@code null} */
    public TimeValue {
        Objects.requireNonNull(time, "time must not be null");
    }

    /**
     * Creates an un-offset time (local time with no UTC offset).
     *
     * @param time the time of day; must not be {@code null}
     * @return a new {@link TimeValue} with {@code offset == null}
     */
    public static TimeValue of(LocalTime time) {
        return new TimeValue(time, null);
    }

    /**
     * Creates an offset time with an explicit UTC offset.
     *
     * @param time   the time of day; must not be {@code null}
     * @param offset the UTC zone offset (e.g. {@code ZoneOffset.UTC}); may be {@code null} to produce
     *               an un-offset value
     * @return a new {@link TimeValue}
     */
    public static TimeValue of(LocalTime time, ZoneOffset offset) {
        return new TimeValue(time, offset);
    }

    /**
     * Formats this value as an ISO-8601 string suitable for OML and codec serialization.
     * The format is {@code HH:MM:SS[.fractional]} optionally followed by {@code Z} for UTC
     * or a numeric offset such as {@code +05:30}. Un-offset values omit the trailing
     * timezone designator entirely.
     *
     * @return the ISO-8601 formatted string; never {@code null}
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(time.toString());
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
