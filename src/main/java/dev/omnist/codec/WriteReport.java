package dev.omnist.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the {@code format.*} adjustments (omnist-spec §8.3.8) a codec's writer
 * made while serializing a document that couldn't be represented exactly in the
 * target format. Mutable during a single write call; callers get an immutable
 * snapshot via {@link #adjustments()}.
 */
public class WriteReport {
    private final List<WriteAdjustment> adjustments = new ArrayList<>();

    /**
     * Records a single adjustment.
     *
     * @param path     the Document path of the affected leaf, e.g. {@code "$.a.b"}
     * @param code     the namespaced diagnostic code, e.g. {@code format.null-unrepresentable}
     * @param message  a human-readable description of the adjustment
     * @param severity {@code "warning"} or {@code "error"} per §8.3.8's table
     */
    public void add(String path, String code, String message, String severity) {
        adjustments.add(new WriteAdjustment(path, code, message, severity));
    }

    /**
     * Appends every adjustment from {@code other} to this report, preserving order.
     */
    public void addAll(List<WriteAdjustment> other) {
        adjustments.addAll(other);
    }

    /**
     * Returns an immutable snapshot of every adjustment recorded so far, in the
     * order they were added.
     */
    public List<WriteAdjustment> adjustments() {
        return List.copyOf(adjustments);
    }

    @Override
    public String toString() {
        if (adjustments.isEmpty()) {
            return "no adjustments";
        }
        StringBuilder sb = new StringBuilder();
        for (WriteAdjustment adj : adjustments) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(adj.severity()).append(": ").append(adj.path()).append(": ").append(adj.message());
        }
        return sb.toString();
    }
}
