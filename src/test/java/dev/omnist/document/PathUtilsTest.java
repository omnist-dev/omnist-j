package dev.omnist.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathUtilsTest {

    @Test
    @DisplayName("countLabels correctly tallies repeated and unique edge labels")
    void testCountLabels() {
        Node node = new Node(List.of(
            new Edge("a", new Scalar.StringScalar("1")),
            new Edge("b", new Scalar.StringScalar("2")),
            new Edge("a", new Scalar.StringScalar("3")),
            new Edge("a", new Scalar.StringScalar("4"))
        ));

        Map<String, Integer> counts = PathUtils.countLabels(node);
        assertEquals(3, counts.get("a"));
        assertEquals(1, counts.get("b"));
    }

    @Test
    @DisplayName("childPath appends [index] for every occurrence when totalCount > 1, omits when totalCount <= 1")
    void testChildPathFormatting() {
        // Root path "$" with repeated label
        assertEquals("$.item[0]", PathUtils.childPath("$", "item", 0, 2));
        assertEquals("$.item[1]", PathUtils.childPath("$", "item", 1, 2));

        // Root path "$" with single label
        assertEquals("$.single", PathUtils.childPath("$", "single", 0, 1));

        // Nested path "$.user" with repeated label
        assertEquals("$.user.email[0]", PathUtils.childPath("$.user", "email", 0, 3));
        assertEquals("$.user.email[1]", PathUtils.childPath("$.user", "email", 1, 3));
        assertEquals("$.user.email[2]", PathUtils.childPath("$.user", "email", 2, 3));

        // Nested path "$.user" with single label
        assertEquals("$.user.name", PathUtils.childPath("$.user", "name", 0, 1));

        // Empty parent path
        assertEquals("item[0]", PathUtils.childPath("", "item", 0, 2));
        assertEquals("item", PathUtils.childPath("", "item", 0, 1));
    }
}
