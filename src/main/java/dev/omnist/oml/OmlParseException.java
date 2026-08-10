package dev.omnist.oml;

/**
 * Structured exception thrown when an OML document fails to parse (omnist-spec §8).
 */
public class OmlParseException extends RuntimeException {
    private final int line;
    private final int column;

    public OmlParseException(int line, int column, String message) {
        super("Parse error at line " + line + ", column " + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }
}
