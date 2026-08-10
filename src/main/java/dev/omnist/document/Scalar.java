package dev.omnist.document;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Represents a scalar value in the Omnist Document model (omnist-spec §2.2.1).
 */
public sealed interface Scalar extends Target {

    ScalarKind kind();

    record StringScalar(String value) implements Scalar {
        public StringScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.STRING;
        }
    }

    record IntegerScalar(BigInteger value) implements Scalar {
        public IntegerScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.INTEGER;
        }
    }

    record NumberScalar(double value) implements Scalar {
        @Override
        public ScalarKind kind() {
            return ScalarKind.NUMBER;
        }
    }

    record BooleanScalar(boolean value) implements Scalar {
        @Override
        public ScalarKind kind() {
            return ScalarKind.BOOLEAN;
        }
    }

    record DateScalar(LocalDate value) implements Scalar {
        public DateScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.DATE;
        }
    }

    record TimeScalar(TimeValue value) implements Scalar {
        public TimeScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.TIME;
        }
    }

    record DateTimeScalar(DateTimeValue value) implements Scalar {
        public DateTimeScalar {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override
        public ScalarKind kind() {
            return ScalarKind.DATE_TIME;
        }
    }
}
