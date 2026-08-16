package dev.omnist.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based fault injection for XmlCodec's two defensive branches that
 * no real input has been found to reach: requireRootElement's null-root check
 * (DocumentBuilder.parse's well-formedness contract guarantees a root element
 * for any successfully-parsed Document) and xmlText's {@code v == null} check
 * (callers always pass a non-null Value instance). Same dynamic-proxy pattern
 * as TomlCodecReflectionTest's toMapNullThrowsNoDocumentFound.
 */
class XmlCodecReflectionTest {

    @Test
    @DisplayName("requireRootElement: getDocumentElement() returning null throws \"no document element found\"")
    void requireRootElementNullThrowsNoDocumentFound() throws Exception {
        Document stub = (Document) Proxy.newProxyInstance(
            Document.class.getClassLoader(),
            new Class<?>[]{Document.class},
            (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                case "getDocumentElement" -> null;
                case "toString" -> "stub-document";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            }
        );

        Method requireRootElement = XmlCodec.class.getDeclaredMethod("requireRootElement", Document.class);
        requireRootElement.setAccessible(true);

        Exception thrown = assertThrows(Exception.class, () -> {
            try {
                requireRootElement.invoke(null, stub);
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        });
        assertInstanceOf(RuntimeException.class, thrown);
        assertTrue(thrown.getMessage().contains("no document element found"));
    }

    @Test
    @DisplayName("xmlText: a literal null argument returns the empty string, same as Value.NullValue")
    void xmlTextNullArgument() throws Exception {
        Method xmlText = XmlCodec.class.getDeclaredMethod("xmlText", Object.class);
        xmlText.setAccessible(true);
        assertEquals("", xmlText.invoke(null, new Object[]{null}));

        // xmlText's real caller (writeNode's check pass) never actually reaches
        // this method with a Value.NullValue instance -- that case is intercepted
        // one level up by its own "doc instanceof Value.NullValue" branch, which
        // reports the adjustment and returns before xmlText would ever be called.
        // Direct invocation with the real singleton exercises the instanceof
        // check's true outcome anyway, since it's real production code guarding
        // a real (if currently unreachable) precondition.
        assertEquals("", xmlText.invoke(null, dev.omnist.document.Value.NULL));
    }
}
