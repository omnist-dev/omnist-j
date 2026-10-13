package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.DocumentParseException;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.PathUtils;
import dev.omnist.document.Scalar.IntegerScalar;
import dev.omnist.document.Scalar.NumberScalar;
import dev.omnist.document.Scalar.StringScalar;
import dev.omnist.oml.OmlParseException;
import dev.omnist.oml.OmlReader;
import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import dev.omnist.validation.ValidationResult;
import dev.omnist.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The behaviour omnist-spec v0.35.0-beta adds: E-36, E-37, E-29, E-4a, C-12 and C-17. */
class SpecV035Test {

    private static Node root(Document d) {
        return (Node) d;
    }

    private static Object leaf(Node n, int i) {
        return n.edges().get(i).target();
    }

    // ---- E-36: Document path labels ----

    @Test
    @DisplayName("E-36: a label that needs it is bracket-quoted with the pinned escapes; others stay bare")
    void pathLabelQuoting() {
        assertEquals("$.name", PathUtils.child("$", "name"));
        assertEquals("$.a b", PathUtils.child("$", "a b"));
        assertEquals("$.\u00e9", PathUtils.child("$", "\u00e9"));
        assertEquals("$[\"\"]", PathUtils.child("$", ""));
        assertEquals("$[\"a.b\"]", PathUtils.child("$", "a.b"));
        assertEquals("$[\"a[0]\"]", PathUtils.child("$", "a[0]"));
        assertEquals("$[\"q\\\"x\"]", PathUtils.child("$", "q\"x"));
        assertEquals("$[\"a\\\\b\"]", PathUtils.child("$", "a\\b"));
        assertEquals("$[\"l1\\nl2\\r\\t\"]", PathUtils.child("$", "l1\nl2\r\t"));
        assertEquals("$[\"a\\u0000\\u001f\\u007f\\u0085\\u2028\\u2029\"]",
                PathUtils.child("$", "a\u0000\u001f\u007f\u0085\u2028\u2029"));
        assertEquals("$[\"\u00e9.\"]", PathUtils.child("$", "\u00e9."));
        assertEquals("$.a[\"b.c\"].d", PathUtils.child(PathUtils.child(PathUtils.child("$", "a"), "b.c"), "d"));
        assertEquals("$[\"a.b\"][1]", PathUtils.childPath("$", "a.b", 1, 2));
        assertEquals("$.item[0]", PathUtils.childPath("$", "item", 0, 2));
    }

    @Test
    @DisplayName("E-36: validate reports an undeclared field at its quoted Document path")
    void validatePathIsQuoted() {
        Schema schema = OsdReader.read("record R { \"x\": integer }\nroot R\n");
        Document doc = OmlReader.read("x: 1\n\"a.b\": 2\n\"a.b\": 3\n");
        ValidationResult result = Validator.validate(doc, schema);
        assertEquals(List.of("$[\"a.b\"][0]", "$[\"a.b\"][1]"),
                result.diagnostics().stream().map(d -> d.path()).toList());
    }

    @Test
    @DisplayName("E-36: a codec's own limit and syntax paths quote a label too")
    void codecPathIsQuoted() {
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> JsonCodec.read("{\"a.b\": [[1]]}"));
        assertEquals("document.unlabeled-element", e.getCode());
        assertTrue(e.getPath().startsWith("$[\"a.b\"]"), e.getPath());
    }

    // ---- E-37: temporals ----

    private static Document timeDoc(String oml) {
        return OmlReader.read(oml);
    }

    @Test
    @DisplayName("E-37: TOML writes a time with its seconds, a datetime with its T, and keeps the offset")
    void tomlTemporals() {
        assertEquals("a = 12:00:00", TomlCodec.write(timeDoc("a: 12:00\n")));
        assertEquals("a = 2024-01-15T12:00:00", TomlCodec.write(timeDoc("a: 2024-01-15T12:00\n")));
        assertEquals("a = 2024-01-15T12:00:00+01:00", TomlCodec.write(timeDoc("a: 2024-01-15T12:00:00+01:00\n")));
        assertEquals("a = 2024-01-15T12:00:00Z", TomlCodec.write(timeDoc("a: 2024-01-15T12:00:00+00:00\n")));
        assertEquals("a = 12:00:00.123456", TomlCodec.write(timeDoc("a: 12:00:00.123456\n")));
    }

    @Test
    @DisplayName("E-37: a TOML time with an offset is a quoted string with the offset, and is reported")
    void tomlOffsetTime() {
        WriteReport report = new WriteReport();
        String text = TomlCodec.write(timeDoc("a: 12:00+01:00\n"), false, report);
        assertEquals("a = \"12:00:00+01:00\"", text);
        assertEquals(1, report.adjustments().size());
        assertEquals("$.a", report.adjustments().get(0).path());
        assertEquals("format.temporal-stringified", report.adjustments().get(0).code());
        assertEquals("warning", report.adjustments().get(0).severity());
        assertEquals(1, TomlCodec.check(timeDoc("a: 12:00+01:00\n")).adjustments().size());
        assertTrue(TomlCodec.check(timeDoc("a: 12:00\n")).adjustments().isEmpty());
    }

    @Test
    @DisplayName("E-37: YAML double-quotes a time, with its seconds and offset, and reports it")
    void yamlTime() {
        WriteReport report = new WriteReport();
        assertEquals("a: \"12:00:00\"\n", YamlCodec.write(timeDoc("a: 12:00\n"), false, report));
        assertEquals("format.temporal-stringified", report.adjustments().get(0).code());
        assertEquals("a: \"12:00:00+01:00\"\n", YamlCodec.write(timeDoc("a: 12:00:00+01:00\n")));
        assertEquals("a: \"12:00:00.500\"\n", YamlCodec.write(timeDoc("a: 12:00:00.5\n")));
        assertEquals("a: 2024-01-15T12:00:00+01:00\n", YamlCodec.write(timeDoc("a: 2024-01-15T12:00+01:00\n")));
    }

    @Test
    @DisplayName("E-37: XML writes a time with its offset and reports it")
    void xmlTimeKeepsOffset() {
        Document doc = timeDoc("r: {\n a: 12:00+01:00\n}\n");
        WriteReport report = new WriteReport();
        assertTrue(XmlCodec.write(doc, false, report).contains("<a>12:00:00+01:00</a>"));
        assertEquals("$.r.a", report.adjustments().get(0).path());
    }

    // ---- E-29: lone CR in OML ----

    @Test
    @DisplayName("E-29: a lone CR outside a string and a comment is unexpected-token at the CR")
    void loneCr() {
        assertCr("a: 1\r}\n", 1, 5);
        assertCr("a:\r1\n", 1, 3);
        assertCr("a: 1\rb: 2\n", 1, 5);
        assertCr("\ra: 1\n", 1, 1);
        assertCr("a: 1\nb: 2\r}\n", 2, 5);
        assertCr("# c\n\ra: 1\n", 2, 1);
        assertCr("a: 1\n\r\nb: 2\n\r", 4, 1);
    }

    private static void assertCr(String text, int line, int col) {
        OmlParseException e = assertThrows(OmlParseException.class, () -> OmlReader.read(text));
        assertEquals("parse.unexpected-token", e.getCode());
        assertEquals(line, e.getLine());
        assertEquals(col, e.getColumn());
    }

    @Test
    @DisplayName("E-29: a lone CR inside a comment is comment text; CRLF is a line break")
    void crInCommentAndCrlf() {
        Node n = root(OmlReader.read("a: 1 # c\rb: }\n"));
        assertEquals(1, n.edges().size());
        assertEquals(2, root(OmlReader.read("a: 1\r\nb: 2\r\n")).edges().size());
        assertEquals(2, root(OmlReader.read("a: 1 # c\r\nb: 2\r\n")).edges().size());
        OmlParseException e = assertThrows(OmlParseException.class, () -> OmlReader.read("a: \"x\ry\"\n"));
        assertEquals("parse.control-character", e.getCode());
    }

    // ---- C-17, C-12 (YAML) ----

    @Test
    @DisplayName("C-17: an exponent needs a point and a sign; the YAML 1.1 spellings resolve")
    void yamlNumberSpellings() {
        Node n = root(YamlCodec.read("a: 1e3\nb: 1.0e3\nc: 1.0e+3\nd: .5\ne: 5.\nf: 1:30.5\ng: 1:30\nh: 010\ni: 0o17\nj: 08\nk: 0:5\n"
                + "l: -.5\nm: 1_0.5\nn: .inf\no: -.inf\np: 2.5E-2\nq: 1.e+2\n"));
        assertEquals(new StringScalar("1e3"), leaf(n, 0));
        assertEquals(new StringScalar("1.0e3"), leaf(n, 1));
        assertEquals(new NumberScalar(1000.0), leaf(n, 2));
        assertEquals(new NumberScalar(0.5), leaf(n, 3));
        assertEquals(new NumberScalar(5.0), leaf(n, 4));
        assertEquals(new NumberScalar(90.5), leaf(n, 5));
        assertEquals(new IntegerScalar(BigInteger.valueOf(90)), leaf(n, 6));
        assertEquals(new IntegerScalar(BigInteger.valueOf(8)), leaf(n, 7));
        assertEquals(new StringScalar("0o17"), leaf(n, 8));
        assertEquals(new StringScalar("08"), leaf(n, 9));
        assertEquals(new StringScalar("0:5"), leaf(n, 10));
        assertEquals(new StringScalar("-.5"), leaf(n, 11));
        assertEquals(new NumberScalar(10.5), leaf(n, 12));
        assertEquals(new NumberScalar(Double.POSITIVE_INFINITY), leaf(n, 13));
        assertEquals(new NumberScalar(Double.NEGATIVE_INFINITY), leaf(n, 14));
        assertEquals(new NumberScalar(0.025), leaf(n, 15));
        assertEquals(new NumberScalar(100.0), leaf(n, 16));
    }

    private static String sexagesimal(int groups, String tail) {
        return "a: 1" + ":30".repeat(groups) + tail + "\n";
    }

    @Test
    @DisplayName("C-17/D-28: a long sexagesimal integer is a coded error past the digit limit, never a crash")
    void yamlLongSexagesimal() {
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> YamlCodec.read(sexagesimal(2500, "")));
        assertEquals("document.limit.int-digits", e.getCode());
        assertEquals("$.a", e.getPath());
        // 1 + 40 groups is about 72 digits: an integer, signed or not
        Node ok = root(YamlCodec.read(sexagesimal(40, "") + "b: -" + "1" + ":30".repeat(40) + "\n"));
        IntegerScalar pos = (IntegerScalar) leaf(ok, 0);
        assertEquals(pos.value().negate(), ((IntegerScalar) leaf(ok, 1)).value());
        BigInteger expected = BigInteger.ONE;
        for (int i = 0; i < 40; i++) {
            expected = expected.multiply(BigInteger.valueOf(60)).add(BigInteger.valueOf(30));
        }
        assertEquals(expected, pos.value());
        // a long sexagesimal-looking text that is not one stays a string, however long
        Node s = root(YamlCodec.read(sexagesimal(2500, "x")));
        assertEquals(StringScalar.class, leaf(s, 0).getClass());
        Node f = root(YamlCodec.read("a: 1" + ":30".repeat(120) + ".5\n"));
        assertEquals(NumberScalar.class, leaf(f, 0).getClass());
    }

    @Test
    @DisplayName("C-12: the entries a merge key brings come first, then the mapping's own, wherever the key is")
    void yamlMergeKeyOrder() {
        Node n = root(YamlCodec.read("a: &a {x: 1, k: a}\nc:\n  p: 0\n  <<: *a\n  k: own\n  q: 9\n"));
        Node c = (Node) leaf(n, 1);
        assertEquals(List.of("x", "k", "p", "q"), c.edges().stream().map(Edge::label).toList());
        assertEquals(new StringScalar("own"), leaf(c, 1));
        Node d = root(YamlCodec.read("a: &a {x: 1}\nb: &b {y: 2}\nc:\n  z: 3\n  <<: *a\n  <<: *b\n"));
        assertEquals(List.of("x", "y", "z"), ((Node) leaf(d, 2)).edges().stream().map(Edge::label).toList());
    }

    // ---- E-4a: XML pretyping through the wrapper ----

    private static final Schema WRAPPED = OsdReader.read(
            "record R { \"n\": integer }\nrecord Root { \"r\": R }\nroot Root\n");

    @Test
    @DisplayName("E-4a: the XML document element is a field of the root record; its integer leaf is typed")
    void xmlPretypeThroughWrapper() {
        Node n = root(XmlCodec.read("<r><n>42</n></r>", WRAPPED));
        Node r = (Node) leaf(n, 0);
        assertEquals(new IntegerScalar(BigInteger.valueOf(42)), leaf(r, 0));
    }

    @Test
    @DisplayName("E-4a: an over-long integer leaf is refused at the leaf's Document path, indexed when repeated")
    void xmlPretypeDigitLimit() {
        String digits = "9".repeat(4301);
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> XmlCodec.read("<r><n>" + digits + "</n></r>", WRAPPED));
        assertEquals("document.limit.int-digits", e.getCode());
        assertEquals("$.r.n", e.getPath());
        Schema many = OsdReader.read("record R { \"n\" [1,]: integer }\nrecord Root { \"r\": R }\nroot Root\n");
        DocumentParseException e2 = assertThrows(DocumentParseException.class,
                () -> XmlCodec.read("<r><n>1</n><n>" + digits + "</n></r>", many));
        assertEquals("$.r.n[1]", e2.getPath());
        // 4300 digits is within the limit
        Node ok = root(XmlCodec.read("<r><n>" + "9".repeat(4300) + "</n></r>", WRAPPED));
        assertEquals(IntegerScalar.class, leaf((Node) leaf(ok, 0), 0).getClass());
    }

    // ---- the long-scalar resolver and the XML pretyping branches ----

    private static Object yamlValue(String scalar) {
        return leaf(root(YamlCodec.read("a: " + scalar + "\n")), 0);
    }

    private static String groups(String first, int n, String tail) {
        return first + ":30".repeat(n) + tail;
    }

    @Test
    @DisplayName("C-17: a long scalar is a sexagesimal number only where YAML 1.1 says so")
    void yamlLongSexagesimalForms() {
        assertEquals(IntegerScalar.class, yamlValue(groups("1", 100, "")).getClass());
        assertEquals(IntegerScalar.class, yamlValue(groups("+1", 100, "")).getClass());
        assertEquals(IntegerScalar.class, yamlValue(groups("-1", 100, "")).getClass());
        assertEquals(IntegerScalar.class, yamlValue(groups("1_0", 100, "")).getClass());
        assertEquals(NumberScalar.class, yamlValue(groups("1", 100, ".5")).getClass());
        assertEquals(NumberScalar.class, yamlValue(groups("0", 100, ".5_5")).getClass());
        // a sexagesimal integer cannot start with 0, and a long such text is a string
        assertEquals(StringScalar.class, yamlValue(groups("0", 100, "")).getClass());
        // a group of no digits, of three digits, or over 59
        assertEquals(StringScalar.class, yamlValue(groups("1", 100, "") + "::30" + ":30".repeat(100)).getClass());
        assertEquals(StringScalar.class, yamlValue(groups("1", 100, "") + ":300" + ":30".repeat(100)).getClass());
        assertEquals(StringScalar.class, yamlValue(groups("1", 100, "") + ":60" + ":30".repeat(100)).getClass());
        assertEquals(IntegerScalar.class, yamlValue(groups("1", 100, "") + ":59" + ":05".repeat(100)).getClass());
        // no first group, a first group followed by something else, text after the groups, a bad fraction
        assertEquals(StringScalar.class, yamlValue(groups("-", 100, "")).getClass());
        assertEquals(StringScalar.class, yamlValue(groups("12ab", 100, "")).getClass());
        assertEquals(StringScalar.class, yamlValue(groups("1", 100, "x")).getClass());
        assertEquals(StringScalar.class, yamlValue(groups("1", 100, ".5x")).getClass());
    }

    @Test
    @DisplayName("C-17: a long plain scalar is an integer, a float or a string by its spelling")
    void yamlLongPlainScalars() {
        assertEquals(IntegerScalar.class, yamlValue("1" + "0".repeat(300)).getClass());
        assertEquals(NumberScalar.class, yamlValue("1" + "0".repeat(300) + ".5").getClass());
        assertEquals(StringScalar.class, yamlValue("1" + "0".repeat(300) + "e3").getClass());
        assertEquals(StringScalar.class, yamlValue("x".repeat(300)).getClass());
    }

    @Test
    @DisplayName("E-4a: XML pretyping leaves a leaf the schema cannot type as the string it was read as")
    void xmlPretypeFallthrough() {
        Schema schema = OsdReader.read("record Inner { \"x\": integer }\n"
                + "record Root { \"flag\": boolean, \"n\": integer, \"d\": number, \"s\": string, \"any\": any,"
                + " \"inner\": Inner, \"m\": integer }\n"
                + "record Doc { \"root\": Root }\nroot Doc\n");
        Node root = (Node) leaf(root(XmlCodec.read("<root><flag>maybe</flag><n>abc</n><d>xyz</d><s>1</s><any>5</any>"
                + "<inner>text</inner><extra>1</extra><m><x>1</x></m></root>", schema)), 0);
        for (int i = 0; i < 5; i++) {
            assertEquals(StringScalar.class, leaf(root, i).getClass(), "field " + i);
        }
        assertEquals(StringScalar.class, leaf(root, 5).getClass());
        assertEquals(StringScalar.class, leaf(root, 6).getClass());
        assertEquals(Node.class, leaf(root, 7).getClass());
        // a root record whose field is `any`, and a schema whose root is not a record of the document
        Schema any = OsdReader.read("record Doc { \"root\": any }\nroot Doc\n");
        assertEquals(Node.class, leaf(root(XmlCodec.read("<root><n>1</n></root>", any)), 0).getClass());
    }

    @Test
    @DisplayName("PathUtils: a closing bracket alone, and a CR/LF mix around a separator")
    void miscBranches() {
        assertEquals("$[\"x]\"]", PathUtils.child("$", "x]"));
        // a field that names a record the schema does not hold is left as read
        dev.omnist.schema.Record doc = new dev.omnist.schema.Record("Doc", List.of(
                new dev.omnist.schema.Field("root", new dev.omnist.schema.Type.Ref("Missing"), 1, 1)));
        Schema dangling = new Schema("Doc", java.util.Map.of("Doc", doc));
        assertEquals(Node.class, leaf(root(XmlCodec.read("<root><n>1</n></root>", dangling)), 0).getClass());
        assertEquals(2, root(OmlReader.read("a: 1;\n \t\r\n; b: 2 ;\n")).edges().size());
        assertEquals(2, root(OmlReader.read("a: 1\n\r\n\tb: 2\n")).edges().size());
    }
}
