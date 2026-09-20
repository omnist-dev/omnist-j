package dev.omnist.oml;

/**
 * Structured exception thrown when an OML document fails to parse (omnist-spec §8).
 *
 * <p>Callers that need to surface parse errors to users should inspect:
 * <ul>
 *   <li>{@link #getLine()} and {@link #getColumn()} — the 1-based source location</li>
 *   <li>{@link #getCode()} — a machine-readable error code (e.g. {@code parse.unterminated-string})</li>
 *   <li>{@link #getMessage()} — a human-readable message combining location, code, and description</li>
 * </ul>
 *
 * <p>This exception is thrown by {@link OmlLexer} and {@link OmlReader} for any lexical
 * or structural violation, including unterminated strings, unexpected tokens, reserved-word
 * labels, empty arrays, and exceeded safety limits.
 */
public class OmlParseException extends RuntimeException {
    /** 1-based source line where the error was detected. */
    private final int line;
    /** 1-based source column where the error was detected. */
    private final int column;
    /** Machine-readable error code identifying the violation category. */
    private final String code;
    /**
     * The diagnostic {@code path} (omnist-spec section 8.4, E-11): a text position for a
     * {@code parse.*} code, a Document path for a {@code document.*} code.
     */
    private final String path;

    /**
     * Constructs an OML parse exception with full location and code details.
     *
     * @param line    1-based line number in the OML source where the error was detected
     * @param column  1-based column number in the OML source where the error was detected
     * @param code    machine-readable error code identifying the violation category,
     *                e.g. {@code parse.unterminated-string}, {@code document.limit.depth}
     * @param message human-readable description of the error
     */
    public OmlParseException(int line, int column, String code, String message) {
        this(line, column, code, line + ":" + column, message);
    }

    /**
     * Constructs an OML parse exception whose diagnostic path is a Document path rather than a
     * text position, as E-11 requires for the {@code document.*} family (for example
     * {@code document.limit.depth}). The line and column still locate the failure in the message.
     *
     * @param line         1-based line number where the error was detected
     * @param column       1-based column number where the error was detected
     * @param code         machine-readable error code
     * @param diagnosticPath the diagnostic path: {@code line:col} for {@code parse.*}, a Document
     *                     path such as {@code $.n} for {@code document.*}
     * @param message      human-readable description of the violation
     */
    public OmlParseException(int line, int column, String code, String diagnosticPath, String message) {
        super(line + ":" + column + ": [" + code + "] " + message);
        this.line = line;
        this.column = column;
        this.code = code;
        this.path = diagnosticPath;
    }

    /**
     * Returns the 1-based line number in the OML source where the error was detected.
     */
    public int getLine() {
        return line;
    }

    /**
     * Returns the 1-based column number in the OML source where the error was detected.
     */
    public int getColumn() {
        return column;
    }

    /**
     * Returns the machine-readable error code identifying the violation category,
     * e.g. {@code parse.unterminated-string} or {@code document.limit.int-digits}.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns a location string in the form {@code "line:column"}, identical to
     * the prefix of {@link #getMessage()}.
     */
    public String getPath() {
        return path;
    }
}
