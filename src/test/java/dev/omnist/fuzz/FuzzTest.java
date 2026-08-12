package dev.omnist.fuzz;

import dev.omnist.codec.*;
import dev.omnist.document.*;
import dev.omnist.oml.*;
import dev.omnist.schema.*;
import net.jqwik.api.*;

public class FuzzTest {

    @Property(tries = 10000)
    void omlReaderFuzz(@ForAll("randomStrings") String input) {
        try {
            Document doc = OmlReader.read(input);
            if (doc != null) {
                doc.toString();
            }
        } catch (OmlParseException ignored) {
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Property(tries = 10000)
    void omlReaderLimitsFuzz(@ForAll("randomStrings") String input, @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 10) int depth) {
        try {
            Limits limits = new Limits(depth, 50, 100);
            OmlReader.read(input, limits);
        } catch (OmlParseException ignored) {
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Property(tries = 10000)
    void osdReaderFuzz(@ForAll("randomStrings") String input) {
        try {
            Schema schema = OsdReader.read(input);
            if (schema != null) {
                OsdWriter.write(schema);
            }
        } catch (OsdParseException ignored) {
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Property(tries = 10000)
    void jsonCodecFuzz(@ForAll("randomStrings") String input) {
        try {
            Document doc = JsonCodec.read(input);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Property(tries = 10000, shrinking = ShrinkingMode.OFF)
    void tomlCodecFuzz(@ForAll("randomStrings") String input) {
        try {
            Document doc = TomlCodec.read(input);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Property(tries = 10000, shrinking = ShrinkingMode.OFF)
    void yamlCodecFuzz(@ForAll("randomStrings") String input) {
        try {
            Document doc = YamlCodec.read(input);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Property(tries = 10000, shrinking = ShrinkingMode.OFF)
    void xmlCodecFuzz(@ForAll("randomStrings") String input) {
        try {
            Document doc = XmlCodec.read(input);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException e) {
            assertNoUnhandledCrash(e);
        }
    }

    @Provide
    Arbitrary<String> randomStrings() {
        Arbitrary<String> ascii = Arbitraries.strings().ascii().ofMinLength(0).ofMaxLength(100);
        Arbitrary<String> utf8 = Arbitraries.strings().all().ofMinLength(0).ofMaxLength(100);
        Arbitrary<String> seeded = Arbitraries.of(
            "name: \"Ann\"\naddress: { city: \"Zurich\" }",
            "d: 2024-01-01\nt: 12:00:00\ndt: 2024-01-01T12:30:00\n",
            "a: 'C:\\no\\escapes'\n",
            "a: \"\"\"\nhello\nworld\"\"\"\n",
            "schema = record Root { id: int, name?: string }",
            "{\"a\": 1, \"b\": [2, 3], \"c\": null}",
            "<root><child attr=\"val\">text</child></root>",
            "a: 1\nb:\n  - x\n  - y\n",
            "[[x]]\nname = \"a\"\n",
            "nan: 1\n",
            "null: 1\n",
            "a: [1, 2, 3]",
            "a: { null: 1 }",
            "9999999999999999999999999"
        );

        return Arbitraries.oneOf(ascii, utf8, seeded);
    }

    private void assertNoUnhandledCrash(Throwable t) {
        if (t instanceof NullPointerException ||
            t instanceof ArrayIndexOutOfBoundsException ||
            t instanceof StringIndexOutOfBoundsException ||
            t instanceof ClassCastException ||
            t instanceof StackOverflowError) {
            throw new AssertionError("Unhandled parser crash: " + t.getClass().getName() + ": " + t.getMessage(), t);
        }
    }
}
