package dev.omnist.algebra;

import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.Schema;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct reflection calls into reachable(schema, sat, rootOk) to pin down each
 * combination of rootOk and name.equals(schema.root()) independently of
 * satisfiableSet's own computed value for a given schema -- more precise than
 * driving this through prune() with a schema crafted to make rootOk land on a
 * specific value indirectly.
 */
class SchemaAlgebraReflectionTest {

    @Test
    @DisplayName("reachable: rootOk=true short-circuits !rootOk to false without evaluating name.equals(root)")
    void reachableRootOkTrueSkipsRootNameCheck() throws Exception {
        Record r = new Record("R", List.of(new Field("x", new Type.Scalar(ScalarKind.STRING, false), 1, 1)));
        Schema schema = new Schema("R", Map.of("R", r));

        Method reachable = SchemaAlgebra.class.getDeclaredMethod("reachable", Schema.class, Set.class, boolean.class);
        reachable.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) reachable.invoke(null, schema, Set.of("R"), true);
        assertEquals(Set.of("R"), result);
    }
}
