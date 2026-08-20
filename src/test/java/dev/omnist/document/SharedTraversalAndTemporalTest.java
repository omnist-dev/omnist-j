package dev.omnist.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SharedTraversalAndTemporalTest {

    @Test
    @DisplayName("DateTimeValue.parse correctly parses un-offset, UTC, and offset date-times")
    void testDateTimeValueParse() {
        assertThrows(NullPointerException.class, () -> DateTimeValue.parse(null));

        DateTimeValue unOffset = DateTimeValue.parse("2024-01-01T12:30:45");
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 30, 45), unOffset.dateTime());
        assertNull(unOffset.offset());

        DateTimeValue utcZ = DateTimeValue.parse("2024-01-01T12:30:45Z");
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 30, 45), utcZ.dateTime());
        assertEquals(ZoneOffset.UTC, utcZ.offset());

        DateTimeValue utcLowerZ = DateTimeValue.parse("2024-01-01T12:30:45z");
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 30, 45), utcLowerZ.dateTime());
        assertEquals(ZoneOffset.UTC, utcLowerZ.offset());

        DateTimeValue offsetPlus = DateTimeValue.parse("2024-01-01T12:30:45+05:30");
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 30, 45), offsetPlus.dateTime());
        assertEquals(ZoneOffset.of("+05:30"), offsetPlus.offset());

        DateTimeValue offsetMinus = DateTimeValue.parse("2024-01-01T12:30:45-08:00");
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 30, 45), offsetMinus.dateTime());
        assertEquals(ZoneOffset.of("-08:00"), offsetMinus.offset());
    }

    @Test
    @DisplayName("TimeValue.parse correctly parses un-offset, UTC, and offset times")
    void testTimeValueParse() {
        assertThrows(NullPointerException.class, () -> TimeValue.parse(null));

        TimeValue unOffset = TimeValue.parse("12:30:45");
        assertEquals(LocalTime.of(12, 30, 45), unOffset.time());
        assertNull(unOffset.offset());

        TimeValue utcZ = TimeValue.parse("12:30:45Z");
        assertEquals(LocalTime.of(12, 30, 45), utcZ.time());
        assertEquals(ZoneOffset.UTC, utcZ.offset());

        TimeValue utcLowerZ = TimeValue.parse("12:30:45z");
        assertEquals(LocalTime.of(12, 30, 45), utcLowerZ.time());
        assertEquals(ZoneOffset.UTC, utcLowerZ.offset());

        TimeValue offsetPlus = TimeValue.parse("12:30:45+05:30");
        assertEquals(LocalTime.of(12, 30, 45), offsetPlus.time());
        assertEquals(ZoneOffset.of("+05:30"), offsetPlus.offset());

        
        // Time with leading sign where colon is after sign
        assertThrows(Exception.class, () -> TimeValue.parse("+12:00"));

        TimeValue offsetMinus = TimeValue.parse("12:30:45-08:00");
        assertEquals(LocalTime.of(12, 30, 45), offsetMinus.time());
        assertEquals(ZoneOffset.of("-08:00"), offsetMinus.offset());
    }

    @Test
    @DisplayName("PathUtils.groupEdges groups repeated and single edges correctly")
    void testGroupEdges() {
        // Scalar passthrough
        assertEquals("scalar", PathUtils.groupEdges("scalar"));

        // Single edge
        List<Object> singleEdge = Collections.singletonList((Object) new Object[]{"key", "value"});
        Object resSingle = PathUtils.groupEdges(singleEdge);
        assertTrue(resSingle instanceof Map);
        Map<?, ?> mapSingle = (Map<?, ?>) resSingle;
        assertEquals("value", mapSingle.get("key"));

        // Repeated edges
        List<Object> repeatedEdges = List.of(
            (Object) new Object[]{"items", "v1"},
            (Object) new Object[]{"items", "v2"},
            (Object) new Object[]{"other", "v3"}
        );
        Object resRep = PathUtils.groupEdges(repeatedEdges);
        assertTrue(resRep instanceof Map);
        Map<?, ?> mapRep = (Map<?, ?>) resRep;
        assertEquals(List.of("v1", "v2"), mapRep.get("items"));
        assertEquals("v3", mapRep.get("other"));

        // Nested structure
        List<Object> nestedEdges = Collections.singletonList(
            (Object) new Object[]{"parent", List.of(
                (Object) new Object[]{"child", "val1"},
                (Object) new Object[]{"child", "val2"}
            )}
        );
        Object resNested = PathUtils.groupEdges(nestedEdges);
        assertTrue(resNested instanceof Map);
        Map<?, ?> mapNested = (Map<?, ?>) resNested;
        assertTrue(mapNested.get("parent") instanceof Map);
        Map<?, ?> childMap = (Map<?, ?>) mapNested.get("parent");
        assertEquals(List.of("val1", "val2"), childMap.get("child"));
    }
}
