package dev.omnist.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizer for OSD (Omnist Schema Definition) grammar (omnist-spec §5.3).
 */
public class OsdLexer {

    public enum TokenType {
        STRING,
        CARDINALITY,
        LBRACE, RBRACE,
        COLON, COMMA, QUESTION,
        RECORD,
        ROOT,
        IDENT,
        EOF
    }

    public record Token(TokenType type, String text, int line, int col) {}

    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*");

    private final String source;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    public OsdLexer(String source) {
        this.source = source != null ? source : "";
    }

    public List<Token> tokenizeAll() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = nextToken();
            tokens.add(t);
        } while (t.type() != TokenType.EOF);
        return tokens;
    }

    public Token nextToken() {
        skipHSpaceCommentsAndNewlines();

        if (pos >= source.length()) {
            return new Token(TokenType.EOF, "", line, col);
        }

        int startLine = line;
        int startCol = col;
        char c = source.charAt(pos);

        // Rule 1: STRING ("...")
        if (c == '"') {
            String strVal = parseOsdString(startLine, startCol);
            return new Token(TokenType.STRING, strVal, startLine, startCol);
        }

        // Rule 2: CARDINALITY ([...])
        if (c == '[') {
            String cardText = parseBracketText(startLine, startCol);
            return new Token(TokenType.CARDINALITY, cardText, startLine, startCol);
        }

        // Rule 3: Punctuation
        switch (c) {
            case '{' -> { consumeChar(); return new Token(TokenType.LBRACE, "{", startLine, startCol); }
            case '}' -> { consumeChar(); return new Token(TokenType.RBRACE, "}", startLine, startCol); }
            case ':' -> { consumeChar(); return new Token(TokenType.COLON, ":", startLine, startCol); }
            case ',' -> { consumeChar(); return new Token(TokenType.COMMA, ",", startLine, startCol); }
            case '?' -> { consumeChar(); return new Token(TokenType.QUESTION, "?", startLine, startCol); }
        }

        // Rule 4: IDENT / Keywords
        String remaining = source.substring(pos);
        Matcher identMatcher = IDENT_PATTERN.matcher(remaining);
        if (identMatcher.find()) {
            String text = identMatcher.group();
            advance(text.length());
            if ("record".equals(text)) {
                return new Token(TokenType.RECORD, text, startLine, startCol);
            }
            if ("root".equals(text)) {
                return new Token(TokenType.ROOT, text, startLine, startCol);
            }
            return new Token(TokenType.IDENT, text, startLine, startCol);
        }

        throw new OsdParseException(startLine, startCol, "Unexpected character: '" + c + "'");
    }

    private void skipHSpaceCommentsAndNewlines() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                consumeChar();
            } else if (c == '#') {
                while (pos < source.length() && source.charAt(pos) != '\n' && source.charAt(pos) != '\r') {
                    consumeChar();
                }
            } else {
                break;
            }
        }
    }

    private String parseOsdString(int startLine, int startCol) {
        consumeChar(); // consume opening "
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = consumeChar();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw new OsdParseException(line, col, "Unterminated escape in string");
                }
                char esc = consumeChar();
                // OSD §5.3.1: replaces every \X with single character X (no named-escape table)
                sb.append(esc);
            } else {
                sb.append(c);
            }
        }
        throw new OsdParseException(startLine, startCol, "Unterminated double-quoted string");
    }

    private String parseBracketText(int startLine, int startCol) {
        consumeChar(); // consume [
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = consumeChar();
            if (c == ']') {
                return sb.toString();
            }
            sb.append(c);
        }
        throw new OsdParseException(startLine, startCol, "Unterminated bracket ']' in cardinality");
    }

    private char consumeChar() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private void advance(int count) {
        for (int i = 0; i < count; i++) {
            consumeChar();
        }
    }
}
