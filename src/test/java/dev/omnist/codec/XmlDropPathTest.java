package dev.omnist.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * omnist-j#124: the read-side drop reports of the XML reader (format.attribute-dropped,
 * format.namespace-dropped) carry the E-10 path of the element: the index appears on every edge of
 * a label that occurs more than once in a node (the first included), and a label that occurs once
 * stays bare (E-9: report paths are normative).
 */
class XmlDropPathTest {

    private static List<String> paths(String xml, String code) {
        WriteReport report = new WriteReport();
        XmlCodec.read(xml, null, report);
        return report.adjustments().stream().filter(a -> a.code().equals(code)).map(WriteAdjustment::path).toList();
    }

    @Test
    @DisplayName("repeated elements: each dropped attribute is reported at its own indexed path")
    void repeatedAttributePathsAreIndexed() {
        assertEquals(List.of("$.r.a[0]", "$.r.a[1]"),
                paths("<r><a x=\"1\"/><a x=\"2\"/></r>", "format.attribute-dropped"));
    }

    @Test
    @DisplayName("repeated elements: each dropped namespace prefix is reported at its own indexed path")
    void repeatedNamespacePathsAreIndexed() {
        assertEquals(List.of("$.r.a[0]", "$.r.a[1]", "$.r.a[2]"),
                paths("<r xmlns:n=\"urn:x\"><n:a/><n:a/><n:a/></r>", "format.namespace-dropped"));
    }

    @Test
    @DisplayName("a single element keeps the bare path")
    void singleElementPathIsBare() {
        assertEquals(List.of("$.r.a"), paths("<r><a x=\"1\"/></r>", "format.attribute-dropped"));
        assertEquals(List.of("$.r.a"), paths("<r xmlns:n=\"urn:x\"><n:a/></r>", "format.namespace-dropped"));
        assertEquals(List.of("$.r"), paths("<r x=\"1\"/>", "format.attribute-dropped"));
    }

    @Test
    @DisplayName("nested: the index of an ancestor is part of the path of its descendants")
    void nestedPathsCarryTheAncestorIndex() {
        assertEquals(List.of("$.r.a[0].b", "$.r.a[1].b[0]", "$.r.a[1].b[1]"),
                paths("<r><a><b x=\"1\"/></a><a><b x=\"1\"/><b x=\"2\"/></a></r>", "format.attribute-dropped"));
    }

    @Test
    @DisplayName("an element that repeats alongside a different label is indexed among its own label only")
    void indexCountsOnlyTheSameLabel() {
        assertEquals(List.of("$.r.a[0]", "$.r.a[1]"),
                paths("<r><a x=\"1\"/><b x=\"1\"/><a x=\"2\"/></r>", "format.attribute-dropped").stream()
                        .filter(p -> p.contains("a[")).toList());
        assertTrue(paths("<r><a x=\"1\"/><b x=\"1\"/><a x=\"2\"/></r>", "format.attribute-dropped").contains("$.r.b"));
    }

    @Test
    @DisplayName("a namespace prefix and an attribute on the same repeated element are both reported at the indexed path")
    void bothDropsAtTheSameIndexedPath() {
        WriteReport report = new WriteReport();
        XmlCodec.read("<r xmlns:n=\"urn:x\"><n:a x=\"1\"/><n:a x=\"2\"/></r>", null, report);
        List<String> pairs = report.adjustments().stream().map(a -> a.path() + "|" + a.code()).toList();
        assertTrue(pairs.contains("$.r.a[0]|format.namespace-dropped"), pairs.toString());
        assertTrue(pairs.contains("$.r.a[0]|format.attribute-dropped"), pairs.toString());
        assertTrue(pairs.contains("$.r.a[1]|format.namespace-dropped"), pairs.toString());
        assertTrue(pairs.contains("$.r.a[1]|format.attribute-dropped"), pairs.toString());
    }
}
