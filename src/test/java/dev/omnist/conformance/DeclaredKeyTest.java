package dev.omnist.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Declared keys in the conformance runner: D-23's {@code declared_max_input_bytes} is honoured on a
 * {@code parse} operation, and E-20a: a {@code declared_*} key the runner does not honour makes the
 * vector skip (never run against the default).
 */
class DeclaredKeyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    private static JsonNode node(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    @ParameterizedTest
    @MethodSource("declaredKeyCases")
    @DisplayName("E-20a: the runner skips (never runs against a default) a declared key it does not honour")
    void declaredKeyDecision(String op, String input, boolean skipped) throws IOException {
        String reason = Track2Runner.unhonouredDeclaredKey(op, node(input));
        assertEquals(skipped, reason != null, String.valueOf(reason));
    }

    static Stream<Arguments> declaredKeyCases() {
        return Stream.of(
            Arguments.of("parse", "{\"format\":\"json\",\"declared_max_input_bytes\":20,\"text\":\"{}\"}", false),
            Arguments.of("parse", "{\"format\":\"yaml\",\"declared_max_input_bytes\":20,\"text\":\"a: 1\"}", false),
            Arguments.of("parse", "{\"format\":\"oml\",\"declared_max_input_bytes\":20,\"text\":\"a: 1\"}", false),
            Arguments.of("parse", "{\"format\":\"toml\",\"declared_max_input_bytes\":20,\"text\":\"a = 1\"}", false),
            Arguments.of("parse", "{\"format\":\"xml\",\"declared_max_input_bytes\":20,\"text\":\"<a/>\"}", false),
            Arguments.of("parse", "{\"format\":\"json\",\"declared_max_input_bytes\":20,\"bytes_hex\":\"7b7d\"}", true),
            Arguments.of("parse_schema", "{\"declared_max_input_bytes\":20,\"text\":\"root R\"}", true),
            Arguments.of("validate", "{\"declared_max_input_bytes\":20}", true),
            Arguments.of("write", "{\"declared_max_depth\":3}", true),
            Arguments.of("parse", "{\"format\":\"json\",\"declared_max_color\":3,\"text\":\"{}\"}", true),
            Arguments.of("parse", "{\"format\":\"json\",\"declared_something\":3,\"text\":\"{}\"}", true),
            Arguments.of("parse", "{\"format\":\"json\",\"declared_max_depth\":3,\"text\":\"{}\"}", true),
            Arguments.of("parse", "{\"format\":\"oml\",\"declared_max_depth\":3,\"text\":\"a: 1\"}", false),
            Arguments.of("parse", "{\"format\":\"json\",\"declared_max_alias_expansion\":3,\"text\":\"{}\"}", true),
            Arguments.of("parse", "{\"format\":\"yaml\",\"declared_max_alias_expansion\":3,\"text\":\"a: 1\"}", false),
            Arguments.of("parse", "{\"format\":\"json\",\"text\":\"{}\"}", false)
        );
    }

    private static final String VECTORS = "{\"vectors\":["
            + vector("size-at-max", "parse", "{\"format\":\"json\",\"declared_max_input_bytes\":7,\"text\":\"{\\\"a\\\":1}\"}",
                "{\"ok\":true,\"document\":{\"edges\":[[\"a\",{\"scalar\":{\"kind\":\"integer\",\"value\":\"1\"}}]]}}") + ","
            + vector("size-over", "parse", "{\"format\":\"json\",\"declared_max_input_bytes\":6,\"text\":\"{\\\"a\\\":1}\"}",
                "{\"ok\":false,\"diagnostics\":[{\"path\":\"$\",\"code\":\"document.limit.input-size\"}]}") + ","
            + vector("size-over-again", "parse", "{\"format\":\"yaml\",\"declared_max_input_bytes\":3,\"text\":\"a: 1\\n\"}",
                "{\"ok\":false,\"diagnostics\":[{\"path\":\"$\",\"code\":\"document.limit.input-size\"}]}") + ","
            + vector("size-over-expected-ok", "parse", "{\"format\":\"json\",\"declared_max_input_bytes\":6,\"text\":\"{\\\"a\\\":1}\"}",
                "{\"ok\":true,\"document\":{\"edges\":[[\"a\",{\"scalar\":{\"kind\":\"integer\",\"value\":\"1\"}}]]}}") + ","
            + vector("unknown-declared", "parse", "{\"format\":\"json\",\"declared_max_color\":1,\"text\":\"{}\"}", "{\"ok\":true,\"document\":{\"edges\":[]}}") + ","
            + vector("declared-on-validate", "validate", "{\"declared_max_input_bytes\":1}", "{\"ok\":true}")
            + "]}";

    private static String vector(String name, String op, String input, String expect) {
        return "{\"name\":\"" + name + "\",\"operation\":\"" + op + "\",\"input\":" + input + ",\"expect\":" + expect + "}";
    }

    @Test
    @DisplayName("the runner honours declared_max_input_bytes on parse (pass at the maximum, pass on the refusal, FAIL when a refusal was expected the other way) and skips the rest")
    void runnerEndToEnd(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("v.json"), VECTORS);
        int[] result = Track2Runner.runTrack2(dir);
        // Passes: size-at-max, size-over, size-over-again. Failure: size-over-expected-ok (it expects success but the
        // input is refused: proof the key is really applied). Skips: unknown-declared, declared-on-validate.
        assertEquals(3, result[0], "pass");
        assertEquals(1, result[1], "fail");
        assertEquals(2, result[2], "skip");
        assertEquals(2, Track2Runner.skipReasons().values().stream().mapToInt(Integer::intValue).sum());
    }
}
