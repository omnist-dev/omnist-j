package dev.omnist.validation;

import dev.omnist.document.*;
import dev.omnist.document.Scalar.*;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MaterializerTest {

    @Test
    @DisplayName("materialize performs value-exact upgrades successfully")
    void testUpgrades() {
        String osd = "record R {\n" +
                     "  \"d\": date,\n" +
                     "  \"t\": time,\n" +
                     "  \"dt\": datetime,\n" +
                     "  \"i\": integer,\n" +
                     "  \"n\": number,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);

        Node input = new Node(List.of(
            new Edge("d", new StringScalar("2024-01-01")),
            new Edge("t", new StringScalar("12:30:00")),
            new Edge("dt", new StringScalar("2024-01-01T12:30:00")),
            new Edge("i", new NumberScalar(1.0)),
            new Edge("n", new IntegerScalar(BigInteger.valueOf(42)))
        ));

        Document out = Materializer.materialize(input, schema);
        assertTrue(out instanceof Node);
        Node outNode = (Node) out;

        assertEquals(5, outNode.edges().size());

        Edge ed = outNode.edges().get(0);
        assertTrue(ed.target() instanceof DateScalar);
        assertEquals(LocalDate.of(2024, 1, 1), ((DateScalar) ed.target()).value());

        Edge et = outNode.edges().get(1);
        assertTrue(et.target() instanceof TimeScalar);
        assertEquals(12, ((TimeScalar) et.target()).value().time().getHour());
        assertEquals(30, ((TimeScalar) et.target()).value().time().getMinute());

        Edge edt = outNode.edges().get(2);
        assertTrue(edt.target() instanceof DateTimeScalar);
        assertEquals(2024, ((DateTimeScalar) edt.target()).value().dateTime().getYear());
        assertEquals(1, ((DateTimeScalar) edt.target()).value().dateTime().getMonthValue());
        assertEquals(1, ((DateTimeScalar) edt.target()).value().dateTime().getDayOfMonth());
        assertEquals(12, ((DateTimeScalar) edt.target()).value().dateTime().getHour());
        assertEquals(30, ((DateTimeScalar) edt.target()).value().dateTime().getMinute());

        Edge ei = outNode.edges().get(3);
        assertTrue(ei.target() instanceof IntegerScalar);
        assertEquals(BigInteger.ONE, ((IntegerScalar) ei.target()).value());

        Edge en = outNode.edges().get(4);
        assertTrue(en.target() instanceof NumberScalar);
        assertEquals(42.0, ((NumberScalar) en.target()).value());
    }

    @Test
    @DisplayName("materialize rejects inexact conversions")
    void testRejections() {
        String osd = "record R {\n" +
                     "  \"i\": integer,\n" +
                     "  \"b\": boolean,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);

        // String "1" to integer -> inexact
        Node input1 = new Node(List.of(
            new Edge("i", new StringScalar("1"))
        ));
        ValidationException ex1 = assertThrows(ValidationException.class, () -> Materializer.materialize(input1, schema));
        assertTrue(ex1.getResult().diagnostics().stream().anyMatch(d -> d.code().equals("materialize.inexact-conversion") && d.path().equals("$.i")));

        // Float 1.5 to integer -> inexact
        Node input2 = new Node(List.of(
            new Edge("i", new NumberScalar(1.5))
        ));
        ValidationException ex2 = assertThrows(ValidationException.class, () -> Materializer.materialize(input2, schema));
        assertTrue(ex2.getResult().diagnostics().stream().anyMatch(d -> d.code().equals("materialize.inexact-conversion") && d.path().equals("$.i")));

        // String "maybe" to boolean -> inexact
        Node input3 = new Node(List.of(
            new Edge("b", new StringScalar("maybe"))
        ));
        ValidationException ex3 = assertThrows(ValidationException.class, () -> Materializer.materialize(input3, schema));
        assertTrue(ex3.getResult().diagnostics().stream().anyMatch(d -> d.code().equals("materialize.inexact-conversion") && d.path().equals("$.b")));
    }

    @Test
    @DisplayName("materialize checks cardinality and unexpected fields")
    void testUnexpectedFieldAndCardinality() {
        String osd = "record R {\n" +
                     "  \"n\": integer,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);

        // n occurs 0 times, but expected 1 time (min=1), plus unexpected field "extra"
        Node input = new Node(List.of(
            new Edge("extra", new StringScalar("val"))
        ));

        ValidationException ex = assertThrows(ValidationException.class, () -> Materializer.materialize(input, schema));
        List<ValidationDiagnostic> diags = ex.getResult().diagnostics();

        assertTrue(diags.stream().anyMatch(d -> d.code().equals("validate.cardinality") && d.path().equals("$")));
        assertTrue(diags.stream().anyMatch(d -> d.code().equals("validate.unexpected-field") && d.path().equals("$.extra")));
    }

    @Test
    @DisplayName("materialize stops upgrading at an any boundary")
    void testAnyBoundary() {
        String osd = "record R {\n" +
                     "  \"data\": any,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);

        Node inner = new Node(List.of(
            new Edge("x", new StringScalar("2024-01-01"))
        ));
        Node input = new Node(List.of(
            new Edge("data", inner)
        ));

        Document out = Materializer.materialize(input, schema);
        assertTrue(out instanceof Node);
        Node outNode = (Node) out;
        Node outInner = (Node) outNode.edges().get(0).target();

        // The date-like string inside 'any' must remain a StringScalar, not upgraded
        assertTrue(outInner.edges().get(0).target() instanceof StringScalar);
        assertEquals("2024-01-01", ((StringScalar) outInner.edges().get(0).target()).value());
    }
}
