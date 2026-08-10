package dev.omnist.oml;

import dev.omnist.document.*;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * OML (Omnist Markup Language) Core Reader (omnist-spec §4).
 * Reads OML-Core text format into a {@link Document}.
 *
 * OML-Extended syntax (raw strings `'...'` and multiline strings `"""..."""`) is explicitly deferred to a later step.
 */
public class OmlReader {

    private final String source;
    private final Limits limits;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    private int currentDepth = 1;
    private int materializedNodeCount = 0;

    public OmlReader(String source, Limits limits) {
        this.source = source != null ? source : "";
        this.limits = limits != null ? limits : Limits.DEFAULT;
    }

    public static Document read(String source) {
        return read(source, Limits.DEFAULT);
    }

    public static Document read(String source, Limits limits) {
        return new OmlReader(source, limits).parseDocument();
    }

    public Document parseDocument() {
        skipHSpaceAndComments();
        if (pos >= source.length()) {
            return createNode(List.of());
        }

        if (isEdgeListStart()) {
            Node rootNode = parseNodeEdges(false);
            skipHSpaceAndComments();
            if (pos < source.length()) {
                throw error("Trailing content after top-level document");
            }
            return rootNode;
        } else {
            Value bareValue = parseScalarValue();
            skipHSpaceAndComments();
            if (pos < source.length()) {
                throw error("Trailing content after bare scalar document");
            }
            return bareValue;
        }
    }

    private boolean isEdgeListStart() {
        int savedPos = pos;
        int savedLine = line;
        int savedCol = col;

        try {
            Token t = nextToken();
            if (t.type == TokenType.STRING) {
                Token colon = nextToken();
                return colon.type == TokenType.COLON;
            } else if (t.type == TokenType.IDENT) {
                if ("null".equals(t.text) || "true".equals(t.text) || "false".equals(t.text)) {
                    return false;
                }
                Token colon = nextToken();
                return colon.type == TokenType.COLON;
            }
            return false;
        } catch (OmlParseException e) {
            return false;
        } finally {
            pos = savedPos;
            line = savedLine;
            col = savedCol;
        }
    }

    private Node parseNodeEdges(boolean insideBraces) {
        List<Edge> edges = new ArrayList<>();
        skipHSpaceAndCommentsAndSep();

        while (pos < source.length()) {
            if (insideBraces && peekChar() == '}') {
                break;
            }

            int edgeLine = line;
            int edgeCol = col;

            String label = parseLabel();

            skipHSpaceAndComments();
            if (pos >= source.length() || source.charAt(pos) != ':') {
                throw new OmlParseException(edgeLine, edgeCol, "Expected ':' after edge label '" + label + "'");
            }
            consumeChar();

            skipHSpaceAndComments();

            if (pos >= source.length()) {
                throw error("Expected value after ':'");
            }

            char c = peekChar();
            if (c == '[') {
                consumeChar();
                parseArrayElements(label, edges);
            } else if (c == '{') {
                consumeChar();
                currentDepth++;
                if (currentDepth > limits.maxDepth()) {
                    throw error("Nesting depth (" + currentDepth + ") exceeds maximum limit of " + limits.maxDepth());
                }
                Node childNode = parseNodeEdges(true);
                skipHSpaceAndCommentsAndSep();
                if (pos >= source.length() || source.charAt(pos) != '}') {
                    throw error("Expected '}' closing braced node");
                }
                consumeChar();
                currentDepth--;
                edges.add(new Edge(label, childNode));
            } else {
                Value val = parseScalarValue();
                edges.add(new Edge(label, val));
            }

            boolean HadSep = skipHSpaceAndCommentsAndSep();
            if (pos < source.length()) {
                if (insideBraces && peekChar() == '}') {
                    break;
                }
                if (!HadSep) {
                    throw error("Edge separator (newline or ';') required between adjacent edges");
                }
            }
        }

        return createNode(edges);
    }

    private Node createNode(List<Edge> edges) {
        materializedNodeCount++;
        if (materializedNodeCount > limits.maxNodeCount()) {
            throw error("Node count (" + materializedNodeCount + ") exceeds maximum limit of " + limits.maxNodeCount());
        }
        return new Node(edges);
    }

    private String parseLabel() {
        skipHSpaceAndComments();
        if (pos >= source.length()) {
            throw error("Expected edge label");
        }

        char c = peekChar();
        if (c == '\'') {
            throw error("OML-Extended raw string `'...'` is deferred in this step");
        }
        if (c == '"') {
            if (source.startsWith("\"\"\"", pos)) {
                throw error("OML-Extended multiline string `\"\"\"...\"\"\"` is deferred in this step");
            }
            return parseDQuoteString();
        }

        int startLine = line;
        int startCol = col;
        String ident = parseIdent();
        if ("null".equals(ident) || "true".equals(ident) || "false".equals(ident)) {
            throw new OmlParseException(startLine, startCol, "Reserved word '" + ident + "' cannot be used as a bare label");
        }
        if ("nan".equals(ident) || "inf".equals(ident) || "-inf".equals(ident)) {
            throw new OmlParseException(startLine, startCol, "Reserved float spelling '" + ident + "' cannot be used as a bare label");
        }
        return ident;
    }

    private void parseArrayElements(String label, List<Edge> edges) {
        skipHSpaceAndCommentsAndSep();
        if (pos < source.length() && source.charAt(pos) == ']') {
            throw error("Empty array `[]` is an error");
        }

        boolean first = true;
        while (pos < source.length()) {
            skipHSpaceAndCommentsAndSep();
            if (source.charAt(pos) == ']') {
                consumeChar();
                break;
            }

            if (!first) {
                if (source.charAt(pos) == ',') {
                    consumeChar();
                    skipHSpaceAndCommentsAndSep();
                    if (pos < source.length() && source.charAt(pos) == ']') {
                        consumeChar();
                        break;
                    }
                } else {
                    throw error("Expected ',' between array elements");
                }
            }

            char c = peekChar();
            if (c == '[') {
                throw error("Arrays cannot be nested inside arrays");
            } else if (c == '{') {
                consumeChar();
                currentDepth++;
                if (currentDepth > limits.maxDepth()) {
                    throw error("Nesting depth (" + currentDepth + ") exceeds maximum limit of " + limits.maxDepth());
                }
                Node childNode = parseNodeEdges(true);
                skipHSpaceAndCommentsAndSep();
                if (pos >= source.length() || source.charAt(pos) != '}') {
                    throw error("Expected '}' closing braced node");
                }
                consumeChar();
                currentDepth--;
                edges.add(new Edge(label, childNode));
            } else {
                Value val = parseScalarValue();
                edges.add(new Edge(label, val));
            }

            first = false;
        }
    }

    private Value parseScalarValue() {
        skipHSpaceAndComments();
        if (pos >= source.length()) {
            throw error("Expected scalar value");
        }

        char c = peekChar();
        if (c == '\'') {
            throw error("OML-Extended raw string `'...'` is deferred in this step");
        }
        if (c == '"') {
            if (source.startsWith("\"\"\"", pos)) {
                throw error("OML-Extended multiline string `\"\"\"...\"\"\"` is deferred in this step");
            }
            return new Scalar.StringScalar(parseDQuoteString());
        }

        String word = readTokenWord();

        if ("null".equals(word)) return Value.NULL;
        if ("true".equals(word)) return new Scalar.BooleanScalar(true);
        if ("false".equals(word)) return new Scalar.BooleanScalar(false);

        if ("nan".equals(word)) return new Scalar.NumberScalar(Double.NaN);
        if ("inf".equals(word)) return new Scalar.NumberScalar(Double.POSITIVE_INFINITY);
        if ("-inf".equals(word)) return new Scalar.NumberScalar(Double.NEGATIVE_INFINITY);

        if (word.contains("T") && (word.contains("-") || word.contains(":"))) {
            try {
                return new Scalar.DateTimeScalar(parseDateTimeValue(word));
            } catch (DateTimeParseException ignored) {}
        }

        if (isDate(word)) {
            try {
                return new Scalar.DateScalar(LocalDate.parse(word));
            } catch (DateTimeParseException ignored) {}
        }

        if (isTime(word)) {
            try {
                return new Scalar.TimeScalar(parseTimeValue(word));
            } catch (DateTimeParseException ignored) {}
        }

        if (word.contains(".") || word.contains("e") || word.contains("E")) {
            try {
                return new Scalar.NumberScalar(Double.parseDouble(word));
            } catch (NumberFormatException ignored) {}
        }

        if (isIntegerDigits(word)) {
            int digits = word.startsWith("-") ? word.length() - 1 : word.length();
            if (digits > limits.maxIntegerDigits()) {
                throw error("Integer literal digit count (" + digits + ") exceeds maximum limit of " + limits.maxIntegerDigits());
            }
            try {
                return new Scalar.IntegerScalar(new BigInteger(word));
            } catch (NumberFormatException e) {
                throw error("Invalid integer literal: " + word);
            }
        }

        throw error("Invalid scalar token: '" + word + "'");
    }

    private static boolean isIntegerDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        int start = 0;
        if (s.charAt(0) == '-') {
            if (s.length() == 1) return false;
            start = 1;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isDate(String s) {
        if (s == null || s.length() != 10) return false;
        return s.charAt(4) == '-' && s.charAt(7) == '-'
                && Character.isDigit(s.charAt(0)) && Character.isDigit(s.charAt(1))
                && Character.isDigit(s.charAt(2)) && Character.isDigit(s.charAt(3))
                && Character.isDigit(s.charAt(5)) && Character.isDigit(s.charAt(6))
                && Character.isDigit(s.charAt(8)) && Character.isDigit(s.charAt(9));
    }

    private static boolean isTime(String s) {
        if (s == null || !s.contains(":") || s.contains("T")) return false;
        return Character.isDigit(s.charAt(0));
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

    private String parseDQuoteString() {
        consumeChar();
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = consumeChar();
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("Control characters below U+0020 are forbidden in strings");
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw error("Unterminated escape in string");
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
                            throw error("Unterminated \\uXXXX escape sequence");
                        }
                        String hex = source.substring(pos, pos + 4);
                        pos += 4;
                        col += 4;
                        int codeUnit;
                        try {
                            codeUnit = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException e) {
                            throw error("Invalid hex in \\uXXXX escape");
                        }
                        if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF) {
                            if (pos + 6 <= source.length() && source.startsWith("\\u", pos)) {
                                pos += 2;
                                col += 2;
                                String hex2 = source.substring(pos, pos + 4);
                                pos += 4;
                                col += 4;
                                int lowUnit = Integer.parseInt(hex2, 16);
                                if (lowUnit >= 0xDC00 && lowUnit <= 0xDFFF) {
                                    int codePoint = Character.toCodePoint((char) codeUnit, (char) lowUnit);
                                    sb.appendCodePoint(codePoint);
                                } else {
                                    throw error("Invalid low surrogate in \\uXXXX pair");
                                }
                            } else {
                                throw error("Unpaired high surrogate in \\uXXXX escape");
                            }
                        } else if (codeUnit >= 0xDC00 && codeUnit <= 0xDFFF) {
                            throw error("Unpaired low surrogate in \\uXXXX escape");
                        } else {
                            sb.append((char) codeUnit);
                        }
                    }
                    default -> throw error("Invalid escape sequence: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated double-quoted string");
    }

    private String parseIdent() {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                consumeChar();
            } else {
                break;
            }
        }
        return source.substring(start, pos);
    }

    private String readTokenWord() {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isWhitespace(c) || c == ';' || c == ',' || c == '{' || c == '}' || c == '[' || c == ']') {
                break;
            }
            consumeChar();
        }
        return source.substring(start, pos);
    }

    private boolean skipHSpaceAndCommentsAndSep() {
        boolean hadSep = false;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == ' ' || c == '\t') {
                consumeChar();
            } else if (c == '#') {
                while (pos < source.length() && source.charAt(pos) != '\n' && source.charAt(pos) != '\r') {
                    consumeChar();
                }
            } else if (c == '\n' || c == '\r' || c == ';') {
                hadSep = true;
                consumeChar();
            } else {
                break;
            }
        }
        return hadSep;
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

    private char peekChar() {
        return source.charAt(pos);
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

    private OmlParseException error(String message) {
        return new OmlParseException(line, col, message);
    }

    private Token nextToken() {
        skipHSpaceAndComments();
        if (pos >= source.length()) {
            return new Token(TokenType.EOF, "", line, col);
        }
        char c = peekChar();
        if (c == ':') {
            int l = line, cl = col;
            consumeChar();
            return new Token(TokenType.COLON, ":", l, cl);
        }
        if (c == '"') {
            int l = line, cl = col;
            String str = parseDQuoteString();
            return new Token(TokenType.STRING, str, l, cl);
        }
        int l = line, cl = col;
        String ident = parseIdent();
        return new Token(TokenType.IDENT, ident, l, cl);
    }

    private enum TokenType {
        STRING, IDENT, COLON, EOF
    }

    private record Token(TokenType type, String text, int line, int col) {}
}
