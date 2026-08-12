package dev.omnist.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TrackRunnerCoverageTest {

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

        // 2. Document referee self-test
        Path ref2 = fixturesDir.resolve("_referee-self-test/02-doc");
        Files.createDirectories(ref2);
        Files.writeString(ref2.resolve("purpose.txt"), "doc equal\n");
        Files.writeString(ref2.resolve("kind.txt"), "document\n");
        Files.writeString(ref2.resolve("expect.txt"), "equal\n");
        Files.writeString(ref2.resolve("a.oml"), "a: \"hello\"\n");
        Files.writeString(ref2.resolve("b.oml"), "a: \"hello\"\n");

        // 3. Failing fixture (expect equal but not equal)
        Path ref3 = fixturesDir.resolve("_referee-self-test/03-fail");
        Files.createDirectories(ref3);
        Files.writeString(ref3.resolve("purpose.txt"), "schema fail\n");
        Files.writeString(ref3.resolve("kind.txt"), "schema\n");
        Files.writeString(ref3.resolve("mode.txt"), "exact\n");
        Files.writeString(ref3.resolve("expect.txt"), "equal\n");
        Files.writeString(ref3.resolve("a.osd"), "record R { \"a\": string }\nroot R\n");
        Files.writeString(ref3.resolve("b.osd"), "record R { \"a\": integer }\nroot R\n");

        int[] results = Track1Runner.runTrack1(fixturesDir, tempDir);
        assertNotNull(results);
        assertEquals(3, results[0] + results[1] + results[2]);
        assertEquals(2, results[0], "2 tests pass");
        assertEquals(1, results[1], "1 test fails");
    }

    @Test
    void testTrack2RunnerEdgeCases(@TempDir Path tempDir) throws Exception {
        Path testSuiteDir = tempDir.resolve("test-suite");
        Files.createDirectories(testSuiteDir);

        // 1. Valid test vector JSON format
        String vecJson = """
            {
              "vectors": [
                {
                  "name": "valid_parse_schema",
                  "operation": "parse_schema",
                  "input": { "text": "record R { \\"a\\": string }\\nroot R" },
                  "expect": { "ok": true }
                },
                {
                  "name": "invalid_parse_schema",
                  "operation": "parse_schema",
                  "input": { "text": "invalid osd schema" },
                  "expect": { "ok": false }
                }
              ]
            }
            """;
        Files.writeString(testSuiteDir.resolve("vectors.json"), vecJson);

        int[] results = Track2Runner.runTrack2(testSuiteDir);
        assertNotNull(results);
        assertEquals(2, results[0] + results[1]);
        assertTrue(results[0] >= 1);
    }
}
