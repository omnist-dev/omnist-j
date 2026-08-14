package dev.omnist.oml;

/**
 * Structured exception thrown when an OML document fails to parse (omnist-spec §8).
 */
public class OmlParseException extends RuntimeException {
    private final int line;
    private final int column;
    private final String code;

    public OmlParseException(int line, int column, String code, String message) {
        super(line + ":" + column + ": [" + code + "] " + message);
        this.line = line;
        this.column = column;
        this.code = code;
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
        return line + ":" + column;
    }
}
