package dev.omnist.schema;

public class OsdParseException extends RuntimeException {
    private final int line;
    private final int column;
    private final String code;
    private final String path;

    public OsdParseException(int line, int column, String code, String path, String message) {
        super("OSD parse error at line " + line + ", column " + column + " (" + path + " | " + code + "): " + message);
        this.line = line;
        this.column = column;
        this.code = code;
        this.path = path;
    }

    public OsdParseException(int line, int column, String message) {
        this(line, column, "schema.parse-error", "$", message);
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getCode() {
        return code;
    }

    public String getPath() {
        return path;
    }
}
