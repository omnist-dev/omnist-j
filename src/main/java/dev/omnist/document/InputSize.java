package dev.omnist.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * The one place the input-size bound lives (omnist-spec section 2.4.2, D-23 to D-26).
 *
 * <p>A reader refuses an input of more than {@link Limits#maxInputBytes()} bytes with the code
 * {@value #CODE} at path {@code $}, before decoding or parsing; an input of exactly the maximum is
 * accepted. The unit is bytes, not characters, and the count is taken on the input as received:
 * a leading byte-order mark counts (three bytes), because the check runs before the BOM is stripped.
 */
public final class InputSize {

    /** {@code document.limit.input-size}: the input is larger than the maximum (D-23). */
    public static final String CODE = "document.limit.input-size";

    private InputSize() {}

    /**
     * The length of {@code text} in UTF-8 bytes, counted without allocating an encoded copy. A lone
     * surrogate has no UTF-8 encoding and is counted as the three bytes of U+FFFD.
     *
     * @param text the text
     * @return its UTF-8 length
     */
    public static long utf8Length(CharSequence text) {
        int n = text.length();
        long bytes = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * Whether {@code text} is more than {@code maxBytes} UTF-8 bytes long. Decides without a scan
     * when the character count alone settles it: every character is at least one byte (a surrogate
     * pair is two characters and four bytes), and at most three bytes per character.
     *
     * @param text     the text, or {@code null} (never over the limit)
     * @param maxBytes the maximum
     * @return {@code true} if the text is over the maximum
     */
    public static boolean exceeds(String text, int maxBytes) {
        if (text == null) {
            return false;
        }
        int n = text.length();
        if (n > maxBytes) {
            return true;
        }
        if ((long) n * 3 <= maxBytes) {
            return false;
        }
        return utf8Length(text) > maxBytes;
    }

    /**
     * The message of a refusal for text of known size.
     *
     * @param text     the refused text
     * @param maxBytes the maximum it exceeded
     * @return the message
     */
    public static String message(String text, int maxBytes) {
        return "input is " + utf8Length(text) + " bytes, more than the maximum of " + maxBytes
                + " bytes (spec D-23); raise Limits.maxInputBytes to accept it";
    }

    /**
     * The refusal for text over the maximum.
     *
     * @param text     the refused text
     * @param maxBytes the maximum it exceeded
     * @return the exception to throw
     */
    public static DocumentParseException refusal(String text, int maxBytes) {
        return new DocumentParseException("$", CODE, message(text, maxBytes));
    }

    /**
     * Reads {@code in} to its end, stopping as soon as it is known to hold more than
     * {@code maxBytes}: at most {@code maxBytes + 1} bytes are ever buffered.
     *
     * @param in       the stream
     * @param maxBytes the maximum accepted size
     * @param advice   appended to the refusal message, saying how to raise the maximum
     * @return the bytes read, at most {@code maxBytes} of them
     * @throws IOException            if the stream fails
     * @throws DocumentParseException with code {@value #CODE} at {@code $} if the stream holds more
     */
    public static byte[] readBounded(InputStream in, int maxBytes, String advice) throws IOException {
        byte[] data = in.readNBytes(maxBytes + 1);
        if (data.length > maxBytes) {
            throw new DocumentParseException("$", CODE,
                    "input is more than " + maxBytes + " bytes, the maximum (spec D-23); " + advice);
        }
        return data;
    }

    /**
     * Checks the size of a byte array.
     *
     * @param length   the length of the input in bytes
     * @param maxBytes the maximum accepted size
     * @param advice   appended to the refusal message, saying how to raise the maximum
     * @throws DocumentParseException with code {@value #CODE} at {@code $} if {@code length > maxBytes}
     */
    public static void checkLength(long length, int maxBytes, String advice) {
        if (length > maxBytes) {
            throw new DocumentParseException("$", CODE,
                    "input is " + length + " bytes, more than the maximum of " + maxBytes
                            + " bytes (spec D-23); " + advice);
        }
    }
}
