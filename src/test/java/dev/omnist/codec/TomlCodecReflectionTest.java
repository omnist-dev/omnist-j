package dev.omnist.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tomlj.TomlParseResult;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based fault injection for TomlCodec's toMap()==null defensive
 * branch. No real TOML text has been found that reaches this through
 * Toml.parse() (tomlj's own declared contract never documents a null
 * return), so this constructs a minimal dynamic-proxy TomlParseResult stub
 * -- hasErrors() false, toMap() null, everything else unused -- and invokes
 * the extracted documentFromParseResult(TomlParseResult) directly. Zero
 * change to TomlCodec's public API; the extraction exists purely as a
 * reflection seam for this one branch.
 */
class TomlCodecReflectionTest {

    @Test
    @DisplayName("documentFromParseResult: toMap() returning null throws \"no document found\"")
    void toMapNullThrowsNoDocumentFound() throws Exception {
        TomlParseResult stub = (TomlParseResult) Proxy.newProxyInstance(
            TomlParseResult.class.getClassLoader(),
            new Class<?>[]{TomlParseResult.class},
            (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                case "hasErrors" -> false;
                case "toMap" -> null;
                case "toString" -> "stub-toml-parse-result";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            }
        );

        Method documentFromParseResult = TomlCodec.class.getDeclaredMethod("documentFromParseResult", TomlParseResult.class);
        documentFromParseResult.setAccessible(true);

        Exception thrown = assertThrows(Exception.class, () -> {
            try {
                documentFromParseResult.invoke(null, stub);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        });
        assertInstanceOf(RuntimeException.class, thrown);
        assertTrue(thrown.getMessage().contains("no document found"));
    }
}
