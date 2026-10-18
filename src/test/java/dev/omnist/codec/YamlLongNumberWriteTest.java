package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.IntegerScalar;
import dev.omnist.document.Scalar.StringScalar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DIV-48: a very long integer is written as a plain scalar, as the other ports write it. */
class YamlLongNumberWriteTest {

    private static final String DIGITS = "9".repeat(4300);

    @Test
    @DisplayName("DIV-48: a 4,300-digit integer is a plain scalar, not !!int and quoted")
    void longIntegerIsPlain() {
        Document doc = YamlCodec.read("n: " + DIGITS + "\n");
        assertTrue(((Node) doc).edges().get(0).target() instanceof IntegerScalar);
        String out = YamlCodec.write(doc);
        assertEquals("n: " + DIGITS + "\n", out);
        assertFalse(out.contains("!!int"));
        assertEquals(new BigInteger(DIGITS),
                ((IntegerScalar) ((Node) YamlCodec.read(out)).edges().get(0).target()).value());
    }

    @Test
    @DisplayName("DIV-48: a 4,300-digit string is quoted so it reads back as a string")
    void longDigitStringStaysString() {
        Document doc = YamlCodec.read("n: \"" + DIGITS + "\"\n");
        assertTrue(((Node) doc).edges().get(0).target() instanceof StringScalar);
        String out = YamlCodec.write(doc);
        assertTrue(((Node) YamlCodec.read(out)).edges().get(0).target() instanceof StringScalar);
    }
}
