package dev.omnist.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The CLI reads YAML through YamlCodec, so the alias limits reach it: exit code and message. */
class YamlAliasCliTest {

    private static int run(String stdin, ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        return Cli.run(args, new PrintStream(out), new PrintStream(err),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String BOMB = "b: &b {k1: 1, k2: 2, k3: 3, k4: 4, k5: 5, k6: 6, k7: 7, k8: 8, k9: 9}\n"
            + "t: {<<: [*b, *b, *b, *b, *b, *b, *b, *b, *b, *b, *b, *b]}\n";

    @Test
    @DisplayName("format refuses an alias bomb: exit code 2 and the limit in the message")
    void formatRefusesBomb() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(BOMB, out, err, "format", "-", "--from", "yaml", "--to", "json");
        assertEquals(2, code);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("Error: $: the expansion factor"), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("--json reports the code and path of the refusal")
    void jsonReportsCode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(BOMB, out, err, "format", "-", "--from", "yaml", "--to", "json", "--json");
        assertEquals(2, code);
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("document.limit.alias-expansion"), json);
        assertTrue(json.contains("\"$\""), json);
    }

    @Test
    @DisplayName("format accepts a config the library's own global alias cap of 50 would have refused")
    void formatAcceptsManyAliases() {
        StringBuilder yaml = new StringBuilder("d: &d {a: 1, b: 2, c: 3}\n");
        for (int i = 0; i < 100; i++) {
            yaml.append("s").append(i).append(": {<<: *d, n: ").append(i).append("}\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(yaml.toString(), out, err, "format", "-", "--from", "yaml", "--to", "json");
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"s99\""));
    }
}
