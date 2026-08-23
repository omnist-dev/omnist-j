package dev.omnist.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OsdReaderTest {

    @Test
    @DisplayName("Valid OSD schema with multiple records, root, scalar kinds, references, any, and cardinalities")
    void testValidOsdSchema() {
        String osd = """
            # Service topology
            record Database {
                "type":            string,
                "server":          string,
                "port":            integer,
            }
            record Service {
                "host":            string,          # cardinality [1,1] by default
                "port":            integer,
                "databases" [1,]:  Database,        # at least one
                "tags" [0,]:       string,          # any count, including zero
                "owner" [0,1]:     string?,         # may be absent, may be null
                "payload":         any,             # one declared opening
            }
            root Service
            """;

        Schema schema = OsdReader.read(osd);
        assertNotNull(schema);
        assertEquals("Service", schema.root());
        assertEquals(2, schema.records().size());

        Record serviceRec = schema.records().get("Service");
        assertNotNull(serviceRec);
        assertEquals(6, serviceRec.fields().size());

        // 1. host
        Field hostF = serviceRec.fields().get(0);
        assertEquals("host", hostF.label());
        assertEquals(new Type.Scalar(ScalarKind.STRING, false), hostF.type());
        assertEquals(1, hostF.min());
        assertEquals(1, hostF.max());

        // 3. databases
        Field dbF = serviceRec.fields().get(2);
        assertEquals("databases", dbF.label());
        assertEquals(new Type.Ref("Database"), dbF.type());
        assertEquals(1, dbF.min());
        assertNull(dbF.max(), "Unbounded max should be null");

        // 4. tags
        Field tagsF = serviceRec.fields().get(3);
        assertEquals("tags", tagsF.label());
        assertEquals(new Type.Scalar(ScalarKind.STRING, false), tagsF.type());
        assertEquals(0, tagsF.min());
        assertNull(tagsF.max());

        // 5. owner
        Field ownerF = serviceRec.fields().get(4);
        assertEquals("owner", ownerF.label());
        assertEquals(new Type.Scalar(ScalarKind.STRING, true), ownerF.type());
        assertEquals(0, ownerF.min());
        assertEquals(1, ownerF.max());

        // 6. payload
        Field payloadF = serviceRec.fields().get(5);
        assertEquals("payload", payloadF.label());
        assertEquals(Type.Any.INSTANCE, payloadF.type());
        assertEquals(1, payloadF.min());
        assertEquals(1, payloadF.max());
    }

    @Test
    @DisplayName("All 5 cardinality bracket syntax forms parse correctly")
    void testCardinalitySyntaxForms() {
        String osd = """
            record R {
                "f1" [3]: string,
                "f2" [1,5]: string,
                "f3" [5,]: string,
                "f4" [,5]: string,
                "f5" [,]: string,
            }
            root R
            """;

        Schema schema = OsdReader.read(osd);
        Record r = schema.records().get("R");

        // [3] -> (3, 3)
        assertEquals(3, r.fields().get(0).min());
        assertEquals(3, r.fields().get(0).max());

        // [1,5] -> (1, 5)
        assertEquals(1, r.fields().get(1).min());
        assertEquals(5, r.fields().get(1).max());

        // [5,] -> (5, unbounded)
        assertEquals(5, r.fields().get(2).min());
        assertNull(r.fields().get(2).max());

        // [,5] -> (0, 5)
        assertEquals(0, r.fields().get(3).min());
        assertEquals(5, r.fields().get(3).max());

        // [,] -> (0, unbounded)
        assertEquals(0, r.fields().get(4).min());
        assertNull(r.fields().get(4).max());
    }

    @Test
    @DisplayName("OSD simplified string unescaping (§5.3.1): \\n becomes letter n")
    void testOsdStringUnescaping() {
        String osd = """
            record R {
                "a\\nb": string,
                "a\\"b": string,
                "a\\\\b": string,
            }
            root R
            """;

        Schema schema = OsdReader.read(osd);
        Record r = schema.records().get("R");

        assertEquals("anb", r.fields().get(0).label(), "\\n becomes letter n in OSD");
        assertEquals("a\"b", r.fields().get(1).label());
        assertEquals("a\\b", r.fields().get(2).label());
    }

    @Test
    @DisplayName("Error cases produce OsdParseException with position info")
    void testErrorCases() {
        // Bare label
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { a: string } root R"));

        // Quoted string in type position
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\": \"string\" } root R"));

        // Reserved scalar record name
        assertThrows(OsdParseException.class, () -> OsdReader.read("record string { \"a\": string } root string"));

        // Reserved any record name
        assertThrows(OsdParseException.class, () -> OsdReader.read("record any { \"a\": string } root any"));

        // any? type error
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\": any? } root R"));

        // Ref? type error
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\": Other? } root R"));

        // [] empty cardinality
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\" []: string } root R"));

        // [-1] negative minimum
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\" [-1]: string } root R"));

        // [1,0] inverted range
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\" [1,0]: string } root R"));

        // [1.5] decimal bound
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\" [1.5]: string } root R"));

        // Missing root
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\": string }"));

        // Duplicate root
        OsdParseException dupRoot = assertThrows(OsdParseException.class,
            () -> OsdReader.read("record R { \"a\": string } root R root R"));
        assertEquals("schema.duplicate-root", dupRoot.getCode());
        assertEquals("$", dupRoot.getPath());

        // Duplicate record definition
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\": string } record R { \"b\": string } root R"));

        // Unknown type reference
        assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"a\": NonExistent } root R"));
    }

    @Test
    @DisplayName("OsdLexer lexical error paths produce normative parse.* codes")
    void testLexerNormativeParseCodes() {
        // Line 146: Unexpected character -> parse.unexpected-token
        OsdParseException ex1 = assertThrows(OsdParseException.class, () -> OsdReader.read("record R { @ } root R"));
        assertEquals("parse.unexpected-token", ex1.getCode());
        assertEquals("$", ex1.getPath());

        // Line 174: Unterminated escape in string -> parse.unterminated-string
        OsdParseException ex2 = assertThrows(OsdParseException.class, () -> new OsdLexer("\"abc\\").tokenizeAll());
        assertEquals("parse.unterminated-string", ex2.getCode());
        assertEquals("$", ex2.getPath());

        // Line 183: Unterminated double-quoted string -> parse.unterminated-string
        OsdParseException ex3 = assertThrows(OsdParseException.class, () -> OsdReader.read("record R { \"unclosed: string } root R"));
        assertEquals("parse.unterminated-string", ex3.getCode());
        assertEquals("$", ex3.getPath());

        // Line 196: Unterminated bracket in cardinality -> parse.unexpected-token
        OsdParseException ex4 = assertThrows(OsdParseException.class, () -> new OsdLexer("record R { \"a\" [1,2").tokenizeAll());
        assertEquals("parse.unexpected-token", ex4.getCode());
        assertEquals("$", ex4.getPath());
    }

    @Test
    @DisplayName("OSD strings forbid literal control characters below U+0020 (issue #72, matching OML's existing rule)")
    void testOsdStringForbidsLiteralControlCharacter() {
        String withControlChar = "record R { \"a\": \"xy\" } root R";
        OsdParseException ex = assertThrows(OsdParseException.class, () -> OsdReader.read(withControlChar));
        assertEquals("parse.control-character", ex.getCode());
        assertEquals("$", ex.getPath());
    }
}
