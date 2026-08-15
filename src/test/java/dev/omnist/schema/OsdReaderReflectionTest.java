package dev.omnist.schema;

import dev.omnist.schema.OsdLexer.Token;
import dev.omnist.schema.OsdLexer.TokenType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based fault injection for peekToken()'s manufactured-EOF
 * fallback -- same rationale as OmlReaderReflectionTest's identical case.
 * OsdLexer always appends a real terminal EOF token, so no malformed OSD
 * text reaches the fallback through normal parsing; this forces the
 * over-consumed-index state directly, with zero production code changes.
 */
class OsdReaderReflectionTest {

    @Test
    @DisplayName("peekToken(): manufactured EOF fallback when index has advanced past the real terminal EOF token")
    void peekTokenReturnsManufacturedEofWhenIndexOverConsumed() throws Exception {
        OsdReader reader = new OsdReader("record R { \"a\": string } root R\n");

        Field tokensField = OsdReader.class.getDeclaredField("tokens");
        tokensField.setAccessible(true);
        java.util.List<Token> tokens = (java.util.List<Token>) tokensField.get(reader);

        Field indexField = OsdReader.class.getDeclaredField("index");
        indexField.setAccessible(true);
        indexField.set(reader, tokens.size() + 1);

        Method peekToken = OsdReader.class.getDeclaredMethod("peekToken");
        peekToken.setAccessible(true);
        Token result = (Token) peekToken.invoke(reader);

        assertEquals(TokenType.EOF, result.type());
        assertEquals("", result.text());
        assertEquals(-1, result.line());
        assertEquals(-1, result.col());
    }

    @Test
    @DisplayName("consumeToken(): index does not advance further once already at/past tokens.size()")
    void consumeTokenDoesNotAdvancePastEnd() throws Exception {
        OsdReader reader = new OsdReader("record R { \"a\": string } root R\n");

        Field tokensField = OsdReader.class.getDeclaredField("tokens");
        tokensField.setAccessible(true);
        java.util.List<Token> tokens = (java.util.List<Token>) tokensField.get(reader);

        Field indexField = OsdReader.class.getDeclaredField("index");
        indexField.setAccessible(true);
        indexField.set(reader, tokens.size() + 1);

        Method consumeToken = OsdReader.class.getDeclaredMethod("consumeToken");
        consumeToken.setAccessible(true);
        consumeToken.invoke(reader);

        assertEquals(tokens.size() + 1, indexField.get(reader));
    }
}
