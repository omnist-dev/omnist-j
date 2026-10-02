package dev.omnist.schema;

/**
 * Thrown when a programmatically built schema violates a well-formedness rule of
 * omnist-spec section 3.3 that is enforced at construction.
 *
 * <p>Two codes are raised here:
 * <ul>
 *   <li>{@code schema.invalid-name} (S-8): a record name, root name or {@link Type.Ref} target does
 *       not match {@code [A-Za-z_][A-Za-z0-9_]*}. The path is always {@code "$"}; the offending name
 *       appears in the message only, because it may be malformed.</li>
 *   <li>{@code schema.invalid-label} (S-22): a field label does not encode to valid UTF-8 (in Java, a
 *       lone UTF-16 surrogate). The path is the name of the record holding the field; the label is
 *       never put in the path.</li>
 * </ul>
 */
public class SchemaException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Machine-readable error code. */
    private final String code;
    /** The schema path where the violation was detected. */
    private final String path;

    /**
     * Constructs a schema well-formedness exception.
     *
     * @param code    machine-readable error code, e.g. {@code schema.invalid-label}
     * @param path    the schema path of the violation ({@code "$"} or a record name)
     * @param message human-readable description
     */
    public SchemaException(String code, String path, String message) {
        super("Schema error (" + path + " | " + code + "): " + message);
        this.code = code;
        this.path = path;
    }

    /**
     * Returns the machine-readable error code.
     *
     * @return the error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the schema path where the violation was detected.
     *
     * @return the path
     */
    public String getPath() {
        return path;
    }
}
