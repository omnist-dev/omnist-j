package dev.omnist.oml;

import dev.omnist.document.*;
import dev.omnist.oml.OmlLexer.Token;
import dev.omnist.oml.OmlLexer.TokenType;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OML (Omnist Markup Language) Core Reader (omnist-spec §4).
 * Reads OML-Core text format into a {@link Document} using {@link OmlLexer}.
 */
public class OmlReader {

    public static final int MAX_INPUT_LENGTH = 2_000_000;

    private static final Set<String> RESERVED_WORDS = Set.of("null", "true", "false");

    private final List<Token> tokens;
    private final Limits limits;
    private int index = 0;

    private int currentDepth = 1;
    private int materializedNodeCount = 0;

    /**
     * One step of the Document path to the value being parsed: the edge list the edge will live
     * in, its label, and the position it takes in that list. Paths (E-9, E-10) need the TOTAL
     * count of a label in its node, which is only known once the node is complete, so a path is
     * resolved after the parse rather than while it runs.
     */
    private record PathSeg(List<Edge> siblings, String label, int position) {}

    /** Ancestor path segments of the value currently being parsed. */
    private final List<PathSeg> pathStack = new ArrayList<>();

    /** The first integer literal over the digit limit, recorded and reported after the parse. */
    private List<PathSeg> overLimitInteger;
    private Token overLimitToken;

    /**
     * Constructs a reader over a pre-tokenized OML source.
     * The source is immediately tokenized by {@link OmlLexer} during construction.
     *
     * @param source the OML text; {@code null} is treated as empty
     * @param limits safety limits applied during tokenization and parsing;
     *               {@code null} defaults to {@link Limits#DEFAULT}
     */
    public OmlReader(String source, Limits limits) {
        if (source != null && source.length() > MAX_INPUT_LENGTH) {
            throw new OmlParseException(1, 1, "parse.input-too-large", "Input exceeds maximum length of " + MAX_INPUT_LENGTH + " characters");
        }
        this.limits = limits != null ? limits : Limits.DEFAULT;
        // D-15 / D-21: one leading U+FEFF is consumed, a second is rejected at 1:1 of what remains.
        source = Bom.strip(source, () -> new OmlParseException(1, 1, "parse.unexpected-token",
                "Unexpected second leading byte-order mark (U+FEFF); exactly one is consumed"));
        OmlLexer lexer = new OmlLexer(source, this.limits);
        this.tokens = lexer.tokenizeAll();
    }

    /**
     * Parses OML text into a {@link Document} using {@link Limits#DEFAULT} safety limits.
     * Equivalent to {@code read(source, Limits.DEFAULT)}.
     *
     * @param source the OML text; {@code null} is treated as empty
     * @return the parsed document
     * @throws OmlParseException if the text contains any lexical or structural error
     */
    public static Document read(String source) {
        return read(source, Limits.DEFAULT);
    }

    /**
     * Parses OML text into a {@link Document} using caller-supplied safety limits.
     *
     * @param source the OML text; {@code null} is treated as empty
     * @param limits safety limits for depth, node count, and integer digit length;
     *               {@code null} defaults to {@link Limits#DEFAULT}
     * @return the parsed document
     * @throws OmlParseException if any limit is exceeded or the text is malformed
     */
    public static Document read(String source, Limits limits) {
        return new OmlReader(source, limits).parseDocument();
    }

    /**
     * Parses the token stream into a top-level {@link Document}.
     * Handles three shapes: an empty document (returns an empty {@link Node}),
     * a node-edge list (label-colon-value pairs separated by newlines), and a bare scalar value.
     *
     * @return the parsed document
     * @throws OmlParseException if the token stream does not form a valid OML document
     */
    public Document parseDocument() {
        Document doc = parseDocumentBody();
        if (overLimitInteger != null) {
            throw new OmlParseException(overLimitToken.line(), overLimitToken.col(), "document.limit.int-digits",
                    resolvePath(overLimitInteger),
                    "Integer literal digit count (" + overLimitToken.text().replace("-", "").length()
                            + ") exceeds maximum limit of " + limits.maxIntegerDigits());
        }
        return doc;
    }

    private Document parseDocumentBody() {

        skipSeparators();
        if (peekType() == TokenType.EOF) {
            return createNode(List.of());
        }

        if (isEdgeListStart()) {
            // parseNodeEdges(false)'s while loop only exits at EOF -- the
            // insideBraces-guarded break never fires for a top-level (non-braced)
            // call -- so there is no reachable "trailing content" case here; any
            // real trailing-content error surfaces from inside that loop instead
            // (see its own parse.trailing-content check).
            return parseNodeEdges(false);
        } else {
            Value bareValue = parseScalarValue(null, null);
            skipSeparators();
            if (peekType() != TokenType.EOF) {
                // OML-25: whatever the scalar was, leftover content is trailing content, at the
                // first leftover significant token.
                Token extra = peekToken();
                throw new OmlParseException(extra.line(), extra.col(), "parse.trailing-content", "Trailing content after bare scalar document");
            }
            return bareValue;
        }
    }

    /**
     * OML-16: a document is an edge list only when its first token is a {@code STRING}, or an
     * {@code IDENT} that is not {@code null}/{@code true}/{@code false}, and the next token is
     * {@code :}. Anything else, including a number-like token such as {@code nan} or {@code 5},
     * takes the scalar branch (so {@code nan: 1} is a scalar followed by trailing content, OML-25).
     */
    private boolean isEdgeListStart() {
        // isEdgeListStart's only caller (parseDocument) already calls
        // skipSeparators() and checks for EOF before invoking this, so
        // peekNonSeparatorToken(0) always finds a real non-separator token here.
        Token t1 = peekNonSeparatorToken(0);

        boolean labelShaped = t1.type() == TokenType.STRING
                || (t1.type() == TokenType.IDENT && !RESERVED_WORDS.contains(t1.text()));
        if (!labelShaped) {
            return false;
        }

        Token t2 = peekNonSeparatorToken(1);
        return t2 != null && t2.type() == TokenType.COLON;
    }

    private Node parseNodeEdges(boolean insideBraces) {
        List<Edge> edges = new ArrayList<>();
        skipSeparators();

        while (peekType() != TokenType.EOF) {
            if (insideBraces && peekType() == TokenType.RBRACE) {
                break;
            }

            String label = parseLabel();

            if (peekType() != TokenType.COLON) {
                Token cur = peekToken();
                throw new OmlParseException(cur.line(), cur.col(), "parse.unexpected-token", "Expected ':' after edge label '" + label + "'");
            }
            consumeToken(); // consume ':'

            skipSeparators();

            if (peekType() == TokenType.EOF) {
                Token cur = peekToken();
                throw new OmlParseException(cur.line(), cur.col(), "parse.unexpected-token", "Expected value after ':'");
            }

            Token valueStart = peekToken();

            if (valueStart.type() == TokenType.LBRACKET) {
                consumeToken(); // consume '['
                parseArrayElements(label, edges, valueStart.line(), valueStart.col());
            } else if (valueStart.type() == TokenType.LBRACE) {
                consumeToken(); // consume '{'
                edges.add(new Edge(label, parseBracedNode(valueStart, edges, label)));
            } else {
                Value val = parseScalarValue(edges, label);
                edges.add(new Edge(label, val));
            }

            boolean HadSep = skipSeparators();
            if (peekType() != TokenType.EOF) {
                if (insideBraces && peekType() == TokenType.RBRACE) {
                    break;
                }
                if (!HadSep) {
                    Token cur = peekToken();
                    // Leftover content after a complete TOP-LEVEL edge is trailing content
                    // (omnist-spec#103); a missing separator inside {...} is an unexpected token.
                    String code = insideBraces ? "parse.unexpected-token" : "parse.trailing-content";
                    throw new OmlParseException(cur.line(), cur.col(), code, "Edge separator (newline or ';') required between adjacent edges");
                }
            }
        }

        return createNode(edges);
    }

    /** Parses the body of a {@code {...}} value; the opening brace has already been consumed. */
    private Node parseBracedNode(Token open, List<Edge> siblings, String label) {
        currentDepth++;
        if (currentDepth > limits.maxDepth()) {
            throw new OmlParseException(open.line(), open.col(), "document.limit.depth", "$",
                    "Nesting depth (" + currentDepth + ") exceeds maximum limit of " + limits.maxDepth());
        }
        pathStack.add(new PathSeg(siblings, label, siblings.size()));
        Node childNode = parseNodeEdges(true);
        skipSeparators();
        if (peekType() != TokenType.RBRACE) {
            Token cur = peekToken();
            throw new OmlParseException(cur.line(), cur.col(), "parse.unexpected-token", "Expected '}' closing braced node");
        }
        consumeToken(); // consume '}'
        pathStack.remove(pathStack.size() - 1);
        currentDepth--;
        return childNode;
    }

    /** Builds the E-9/E-10 Document path for a recorded segment list, now the nodes are complete. */
    private static String resolvePath(List<PathSeg> segments) {
        String path = "$";
        for (PathSeg seg : segments) {
            int before = 0;
            int total = 0;
            for (int i = 0; i < seg.siblings().size(); i++) {
                if (seg.siblings().get(i).label().equals(seg.label())) {
                    total++;
                    if (i < seg.position()) {
                        before++;
                    }
                }
            }
            path = PathUtils.childPath(path, seg.label(), before, total);
        }
        return path;
    }

    private Node createNode(List<Edge> edges) {
        materializedNodeCount++;
        if (materializedNodeCount > limits.maxNodeCount()) {
            Token cur = peekToken();
            throw new OmlParseException(cur.line(), cur.col(), "document.limit.nodes", "$",
                    "Node count (" + materializedNodeCount + ") exceeds maximum limit of " + limits.maxNodeCount());
        }
        return new Node(edges);
    }

    private String parseLabel() {
        Token t = peekToken();
        if (t.type() == TokenType.STRING) {
            consumeToken();
            return (String) t.value();
        } else if (t.type() == TokenType.IDENT) {
            if (RESERVED_WORDS.contains(t.text())) {
                throw new OmlParseException(t.line(), t.col(), "parse.reserved-word-label", "Reserved word '" + t.text() + "' cannot be used as a bare label");
            }
            consumeToken();
            return t.text();
        } else {
            throw new OmlParseException(t.line(), t.col(), "parse.unexpected-token", "Expected edge label");
        }
    }

    private void parseArrayElements(String label, List<Edge> edges, int bracketLine, int bracketCol) {
        // Comma is the only element separator (OML-11): a newline or ';' where a comma belongs is
        // parse.separator-in-array, but ONLY when a further element follows it. Separators are
        // otherwise insignificant inside [...] (after '[', after a comma, before ']').
        skipSeparators();
        if (peekType() == TokenType.RBRACKET) {
            throw new OmlParseException(bracketLine, bracketCol, "parse.empty-array", "Empty array `[]` is an error");
        }

        while (true) {
            Token valToken = peekToken();
            if (valToken.type() == TokenType.LBRACKET) {
                throw new OmlParseException(valToken.line(), valToken.col(), "parse.nested-array", "Arrays cannot be nested inside arrays");
            } else if (valToken.type() == TokenType.LBRACE) {
                consumeToken(); // consume '{'
                edges.add(new Edge(label, parseBracedNode(valToken, edges, label)));
            } else {
                Value val = parseScalarValue(edges, label);
                edges.add(new Edge(label, val));
            }

            boolean hadSeparator = skipSeparators();
            if (peekType() == TokenType.COMMA) {
                consumeToken();
                skipSeparators();
                if (peekType() == TokenType.RBRACKET) {
                    consumeToken(); // trailing comma
                    return;
                }
                continue;
            }
            if (peekType() == TokenType.RBRACKET) {
                consumeToken();
                return;
            }
            Token cur = peekToken();
            String code = hadSeparator && startsElement(cur.type()) ? "parse.separator-in-array" : "parse.unexpected-token";
            throw new OmlParseException(cur.line(), cur.col(), code, "Expected ',' or ']' in array, got " + describe(cur));
        }
    }

    private static boolean startsElement(TokenType type) {
        return switch (type) {
            case STRING, DATETIME, DATE, TIME, NUMBER, INTEGER, IDENT, LBRACE, LBRACKET -> true;
            default -> false;
        };
    }

    private static String describe(Token t) {
        return t.type() == TokenType.EOF ? "end of input" : "'" + t.text() + "'";
    }

    /**
     * Parses one scalar.
     *
     * @param siblings the edge list the resulting edge will be added to, or {@code null} for the
     *                 top-level bare scalar (whose Document path is {@code $})
     * @param label    the edge label, or {@code null} for the top-level bare scalar
     */
    private Value parseScalarValue(List<Edge> siblings, String label) {
        Token t = peekToken();
        if (t.type() == TokenType.STRING) {
            consumeToken();
            return new Scalar.StringScalar((String) t.value());
        } else if (t.type() == TokenType.DATETIME) {
            consumeToken();
            return new Scalar.DateTimeScalar((DateTimeValue) t.value());
        } else if (t.type() == TokenType.DATE) {
            consumeToken();
            return new Scalar.DateScalar((LocalDate) t.value());
        } else if (t.type() == TokenType.TIME) {
            consumeToken();
            return new Scalar.TimeScalar((TimeValue) t.value());
        } else if (t.type() == TokenType.NUMBER) {
            consumeToken();
            return new Scalar.NumberScalar((Double) t.value());
        } else if (t.type() == TokenType.INTEGER) {
            consumeToken();
            if (t.value() == null) {
                // Over the digit limit (see OmlLexer). Keep parsing so the Document path can be
                // resolved against complete nodes; parseDocument raises it, first violation only.
                if (overLimitInteger == null) {
                    List<PathSeg> segs = new ArrayList<>(pathStack);
                    if (siblings != null) {
                        segs.add(new PathSeg(siblings, label, siblings.size()));
                    }
                    overLimitInteger = segs;
                    overLimitToken = t;
                }
                return new Scalar.IntegerScalar(BigInteger.ZERO);
            }
            return new Scalar.IntegerScalar((BigInteger) t.value());
        } else if (t.type() == TokenType.IDENT) {
            consumeToken();
            if ("null".equals(t.text())) return Value.NULL;
            if ("true".equals(t.text())) return new Scalar.BooleanScalar(true);
            if ("false".equals(t.text())) return new Scalar.BooleanScalar(false);
            throw new OmlParseException(t.line(), t.col(), "parse.bare-word", "Invalid scalar token: '" + t.text() + "'");
        } else {
            throw new OmlParseException(t.line(), t.col(), "parse.unexpected-token", "Expected scalar value");
        }
    }

    private boolean skipSeparators() {
        boolean hadSep = false;
        while (index < tokens.size() && tokens.get(index).type() == TokenType.SEPARATOR) {
            hadSep = true;
            index++;
        }
        return hadSep;
    }

    private Token peekToken() {
        // The fallback branch is not known to be reachable: OmlLexer.tokenizeAll()
        // always appends a real terminal EOF token, so index only reaches
        // tokens.size() if something calls consumeToken() one extra time after
        // already consuming that real EOF token -- no malformed-input probe this
        // session triggered that call pattern. Kept as a defensive bounds guard.
        if (index < tokens.size()) {
            return tokens.get(index);
        }
        return new Token(TokenType.EOF, "", null, -1, -1);
    }

    private TokenType peekType() {
        return peekToken().type();
    }

    private Token consumeToken() {
        Token t = peekToken();
        if (index < tokens.size()) {
            index++;
        }
        return t;
    }

    private Token peekNonSeparatorToken(int offset) {
        int count = 0;
        for (int i = index; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type() != TokenType.SEPARATOR) {
                if (count == offset) {
                    return t;
                }
                count++;
            }
        }
        return null;
    }
}
