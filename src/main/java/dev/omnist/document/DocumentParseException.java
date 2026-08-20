package dev.omnist.document;

/**
 * Structured exception thrown when a document fails to parse, violates safety limits,
 * or contains invalid/unsupported structures across format codecs (omnist-spec ?7, ?8).
 *
 * <p>Callers that need to surface document parse errors should inspect:
 * <ul>
 *   <li>{@link #getPath()} ? JSONPath-style document path where the error was detected (e.g. {@code "$"} or {@code "$.a[0].b"})</li>
 *   <li>{@link #getCode()} ? machine-readable error code (e.g. {@code document.limit.depth}, {@code document.unlabeled-element})</li>
 *   <li>{@link #getMessage()} ? human-readable message explaining the violation</li>
 * </ul>
 */
public class DocumentParseException extends RuntimeException {
    private final String path;
    private final String code;

    /**
     * Constructs a document parse exception with path, code, and message.
     *
     * @param path    the document path where the violation was detected; use {@code "$"} for root-level errors
     * @param code    the machine-readable error code identifying the error category
     * @param message human-readable explanation of the error
     */
    public DocumentParseException(String path, String code, String message) {
        super(message);
        this.path = path != null ? path : "$";
        this.code = code != null ? code : "document.parse-error";
    }

    /**
     * Constructs a document parse exception with path, code, message, and cause.
     *
     * @param path    the document path where the violation was detected; use {@code "$"} for root-level errors
     * @param code    the machine-readable error code identifying the error category
     * @param message human-readable explanation of the error
     * @param cause   the underlying exception
     */
    public DocumentParseException(String path, String code, String message, Throwable cause) {
        super(message, cause);
        this.path = path != null ? path : "$";
        this.code = code != null ? code : "document.parse-error";
    }

    /**
     * Returns the JSONPath-style document path where the error was detected.
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
