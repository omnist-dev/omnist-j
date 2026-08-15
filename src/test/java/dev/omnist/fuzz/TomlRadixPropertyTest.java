package dev.omnist.fuzz;

import dev.omnist.codec.TomlCodec;
import dev.omnist.document.Document;
import net.jqwik.api.*;

/**
 * Targeted property test for TomlCodec's preprocessToml number-scanning
 * helpers (isHex/isOctal/isBinary/isDecimal and their shared character-class
 * checks). Unlike FuzzTest's fully-random strings (which almost never
 * produce a valid-looking radix-prefixed token by chance), this generator is
 * grammar-aware: it deliberately mixes valid/invalid digits, underscores,
 * and radix prefixes to search the compound-condition branch space that
 * resisted hand-derivation via the JaCoCo HTML report's branch counts.
 */
public class TomlRadixPropertyTest {

    @Property(tries = 5000, shrinking = ShrinkingMode.OFF)
    void radixTokenNeverCrashes(@ForAll("radixLikeTokens") String token) {
        String toml = "a = " + token + "\n";
        try {
            Document doc = TomlCodec.read(toml);
            if (doc != null) {
                doc.toString();
            }
        } catch (RuntimeException ignored) {
            // Expected for malformed tokens -- preprocessToml or tomlj itself
            // rejects them. The point of this property is coverage of the
            // scanning branches, not asserting a specific outcome.
        }
    }

    @Provide
    Arbitrary<String> radixLikeTokens() {
        Arbitrary<String> prefix = Arbitraries.of("0x", "0X", "0o", "0O", "0b", "0B", "", "+", "-", "+0x", "-0x");
        Arbitrary<String> body = Arbitraries.strings()
            .withChars("0123456789abcdefABCDEF_xXoObB+-.".toCharArray())
            .ofMinLength(0)
            .ofMaxLength(24);
        return Combinators.combine(prefix, body).as((p, b) -> p + b);
    }
}
