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
        // Bumped to omnist-spec v0.9.1-beta, which adds:
        // - Sec3.3 S-8 (Name grammar, [A-Za-z_][A-Za-z0-9_]*) -- no code
        //   change needed, OsdLexer.IDENT_PATTERN already matches exactly.
        // - Sec3.3 S-3 clarified (reserved-name check is exact,
        //   case-sensitive) -- 1 new characterization vector
        //   (case-mismatched-name-is-not-reserved), passing, since
        //   ScalarKind.fromKeyword already does plain case-sensitive
        //   String.equals with no folding. 175 + 1 = 176 passing.
        // - 4 new extensions-osd-oml/* vectors (schema.invalid-name and
        //   friends) -- Java doesn't implement OSD-OML yet (omnist-j#105),
        //   so these skip like the other 24. 24 + 4 = 28 skipped.
        // Bumped to omnist-spec v0.19.0-beta: 249 vectors, none failing. Skips are exactly the 28
        // not-yet-implemented OSD-OML vectors (omnist-j#105) and the 6 alias-expansion vectors
        // (D-18, DIV-3) that carry declared_max_alias_expansion.
        assertEquals(215, results[0], "Track 2 should pass 215 real JSON test vectors");
        assertEquals(0, results[1], "Track 2 should have 0 failures");
        assertEquals(34, results[2], "Track 2 should skip 28 OSD-OML vectors and 6 alias-expansion vectors");
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
