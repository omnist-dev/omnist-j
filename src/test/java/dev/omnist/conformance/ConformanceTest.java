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
        // Bumped to omnist-spec v0.21.0-beta: 273 vectors in this track. 239 = 215 + 14 new
        // bytes_hex D-14 vectors (Cli.readInput now decodes strictly via
        // CharsetDecoder.REPORT and the runner presents them through the CLI's
        // byte-oriented entry point, omnist-j D-14/DIV-6 sweep) + 5 new OML-26/OML-27
        // stray-token vectors (4 shape/* + 1 arrays/*, already satisfied ahead of the
        // spec text) + 4 new OSD-15 canonical-escaping vectors (already satisfied) + 1
        // new infer/allow-any/mixed-object-and-scalar-shapes-open-to-any-when-allowed
        // vector (already satisfied). 215 + 14 + 5 + 4 + 1 = 239.
        // Skip breakdown is unchanged from v0.19.0-beta: 34 total, 28 OSD-OML
        // (25 parse_schema_oml + 3 write_schema_oml, still #105) + 6 alias-expansion
        // (unchanged, DIV-3 untouched).
        // Bumped to omnist-spec v0.22.0-beta: 287 vectors in this track. 253 = 239 + 14 new
        // vectors (12 OML-26/OML-25 separator-then-stray-token, and 2 E-28 code-point column
        // vectors, which failed before the OML/OSD lexers counted code points). Skips unchanged.
        // Bumped to omnist-spec v0.26.0-beta (commit 7744a5c): 331 vectors in this track. 303 = 253 + 50.
        // The 6 alias-expansion vectors that used to skip now run (D-18, D-19, D-20 implemented: the
        // runner passes declared_max_alias_expansion and declared_max_expanded_slots through
        // YamlLimits). 44 more are new: the 29 of v0.25.0-beta's alias-expansion.json that this
        // port had not run (all passing), 6 more D-18/D-18a ones, the 5 D-22 expanded-size vectors
        // (+3 pinning the exemption and the order), 5 malformed-merge syntax vectors, and 3 E-32
        // "line:col" vectors in the other formats. The only skips left are the 28 OSD-OML ones.
        // Bumped to omnist-spec v0.27.0-beta (commit a6a6090): 338 vectors. 310 = 303 + 7 new D-18a
        // empty-merge-sequence vectors, all passing with no code change.
        // Bumped to omnist-spec v0.33.0-beta (commit 64cbb68): 367 vectors. 329 = 310 + 19 (7 E-10
        // repeated-label path vectors, 7 OML-29 colon-separator vectors, 5 C-10 XML null-leaf vectors),
        // all passing with no code change. The 10 new document-model/input-size vectors skip until
        // declared_max_input_bytes is honoured: 28 OSD-OML + 10 input-size = 38.
        assertEquals(329, results[0], "Track 2 should pass 329 real JSON test vectors");
        assertEquals(0, results[1], "Track 2 should have 0 failures");
        assertEquals(38, results[2], "Track 2 should skip the 28 OSD-OML vectors and the 10 input-size vectors");
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
