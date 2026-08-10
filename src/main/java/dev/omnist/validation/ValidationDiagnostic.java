package dev.omnist.validation;

/**
 * Diagnostic emitted when validation fails against an OSD Schema (§8.2, §8.3.4).
 *
 * @param path    Document path ($ or $.foo.bar[1]) where the diagnostic applies.
 * @param code    Namespaced diagnostic code (e.g. validate.type-mismatch).
 * @param message Human-readable error message.
 */
public record ValidationDiagnostic(String path, String code, String message) {}
