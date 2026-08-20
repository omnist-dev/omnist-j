package dev.omnist.oml;

import dev.omnist.document.DateTimeValue;
import dev.omnist.document.Limits;
import dev.omnist.document.TimeValue;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normative OML Tokenizer (omnist-spec §4.2).
 *
 * Scans with maximal munch under a fixed 9-rule priority order:
 * 1. STRING family (dquote or raw/multiline)
 * 2. Punctuation: { } [ ] : ,
 * 3. DATETIME
 * 4. DATE (with T+TIME lookahead rule)
 * 5. TIME
 * 6. NUMBER
 * 7. Reserved float spellings nan, inf, -inf (emitted as NUMBER)
 * 8. INTEGER (maxIntegerDigits limit enforced here)
 * 9. IDENT
 */
public class OmlLexer {

    /**
     * Token types produced by the OML lexer (omnist-spec §4.2).
     * Each variant corresponds to one lexical category in the OML grammar.
     */
    public enum TokenType {
        /** A string value — double-quoted, raw single-quoted, or triple-quoted multiline. */
        STRING,
        /** Opening brace token, {@code &#123;}. */
        LBRACE,
        /** Closing brace token, {@code &#125;}. */
        RBRACE,
        /** Opening bracket {@code [}. */
        LBRACKET,
        /** Closing bracket {@code ]}. */
        RBRACKET,
        /** Colon {@code :} separating a label from its value. */
        COLON,
        /** Comma {@code ,} separating array elements. */
        COMMA,
        /** An ISO-8601 date-time literal (§4.2 rule 3); carries a {@link DateTimeValue} as its value. */
        DATETIME,
        /** An ISO-8601 date literal (§4.2 rule 4); carries a {@link LocalDate} as its value. */
        DATE,
        /** An ISO-8601 time literal (§4.2 rule 5); carries a {@link TimeValue} as its value. */
        TIME,
        /** A floating-point number or reserved float spelling (nan, inf, -inf); carries a {@code double}. */
        NUMBER,
        /** A decimal integer literal (§4.2 rule 8); carries a {@link BigInteger} as its value. */
        INTEGER,
        /** A bare identifier used as an edge label, boolean keyword, or null keyword. */
        IDENT,
        /** A logical line separator: one or more of {@code \n}, {@code \r}, or {@code ;}. */
        SEPARATOR,
        /** End-of-input sentinel; always the last token returned by {@link #tokenizeAll()}. */
        EOF
    }

    /**
     * An individual token produced by {@link OmlLexer}.
     *
     * @param type  the token category
     * @param text  the raw source text matched for this token
     * @param value the parsed value (type-dependent: {@link BigInteger}, {@code Double},
     *              {@link DateTimeValue}, {@link TimeValue}, {@link LocalDate}, or {@code String});
     *              {@code null} for punctuation and separator tokens
     * @param line  1-based source line number at the start of this token
     * @param col   1-based source column number at the start of this token
     */
    public record Token(TokenType type, String text, Object value, int line, int col) {}

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?");
    private static final Pattern DATETIME_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+\\.\\d+(?:[eE][-+]?\\d+)?|^-?\\d+[eE][-+]?\\d+");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+");
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]*");

    private final String source;
    private final Limits limits;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    /**
     * Constructs a lexer for the given OML source text.
     *
     * @param source the OML text to tokenize; {@code null} is treated as an empty string
     * @param limits safety limits applied during tokenization (e.g. {@link Limits#maxIntegerDigits()});
     *               {@code null} defaults to {@link Limits#DEFAULT}
     */
    public OmlLexer(String source, Limits limits) {
        this.source = source != null ? source : "";
        this.limits = limits != null ? limits : Limits.DEFAULT;
    }

    /**
     * Tokenizes the entire source, returning a list ending with a single {@link TokenType#EOF} token.
     * Calls {@link #nextToken()} repeatedly until EOF is reached.
     *
     * @return an unmodifiable snapshot of all tokens, never empty (always contains at least EOF)
     * @throws OmlParseException if any lexical error is encountered
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
     * Scans and returns the next token from the source, advancing the lexer position.
     * Applies the 9-rule priority order described in the class Javadoc (omnist-spec §4.2).
     * Horizontal whitespace and {@code #}-comments are silently skipped before each token.
     *
     * @return the next {@link Token}; returns {@link TokenType#EOF} at end of input
     * @throws OmlParseException if the character sequence does not match any rule,
     *         or if an integer literal exceeds {@link Limits#maxIntegerDigits()},
     *         or if a string literal is malformed or unterminated
     */
    public Token nextToken() {
        skipHSpaceAndComments();

        if (pos >= source.length()) {
            return new Token(TokenType.EOF, "", null, line, col);
        }

        int startLine = line;
        int startCol = col;

        char c = source.charAt(pos);

        // Check SEPARATOR (\n, \r, ;)
        if (c == '\n' || c == '\r' || c == ';') {
            StringBuilder sb = new StringBuilder();
            while (pos < source.length()) {
                char ch = source.charAt(pos);
                if (ch == '\n' || ch == '\r' || ch == ';' || ch == ' ' || ch == '\t') {
                    sb.append(consumeChar());
                } else if (ch == '#') {
                    while (pos < source.length() && source.charAt(pos) != '\n' && source.charAt(pos) != '\r') {
                        sb.append(consumeChar());
                    }
                } else {
                    break;
                }
            }
            return new Token(TokenType.SEPARATOR, sb.toString(), null, startLine, startCol);
        }

        // Rule 1: STRING family (leading " or ')
        if (c == '\'') {
            String strVal = parseRawString(startLine, startCol);
            return new Token(TokenType.STRING, strVal, strVal, startLine, startCol);
        }
        if (c == '"') {
            if (source.startsWith("\"\"\"", pos)) {
                String strVal = parseMultilineString(startLine, startCol);
                return new Token(TokenType.STRING, strVal, strVal, startLine, startCol);
            }
            String strVal = parseDQuoteString(startLine, startCol);
            return new Token(TokenType.STRING, strVal, strVal, startLine, startCol);
        }

        // Rule 2: Punctuation { } [ ] : ,
        switch (c) {
            case '{' -> { consumeChar(); return new Token(TokenType.LBRACE, "{", null, startLine, startCol); }
            case '}' -> { consumeChar(); return new Token(TokenType.RBRACE, "}", null, startLine, startCol); }
            case '[' -> { consumeChar(); return new Token(TokenType.LBRACKET, "[", null, startLine, startCol); }
            case ']' -> { consumeChar(); return new Token(TokenType.RBRACKET, "]", null, startLine, startCol); }
            case ':' -> { consumeChar(); return new Token(TokenType.COLON, ":", null, startLine, startCol); }
            case ',' -> { consumeChar(); return new Token(TokenType.COMMA, ",", null, startLine, startCol); }
        }

        String remaining = source.substring(pos);

        // Rule 3: DATETIME
        Matcher dtMatcher = DATETIME_PATTERN.matcher(remaining);
        if (dtMatcher.find()) {
            String text = dtMatcher.group();
            try {
                DateTimeValue dtVal = parseDateTimeValue(text);
                advance(text.length());
                return new Token(TokenType.DATETIME, text, dtVal, startLine, startCol);
            } catch (DateTimeParseException ignored) {}
        }

        // Rule 4: DATE (only when not followed by T plus a TIME-shaped lookahead)
        Matcher dateMatcher = DATE_PATTERN.matcher(remaining);
        if (dateMatcher.find()) {
            String dateText = dateMatcher.group();
            int dateLen = dateText.length();
            boolean isDateTimeLookahead = false;

            if (remaining.length() > dateLen && remaining.charAt(dateLen) == 'T') {
                String afterT = remaining.substring(dateLen + 1);
                Matcher timeLookahead = TIME_PATTERN.matcher(afterT);
                if (timeLookahead.find()) {
                    isDateTimeLookahead = true;
                }
            }

            if (!isDateTimeLookahead) {
                try {
                    LocalDate dVal = LocalDate.parse(dateText);
                    advance(dateLen);
                    return new Token(TokenType.DATE, dateText, dVal, startLine, startCol);
                } catch (DateTimeParseException ignored) {}
            }
        }

        // Rule 5: TIME
        Matcher timeMatcher = TIME_PATTERN.matcher(remaining);
        if (timeMatcher.find()) {
            String text = timeMatcher.group();
            try {
                TimeValue tVal = parseTimeValue(text);
                advance(text.length());
                return new Token(TokenType.TIME, text, tVal, startLine, startCol);
            } catch (DateTimeParseException ignored) {}
        }

        // Rule 6: NUMBER (decimal or exponent form)
        Matcher numMatcher = NUMBER_PATTERN.matcher(remaining);
        if (numMatcher.find()) {
            String text = numMatcher.group();
            try {
                double d = Double.parseDouble(text);
                advance(text.length());
                return new Token(TokenType.NUMBER, text, d, startLine, startCol);
            } catch (NumberFormatException ignored) {
                // Unreachable in practice: every string NUMBER_PATTERN can match is
                // -?\d+\.\d+([eE][-+]?\d+)? or -?\d+[eE][-+]?\d+, both of which are
                // strict subsets of Double.parseDouble's grammar and never overflow
                // into an exception (only into Infinity/0, neither of which throws).
            }
        }

        // Rule 7: Reserved float spellings nan, inf, -inf (emitted as NUMBER)
        if (isReservedFloatWord("nan", remaining)) {
            advance(3);
            return new Token(TokenType.NUMBER, "nan", Double.NaN, startLine, startCol);
        }
        if (isReservedFloatWord("inf", remaining)) {
            advance(3);
            return new Token(TokenType.NUMBER, "inf", Double.POSITIVE_INFINITY, startLine, startCol);
        }
        if (isReservedFloatWord("-inf", remaining)) {
            advance(4);
            return new Token(TokenType.NUMBER, "-inf", Double.NEGATIVE_INFINITY, startLine, startCol);
        }

        // Rule 8: INTEGER (maxIntegerDigits limit enforced here)
        Matcher intMatcher = INTEGER_PATTERN.matcher(remaining);
        if (intMatcher.find()) {
            String text = intMatcher.group();
            int digits = text.startsWith("-") ? text.length() - 1 : text.length();
            if (digits > limits.maxIntegerDigits()) {
                throw error("document.limit.int-digits", "Integer literal digit count (" + digits + ") exceeds maximum limit of " + limits.maxIntegerDigits(), startLine, startCol);
            }
            try {
                BigInteger bi = new BigInteger(text);
                advance(text.length());
                return new Token(TokenType.INTEGER, text, bi, startLine, startCol);
            } catch (NumberFormatException e) {
                // Unreachable in practice: every string INTEGER_PATTERN (-?\d+) can
                // match is exactly BigInteger(String)'s accepted grammar, so this
                // catch can't actually fire. Kept as defensive handling for the
                // checked-exception-shaped contract, same as JsonCodec.write's
                // IOException catch below.
                throw error("parse.unexpected-token", "Invalid integer literal: " + text, startLine, startCol);
            }
        }

        // Rule 9: IDENT
        Matcher identMatcher = IDENT_PATTERN.matcher(remaining);
        if (identMatcher.find()) {
            String text = identMatcher.group();
            advance(text.length());
            return new Token(TokenType.IDENT, text, text, startLine, startCol);
        }

        throw error("parse.unexpected-token", "Unexpected character: '" + c + "'", startLine, startCol);
    }

    private boolean isReservedFloatWord(String target, String remaining) {
        if (!remaining.startsWith(target)) return false;
        int len = target.length();
        if (remaining.length() == len) return true;
        char next = remaining.charAt(len);
        return !isIdentContinuationChar(next);
    }

    private boolean isIdentContinuationChar(char c) {
        // ASCII-only by design (matching isTokenChar's own ASCII-only ranges
        // elsewhere in this codebase) -- Character.isLetterOrDigit accepts
        // Unicode letters/digits too, which would silently change behavior here.
        boolean isAsciiLetter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        boolean isAsciiDigit = c >= '0' && c <= '9';
        return isAsciiLetter || isAsciiDigit || c == '_' || c == '-';
    }

    private void skipHSpaceAndComments() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == ' ' || c == '\t') {
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

    private String parseRawString(int startLine, int startCol) {
        consumeChar(); // consume opening '\''
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = consumeChar();
            if (c == '\'') {
                return sb.toString();
            }
            sb.append(c);
        }
        throw error("parse.unterminated-string", "Unterminated raw string", startLine, startCol);
    }

    private String parseMultilineString(int startLine, int startCol) {
        advance(3); // consume opening """
        if (pos < source.length() && source.charAt(pos) == '\r' && pos + 1 < source.length() && source.charAt(pos + 1) == '\n') {
            consumeChar();
            consumeChar();
        } else if (pos < source.length() && source.charAt(pos) == '\n') {
            consumeChar();
        }

        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            if (source.startsWith("\"\"\"", pos)) {
                advance(3); // consume ONLY the first 3 quotes
                return sb.toString();
            }
            char c = source.charAt(pos);
            if (c < 0x0020 && c != '\t' && c != '\n' && c != '\r') {
                throw error("parse.control-character", "Unescaped control character in string", startLine, startCol);
            }
            sb.append(consumeChar());
        }
        throw error("parse.unterminated-string", "Unterminated multiline string", startLine, startCol);
    }

    private String parseDQuoteString(int startLine, int startCol) {
        consumeChar(); // consume opening "
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = consumeChar();
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("parse.control-character", "Control characters below U+0020 are forbidden in strings", startLine, startCol);
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw error("parse.unterminated-string", "Unterminated escape in string", startLine, startCol);
                }
                char esc = consumeChar();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > source.length()) {
                            throw error("parse.unterminated-string", "Unterminated \\uXXXX escape sequence", startLine, startCol);
                        }
                        String hex = source.substring(pos, pos + 4);
                        advance(4);
                        int codeUnit;
                        try {
                            codeUnit = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException e) {
                            throw error("parse.invalid-escape", "Invalid hex in \\uXXXX escape", startLine, startCol);
                        }
                        if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF) {
                            if (pos + 6 <= source.length() && source.startsWith("\\u", pos)) {
                                advance(2);
                                String hex2 = source.substring(pos, pos + 4);
                                advance(4);
                                int lowUnit = Integer.parseInt(hex2, 16);
                                if (lowUnit >= 0xDC00 && lowUnit <= 0xDFFF) {
                                    int codePoint = Character.toCodePoint((char) codeUnit, (char) lowUnit);
                                    sb.appendCodePoint(codePoint);
                                } else {
                                    throw error("parse.unpaired-surrogate", "Invalid low surrogate in \\uXXXX pair", startLine, startCol);
                                }
                            } else {
                                throw error("parse.unpaired-surrogate", "Unpaired high surrogate in \\uXXXX escape", startLine, startCol);
                            }
                        } else if (codeUnit >= 0xDC00 && codeUnit <= 0xDFFF) {
                            throw error("parse.unpaired-surrogate", "Unpaired low surrogate in \\uXXXX escape", startLine, startCol);
                        } else {
                            sb.append((char) codeUnit);
                        }
                    }
                    default -> throw error("parse.invalid-escape", "Invalid escape sequence: \\" + esc, startLine, startCol);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("parse.unterminated-string", "Unterminated double-quoted string", startLine, startCol);
    }

    private DateTimeValue parseDateTimeValue(String text) {
        if (text.endsWith("Z")) {
            LocalDateTime dt = LocalDateTime.parse(text.substring(0, text.length() - 1));
            return DateTimeValue.of(dt, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (signPos > 10) {
            LocalDateTime dt = LocalDateTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return DateTimeValue.of(dt, offset);
        }
        return DateTimeValue.of(LocalDateTime.parse(text));
    }

    private TimeValue parseTimeValue(String text) {
        if (text.endsWith("Z")) {
            LocalTime t = LocalTime.parse(text.substring(0, text.length() - 1));
            return TimeValue.of(t, ZoneOffset.UTC);
        }
        int signPos = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        // text.indexOf(':') < signPos is always true whenever signPos > 0: this
        // method is only ever called with text TIME_PATTERN already matched, whose
        // mandatory "HH:MM" prefix puts the first ':' at index 2, while a +/-HH:MM
        // offset suffix (the only place a sign char can appear) can't start before
        // index 5 -- so the ':' is provably always earlier than any real signPos.
        if (signPos > 0 && text.indexOf(':') < signPos) {
            LocalTime t = LocalTime.parse(text.substring(0, signPos));
            ZoneOffset offset = ZoneOffset.of(text.substring(signPos));
            return TimeValue.of(t, offset);
        }
        return TimeValue.of(LocalTime.parse(text));
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

    private OmlParseException error(String code, String message, int errLine, int errCol) {
        return new OmlParseException(errLine, errCol, code, message);
    }
}
