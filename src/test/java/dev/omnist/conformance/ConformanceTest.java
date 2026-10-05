package dev.omnist.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class ConformanceTest {

    @Test
    void testTrack1RunnerWithRealFixtures() throws Exception {
        Path repoDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path fixturesPath = repoDir.resolve("vendor/omnist-spec/conformance/fixtures");
        assertTrue(Files.exists(fixturesPath), "Fixtures directory must exist");

        int[] results = Track1Runner.runTrack1(fixturesPath, repoDir);
        assertNotNull(results);
        assertEquals(3, results.length);
        assertEquals(29, results[0], "Track 1 should pass all 29 real fixtures");
        assertEquals(0, results[1], "Track 1 should have 0 failures");
        assertEquals(0, results[2], "Track 1 should have 0 skips");
    }

    @Test
    void testTrack2RunnerWithRealVectors() throws Exception {
        Path repoDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path testSuitePath = repoDir.resolve("vendor/omnist-spec/test-suite");
        assertTrue(Files.exists(testSuitePath), "Test suite directory must exist");

        int[] results = Track2Runner.runTrack2(testSuitePath);
        assertNotNull(results);
        assertEquals(3, results.length);
        // The expected numbers are derived from the suite itself, so a spec bump no longer needs a
        // hand-edited count: every vector is either run or skipped, none fails, and the only skips are
        // the OSD-OML extension's operations (omnist-j#105; E-20, each reported with its reason).
        int total = 0;
        int osdOml = 0;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Set<String> unimplemented = java.util.Set.of(
                "parse_schema_oml", "write_schema_oml", "schema_from_document", "schema_to_document");
        try (java.util.stream.Stream<Path> files = Files.walk(testSuitePath)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
                com.fasterxml.jackson.databind.JsonNode vectors = mapper.readTree(Files.readString(file)).get("vectors");
                if (vectors == null) {
                    continue;
                }
                for (com.fasterxml.jackson.databind.JsonNode vector : vectors) {
                    total++;
                    if (unimplemented.contains(vector.get("operation").asText())) {
                        osdOml++;
                    }
                }
            }
        }
        assertEquals(0, results[1], "Track 2 should have 0 failures");
        assertEquals(osdOml, results[2], "Track 2 should skip only the OSD-OML vectors");
        assertEquals(total - osdOml, results[0], "Track 2 should pass every other vector");
        assertTrue(total > 0, "the suite must not be empty");
    }

    @Test
    void testTrack1RunnerEdgeCases(@TempDir Path tempDir) throws Exception {
        Path fixturesDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixturesDir);

        // 1. Referee self-test schema exact equal
        Path ref1 = fixturesDir.resolve("_referee-self-test/01-exact");
        Files.createDirectories(ref1);
        Files.writeString(ref1.resolve("purpose.txt"), "schema exact equal\n");
        Files.writeString(ref1.resolve("kind.txt"), "schema\n");
        Files.writeString(ref1.resolve("mode.txt"), "exact\n");
        Files.writeString(ref1.resolve("expect.txt"), "equal\n");
        Files.writeString(ref1.resolve("a.osd"), "record R { \"a\": string }\nroot R\n");
        Files.writeString(ref1.resolve("b.osd"), "record R { \"a\": string }\nroot R\n");

        int[] results = Track1Runner.runTrack1(fixturesDir, tempDir);
        assertNotNull(results);
        assertEquals(1, results[0]);
    }
}
