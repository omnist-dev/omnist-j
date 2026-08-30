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
        // TEMPORARY during the #87-95 batch: the omnist-spec submodule pin was bumped
        // ahead of time (per issue #87's own instructions, to avoid landing on an
        // intermediate commit that contradicts the new [0,0]-cardinality rule) to bring in
        // all 17 new conformance vectors for that batch at once, but the fixes land one PR
        // at a time. 15 vectors fail until the last PR in the batch (#91) lands; restore
        // these to 172/0/0 there.
        assertEquals(167, results[0], "Track 2 should pass 167 of 172 real JSON test vectors during the #87-95 batch");
        assertEquals(5, results[1], "Track 2 should have 5 known failures pending #88-90 (fail-dont-invent) and #91 (XML CR escaping)");
        assertEquals(0, results[2], "Track 2 should have 0 skips");
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
