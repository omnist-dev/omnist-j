package dev.omnist;

import dev.omnist.cli.Cli;
import dev.omnist.codec.JsonCodec;
import dev.omnist.codec.TomlCodec;
import dev.omnist.codec.WriteReport;
import dev.omnist.codec.XmlCodec;
import dev.omnist.codec.YamlCodec;
import dev.omnist.document.*;
import dev.omnist.oml.OmlReader;
import dev.omnist.oml.OmlWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bug wave 0.7.1-alpha: OML fractions beyond six digits, CLI format with a schema for XML. */
class BugWave071Test {

    private static String frac(int digits) {
        return "123456789".substring(0, digits);
    }

    /** The fraction as the Document model holds it: LocalTime prints 3, 6 or 9 digits. */
    private static String held(int digits) {
        String f = frac(digits);
        int width = digits <= 3 ? 3 : digits <= 6 ? 6 : 9;
        return f + "0".repeat(width - digits);
    }

    private static Document timeDoc(String text) {
        return new Node(List.of(new Edge("t", new Scalar.TimeScalar(TimeValue.parse(text)))));
    }

    private static Document dateTimeDoc(String text) {
        return new Node(List.of(new Edge("t", new Scalar.DateTimeScalar(DateTimeValue.parse(text)))));
    }

    @Test
    @DisplayName("OML writer: 1 to 9 fraction digits always read back, exact up to six, else a string with every digit")
    void omlRoundTripAllFractionLengths() {
        for (int digits = 1; digits <= 9; digits++) {
            for (String offset : new String[]{"", "+01:00", "-05:30", "+00:00"}) {
                String time = "12:34:56." + frac(digits) + offset;
                String dt = "2026-01-02T" + time;
                for (Document doc : new Document[]{timeDoc(time), dateTimeDoc(dt)}) {
                    boolean exact = digits <= 6;
                    for (boolean compact : new boolean[]{false, true}) {
                        WriteReport rep = new WriteReport();
                        String text = compact ? OmlWriter.writeCompact(doc, rep) : OmlWriter.write(doc, rep);
                        Document back = OmlReader.read(text);
                        Object orig = ((Node) doc).edges().get(0).target();
                        Document got = (Document) ((Node) back).edges().get(0).target();
                        if (exact) {
                            assertEquals(orig, got, text);
                            assertTrue(rep.adjustments().isEmpty(), text);
                        } else {
                            assertInstanceOf(Scalar.StringScalar.class, got, text);
                            String s = ((Scalar.StringScalar) got).value();
                            assertTrue(s.contains("." + held(digits)), s);
                            assertEquals(1, rep.adjustments().size());
                            assertEquals("format.temporal-stringified", rep.adjustments().get(0).code());
                            assertEquals("$.t", rep.adjustments().get(0).path());
                        }
                        // The no-report overloads write the same text.
                        assertEquals(text, compact ? OmlWriter.writeCompact(doc) : OmlWriter.write(doc));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("OML writer reports the path of a nested and repeated stringified temporal")
    void omlReportPaths() {
        Node inner = new Node(List.of(
                new Edge("t", new Scalar.TimeScalar(TimeValue.parse("01:02:03.123456789"))),
                new Edge("t", new Scalar.TimeScalar(TimeValue.parse("01:02:03.123456")))));
        Node doc = new Node(List.of(new Edge("o", inner), new Edge("o", inner)));
        WriteReport rep = new WriteReport();
        OmlWriter.write(doc, rep);
        assertEquals(2, rep.adjustments().size());
        assertEquals("$.o[0].t[0]", rep.adjustments().get(0).path());
        assertEquals("$.o[1].t[0]", rep.adjustments().get(1).path());
        OmlWriter.writeCompact(doc, null);
    }

    @Test
    @DisplayName("every writer keeps all 1 to 9 fraction digits of an offset time and datetime")
    void everyWriterKeepsEveryDigit() {
        for (int digits = 1; digits <= 9; digits++) {
            String time = "12:34:56." + frac(digits) + "+01:00";
            String dt = "2026-01-02T" + time;
            for (Document doc : new Document[]{timeDoc(time), dateTimeDoc(dt)}) {
                String[] outs = {JsonCodec.write(doc), YamlCodec.write(doc), TomlCodec.write(doc), XmlCodec.write(doc)};
                for (String out : outs) {
                    assertTrue(out.contains("12:34:56." + held(digits) + "+01:00"), out);
                }
            }
        }
    }

    @Test
    @DisplayName("readers never alter offset times and datetimes or their fractions")
    void readersKeepTemporals() {
        for (int digits = 1; digits <= 9; digits++) {
            String time = "12:34:56." + frac(digits) + "+01:00";
            String dt = "2026-01-02T" + time;
            Document tomlDt = TomlCodec.read("t = " + dt + "\n");
            assertEquals(new Scalar.DateTimeScalar(DateTimeValue.parse(dt)), ((Node) tomlDt).edges().get(0).target());
            Document yamlDt = YamlCodec.read("t: " + dt + "\n");
            Object y = ((Node) yamlDt).edges().get(0).target();
            assertEquals(new Scalar.DateTimeScalar(DateTimeValue.parse(dt)), y, dt);
        }
        assertEquals(new Scalar.DateTimeScalar(DateTimeValue.parse("2026-01-02T12:00:00+01:00")),
                ((Node) TomlCodec.read("t = 2026-01-02T12:00:00+01:00\n")).edges().get(0).target());
    }

    private static int[] run(String[] args, String stdin, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        int code = Cli.run(args, new PrintStream(out), new PrintStream(err),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return new int[]{code};
    }

    @Test
    @DisplayName("CLI format --schema types XML input like convert and validate (E-4a)")
    void cliFormatSchemaTypesXml(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("s.osd");
        Files.writeString(schema, "record Root {\n  \"n\": integer,\n  \"s\": string,\n}\nrecord Doc { \"Root\": Root }\nroot Doc\n");
        String xml = "<Root><n>42</n><s>7</s></Root>";
        Path in = dir.resolve("d.xml");
        Files.writeString(in, xml);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(new String[]{"format", in.toString(), "--from", "xml", "--to", "json", "--schema", schema.toString()}, "", out, err)[0];
        assertEquals(0, code, err.toString());
        String typed = out.toString(StandardCharsets.UTF_8);
        assertTrue(typed.contains("\"n\":42"), typed);
        assertTrue(typed.contains("\"s\":\"7\""), typed);

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        run(new String[]{"convert", in.toString(), "--from", "xml", "--to", "json", "--schema", schema.toString()}, "", out2, err2);
        assertEquals(out2.toString(StandardCharsets.UTF_8), typed, "format and convert agree");

        ByteArrayOutputStream out3 = new ByteArrayOutputStream();
        run(new String[]{"format", in.toString(), "--from", "xml", "--to", "json"}, "", out3, new ByteArrayOutputStream());
        assertTrue(out3.toString(StandardCharsets.UTF_8).contains("\"n\":\"42\""), "no schema: still a string");

        // a schema file for a non-XML input is read and ignored
        Path oml = dir.resolve("d.oml");
        Files.writeString(oml, "a: 1\n");
        ByteArrayOutputStream out4 = new ByteArrayOutputStream();
        assertEquals(0, run(new String[]{"format", oml.toString(), "--schema", schema.toString()}, "", out4, new ByteArrayOutputStream())[0]);
    }
}
