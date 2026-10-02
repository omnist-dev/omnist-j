package dev.omnist.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.codec.WriteAdjustment;
import dev.omnist.codec.WriteException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * omnist-spec v0.28.0-beta programmatic-schema rules: S-22, the S-8 path, S-23 (not applicable),
 * and OSD-16 / S-24. DIV-5: no conformance vector reaches any of these, so this class is their only pin.
 */
class SchemaV028Test {

    private static final Type STR = new Type.Scalar(ScalarKind.STRING, false);

    private static Field f(String label) {
        return new Field(label, STR, 1, 1);
    }

    private static void assertSchemaError(String code, String path, Runnable r) {
        SchemaException ex = assertThrows(SchemaException.class, r::run);
        assertEquals(code, ex.getCode());
        assertEquals(path, ex.getPath());
        assertNotNull(ex.getMessage());
    }

    // ---- S-22 ----

    @ParameterizedTest
    @ValueSource(strings = {"a\uD800", "\uDC00x", "x\uD800y", "\uDBFF\uDBFF", "\uDFFF", "ab\uDC80"})
    void s22LoneSurrogateLabelIsInvalidLabelAtRecordPath(String label) {
        assertSchemaError("schema.invalid-label", "Person", () -> new Record("Person", List.of(f("ok"), f(label))));
    }

    @Test
    void s22MessageAndPathNeverCarryTheLabel() {
        SchemaException ex = assertThrows(SchemaException.class, () -> new Record("R", List.of(f("zz\uD800zz"))));
        assertEquals("R", ex.getPath());
        assertFalse(ex.getPath().contains("zz"));
        assertFalse(ex.getMessage().contains("zz"));
        assertTrue(ex.getMessage().contains("D800"));
    }

    @Test
    void s22WellFormedSupplementaryAndBmpLabelsAreAccepted() {
        String supp = new String(Character.toChars(0x1F600));
        Record r = new Record("R", List.of(f(supp), f("￿"), f(""), f("café")));
        assertEquals(4, r.fields().size());
    }

    @Test
    void s22OsdReaderReportsLoneSurrogateStringInputAsOsdParseException() {
        OsdParseException ex = assertThrows(OsdParseException.class,
                () -> OsdReader.read("record R { \"a\uD800\": string }\nroot R\n"));
        assertEquals("schema.invalid-label", ex.getCode());
        assertEquals("R", ex.getPath());
    }

    // ---- S-8 (programmatic path is $, name in message only) ----

    private static final String[] BAD_NAMES = {"", "1a", "a b", "a-b", "café", "a.b", " a", "\uD800"};

    static Supplier<?>[] badNameConstructions() {
        return new Supplier<?>[] {
            () -> new Record("1a", List.of()),
            () -> new Record("a b", List.of()),
            () -> new Record("", List.of()),
            () -> new Record("café", List.of()),
            () -> new Record("a-b", List.of()),
            () -> new Type.Ref("1a"),
            () -> new Type.Ref(""),
            () -> new Type.Ref("a.b"),
            () -> new Type.Ref("café"),
            () -> new Schema("a b", Map.of()),
            () -> new Schema("", Map.of()),
            () -> new Schema("R", Map.of("1x", new Record("X", List.of()))),
        };
    }

    @ParameterizedTest
    @MethodSource("badNameConstructions")
    void s8InvalidNameIsReportedAtDollar(Supplier<?> construct) {
        assertSchemaError("schema.invalid-name", "$", construct::get);
    }

    @Test
    void s8NameAppearsInMessageOnly() {
        for (String bad : BAD_NAMES) {
            SchemaException ex = assertThrows(SchemaException.class, () -> new Type.Ref(bad));
            assertEquals("$", ex.getPath());
            assertTrue(ex.getMessage().contains("'" + bad.replace("\u00e9", "\\u00E9").replace("\uD800", "\\uD800") + "'"), bad);
        }
    }

    @Test
    void s8MessageEscapesNonPrintableCharactersInTheName() {
        SchemaException ex = assertThrows(SchemaException.class, () -> new Type.Ref("a\nb\r\u0000"));
        assertFalse(ex.getMessage().contains("\n"));
        assertFalse(ex.getMessage().contains("\r"));
        assertFalse(ex.getMessage().contains("\u0000"));
        assertTrue(ex.getMessage().contains("a\\u000Ab\\u000D\\u0000"));
        assertEquals("$", ex.getPath());
    }

    @Test
    void inferRecordNameMatrixMatchesPythonAndTypeScript() {
        String[][] cases = {{"123", "Rec"}, {"9", "Rec"}, {"\u65e5\u672c", "Rec"}, {"\u00e9clair", "Clair"},
            {"a b", "A_b"}, {"", "Rec"}};
        dev.omnist.document.Node child = new dev.omnist.document.Node(List.of(new dev.omnist.document.Edge("x",
                new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.ONE))));
        for (String[] c : cases) {
            dev.omnist.document.Node sample = new dev.omnist.document.Node(List.of(
                    new dev.omnist.document.Edge(c[0], child)));
            Schema s = SchemaAlgebra.infer(List.of(sample));
            assertTrue(s.records().containsKey(c[1]), c[0] + " -> " + s.records().keySet());
        }
    }

    @Test
    void s8ValidNamesAreAccepted() {
        for (String ok : new String[] {"R", "_x", "a1", "Abc_9", "_"}) {
            new Record(ok, List.of());
            new Type.Ref(ok);
            new Schema(ok, Map.of());
        }
    }

    @Test
    void s8InvalidNameInRefFieldTypeFailsAtDollarNotAtTheRecordPath() {
        assertSchemaError("schema.invalid-name", "$", () -> new Record("R", List.of(
                new Field("x", new Type.Ref("not valid"), 1, 1))));
    }

    @Test
    void s8InferWithMalformedRootNameFailsAtDollar() {
        dev.omnist.document.Node n = new dev.omnist.document.Node(List.of(
                new dev.omnist.document.Edge("a", new dev.omnist.document.Scalar.IntegerScalar(java.math.BigInteger.ONE))));
        assertSchemaError("schema.invalid-name", "$", () -> SchemaAlgebra.infer(List.of(n), "bad name", false));
    }

    // ---- S-23: Java's Schema API takes no caller-supplied record ordering ----

    @Test
    void s23NotApplicableSchemaOrderIsOnlyTheInsertionOrderOfTheRecordsMap() {
        // Schema(root, records) copies the map in iteration order; there is no separate ordering
        // parameter naming records, so "an ordering entry naming a record absent from env" cannot arise.
        Map<String, Record> m = new LinkedHashMap<>();
        m.put("B", new Record("B", List.of()));
        m.put("A", new Record("A", List.of()));
        Schema s = new Schema("A", m);
        assertEquals(List.of("B", "A"), List.copyOf(s.records().keySet()));
        assertEquals(2, Schema.class.getRecordComponents().length);
    }

    // ---- OSD-16 / S-24 ----

    private static Schema schemaWithZeroMax() {
        Map<String, Record> m = new LinkedHashMap<>();
        m.put("Root", new Record("Root", List.of(f("keep"), new Field("never", STR, 0, 0))));
        return new Schema("Root", m);
    }

    private static void assertOsd16(Runnable write) {
        WriteException ex = assertThrows(WriteException.class, write::run);
        List<WriteAdjustment> adj = ex.report().adjustments();
        assertEquals(1, adj.size());
        assertEquals("write.unsupported-value", adj.get(0).code());
        assertEquals("Root", adj.get(0).path());
        assertEquals("error", adj.get(0).severity());
    }

    @Test
    void osd16CanonicalWriterFailsOnMaxZeroAtRecordPath() {
        assertOsd16(() -> OsdWriter.write(schemaWithZeroMax()));
    }

    @Test
    void osd16CompactWriterFailsOnMaxZeroAtRecordPath() {
        assertOsd16(() -> OsdWriter.writeCompact(schemaWithZeroMax()));
    }

    @Test
    void osd16MaxZeroIsStillRepresentableInTheModel() {
        Field never = new Field("never", STR, 0, 0);
        assertEquals(0, never.max());
    }

    @Test
    void osd16UnboundedAndOtherCardinalitiesStillWrite() {
        Map<String, Record> m = new LinkedHashMap<>();
        m.put("Root", new Record("Root", List.of(
                new Field("a", STR, 0, null), new Field("b", STR, 0, 1), new Field("c", STR, 1, 3))));
        String text = OsdWriter.write(new Schema("Root", m));
        assertEquals(new Schema("Root", m), OsdReader.read(text));
    }

    @Test
    void s24PruneDropsMaxZeroSoPruneThenWriteSucceeds() {
        Schema pruned = SchemaAlgebra.prune(schemaWithZeroMax());
        assertNoMaxZero(pruned);
        assertEquals(List.of("keep"), pruned.records().get("Root").fields().stream().map(Field::label).toList());
        assertEquals(pruned, OsdReader.read(OsdWriter.write(pruned)));
    }

    @Test
    void s24NormalizeAndExtractOutputNeverContainMaxZero() {
        Schema s = schemaWithZeroMax();
        assertNoMaxZero(SchemaAlgebra.extract(s, Set.of("keep", "never")));
        assertNoMaxZero(SchemaAlgebra.normalize(SchemaAlgebra.prune(s)));
    }

    private static void assertNoMaxZero(Schema s) {
        for (Record r : s.records().values()) {
            for (Field fld : r.fields()) {
                assertFalse(fld.max() != null && fld.max() == 0, r.name() + "." + fld.label());
            }
        }
    }
}
