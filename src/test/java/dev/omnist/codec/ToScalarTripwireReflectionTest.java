package dev.omnist.codec;

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
        Method toScalar = codec.getDeclaredMethod("toScalar", Object.class);
        toScalar.setAccessible(true);
        try {
            return (Value) toScalar.invoke(null, value);
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

    @Test
    @DisplayName("TomlCodec.buildNode: budget guard and object-key-not-string")
    void tomlCodecBuildNodeBudgetAndKeyGuards() throws Exception {
        Method buildNode = TomlCodec.class.getDeclaredMethod(
            "buildNode", Object.class, String.class, int.class, int[].class);
        buildNode.setAccessible(true);

        int[] overBudget = new int[]{1_000_001};
        InvocationTargetException budgetThrown = assertThrows(InvocationTargetException.class,
            () -> buildNode.invoke(null, java.util.Map.of("k", "v"), "$", 0, overBudget));
        assertTrue(budgetThrown.getCause().getMessage().contains("too many nodes materialized"));

        java.util.Map<Object, Object> badMap = new java.util.LinkedHashMap<>();
        badMap.put(123, "value");
        int[] freshBudget = new int[]{0};
        InvocationTargetException keyThrown = assertThrows(InvocationTargetException.class,
            () -> buildNode.invoke(null, badMap, "$", 0, freshBudget));
        assertTrue(keyThrown.getCause().getMessage().contains("is not a string"));
    }
}
