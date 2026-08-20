package dev.omnist.algebra;

/**
 * Structured exception thrown when a schema algebra or inference operation fails (omnist-spec §6).
 *
 * <p>Callers that need to surface algebra/inference errors should inspect:
 * <ul>
 *   <li>{@link #getPath()} — target schema element path where the error occurred (e.g. {@code "$"} or {@code "MyRecord.myField"})</li>
 *   <li>{@link #getCode()} — machine-readable error code (e.g. {@code algebra.extract-invalidates-root}, {@code algebra.infer-mixed-shape})</li>
 *   <li>{@link #getMessage()} — human-readable message explaining the violation</li>
 * </ul>
 */
public class AlgebraException extends IllegalArgumentException {
    private final String path;
    private final String code;

    /**
     * Constructs an algebra exception with path, code, and message.
     *
     * @param path    the schema element path where the violation was detected; use {@code "$"} for root-level errors
     * @param code    the machine-readable error code identifying the error category
     * @param message human-readable explanation of the error
     */
    public AlgebraException(String path, String code, String message) {
        super(message);
        this.path = path != null ? path : "$";
        this.code = code != null ? code : "algebra.error";
    }

    /**
     * Constructs an algebra exception with path, code, message, and cause.
     *
     * @param path    the schema element path where the violation was detected; use {@code "$"} for root-level errors
     * @param code    the machine-readable error code identifying the error category
     * @param message human-readable explanation of the error
     * @param cause   the underlying exception
     */
    public AlgebraException(String path, String code, String message, Throwable cause) {
        super(message, cause);
        this.path = path != null ? path : "$";
        this.code = code != null ? code : "algebra.error";
    }

    /**
     * Returns the schema element path where the error was detected.
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the machine-readable error code identifying the violation category.
     */
    public String getCode() {
        return code;
    }
}
