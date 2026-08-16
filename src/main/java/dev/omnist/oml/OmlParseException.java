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
     * Constructs an OML parse exception with full location and code details.
     *
     * @param line    1-based line number in the OML source where the error was detected
     * @param column  1-based column number in the OML source where the error was detected
     * @param code    machine-readable error code identifying the violation category,
     *                e.g. {@code parse.unterminated-string}, {@code document.limit.depth}
     * @param message human-readable description of the error
     */
    public OmlParseException(int line, int column, String code, String message) {
        super(line + ":" + column + ": [" + code + "] " + message);
        this.line = line;
        this.column = column;
        this.code = code;
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
        return line + ":" + column;
    }
}
