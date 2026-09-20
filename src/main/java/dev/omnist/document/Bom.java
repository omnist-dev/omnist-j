package dev.omnist.document;

import java.util.function.Supplier;

/**
 * The one place the leading byte-order mark rules live (omnist-spec section 2.5, D-15 and D-21).
 *
 * <p>Every read surface (OML, OSD, JSON, YAML, TOML, XML) calls {@link #strip} on its input text
 * before doing anything else, so the rule is stated once rather than re-implemented per reader:
 *
 * <ul>
 *   <li><b>D-15.</b> A leading U+FEFF (offset zero) is consumed and contributes nothing.
 *   <li><b>D-21.</b> Exactly one is consumed. A second U+FEFF still standing at offset zero of the
 *       text that remains is rejected, reported at text position {@code 1:1} of the remaining text.
 *       No library gets a chance to swallow it quietly.
 *   <li>A U+FEFF anywhere else is ordinary content and is never touched.
 * </ul>
 *
 * <p>Writers never emit a mark (D-15), so there is no writer-side counterpart here.
 *
 * <p>The mark is spelled {@code (char) 0xFEFF} in code and as a Java unicode escape in tests, never
 * as a raw invisible character in a source file; {@code NoRawByteOrderMarkTest} enforces that.
 */
public final class Bom {

    /** The byte-order mark, U+FEFF. */
    public static final char MARK = (char) 0xFEFF;

    private Bom() {}

    /**
     * Strips exactly one leading byte-order mark and rejects a second (D-15, D-21).
     *
     * @param text     the input text; {@code null} is returned unchanged
     * @param onDoubled supplies the surface-specific exception to throw when a second U+FEFF is
     *                  still at offset zero after the strip
     * @return {@code text} without its single leading mark, or {@code text} itself if it had none
     */
    public static String strip(String text, Supplier<? extends RuntimeException> onDoubled) {
        if (text == null || text.isEmpty() || text.charAt(0) != MARK) {
            return text;
        }
        String rest = text.substring(1);
        if (!rest.isEmpty() && rest.charAt(0) == MARK) {
            throw onDoubled.get();
        }
        return rest;
    }
}
