package dev.omnist.oml;

import dev.omnist.document.*;
import dev.omnist.oml.OmlLexer.Token;
import dev.omnist.oml.OmlLexer.TokenType;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * OML (Omnist Markup Language) Core Reader (omnist-spec §4).
 * Reads OML-Core text format into a {@link Document} using {@link OmlLexer}.
 */
public class OmlReader {

    private final List<Token> tokens;
    private final Limits limits;
    private int index = 0;

    private int currentDepth = 1;
    private int materializedNodeCount = 0;

    public OmlReader(String source, Limits limits) {
        this.limits = limits != null ? limits : Limits.DEFAULT;
        OmlLexer lexer = new OmlLexer(source, this.limits);
        this.tokens = lexer.tokenizeAll();
    }

    public static Document read(String source) {
        return read(source, Limits.DEFAULT);
    }

    public static Document read(String source, Limits limits) {
        return new OmlReader(source, limits).parseDocument();
    }

    public Document parseDocument() {
        skipSeparators();
        if (peekType() == TokenType.EOF) {
            return createNode(List.of());
        }

        if (isEdgeListStart()) {
            Node rootNode = parseNodeEdges(false);
            skipSeparators();
            if (peekType() != TokenType.EOF) {
                Token extra = peekToken();
                throw new OmlParseException(extra.line(), extra.col(), "Trailing content after top-level document");
            }
            return rootNode;
        } else {
            Value bareValue = parseScalarValue();
            skipSeparators();
            if (peekType() != TokenType.EOF) {
                Token extra = peekToken();
                throw new OmlParseException(extra.line(), extra.col(), "Trailing content after bare scalar document");
            }
            return bareValue;
        }
    }

    private boolean isEdgeListStart() {
        Token t1 = peekNonSeparatorToken(0);
        if (t1 == null) return false;

        if (t1.type() == TokenType.STRING) {
            Token t2 = peekNonSeparatorToken(1);
            return t2 != null && t2.type() == TokenType.COLON;
        } else if (t1.type() == TokenType.IDENT) {
            if ("null".equals(t1.text()) || "true".equals(t1.text()) || "false".equals(t1.text())) {
                return false;
            }
            Token t2 = peekNonSeparatorToken(1);
            return t2 != null && t2.type() == TokenType.COLON;
        }
        return false;
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
                throw new OmlParseException(cur.line(), cur.col(), "Expected ':' after edge label '" + label + "'");
            }
            consumeToken(); // consume ':'

            skipSeparators();

            if (peekType() == TokenType.EOF) {
                Token cur = peekToken();
                throw new OmlParseException(cur.line(), cur.col(), "Expected value after ':'");
            }

            Token valueStart = peekToken();

            if (valueStart.type() == TokenType.LBRACKET) {
                consumeToken(); // consume '['
                parseArrayElements(label, edges);
            } else if (valueStart.type() == TokenType.LBRACE) {
                consumeToken(); // consume '{'
                currentDepth++;
                if (currentDepth > limits.maxDepth()) {
                    throw new OmlParseException(valueStart.line(), valueStart.col(),
                            "Nesting depth (" + currentDepth + ") exceeds maximum limit of " + limits.maxDepth());
                }
                Node childNode = parseNodeEdges(true);
                skipSeparators();
                if (peekType() != TokenType.RBRACE) {
                    Token cur = peekToken();
                    throw new OmlParseException(cur.line(), cur.col(), "Expected '}' closing braced node");
                }
                consumeToken(); // consume '}'
                currentDepth--;
                edges.add(new Edge(label, childNode));
            } else {
                Value val = parseScalarValue();
                edges.add(new Edge(label, val));
            }

            boolean HadSep = skipSeparators();
            if (peekType() != TokenType.EOF) {
                if (insideBraces && peekType() == TokenType.RBRACE) {
                    break;
                }
                if (!HadSep) {
                    Token cur = peekToken();
                    throw new OmlParseException(cur.line(), cur.col(), "Edge separator (newline or ';') required between adjacent edges");
                }
            }
        }

        return createNode(edges);
    }

    private Node createNode(List<Edge> edges) {
        materializedNodeCount++;
        if (materializedNodeCount > limits.maxNodeCount()) {
            Token cur = peekToken();
            throw new OmlParseException(cur.line(), cur.col(),
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
            if ("null".equals(t.text()) || "true".equals(t.text()) || "false".equals(t.text())) {
                throw new OmlParseException(t.line(), t.col(), "Reserved word '" + t.text() + "' cannot be used as a bare label");
            }
            consumeToken();
            return t.text();
        } else {
            throw new OmlParseException(t.line(), t.col(), "Expected edge label");
        }
    }

    private void parseArrayElements(String label, List<Edge> edges) {
        skipSeparators();
        if (peekType() == TokenType.RBRACKET) {
            Token cur = peekToken();
            throw new OmlParseException(cur.line(), cur.col(), "Empty array `[]` is an error");
        }

        boolean first = true;
        while (peekType() != TokenType.EOF) {
            skipSeparators();
            if (peekType() == TokenType.RBRACKET) {
                consumeToken();
                break;
            }

            if (!first) {
                if (peekType() == TokenType.COMMA) {
                    consumeToken();
                    skipSeparators();
                    if (peekType() == TokenType.RBRACKET) {
                        consumeToken();
                        break;
                    }
                } else {
                    Token cur = peekToken();
                    throw new OmlParseException(cur.line(), cur.col(), "Expected ',' between array elements");
                }
            }

            Token valToken = peekToken();
            if (valToken.type() == TokenType.LBRACKET) {
                throw new OmlParseException(valToken.line(), valToken.col(), "Arrays cannot be nested inside arrays");
            } else if (valToken.type() == TokenType.LBRACE) {
                consumeToken(); // consume '{'
                currentDepth++;
                if (currentDepth > limits.maxDepth()) {
                    throw new OmlParseException(valToken.line(), valToken.col(),
                            "Nesting depth (" + currentDepth + ") exceeds maximum limit of " + limits.maxDepth());
                }
                Node childNode = parseNodeEdges(true);
                skipSeparators();
                if (peekType() != TokenType.RBRACE) {
                    Token cur = peekToken();
                    throw new OmlParseException(cur.line(), cur.col(), "Expected '}' closing braced node");
                }
                consumeToken();
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
            return new Scalar.IntegerScalar((BigInteger) t.value());
        } else if (t.type() == TokenType.IDENT) {
            consumeToken();
            if ("null".equals(t.text())) return Value.NULL;
            if ("true".equals(t.text())) return new Scalar.BooleanScalar(true);
            if ("false".equals(t.text())) return new Scalar.BooleanScalar(false);
            throw new OmlParseException(t.line(), t.col(), "Invalid scalar token: '" + t.text() + "'");
        } else {
            throw new OmlParseException(t.line(), t.col(), "Expected scalar value");
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
