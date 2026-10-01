package dev.omnist.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Track 2 runner must be strict (omnist-spec section 8.5.2 / E-17: (path, code) sets, no
 * loosening) and honest about skips (E-20): a declared-limit key it cannot honour is a skip with a
 * true reason, never a run against the port's own default, and an operation it has never heard of
 * is a failure, not a skip.
 */
class RunnerHonestyTest {

    private static int[] run(Path dir, String vectors) throws Exception {
        Files.writeString(dir.resolve("v.json"), "{\"vectors\": [" + vectors + "]}");
        return Track2Runner.runTrack2(dir);
    }

    private static String parseVector(String name, String input, String expect) {
        return "{\"name\":\"" + name + "\",\"operation\":\"parse\",\"input\":" + input + ",\"expect\":" + expect + "}";
    }

    @Test
    void aParseDiagnosticWithTheRightCodeButTheWrongPathFails(@TempDir Path dir) throws Exception {
        // The input ends at 1:6; the point is that
        // a parse.* code no longer excuses a wrong path, which the old runner allowed.
        String right = parseVector("right", "{\"format\":\"oml\",\"text\":\"a: [1\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"1:6\",\"code\":\"parse.unexpected-token\"}]}");
        String wrongPath = parseVector("wrong-path", "{\"format\":\"oml\",\"text\":\"a: [1\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"9:9\",\"code\":\"parse.unexpected-token\"}]}");
        String wrongCode = parseVector("wrong-code", "{\"format\":\"oml\",\"text\":\"a: [1\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"1:6\",\"code\":\"parse.trailing-content\"}]}");
        String missing = parseVector("extra-diagnostic", "{\"format\":\"oml\",\"text\":\"a: [1\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"1:6\",\"code\":\"parse.unexpected-token\"},{\"path\":\"1:1\",\"code\":\"parse.bare-word\"}]}");
        int[] results = run(dir, right + "," + wrongPath + "," + wrongCode + "," + missing);
        assertEquals(1, results[0]);
        assertEquals(3, results[1]);
    }

    @Test
    void aDocumentLimitDiagnosticMustCarryItsDocumentPath(@TempDir Path dir) throws Exception {
        String ok = parseVector("digits", "{\"format\":\"oml\",\"declared_max_int_digits\":3,\"text\":\"n: 1000\\n\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"$.n\",\"code\":\"document.limit.int-digits\"}]}");
        String textPosition = parseVector("digits-as-position", "{\"format\":\"oml\",\"declared_max_int_digits\":3,\"text\":\"n: 1000\\n\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"1:4\",\"code\":\"document.limit.int-digits\"}]}");
        int[] results = run(dir, ok + "," + textPosition);
        assertEquals(1, results[0]);
        assertEquals(1, results[1]);
    }

    @Test
    void aFailureWithNoStructuredCodeIsAFailureNotAGuess(@TempDir Path dir) throws Exception {
        // An unknown format makes parseFormat throw a plain IllegalArgumentException: no code, no path.
        String v = parseVector("unstructured", "{\"format\":\"ini\",\"text\":\"x\"}",
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"$\",\"code\":\"parse.codec-syntax\"}]}");
        int[] results = run(dir, v);
        assertEquals(0, results[0]);
        assertEquals(1, results[1]);
    }

    @Test
    void declaredLimitKeysTheRunnerCannotHonourAreSkippedWithATrueReason(@TempDir Path dir) throws Exception {
        String alias = parseVector("alias", "{\"format\":\"json\",\"declared_max_alias_expansion\":3,\"text\":\"{}\"}", "{\"ok\":true,\"document\":{\"edges\":[[\"a\",{\"scalar\":{\"kind\":\"integer\",\"value\":1}}]]}}");
        String unknownKey = parseVector("unknown-key", "{\"format\":\"oml\",\"declared_max_widgets\":3,\"text\":\"a: 1\\n\"}", "{\"ok\":true,\"document\":{\"edges\":[]}}");
        String codecLimit = parseVector("codec-limit", "{\"format\":\"json\",\"declared_max_depth\":3,\"text\":\"{}\"}", "{\"ok\":true,\"document\":{\"edges\":[]}}");
        int[] results = run(dir, alias + "," + unknownKey + "," + codecLimit);
        assertEquals(0, results[0]);
        assertEquals(0, results[1]);
        assertEquals(3, results[2]);

        Map<String, Integer> reasons = Track2Runner.skipReasons();
        assertEquals(3, reasons.size());
        assertTrue(reasons.keySet().stream().anyMatch(r -> r.contains("no configuration surface for declared_max_alias_expansion on json")), reasons.toString());
        assertTrue(reasons.keySet().stream().anyMatch(r -> r.contains("unrecognised limit key declared_max_widgets")), reasons.toString());
        assertTrue(reasons.keySet().stream().anyMatch(r -> r.contains("no configuration surface for declared_max_depth on json")), reasons.toString());
    }

    private static final String BOMB_TEXT = "b: &b {k1: 1, k2: 2, k3: 3}\\nt: {<<: [*b, *b, *b, *b]}\\n";

    @Test
    void theYamlAliasLimitsAreRunAgainstTheDeclaredNumberOnlyForAVectorThatCarriesTheKey(@TempDir Path dir) throws Exception {
        String rejected = "{\"ok\":false,\"diagnostics\":[{\"path\":\"$\",\"code\":\"document.limit.alias-expansion\"}]}";
        String sizeRejected = "{\"ok\":false,\"diagnostics\":[{\"path\":\"$\",\"code\":\"document.limit.expanded-size\"}]}";
        // W(t) = 13, S(t) = 2, E = 6.5: over a declared 6, under the default of 50
        String declared = parseVector("declared", "{\"format\":\"yaml\",\"declared_max_alias_expansion\":6,\"text\":\"" + BOMB_TEXT + "\"}", rejected);
        String cap = parseVector("cap", "{\"format\":\"yaml\",\"declared_max_expanded_slots\":17,\"text\":\"" + BOMB_TEXT + "\"}", sizeRejected);
        String wrongCode = parseVector("wrong-code", "{\"format\":\"yaml\",\"declared_max_expanded_slots\":17,\"text\":\"" + BOMB_TEXT + "\"}", rejected);
        int[] results = run(dir, declared + "," + cap + "," + wrongCode);
        assertEquals(2, results[0], "each declared number is the one the reader runs against");
        assertEquals(1, results[1]);
        assertEquals(0, results[2]);
    }

    @Test
    void theLineColPlaceholderMatchesAWellFormedPositionOfTheSameCodeOnly(@TempDir Path dir) throws Exception {
        String bad = "{\"format\":\"yaml\",\"text\":\"a: [1\"}";
        String placeholder = parseVector("placeholder", bad,
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"line:col\",\"code\":\"parse.codec-syntax\"}]}");
        String wrongCode = parseVector("wrong-code", bad,
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"line:col\",\"code\":\"parse.unexpected-token\"}]}");
        // E-32a: the placeholder is for parse.codec-syntax only, so another code is a defect in the vector
        String exact = parseVector("exact-still-compared", bad,
            "{\"ok\":false,\"diagnostics\":[{\"path\":\"9:9\",\"code\":\"parse.codec-syntax\"}]}");
        int[] results = run(dir, placeholder + "," + wrongCode + "," + exact);
        assertEquals(1, results[0]);
        assertEquals(2, results[1]);
    }

    @Test
    void theOsdOmlOperationsAreSkippedCitingTheTrackingIssueAndAnUnknownOperationFails(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        String[] ops = {"parse_schema_oml", "write_schema_oml", "schema_from_document", "schema_to_document"};
        for (int i = 0; i < ops.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"n").append(i).append("\",\"operation\":\"").append(ops[i]).append("\",\"input\":{},\"expect\":{}}");
        }
        sb.append(",{\"name\":\"novel\",\"operation\":\"frobnicate\",\"input\":{},\"expect\":{}}");
        int[] results = run(dir, sb.toString());
        assertEquals(0, results[0]);
        assertEquals(1, results[1], "an operation the runner has never heard of is a failure");
        assertEquals(4, results[2]);
        assertTrue(Track2Runner.skipReasons().keySet().stream().allMatch(r -> r.contains("omnist-j#105") && r.startsWith("E-20")));
    }

    @Test
    void theCountOfRefereeSelfTestFixturesIsReportedSeparately(@TempDir Path tempDir) throws IOException, Exception {
        Path fixtures = tempDir.resolve("fixtures");
        Path ref = fixtures.resolve("_referee-self-test/01");
        Files.createDirectories(ref);
        Files.writeString(ref.resolve("purpose.txt"), "p\n");
        Files.writeString(ref.resolve("kind.txt"), "document\n");
        Files.writeString(ref.resolve("expect.txt"), "equal\n");
        Files.writeString(ref.resolve("a.oml"), "a: 1\n");
        Files.writeString(ref.resolve("b.oml"), "a: 1\n");
        int[] results = Track1Runner.runTrack1(fixtures, tempDir);
        assertEquals(1, results[0]);
        assertEquals(1, Track1Runner.refereeSelfTestCount());
    }

    @Test
    void aParseSchemaVectorsExpectedCanonicalSchemaTextIsCheckedByteForByte(@TempDir Path dir) throws Exception {
        // omnist-spec Sec3.3/Sec5.9: the canonical OSD `expect.schema` on a successful
        // parse_schema vector is byte-for-byte, not merely structural. This is the exact
        // defect a real PR fixed (runParseSchemaVector never compared expect.schema on
        // success at all): a vector whose expect.schema does not match what the writer
        // actually produces must fail, not silently pass.
        String schemaVector = "{\"name\":\"correct\",\"operation\":\"parse_schema\","
            + "\"input\":{\"text\":\"record R {\\n    \\\"a\\\": string,\\n}\\nroot R\\n\"},"
            + "\"expect\":{\"ok\":true,\"schema\":\"record R {\\n    \\\"a\\\": string,\\n}\\nroot R\\n\"}}";
        String corruptedSchemaVector = "{\"name\":\"corrupted\",\"operation\":\"parse_schema\","
            + "\"input\":{\"text\":\"record R {\\n    \\\"a\\\": string,\\n}\\nroot R\\n\"},"
            + "\"expect\":{\"ok\":true,\"schema\":\"record R {\\n    \\\"a\\\": integer,\\n}\\nroot R\\n\"}}";
        int[] results = run(dir, schemaVector + "," + corruptedSchemaVector);
        assertEquals(1, results[0], "the vector with a matching expect.schema must pass");
        assertEquals(1, results[1], "the vector with a corrupted expect.schema must fail, "
            + "not silently pass on a runner that never checks expect.schema at all");
    }
}
