package dev.omnist.document;

/**
 * Root interface representing an Omnist Document (omnist-spec §2.2).
 * <pre>
 * Document = node | value
 * </pre>
 * A bare Document can be either a {@link Node} or a {@link Value} (a scalar or null).
 */
public sealed interface Document permits Node, Value {
}
