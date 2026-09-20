package dev.omnist.codec;

import dev.omnist.document.Bom;
import dev.omnist.document.DocumentParseException;

/**
 * What every codec does to its input text before its own parsing library sees it, and how every
 * codec reports a read failure, kept in one place so the four codecs cannot drift apart.
 */
final class CodecInput {

    /** {@code parse.codec-syntax} (omnist-spec section 8.3.1): not well-formed, or refused by D-21. */
    static final String CODEC_SYNTAX = "parse.codec-syntax";

    private CodecInput() {}

    /**
     * Validates and normalises input text: rejects {@code null} and oversized input, strips one
     * leading byte-order mark (D-15) and rejects a second one (D-21, E-24) at {@code 1:1}.
     *
     * @param text      the raw input text
     * @param format    the format's display name, used in messages ({@code "JSON"}, {@code "XML"})
     * @param maxLength the codec's input length cap
     * @return the text the codec's own library should parse
     */
    static String prepare(String text, String format, int maxLength) {
        if (text == null) {
            throw new IllegalArgumentException("input text cannot be null");
        }
        if (text.length() > maxLength) {
            throw new DocumentParseException("$", "document.parse-error",
                    "invalid " + format + ": input exceeds maximum size limit of " + maxLength + " characters");
        }
        return Bom.strip(text, () -> syntax(format, "unexpected second leading byte-order mark (U+FEFF); "
                + "exactly one is consumed (D-15, D-21)", 1, 1, null));
    }

    /**
     * Builds the {@code parse.codec-syntax} exception for a codec read failure. The path is a
     * text position {@code line:col} (E-11); a position the underlying library did not supply is
     * reported as {@code 1:1}.
     *
     * @param format the format's display name
     * @param detail what the underlying parser said
     * @param line   1-based line, or a value below 1 if unknown
     * @param column 1-based column, or a value below 1 if unknown
     * @param cause  the underlying exception, or {@code null}
     * @return the exception to throw
     */
    static DocumentParseException syntax(String format, String detail, int line, int column, Throwable cause) {
        String path = (line < 1 ? 1 : line) + ":" + (column < 1 ? 1 : column);
        String message = "invalid " + format + " at " + path + ": " + detail;
        return cause == null
                ? new DocumentParseException(path, CODEC_SYNTAX, message)
                : new DocumentParseException(path, CODEC_SYNTAX, message, cause);
    }
}
