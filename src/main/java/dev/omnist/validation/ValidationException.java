package dev.omnist.validation;

/**
 * Thrown by strict-mode validation calls when a {@link dev.omnist.document.Document} fails to conform
 * to a {@link dev.omnist.schema.Schema} (omnist-spec §3.6). Callers that only need
 * the pass/fail outcome and diagnostics without exception-based control flow should
 * use the non-throwing validate entry point and inspect its {@link ValidationResult} directly.
 */
public class ValidationException extends RuntimeException {
    /** The validation result that caused this exception. */
    private final ValidationResult result;

    /**
     * Constructs a validation exception carrying the full result that caused it.
     *
     * @param result the failed validation result; its {@code toString()} becomes this
     *               exception's message
     */
    public ValidationException(ValidationResult result) {
        super(result.toString());
        this.result = result;
    }

    /**
     * Returns the validation result that caused this exception, including every
     * diagnostic collected before validation was aborted.
     */
    public ValidationResult getResult() {
        return result;
    }
}
