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

    @Test
    @DisplayName("isEdgeListStart(): peekNonSeparatorToken(1) returning null when positioned exactly at the terminal EOF token")
    void isEdgeListStartHandlesNullSecondPeek() throws Exception {
        // OmlLexer's token list always ends with a real EOF token, which is itself
        // non-separator -- so peekNonSeparatorToken(1) normally finds either a real
        // next token or that EOF sentinel, never null, through any real parse.
        // Positioning `index` exactly at the EOF token itself means offset 0 finds
        // EOF as t1 and offset 1 finds nothing after it, forcing the null return
        // isEdgeListStart's t2 != null guards against.
        OmlReader reader = new OmlReader("a\n", null);

        Field tokensField = OmlReader.class.getDeclaredField("tokens");
        tokensField.setAccessible(true);
        java.util.List<Token> tokens = (java.util.List<Token>) tokensField.get(reader);

        Field indexField = OmlReader.class.getDeclaredField("index");
        indexField.setAccessible(true);
        indexField.set(reader, tokens.size() - 1);

        Method isEdgeListStart = OmlReader.class.getDeclaredMethod("isEdgeListStart");
        isEdgeListStart.setAccessible(true);
        assertEquals(false, isEdgeListStart.invoke(reader));
    }
}
