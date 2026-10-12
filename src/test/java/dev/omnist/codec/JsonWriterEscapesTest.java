package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar.StringScalar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** C-14: the JSON writer spells the hex digits of an escape in lowercase. */
class JsonWriterEscapesTest {


    // ---- C-14: lowercase hex digits in an escape ----

    @Test
    @DisplayName("C-14: the JSON writer spells a control character's escape with lowercase hex digits")
    void jsonWriterEscapesInLowercase() {
        Document doc = new Node(List.of(new Edge("a", new StringScalar("a\u001fb\u000bc\u0001"))));
        assertEquals("{\"a\":\"a\\u001fb\\u000bc\\u0001\"}", JsonCodec.write(doc));
    }

    @Test
    @DisplayName("C-14: text that merely spells an escape (a backslash and letters) is not rewritten")
    void jsonWriterLeavesSpelledEscapesAlone() {
        // the value is the six characters  \ u 0 0 1 F  : the writer escapes the backslash, the hex stays as given
        Document doc = new Node(List.of(new Edge("a", new StringScalar("\\u001F")), new Edge("\u001f\u0001", new StringScalar("x"))));
        assertEquals("{\"a\":\"\\\\u001F\",\"\\u001f\\u0001\":\"x\"}", JsonCodec.write(doc));
        assertEquals("{\n  \"a\" : \"\\u001f\"\n}", JsonCodec.write(
                new Node(List.of(new Edge("a", new StringScalar("\u001f")))), 2, false, null));
    }
}
