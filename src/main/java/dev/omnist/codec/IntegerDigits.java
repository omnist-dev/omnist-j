package dev.omnist.codec;

import dev.omnist.document.DocumentParseException;

import java.math.BigInteger;

/**
 * The integer-digit limit of D-9, counted as D-28 says: the decimal digits of the <em>value</em>
 * of an integer literal, sign excluded, whatever notation spelled it (a hexadecimal, octal or
 * binary literal, underscores, leading zeros). Shared by the JSON, YAML and TOML readers and the
 * schema-directed pretyping of an XML leaf so they cannot drift apart.
 */
final class IntegerDigits {

    /** {@code document.limit.int-digits} (D-13, E-4a). */
    static final String CODE = "document.limit.int-digits";

    /** log10(2), rounded down at the 15th digit so a lower bound derived from it stays a lower bound. */
    private static final double LOG10_2_FLOOR = 0.301029995663981;

    private IntegerDigits() {}

    /**
     * Builds the exception for an integer whose value has more decimal digits than {@code max}, at
     * the Document path of the literal (E-9, E-11).
     *
     * @param path the Document path of the integer
     * @param max  the maximum number of digits
     * @return the exception to throw
     */
    static DocumentParseException error(String path, int max) {
        return new DocumentParseException(path, CODE,
                path + ": integer literal has more than " + max + " decimal digits (the limit counts the digits of the value)");
    }

    /**
     * Reports whether {@code value} has more than {@code max} decimal digits, sign excluded.
     *
     * @param value the integer
     * @param max   the maximum number of digits
     * @return {@code true} if the value is over the limit
     */
    static boolean exceeds(BigInteger value, int max) {
        int bits = value.bitLength();
        if (lowerBound(bits) > max) {
            return true;
        }
        if (bits < 64) {
            return Long.toString(Math.abs(value.longValue())).length() > max;
        }
        return value.abs().toString().length() > max;
    }

    /**
     * Reports whether {@code value} has more than {@code max} decimal digits, sign excluded.
     *
     * @param value the integer
     * @param max   the maximum number of digits
     * @return {@code true} if the value is over the limit
     */
    static boolean exceeds(long value, int max) {
        // Long.toString keeps the sign, which is not a digit; this also holds for Long.MIN_VALUE.
        return Long.toString(value).length() - (value < 0 ? 1 : 0) > max;
    }

    /**
     * Parses the digits of a positional literal without paying the cost of converting a value that
     * is over the limit: the number of decimal digits is bounded from below by the bit length before
     * anything is converted, so a hostile literal is refused in time linear in its length.
     *
     * @param digits the digits, with sign, radix prefix and underscores already removed (may carry
     *               leading zeros); must not be empty
     * @param radix  2, 8, 10 or 16
     * @param max    the maximum number of decimal digits of the value
     * @return the non-negative value, or {@code null} if it has more than {@code max} decimal digits
     */
    static BigInteger parse(String digits, int radix, int max) {
        int first = 0;
        while (first < digits.length() - 1 && digits.charAt(first) == '0') {
            first++;
        }
        String significant = digits.substring(first);
        if (radix == 10) {
            if (significant.length() > max) {
                return null;
            }
        } else {
            int bitsPerDigit = radix == 16 ? 4 : radix == 8 ? 3 : 1;
            // The top digit contributes at least one bit, so the value has at least this many bits.
            long minBits = (long) (significant.length() - 1) * bitsPerDigit + 1;
            if (!"0".equals(significant) && lowerBound(minBits) > max) {
                return null;
            }
        }
        BigInteger value = new BigInteger(significant, radix);
        return exceeds(value, max) ? null : value;
    }

    /** A lower bound of the decimal digits of a value of {@code bits} bits (bits above zero). */
    private static long lowerBound(long bits) {
        return bits <= 1 ? 1 : (long) Math.floor((bits - 1) * LOG10_2_FLOOR) + 1;
    }
}
