package dev.omnist.validation;

import java.util.List;

/**
 * Structured outcome of validating a Document against a Schema (§3.6).
 *
 * @param isValid     true if zero diagnostics were raised.
 * @param diagnostics List of all validation diagnostics found across the document tree.
 */
public record ValidationResult(boolean isValid, List<ValidationDiagnostic> diagnostics) {}
