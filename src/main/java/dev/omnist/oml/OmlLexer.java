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

    public enum TokenType {
        STRING,
        LBRACE, RBRACE,
        LBRACKET, RBRACKET,
        COLON, COMMA,
        DATETIME,
        DATE,
        TIME,
        NUMBER,
        INTEGER,
        IDENT,
        SEPARATOR,
        EOF
    }

    public record Token(TokenType type, String text, Object value, int line, int col) {}

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?");
    private static final Pattern DATETIME_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?(Z|[-+]\\d{2}:\\d{2})?");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+\\.\\d+(?:[eE][-+]?\\d+)?|^-?\\d+[eE][-+]?\\d+");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+");
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+");

    private final String source;
    private final Limits limits;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    public OmlLexer(String source, Limits limits) {
        this.source = source != null ? source : "";
        this.limits = limits != null ? limits : Limits.DEFAULT;
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
            } catch (NumberFormatException ignored) {}
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
        return !Character.isLetterOrDigit(next) && next != '_' && next != '-';
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

    private OmlParseException error(String message, int errLine, int errCol) {
        return new OmlParseException(errLine, errCol, "parse.unexpected-token", message);
    }
}
