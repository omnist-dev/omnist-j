package dev.omnist.schema;

/**
 * Thrown when OSD (Omnist Schema Definition) text fails to parse (omnist-spec §5).
 *
 * <p>Callers that need to surface parse errors to users should inspect:
 * <ul>
 *   <li>{@link #getLine()} and {@link #getColumn()} — source location</li>
 *   <li>{@link #getCode()} — a machine-readable error code (e.g. {@code schema.duplicate-record})</li>
 *   <li>{@link #getPath()} — the schema element path where the error was detected (e.g. a record name)</li>
 *   <li>{@link #getMessage()} — human-readable explanation combining all of the above</li>
 * </ul>
 *
 * <p>This exception is thrown by {@link OsdReader} and {@link OsdLexer} for any structural,
 * constraint, or lexical violation detected during parsing.
 */
public class OsdParseException extends RuntimeException {
    /** 1-based source line where the error was detected. */
    private final int line;
    /** 1-based source column where the error was detected. */
    private final int column;
    /** Machine-readable error code identifying the violation category. */
    private final String code;
    /** The schema element path where the error was detected (e.g. a record name). */
    private final String path;

    /**
     * Constructs a fully-detailed parse exception.
     *
     * @param line    1-based line number in the OSD source where the error was detected
     * @param column  1-based column number in the OSD source where the error was detected
     * @param code    machine-readable error code identifying the violation category
     *                (e.g. {@code schema.duplicate-record}, {@code schema.unknown-type})
     * @param path    schema-path to the element that triggered the error (e.g. a record name or field path);
     *                use {@code "$"} when the error is at the schema root
     * @param message human-readable description of the error
     */
    public OsdParseException(int line, int column, String code, String path, String message) {
        super("OSD parse error at line " + line + ", column " + column + " (" + path + " | " + code + "): " + message);
        this.line = line;
        this.column = column;
        this.code = code;
        this.path = path;
    }

    /**
     * Constructs a parse exception with default code {@code schema.parse-error} and path {@code "$"}.
     * Convenience constructor for generic lexical errors from {@link OsdLexer}.
     *
     * @param line    1-based line number where the error was detected
     * @param column  1-based column number where the error was detected
     * @param message human-readable description of the error
     */
    public OsdParseException(int line, int column, String message) {
        this(line, column, "schema.parse-error", "$", message);
    }

    /**
     * Returns the 1-based line number in the OSD source where the error was detected.
     */
    public int getLine() {
        return line;
    }

    /**
     * Returns the 1-based column number in the OSD source where the error was detected.
     */
    public int getColumn() {
        return column;
    }

    /**
     * Returns the machine-readable error code identifying the violation category,
     * e.g. {@code schema.duplicate-record} or {@code schema.unknown-type}.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the schema-path to the element that triggered the error,
     * e.g. a record name, a field path such as {@code "MyRecord.myField"}, or {@code "$"} for root-level errors.
     */
    public String getPath() {
        return path;
    }
}
