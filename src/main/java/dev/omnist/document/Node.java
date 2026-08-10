package dev.omnist.document;

import java.util.List;
import java.util.Objects;

/**
 * A node in the Document model, consisting of an ordered list of labeled edges (omnist-spec §2.1/§2.2).
 * Edge order is preserved exactly as constructed (invariant D-1).
 * Repeated labels are permitted and preserved as separate edges (invariant D-2).
 * Equality is order-sensitive (invariant D-1 / D-3).
 *
 * @param edges the ordered list of edges
 */
public record Node(List<Edge> edges) implements Target {
    public Node {
        Objects.requireNonNull(edges, "edges must not be null");
        edges = List.copyOf(edges);
    }
}
