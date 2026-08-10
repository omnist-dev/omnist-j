package dev.omnist.document;

import java.util.Objects;

/**
 * An edge in the Document model, consisting of a label and a target (omnist-spec §2.2).
 *
 * @param label  the edge label (any Unicode string, non-null)
 * @param target the target (Scalar, Node, or NullTarget, non-null)
 */
public record Edge(String label, Target target) {
    public Edge {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(target, "target must not be null");
    }
}
