package dev.omnist.oml;

import dev.omnist.schema.OsdLexer;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LexerPerformanceScalingTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("OmlLexer tokenizes 100,000 tokens linearly without quadratic substring overhead")
    void testOmlLexerLinearScaling() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25000; i++) {
            sb.append("field_").append(i).append(": 12345\n");
        }
        String input = sb.toString();
        OmlLexer lexer = new OmlLexer(input, null);
        List<OmlLexer.Token> tokens = lexer.tokenizeAll();
        // 25,000 * 4 tokens per line (IDENT, COLON, INTEGER, SEPARATOR) + EOF = 100,001 tokens
        assertEquals(100001, tokens.size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("OsdLexer and OsdReader scale linearly with 10,000 schema fields")
    void testOsdLexerAndReaderLinearScaling() {
        StringBuilder sb = new StringBuilder("root R\nrecord R {\n");
        for (int i = 0; i < 10000; i++) {
            sb.append("  \"field_").append(i).append("\": string,\n");
        }
        sb.append("}\n");
        String input = sb.toString();

        OsdLexer lexer = new OsdLexer(input);
        List<OsdLexer.Token> tokens = lexer.tokenizeAll();
        assertTrue(tokens.size() > 40000);

        Schema schema = OsdReader.read(input);
        assertNotNull(schema);
        assertEquals(10000, schema.records().get("R").fields().size());
    }
}
