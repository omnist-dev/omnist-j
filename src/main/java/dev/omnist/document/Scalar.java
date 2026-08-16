package dev.omnist.document;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Represents a scalar value in the Omnist Document model (omnist-spec §2.2.1).
 * <pre>
 * scalar-value = string | integer | number | boolean | date | time | datetime
 * </pre>
 *
 * <p>The seven concrete variants are each a distinct {@code record} implementing this interface.
 * Use {@link #kind()} for uniform dispatch, or use {@code instanceof} pattern-matching
 * for variant-specific access.
 */
public sealed interface Scalar extends Value {

    /**
     * Returns the {@link ScalarKind} identifying which of the seven scalar types this value is.
     * Never returns {@code null}.
     */
    ScalarKind kind();

    /**
     * A scalar holding a Unicode text value (omnist-spec §2.2.1, type {@code string}).
     *
     * @param value the string content; never {@code null}
     */
    record StringScalar(String value) implements Scalar {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public StringScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.STRING;
        }
    }

    /**
     * A scalar holding an arbitrary-precision integer value (omnist-spec §2.2.1, type {@code integer}).
     *
     * @param value the integer value; never {@code null}
     */
    record IntegerScalar(BigInteger value) implements Scalar {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public IntegerScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.INTEGER;
        }
    }

    /**
     * A scalar holding an IEEE-754 64-bit floating-point number (omnist-spec §2.2.1, type {@code number}).
     * May be {@link Double#NaN}, {@link Double#POSITIVE_INFINITY}, or {@link Double#NEGATIVE_INFINITY};
     * not all output formats can represent these (see codec {@code WriteReport} adjustments).
     *
     * @param value the numeric value
     */
    record NumberScalar(double value) implements Scalar {
        @Override
        public ScalarKind kind() {
            return ScalarKind.NUMBER;
        }
    }

    /**
     * A scalar holding a boolean value (omnist-spec §2.2.1, type {@code boolean}).
     *
     * @param value {@code true} or {@code false}
     */
    record BooleanScalar(boolean value) implements Scalar {
        @Override
        public ScalarKind kind() {
            return ScalarKind.BOOLEAN;
        }
    }

    /**
     * A scalar holding a calendar date without a time component (omnist-spec §2.2.1, type {@code date}).
     *
     * @param value the calendar date; never {@code null}
     */
    record DateScalar(LocalDate value) implements Scalar {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public DateScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.DATE;
        }
    }

    /**
     * A scalar holding a time-of-day with an optional UTC offset (omnist-spec §2.2.1, type {@code time}).
     *
     * @param value the time value; never {@code null}
     */
    record TimeScalar(TimeValue value) implements Scalar {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public TimeScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.TIME;
        }
    }

    /**
     * A scalar holding a date-time with an optional UTC offset (omnist-spec §2.2.1, type {@code datetime}).
     *
     * @param value the date-time value; never {@code null}
     */
    record DateTimeScalar(DateTimeValue value) implements Scalar {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public DateTimeScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.DATE_TIME;
        }
    }
}
