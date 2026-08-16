package dev.omnist.codec;

/**
 * A single format-specific adjustment a codec's writer made to represent a document
 * in a target format that cannot express it exactly, part of a {@link WriteReport}
 * (omnist-spec §8.3.8's {@code format.*} diagnostic family — e.g. a dropped null,
 * or a temporal value stringified because the format has no native temporal type).
 *
 * @param path     the Document path of the affected leaf, e.g. {@code "$.a.b"}
 * @param code     the namespaced diagnostic code, e.g. {@code format.null-unrepresentable}
 * @param message  a human-readable description of the adjustment
 * @param severity {@code "warning"} or {@code "error"} per §8.3.8's table
 */
public record WriteAdjustment(String path, String code, String message, String severity) {}
