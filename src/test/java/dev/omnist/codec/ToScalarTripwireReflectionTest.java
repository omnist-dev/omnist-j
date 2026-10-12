package dev.omnist.codec;

import dev.omnist.document.Limits;
import dev.omnist.document.Scalar.NumberScalar;
import dev.omnist.document.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based tests for JsonCodec/YamlCodec/TomlCodec's toScalar()
 * Float/BigDecimal (and YamlCodec's java.util.Date) branches.
 */
class ToScalarTripwireReflectionTest {

    private static Value invokeToScalar(Class<?> codec, Object value) throws Exception {
        return invokeToScalar(codec, value, Limits.DEFAULT);
    }

    @Test
    @DisplayName("TomlCodec.toScalar: an Integer or a Long over the configured digit limit is refused at the path (tomlj only produces a Long)")
    void tomlCodecIntegerAndLongOverTheLimit() throws Exception {
        Limits three = new Limits(200, 1_000_000, 3);
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.valueOf(999)), invokeToScalar(TomlCodec.class, 999, three));
        RuntimeException e = assertThrows(RuntimeException.class, () -> invokeToScalar(TomlCodec.class, 1000, three));
        assertEquals("document.limit.int-digits", ((dev.omnist.document.DocumentParseException) e).getCode());
        assertThrows(RuntimeException.class, () -> invokeToScalar(TomlCodec.class, 1000L, three));
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.valueOf(999)), invokeToScalar(TomlCodec.class, 999L, three));
    }

    private static Value invokeToScalar(Class<?> codec, Object value, Limits limits) throws Exception {
        // JSON and YAML: toScalar(value, path, limits); TOML also takes the marker of its wrapped integers.
        boolean toml = codec == TomlCodec.class;
        Method toScalar = toml
            ? codec.getDeclaredMethod("toScalar", Object.class, String.class, String.class, Limits.class)
            : codec.getDeclaredMethod("toScalar", Object.class, String.class, Limits.class);
        toScalar.setAccessible(true);
        try {
            return (Value) (toml
                ? toScalar.invoke(null, value, "$", "__marker__", limits)
                : toScalar.invoke(null, value, "$", limits));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    @Test
    @DisplayName("JsonCodec.toScalar: Float, BigDecimal, and BigInteger digit limit checks")
    void jsonCodecFloatAndBigDecimal() throws Exception {
        assertEquals(new NumberScalar(3.5), invokeToScalar(JsonCodec.class, 3.5f));
        assertEquals(new NumberScalar(3.5), invokeToScalar(JsonCodec.class, new BigDecimal("3.5")));
        Value exact = invokeToScalar(JsonCodec.class, new BigDecimal("42"));
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.valueOf(42)), exact);

        BigInteger bigPos = new BigInteger("9".repeat(4301));
        BigInteger bigNeg = new BigInteger("-" + "9".repeat(4301));
        assertThrows(RuntimeException.class, () -> invokeToScalar(JsonCodec.class, bigPos));
        assertThrows(RuntimeException.class, () -> invokeToScalar(JsonCodec.class, bigNeg));
    }

    @Test
    @DisplayName("YamlCodec.toScalar: Float, BigDecimal, Date, and BigInteger digit limit checks")
    void yamlCodecFloatBigDecimalAndDate() throws Exception {
        assertEquals(new NumberScalar(3.5), invokeToScalar(YamlCodec.class, 3.5f));
        assertEquals(new NumberScalar(3.5), invokeToScalar(YamlCodec.class, new BigDecimal("3.5")));
        Value exact = invokeToScalar(YamlCodec.class, new BigDecimal("42"));
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.valueOf(42)), exact);
        Value fromDate = invokeToScalar(YamlCodec.class, new java.util.Date(0));
        assertInstanceOf(dev.omnist.document.Scalar.DateTimeScalar.class, fromDate);

        BigInteger bigPos = new BigInteger("9".repeat(4301));
        BigInteger bigNeg = new BigInteger("-" + "9".repeat(4301));
        assertThrows(RuntimeException.class, () -> invokeToScalar(YamlCodec.class, bigPos));
        assertThrows(RuntimeException.class, () -> invokeToScalar(YamlCodec.class, bigNeg));
    }

    @Test
    @DisplayName("TomlCodec.toScalar: BigInteger, Integer, Float, and BigDecimal (both branches)")
    void tomlCodecBigIntegerIntegerFloatAndBigDecimal() throws Exception {
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.TEN), invokeToScalar(TomlCodec.class, BigInteger.TEN));
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.valueOf(7)), invokeToScalar(TomlCodec.class, 7));
        assertEquals(new NumberScalar(3.5), invokeToScalar(TomlCodec.class, 3.5f));
        assertEquals(new NumberScalar(3.5), invokeToScalar(TomlCodec.class, new BigDecimal("3.5")));
        Value exact = invokeToScalar(TomlCodec.class, new BigDecimal("42"));
        assertEquals(new dev.omnist.document.Scalar.IntegerScalar(BigInteger.valueOf(42)), exact);

        BigInteger bigPos = new BigInteger("9".repeat(4301));
        BigInteger bigNeg = new BigInteger("-" + "9".repeat(4301));
        assertThrows(RuntimeException.class, () -> invokeToScalar(TomlCodec.class, bigPos));
        assertThrows(RuntimeException.class, () -> invokeToScalar(TomlCodec.class, bigNeg));
    }

    @Test
    @DisplayName("Unsupported-type fallback throw in all three toScalar() implementations")
    void unsupportedTypeFallbackThrows() {
        Object unsupported = new Object();
        assertThrows(IllegalArgumentException.class, () -> invokeToScalar(JsonCodec.class, unsupported));
        assertThrows(IllegalArgumentException.class, () -> invokeToScalar(YamlCodec.class, unsupported));
        assertThrows(IllegalArgumentException.class, () -> invokeToScalar(TomlCodec.class, unsupported));
    }

    @Test
    @DisplayName("TomlCodec.toScalar: value == null")
    void tomlCodecNullValue() throws Exception {
        assertEquals(Value.NULL, invokeToScalar(TomlCodec.class, null));
    }
}
