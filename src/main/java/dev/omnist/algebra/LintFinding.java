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
        // location embeds record/field names; compare by Unicode codepoint
        // (not Java's default UTF-16 code-unit order) per omnist-spec
        // section 3.3 principle 3 -- same rule SchemaAlgebra's
        // normalize/extract alphabetical fallbacks use.
        return SchemaAlgebra.compareCodePoints(this.location, o.location);
    }
}
