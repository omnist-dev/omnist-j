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
    public TimeValue {
        Objects.requireNonNull(time, "time must not be null");
    }

    public static TimeValue of(LocalTime time) {
        return new TimeValue(time, null);
    }

    public static TimeValue of(LocalTime time, ZoneOffset offset) {
        return new TimeValue(time, offset);
    }
}
