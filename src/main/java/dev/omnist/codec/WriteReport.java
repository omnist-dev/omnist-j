package dev.omnist.codec;

import java.util.ArrayList;
import java.util.List;

public class WriteReport {
    private final List<WriteAdjustment> adjustments = new ArrayList<>();

    public void add(String path, String code, String message, String severity) {
        adjustments.add(new WriteAdjustment(path, code, message, severity));
    }

    public void addAll(List<WriteAdjustment> other) {
        adjustments.addAll(other);
    }

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
