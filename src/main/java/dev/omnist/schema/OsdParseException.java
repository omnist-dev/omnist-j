package dev.omnist.schema;

public class OsdParseException extends RuntimeException {
    private final int line;
    private final int column;

    public OsdParseException(int line, int column, String message) {
        super("OSD parse error at line " + line + ", column " + column + ": " + message);
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
