package dev.omnist.codec;

import dev.omnist.document.DocumentParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** D-28: the digit limit counts the decimal digits of the value, sign excluded. */
class IntegerDigitsTest {

    @Test
    @DisplayName("exceeds(BigInteger): below 64 bits, at the thin band above the bit-length bound, and far above it")
    void bigIntegerBoundaries() {
        assertFalse(IntegerDigits.exceeds(BigInteger.ZERO, 1));
        assertFalse(IntegerDigits.exceeds(BigInteger.ONE, 1));
        assertFalse(IntegerDigits.exceeds(BigInteger.valueOf(999), 3));
        assertTrue(IntegerDigits.exceeds(BigInteger.valueOf(1000), 3));
        assertTrue(IntegerDigits.exceeds(BigInteger.valueOf(-1000), 3));
        // 20 digits (a 66-bit value) against a limit of 19 and 20 digits: the bit length alone says
        // "at least 19 digits", so the exact count decides
        BigInteger twentyDigits = BigInteger.TEN.pow(19);
        assertTrue(twentyDigits.bitLength() >= 64);
        assertTrue(IntegerDigits.exceeds(twentyDigits, 19));
        assertFalse(IntegerDigits.exceeds(twentyDigits, 20));
        BigInteger twentyOneDigits = BigInteger.TEN.pow(20);
        assertTrue(IntegerDigits.exceeds(twentyOneDigits, 20));
        assertFalse(IntegerDigits.exceeds(twentyOneDigits.subtract(BigInteger.ONE), 20));
        // far above: decided by the bit length, no conversion to decimal
        assertTrue(IntegerDigits.exceeds(BigInteger.ONE.shiftLeft(100_000), 4_300));
    }

    @Test
    @DisplayName("exceeds(long): the sign is not a digit, Long.MIN_VALUE is 19 digits")
    void longBoundaries() {
        assertFalse(IntegerDigits.exceeds(999L, 3));
        assertTrue(IntegerDigits.exceeds(1000L, 3));
        assertFalse(IntegerDigits.exceeds(-999L, 3));
        assertTrue(IntegerDigits.exceeds(-1000L, 3));
        assertTrue(IntegerDigits.exceeds(Long.MIN_VALUE, 18));
        assertFalse(IntegerDigits.exceeds(Long.MIN_VALUE, 19));
    }

    @Test
    @DisplayName("parse: the value of a literal, or null when it has more digits than the limit")
    void parseByValue() {
        assertEquals(BigInteger.valueOf(999), IntegerDigits.parse("999", 10, 3));
        assertNull(IntegerDigits.parse("1000", 10, 3));
        assertEquals(BigInteger.valueOf(999), IntegerDigits.parse("0000999", 10, 3));
        assertEquals(BigInteger.ZERO, IntegerDigits.parse("0", 10, 3));
        assertEquals(BigInteger.ZERO, IntegerDigits.parse("000", 16, 1));
        assertEquals(BigInteger.valueOf(999), IntegerDigits.parse("3E7", 16, 3));
        assertNull(IntegerDigits.parse("FFF", 16, 3));
        assertEquals(BigInteger.valueOf(999), IntegerDigits.parse("1747", 8, 3));
        assertNull(IntegerDigits.parse("1750", 8, 3));
        assertEquals(BigInteger.valueOf(999), IntegerDigits.parse("1111100111", 2, 3));
        assertNull(IntegerDigits.parse("1111101000", 2, 3));
        // refused from the length alone, without converting a 1 000 000-digit literal
        assertNull(IntegerDigits.parse("F".repeat(1_000_000), 16, 4_300));
        assertNull(IntegerDigits.parse("9".repeat(1_000_000), 10, 4_300));
    }

    @Test
    @DisplayName("error: document.limit.int-digits at the given path")
    void errorShape() {
        DocumentParseException e = IntegerDigits.error("$.a[1]", 3);
        assertEquals("document.limit.int-digits", e.getCode());
        assertEquals("$.a[1]", e.getPath());
    }
}
