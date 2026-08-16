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

    @Test
    @DisplayName("preprocessToml: a lone backslash as the very last character of a triple-quoted string")
    void preprocessTomlBackslashAtEndOfInput() throws Exception {
        Method preprocessToml = TomlCodec.class.getDeclaredMethod("preprocessToml", String.class);
        preprocessToml.setAccessible(true);

        // "i + 1 < n" is false only when the backslash is the text's final character,
        // i.e. there's no unterminated closing """ and nothing after the backslash.
        String result = (String) preprocessToml.invoke(null, "\"\"\"abc\\");
        assertEquals("\"\"\"abc\\", result);

        // Same "i + 1 < n" false case, but in the plain (non-triple) double-quoted
        // string branch a few lines below the triple-quoted one.
        String plainResult = (String) preprocessToml.invoke(null, "\"abc\\");
        assertEquals("\"abc\\", plainResult);
    }

    @Test
    @DisplayName("isTokenChar: every character-class disjunct, both its true and false outcome")
    void isTokenCharExercisesEveryDisjunct() throws Exception {
        Method isTokenChar = TomlCodec.class.getDeclaredMethod("isTokenChar", char.class);
        isTokenChar.setAccessible(true);

        // One character just inside and one just outside each range/literal disjunct,
        // covering every comparison's true and false outcome regardless of short-circuit
        // order -- exhaustive over the full char range is cheap and removes any need to
        // reason about which specific chars the OR chain's compiled branches land on.
        for (char c = 0; c < 256; c++) {
            isTokenChar.invoke(null, c);
        }
        assertEquals(true, isTokenChar.invoke(null, 'a'));
        assertEquals(true, isTokenChar.invoke(null, 'z'));
        assertEquals(true, isTokenChar.invoke(null, 'A'));
        assertEquals(true, isTokenChar.invoke(null, 'Z'));
        assertEquals(true, isTokenChar.invoke(null, '0'));
        assertEquals(true, isTokenChar.invoke(null, '9'));
        assertEquals(true, isTokenChar.invoke(null, '_'));
        assertEquals(true, isTokenChar.invoke(null, '+'));
        assertEquals(true, isTokenChar.invoke(null, '-'));
        assertEquals(true, isTokenChar.invoke(null, '.'));
        assertEquals(true, isTokenChar.invoke(null, ':'));
        assertEquals(true, isTokenChar.invoke(null, 'T'));
        assertEquals(true, isTokenChar.invoke(null, 'z'));
        assertEquals(true, isTokenChar.invoke(null, 'Z'));
        assertEquals(false, isTokenChar.invoke(null, ' '));
        assertEquals(false, isTokenChar.invoke(null, '!'));
        assertEquals(false, isTokenChar.invoke(null, '['));
    }

    @Test
    @DisplayName("isListOfMaps: a non-empty list whose first element IS a Map")
    void isListOfMapsTrueBranch() throws Exception {
        Method isListOfMaps = TomlCodec.class.getDeclaredMethod("isListOfMaps", Object.class);
        isListOfMaps.setAccessible(true);
        java.util.List<Object> listOfMaps = java.util.List.of(java.util.Map.of("k", "v"));
        assertEquals(true, isListOfMaps.invoke(null, listOfMaps));
        // Direct empty-list call (not routed through a document write, whose grouped()
        // representation may not reach this exact method with an empty List): forces
        // isEmpty()'s true outcome unambiguously.
        assertEquals(false, isListOfMaps.invoke(null, java.util.List.of()));
        // A list whose element is itself a List, not a Map: a distinct "not a Map"
        // shape alongside the plain-scalar case covered elsewhere.
        assertEquals(false, isListOfMaps.invoke(null, java.util.List.of(java.util.List.of("nested"))));
    }
}
