package dev.omnist.validation;

public class ValidationException extends RuntimeException {
    private final ValidationResult result;

    public ValidationException(ValidationResult result) {
        super(result.toString());
        this.result = result;
    }

    public ValidationResult getResult() {
        return result;
    }
}
