package dev.omnist.document;

/**
 * A target is either a value or a node (omnist-spec §2.2).
 * <pre>
 * target = value | node
 * </pre>
 */
public sealed interface Target permits Value, Node {
}
