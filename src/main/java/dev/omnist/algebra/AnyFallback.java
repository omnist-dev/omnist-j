package dev.omnist.algebra;

/**
 * Explains why a single field was inferred as {@code any} rather than a specific
 * scalar kind, part of {@link InferResult}.
 *
 * @param location {@code "RecordName.fieldLabel"} identifying the field
 * @param reason   a human-readable explanation of the kind conflict that forced {@code any}
 */
public record AnyFallback(String location, String reason) {}
