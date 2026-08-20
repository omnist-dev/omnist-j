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
    @DisplayName("materialize: a NUMBER-typed field given a STRING value doesn't match the number<-integer rule")
    void testNumberFieldWithStringValueSkipsIntegerCoercionRule() {
        // Distinct from testUpgrades' integer->number case (targetKind == NUMBER &&
        // valueKind == INTEGER, both true): here targetKind == NUMBER is true but
        // valueKind == INTEGER is false (it's STRING), so the number<-integer rule's
        // whole condition is false and this falls through to the string-based
        // coercion attempts below, ultimately failing as inexact.
        String osd = "record R {\n" +
                     "  \"n\": number,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);
        Node input = new Node(List.of(new Edge("n", new StringScalar("not a number"))));
        ValidationException ex = assertThrows(ValidationException.class, () -> Materializer.materialize(input, schema));
        assertTrue(ex.getResult().diagnostics().stream().anyMatch(d -> d.code().equals("materialize.inexact-conversion") && d.path().equals("$.n")));
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
    @DisplayName("materialize checks cardinality against a bounded max, not just an unbounded min")
    void testCardinalityExceedsBoundedMax() {
        String osd = "record R {\n" +
                     "  \"tags\" [0,2]: string,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);

        // "tags" occurs 3 times, exceeding its [0,2] bound.
        Node input = new Node(List.of(
            new Edge("tags", new StringScalar("a")),
            new Edge("tags", new StringScalar("b")),
            new Edge("tags", new StringScalar("c"))
        ));

        ValidationException ex = assertThrows(ValidationException.class, () -> Materializer.materialize(input, schema));
        List<ValidationDiagnostic> diags = ex.getResult().diagnostics();
        assertTrue(diags.stream().anyMatch(d -> d.code().equals("validate.cardinality") && d.path().equals("$")
                && d.message().contains("expected [0,2]")));
    }

    @Test
    @DisplayName("materialize: an unbounded max field can never exceed cardinality, however many times it occurs")
    void testCardinalityUnboundedMaxNeverExceeds() {
        String osd = "record R {\n" +
                     "  \"tags\" [0,]: string,\n" +
                     "}\n" +
                     "root R\n";
        Schema schema = OsdReader.read(osd);

        Node input = new Node(List.of(
            new Edge("tags", new StringScalar("a")),
            new Edge("tags", new StringScalar("b")),
            new Edge("tags", new StringScalar("c"))
        ));

        Document result = Materializer.materialize(input, schema);
        assertNotNull(result);
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

    @Test
    @DisplayName("materializeType: budget guard rejects when the node count would exceed 1,000,000 (reflection)")
    void testBudgetGuardViaReflection() throws Exception {
        // Materializing over one million real nodes for one defensive line
        // would be a multi-second, multi-hundred-MB test; reflection invokes
        // the private method directly with a pre-seeded budget array instead,
        // with zero production code changes.
        Schema schema = OsdReader.read("record R { \"x\": string }\nroot R\n");
        Node doc = new Node(List.of(new Edge("x", new StringScalar("v"))));

        java.lang.reflect.Method materializeType = Materializer.class.getDeclaredMethod(
            "materializeType", Document.class, Schema.class, dev.omnist.schema.Type.class,
            String.class, int.class, int[].class, List.class);
        materializeType.setAccessible(true);

        int[] budget = new int[]{1_000_001};
        java.util.List<ValidationDiagnostic> diagnostics = new java.util.ArrayList<>();
        dev.omnist.schema.Type.Ref rootType = new dev.omnist.schema.Type.Ref(schema.root());

        java.lang.reflect.InvocationTargetException thrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> materializeType.invoke(null, doc, schema, rootType, "$", 0, budget, diagnostics));
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("too many nodes materialized"));
    }

    @Test
    @DisplayName("Issue #41: BigInteger to double conversion tests exact binary64 representability")
    void testBigIntegerToDoubleExactness() {
        Schema schema = OsdReader.read("record R { \"n\": number }\nroot R\n");

        // 2^53 - 1 is exact
        BigInteger bi2pow53Minus1 = BigInteger.valueOf(9007199254740991L);
        Node doc1 = new Node(List.of(new Edge("n", new IntegerScalar(bi2pow53Minus1))));
        Document out1 = Materializer.materialize(doc1, schema);
        assertEquals(9007199254740991.0, ((NumberScalar) ((Node) out1).edges().get(0).target()).value());

        // 2^53 is exact
        BigInteger bi2pow53 = BigInteger.valueOf(9007199254740992L);
        Node doc2 = new Node(List.of(new Edge("n", new IntegerScalar(bi2pow53))));
        Document out2 = Materializer.materialize(doc2, schema);
        assertEquals(9007199254740992.0, ((NumberScalar) ((Node) out2).edges().get(0).target()).value());

        // 2^53 + 1 is inexact in binary64 -> emits materialize.inexact-conversion
        BigInteger bi2pow53Plus1 = BigInteger.valueOf(9007199254740993L);
        Node doc3 = new Node(List.of(new Edge("n", new IntegerScalar(bi2pow53Plus1))));
        ValidationException ex3 = assertThrows(ValidationException.class, () -> Materializer.materialize(doc3, schema));
        assertEquals(1, ex3.getResult().diagnostics().size());
        assertEquals("materialize.inexact-conversion", ex3.getResult().diagnostics().get(0).code());

        // Max finite double is exact
        BigInteger biMaxDouble = new java.math.BigDecimal(Double.MAX_VALUE).toBigInteger();
        Node docMax = new Node(List.of(new Edge("n", new IntegerScalar(biMaxDouble))));
        Document outMax = Materializer.materialize(docMax, schema);
        assertEquals(Double.MAX_VALUE, ((NumberScalar) ((Node) outMax).edges().get(0).target()).value());

        // Overflow value (> Double.MAX_VALUE) is inexact
        BigInteger biOverflow = biMaxDouble.multiply(BigInteger.valueOf(2));
        Node docOver = new Node(List.of(new Edge("n", new IntegerScalar(biOverflow))));
        ValidationException exOver = assertThrows(ValidationException.class, () -> Materializer.materialize(docOver, schema));
        assertEquals(1, exOver.getResult().diagnostics().size());
        assertEquals("materialize.inexact-conversion", exOver.getResult().diagnostics().get(0).code());
    }
}
