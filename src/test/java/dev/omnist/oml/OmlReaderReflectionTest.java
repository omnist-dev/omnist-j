package dev.omnist.oml;

import dev.omnist.oml.OmlLexer.Token;
import dev.omnist.oml.OmlLexer.TokenType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based fault injection for peekToken()'s manufactured-EOF
 * fallback. OmlLexer always appends a real terminal EOF token, so no
 * malformed OML text reaches the fallback through normal parsing (see
 * FinalCoverageTest's malformed-input probes) -- this test forces the
 * over-consumed-index state directly instead, with zero production code
 * changes (private method/field access via reflection only).
 */
class OmlReaderReflectionTest {

    @Test
    @DisplayName("peekToken(): manufactured EOF fallback when index has advanced past the real terminal EOF token")
    void peekTokenReturnsManufacturedEofWhenIndexOverConsumed() throws Exception {
        OmlReader reader = new OmlReader("a: 1\n", null);

        Field tokensField = OmlReader.class.getDeclaredField("tokens");
        tokensField.setAccessible(true);
        java.util.List<Token> tokens = (java.util.List<Token>) tokensField.get(reader);

        Field indexField = OmlReader.class.getDeclaredField("index");
        indexField.setAccessible(true);
        // Force index one past the real terminal EOF token's own position.
        indexField.set(reader, tokens.size() + 1);

        Method peekToken = OmlReader.class.getDeclaredMethod("peekToken");
        peekToken.setAccessible(true);
        Token result = (Token) peekToken.invoke(reader);

        assertEquals(TokenType.EOF, result.type());
        assertEquals("", result.text());
        assertNull(result.value());
        assertEquals(-1, result.line());
        assertEquals(-1, result.col());
    }
}
