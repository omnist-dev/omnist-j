package dev.omnist.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizer for OSD (Omnist Schema Definition) grammar (omnist-spec §5.3).
 *
 * <p>Recognizes four lexical rules in priority order:
 * <ol>
 *   <li>STRING — a double-quoted field name ({@code "label"})</li>
 *   <li>CARDINALITY — a bracket-enclosed cardinality expression ({@code [m,n]})</li>
 *   <li>Punctuation — {@code { } : , ?}</li>
 *   <li>IDENT / Keywords — {@code record}, {@code root}, or any other identifier</li>
 * </ol>
 * Whitespace, newlines, and {@code #}-comments are silently skipped between tokens.
 */
public class OsdLexer {

    /**
     * Token types produced by the OSD lexer (omnist-spec §5.3).
     */
    public enum TokenType {
        /** A double-quoted field-name string (§5.3.1); the token text contains the unescaped content. */
        STRING,
        /** A bracket-enclosed cardinality expression, e.g. {@code [0,1]} or {@code [2]}; text is the content inside brackets. */
        CARDINALITY,
        /** Opening brace token, {@code &#123;}. */
        LBRACE,
        /** Closing brace token, {@code &#125;}. */
        RBRACE,
        /** Colon {@code :} separating a field label from its type. */
        COLON,
        /** Comma {@code ,} separating fields within a record. */
        COMMA,
        /** Question mark {@code ?} marking a scalar type as nullable (§5.4). */
        QUESTION,
        /** The keyword {@code record}, introducing a record definition (§5.5). */
        RECORD,
        /** The keyword {@code root}, declaring the root record name (§5.6). */
        ROOT,
        /** An identifier: a record name, field type name, or scalar keyword (§5.3). */
        IDENT,
        /** End-of-input sentinel; always the last token in the list returned by {@link #tokenizeAll()}. */
        EOF
    }

    /**
     * An individual token produced by {@link OsdLexer}.
     *
     * @param type the token category
     * @param text the raw source text matched, or the unescaped string content for STRING tokens
     * @param line 1-based source line number at the start of this token
     * @param col  1-based source column number at the start of this token
     */
    public record Token(TokenType type, String text, int line, int col) {}

    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*");

    private final String source;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    private final Matcher identMatcher = IDENT_PATTERN.matcher("");

    /**
     * Constructs a lexer for the given OSD source text.
     *
     * @param source the OSD text to tokenize; {@code null} is treated as an empty string
     */
    public OsdLexer(String source) {
        this.source = source != null ? source : "";
    }

    /**
     * Tokenizes the entire source, returning a list ending with a single {@link TokenType#EOF} token.
     *
     * @return the complete token list, never empty (always contains at least EOF)
     * @throws OsdParseException if any lexical error is encountered
     */
    public List<Token> tokenizeAll() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = nextToken();
            tokens.add(t);
        } while (t.type() != TokenType.EOF);
        return tokens;
    }

    /**
     * Scans and returns the next token, advancing the lexer position.
     * Whitespace, newlines, and {@code #}-to-end-of-line comments are skipped first.
     *
     * @return the next {@link Token}; {@link TokenType#EOF} at end of input
     * @throws OsdParseException if no rule matches the current character
     */
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
        if (identMatcher.reset(source).region(pos, source.length()).lookingAt()) {
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
