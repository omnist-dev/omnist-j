package dev.omnist.algebra;

/**
 * One structural diagnostic returned by SchemaAlgebra.lint(S) (§6.11).
 */
public record LintFinding(String code, String severity, String location, String message) implements Comparable<LintFinding> {
    @Override
    public int compareTo(LintFinding o) {
        int c = this.code.compareTo(o.code);
        if (c != 0) {
            return c;
        }
        return this.location.compareTo(o.location);
    }
}
