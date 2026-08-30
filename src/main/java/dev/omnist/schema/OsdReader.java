package dev.omnist.schema;

import dev.omnist.schema.OsdLexer.Token;
import dev.omnist.schema.OsdLexer.TokenType;

import java.util.*;

/**
 * OSD (Omnist Schema Definition) Reader (omnist-spec §5).
 * Parses OSD text into a {@link Schema}.
 */
public class OsdReader {

    public static final int MAX_INPUT_LENGTH = 2_000_000;

    private final List<Token> tokens;
    private int index = 0;

    /**
     * Constructs a reader for the given OSD source text.
     * The source is immediately tokenized by {@link OsdLexer} during construction.
     *
     * @param source the OSD text to parse; {@code null} is treated as empty
     */
    public OsdReader(String source) {
        if (source != null && source.length() > MAX_INPUT_LENGTH) {
            throw new OsdParseException(1, 1, "schema.input-too-large", "$", "Input exceeds maximum length of " + MAX_INPUT_LENGTH + " characters");
        }
        OsdLexer lexer = new OsdLexer(source);
        this.tokens = lexer.tokenizeAll();
    }

    /**
     * Parses OSD text and returns the resulting {@link Schema}.
     * Convenience wrapper for {@code new OsdReader(source).parseSchema()}.
     *
     * @param source the OSD text; {@code null} is treated as empty
     * @return the fully-validated {@link Schema}
     * @throws OsdParseException if the text is syntactically or structurally invalid
     */
    public static Schema read(String source) {
        return new OsdReader(source).parseSchema();
    }

    /**
     * Parses the token stream into a {@link Schema} (omnist-spec §5.5–§5.8).
     * Processes {@code record} and {@code root} declarations in any order, then validates
     * that a root is declared, that the root record exists, and that all {@link Type.Ref}
     * targets resolve to defined records.
     *
     * @return the fully-validated {@link Schema}
     * @throws OsdParseException on duplicate record names, duplicate root declarations,
     *         missing root, undeclared root record, or unresolved type references
     */
    public Schema parseSchema() {

        String rootName = null;
        Map<String, Record> records = new LinkedHashMap<>();

        while (peekType() != TokenType.EOF) {
            Token t = peekToken();
            if (t.type() == TokenType.RECORD) {
                consumeToken(); // consume 'record'
                Record record = parseRecord();
                if (records.containsKey(record.name())) {
                    throw new OsdParseException(t.line(), t.col(), "schema.duplicate-record", record.name(), "Duplicate record definition: '" + record.name() + "'");
                }
                records.put(record.name(), record);
            } else if (t.type() == TokenType.ROOT) {
                consumeToken(); // consume 'root'
                if (rootName != null) {
                    throw new OsdParseException(t.line(), t.col(), "schema.duplicate-root", "$", "Duplicate root declaration");
                }
                Token nameTok = peekToken();
                if (nameTok.type() != TokenType.IDENT) {
                    throw new OsdParseException(nameTok.line(), nameTok.col(), "schema.no-root", "$", "Expected root record name identifier");
                }
                consumeToken();
                rootName = nameTok.text();
            } else {
                throw new OsdParseException(t.line(), t.col(), "schema.no-root", "$", "Expected 'record' or 'root' declaration");
            }
        }

        if (rootName == null) {
            throw new OsdParseException(1, 1, "schema.no-root", "$", "A schema must declare a root");
        }

        if (!records.containsKey(rootName)) {
            throw new OsdParseException(1, 1, "schema.unknown-type", "$", "Root record '" + rootName + "' is not defined in schema");
        }

        // Post-parse validation: check reference targets
        for (Record record : records.values()) {
            for (Field field : record.fields()) {
                if (field.type() instanceof Type.Ref ref) {
                    if (!records.containsKey(ref.name())) {
                        throw new OsdParseException(1, 1, "schema.unknown-type", record.name() + "." + field.label(), "Unknown type '" + ref.name() + "'");
                    }
                }
            }
        }

        return new Schema(rootName, records);
    }

    private Record parseRecord() {
        Token nameTok = peekToken();
        if (nameTok.type() != TokenType.IDENT) {
            throw new OsdParseException(nameTok.line(), nameTok.col(), "schema.parse-error", "$", "Expected record name identifier");
        }

        String recordName = nameTok.text();

        // §5.7 Reserved record names: cannot be scalar keyword or 'any'
        if (ScalarKind.fromKeyword(recordName) != null || "any".equals(recordName)) {
            throw new OsdParseException(nameTok.line(), nameTok.col(), "schema.reserved-name", recordName, "Reserved type name '" + recordName + "' cannot be used as a record name");
        }

        consumeToken(); // consume record name

        Token braceTok = peekToken();
        if (braceTok.type() != TokenType.LBRACE) {
            throw new OsdParseException(braceTok.line(), braceTok.col(), "schema.parse-error", recordName, "Expected '{' after record name");
        }
        consumeToken(); // consume '{'

        List<Field> fields = new ArrayList<>();
        java.util.Set<String> seenLabels = new java.util.HashSet<>();
        while (peekType() != TokenType.EOF && peekType() != TokenType.RBRACE) {
            Token labelTok = peekToken();

            if (labelTok.type() != TokenType.STRING) {
                throw new OsdParseException(labelTok.line(), labelTok.col(), "schema.unquoted-label", recordName, "Expected a quoted field name");
            }
            consumeToken(); // consume field label string
            String label = labelTok.text();

            if (!seenLabels.add(label)) {
                throw new OsdParseException(labelTok.line(), labelTok.col(), "schema.duplicate-field", recordName, "Duplicate field label '" + label + "' in record '" + recordName + "'");
            }

            int min = 1;
            Integer max = 1;

            if (peekType() == TokenType.CARDINALITY) {
                Token cardTok = consumeToken();
                CardBound cb = parseCardinality(cardTok, recordName + "." + label);
                min = cb.min;
                max = cb.max;
            }

            Token colonTok = peekToken();
            if (colonTok.type() != TokenType.COLON) {
                throw new OsdParseException(colonTok.line(), colonTok.col(), "schema.parse-error", recordName + "." + label, "Expected ':' after field label");
            }
            consumeToken(); // consume ':'

            Token typeTok = peekToken();
            if (typeTok.type() == TokenType.STRING) {
                throw new OsdParseException(typeTok.line(), typeTok.col(), "schema.quoted-type", recordName, "A quoted string cannot appear in type position");
            }
            if (typeTok.type() != TokenType.IDENT) {
                throw new OsdParseException(typeTok.line(), typeTok.col(), "schema.parse-error", recordName + "." + label, "Expected type name identifier");
            }
            consumeToken();

            String typeName = typeTok.text();
            boolean nullable = false;

            if (peekType() == TokenType.QUESTION) {
                consumeToken(); // consume '?'
                nullable = true;
            }

            Type fieldType;
            ScalarKind scalarKind = ScalarKind.fromKeyword(typeName);
            if (scalarKind != null) {
                fieldType = new Type.Scalar(scalarKind, nullable);
            } else if ("any".equals(typeName)) {
                if (nullable) {
                    throw new OsdParseException(typeTok.line(), typeTok.col(), "schema.nullable-any", recordName + "." + label, "any already includes null");
                }
                fieldType = Type.Any.INSTANCE;
            } else {
                if (nullable) {
                    throw new OsdParseException(typeTok.line(), typeTok.col(), "schema.nullable-ref", recordName + "." + label, "? cannot apply to a reference; use [0,1]");
                }
                fieldType = new Type.Ref(typeName);
            }

            fields.add(new Field(label, fieldType, min, max));

            if (peekType() == TokenType.COMMA) {
                consumeToken(); // consume optional trailing/separating comma
            }
        }

        if (peekType() != TokenType.RBRACE) {
            Token cur = peekToken();
            throw new OsdParseException(cur.line(), cur.col(), "schema.parse-error", recordName, "Expected '}' closing record definition");
        }
        consumeToken(); // consume '}'

        return new Record(recordName, fields);
    }

    private record CardBound(int min, Integer max) {}

    private CardBound parseCardinality(Token cardTok, String fieldPath) {
        String s = cardTok.text().trim();
        if (s.isEmpty()) {
            throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.empty-cardinality", fieldPath, "Empty cardinality '[]' is an error");
        }
        if (s.contains(".")) {
            throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.non-integer-cardinality", fieldPath, "Cardinality bound must be a whole number");
        }
        if (s.contains("-")) {
            throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.invalid-cardinality", fieldPath, "Cardinality bound cannot be negative");
        }

        try {
            if (!s.contains(",")) {
                int exact = Integer.parseInt(s);
                return new CardBound(exact, exact);
            }
            String[] parts = s.split(",", -1);
            if (parts.length != 2) {
                throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.invalid-cardinality", fieldPath, "Invalid cardinality format: [" + s + "]");
            }
            String minStr = parts[0].trim();
            String maxStr = parts[1].trim();

            int min = minStr.isEmpty() ? 0 : Integer.parseInt(minStr);
            Integer max = maxStr.isEmpty() ? null : Integer.parseInt(maxStr);

            if (max != null && max < min) {
                throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.invalid-cardinality", fieldPath, "Invalid cardinality: max (" + max + ") < min (" + min + ")");
            }
            if (min == 0 && max != null && max == 0) {
                throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.invalid-cardinality", fieldPath, "Cardinality [0,0] is redundant with not declaring the field");
            }
            return new CardBound(min, max);
        } catch (NumberFormatException e) {
            throw new OsdParseException(cardTok.line(), cardTok.col(), "schema.invalid-cardinality", fieldPath, "Invalid integer in cardinality: [" + s + "]");
        }
    }

    private Token peekToken() {
        // The fallback branch is not known to be reachable: OsdLexer's own
        // tokenizer always appends a real terminal EOF token, so index only
        // reaches tokens.size() if something calls consumeToken() one extra
        // time after already consuming that real EOF token -- no malformed-
        // schema probe this session triggered that call pattern. Kept as a
        // defensive bounds guard (same reasoning as OmlReader's identical case).
        if (index < tokens.size()) {
            return tokens.get(index);
        }
        return new Token(TokenType.EOF, "", -1, -1);
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
}
