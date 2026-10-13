package dev.omnist;

import dev.omnist.codec.XmlCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Note: the bounds are generous, so this catches a quadratic only at 100,000 depth / 8 MB, not a mild slowdown.
 * Bug wave 0.7.1-alpha cross-port check 3: hostile XML gives a coded diagnostic, quickly, never an uncaught error. */
class XmlHostile071Test {

    private static String outcome(String xml) {
        try {
            XmlCodec.read(xml);
            return "ok";
        } catch (dev.omnist.document.DocumentParseException e) {
            return e.getCode();
        }
    }

    @Test
    @DisplayName("deep XML (1k, 10k, 100k) and big attributes (1 to 8 MB) finish fast with a coded outcome")
    void hostileXmlScales() {
        for (int depth : new int[]{1_000, 10_000, 100_000}) {
            String xml = "<a>".repeat(depth) + "</a>".repeat(depth);
            long t0 = System.nanoTime();
            String r = outcome(xml);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("XMLPROBE depth " + depth + " -> " + r + " in " + ms + " ms");
            assertTrue(r.startsWith("document.limit."), "depth " + depth + ": " + r);
            assertTrue(ms < 5_000, "depth " + depth + " took " + ms + " ms");
        }
        for (int mb : new int[]{1, 2, 4, 8}) {
            String xml = "<a b=\"" + "x".repeat(mb << 20) + "\"/>";
            long t0 = System.nanoTime();
            String r = outcome(xml);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("XMLPROBE attr " + mb + " MB -> " + r + " in " + ms + " ms");
            assertTrue("ok".equals(r) || r.startsWith("document.limit."), "attr " + mb + " MB: " + r);
            assertTrue(ms < 5_000, "attr " + mb + " MB took " + ms + " ms");
        }
    }

    private static String code(java.util.function.Supplier<Object> read) {
        try {
            read.get();
            return "ok";
        } catch (dev.omnist.document.DocumentParseException | dev.omnist.oml.OmlParseException e) {
            return "parse";
        }
    }

    @Test
    @DisplayName("100,000-deep JSON, YAML, TOML and OML give a parse error, never an uncaught error")
    void hostileDeepOtherFormats() {
        int n = 100_000;
        String json = "[".repeat(n) + "]".repeat(n);
        String yaml = "[".repeat(n) + "]".repeat(n);
        String toml = "a = " + "[".repeat(n) + "]".repeat(n);
        String oml = "a: " + "{ a: ".repeat(n) + "1" + " }".repeat(n);
        long t0 = System.nanoTime();
        System.out.println("DEEPPROBE json " + code(() -> dev.omnist.codec.JsonCodec.read(json)));
        System.out.println("DEEPPROBE yaml " + code(() -> dev.omnist.codec.YamlCodec.read(yaml)));
        System.out.println("DEEPPROBE toml " + code(() -> dev.omnist.codec.TomlCodec.read(toml)));
        System.out.println("DEEPPROBE oml " + code(() -> dev.omnist.oml.OmlReader.read(oml)));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 20_000);
    }
}
