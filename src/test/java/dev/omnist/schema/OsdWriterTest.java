package dev.omnist.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsdWriterTest {

    @Test
    @DisplayName("Full canonical and compact round-trip test across all types and cardinalities")
    void testFullRoundTrip() {
        Record dbRecord = new Record("Database", List.of(
                new Field("type", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("server", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("port", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1)
        ));

        Record serviceRecord = new Record("Service", List.of(
                new Field("host", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("port", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1),
                new Field("databases", new Type.Ref("Database"), 1, null),
                new Field("tags", new Type.Scalar(ScalarKind.STRING, false), 0, null),
                new Field("owner", new Type.Scalar(ScalarKind.STRING, true), 0, 1),
                new Field("payload", Type.Any.INSTANCE, 1, 1)
        ));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("Database", dbRecord);
        records.put("Service", serviceRecord);
        Schema schema = new Schema("Service", records);

        String canonicalStr = OsdWriter.write(schema);
        Schema readBackCanonical = OsdReader.read(canonicalStr);
        assertEquals(schema, readBackCanonical, "Canonical OSD write round-trip failed");

        String compactStr = OsdWriter.writeCompact(schema);
        Schema readBackCompact = OsdReader.read(compactStr);
        assertEquals(schema, readBackCompact, "Compact OSD write round-trip failed");
    }

    @Test
    @DisplayName("Canonical output formatting (§5.9): [1,1] omitted, 4-space indent, trailing comma, root last")
    void testCanonicalFormattingRules() {
        Record r = new Record("R", List.of(
                new Field("default_field", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("custom_card", new Type.Scalar(ScalarKind.STRING, true), 0, 3)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        String written = OsdWriter.write(schema);

        // Check [1,1] omitted
        assertFalse(written.contains("[1,1]"), "[1,1] should be omitted in canonical writer output");
        assertTrue(written.contains("\"default_field\": string,"), "Default field should omit cardinality");

        // Check non-default cardinality min=0 max=3 formatted as [,3]
        assertTrue(written.contains("\"custom_card\" [,3]: string?,"), "Non-default cardinality min=0 max=3 should be written as [,3]");

        // Check 4-space indent
        assertTrue(written.contains("    \"default_field\""), "Fields must be indented with 4 spaces");

        // Check root last
        String[] lines = written.trim().split("\r?\n");
        assertEquals("root R", lines[lines.length - 1].trim(), "root declaration must be the last line");
    }

    @Test
    @DisplayName("All 5 non-default cardinality form spellings (§5.9) format correctly")
    void testCardinalityFormSpellings() {
        Record r = new Record("R", List.of(
                new Field("f1", new Type.Scalar(ScalarKind.STRING, false), 3, 3),
                new Field("f2", new Type.Scalar(ScalarKind.STRING, false), 1, 5),
                new Field("f3", new Type.Scalar(ScalarKind.STRING, false), 5, null),
                new Field("f4", new Type.Scalar(ScalarKind.STRING, false), 0, 5),
                new Field("f5", new Type.Scalar(ScalarKind.STRING, false), 0, null)
        ));
        Map<String, Record> records = new LinkedHashMap<>();
        records.put("R", r);
        Schema schema = new Schema("R", records);

        String written = OsdWriter.write(schema);

        assertTrue(written.contains("\"f1\" [3]: string,"), "[3,3] must format as [3]");
        assertTrue(written.contains("\"f2\" [1,5]: string,"), "[1,5] must format as [1,5]");
        assertTrue(written.contains("\"f3\" [5,]: string,"), "[5,unbounded] must format as [5,]");
        assertTrue(written.contains("\"f4\" [,5]: string,"), "[0,5] must format as [,5]");
        assertTrue(written.contains("\"f5\" [,]: string,"), "[0,unbounded] must format as [,]");

        Schema readBack = OsdReader.read(written);
        assertEquals(schema, readBack, "Cardinality spellings round-trip failed");
    }

    @Test
    @DisplayName("All 7 scalar kinds + nullable + any + refs round-trip")
    void testAllScalarKindsAndTypes() {
        Record targetRec = new Record("Target", List.of(
                new Field("dummy", new Type.Scalar(ScalarKind.BOOLEAN, false), 1, 1)
        ));

        Record allTypesRec = new Record("AllTypes", List.of(
                new Field("s", new Type.Scalar(ScalarKind.STRING, false), 1, 1),
                new Field("i", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1),
                new Field("n", new Type.Scalar(ScalarKind.NUMBER, false), 1, 1),
                new Field("b", new Type.Scalar(ScalarKind.BOOLEAN, false), 1, 1),
                new Field("d", new Type.Scalar(ScalarKind.DATE, false), 1, 1),
                new Field("t", new Type.Scalar(ScalarKind.TIME, false), 1, 1),
                new Field("dt", new Type.Scalar(ScalarKind.DATETIME, false), 1, 1),
                new Field("s_null", new Type.Scalar(ScalarKind.STRING, true), 1, 1),
                new Field("a", Type.Any.INSTANCE, 1, 1),
                new Field("ref", new Type.Ref("Target"), 1, 1)
        ));

        Map<String, Record> records = new LinkedHashMap<>();
        records.put("Target", targetRec);
        records.put("AllTypes", allTypesRec);
        Schema schema = new Schema("AllTypes", records);

        String written = OsdWriter.write(schema);
        Schema readBack = OsdReader.read(written);
        assertEquals(schema, readBack, "All scalar kinds & types round-trip failed");
    }
}
