package dev.omnist.codec;

/**
 * Thrown by a codec's {@code write}/{@code check} path when a document cannot be
 * written to the target format at all (e.g. TOML requires a top-level table), or
 * when a strict-mode write encountered an adjustment it was told not to tolerate.
 */
public class WriteException extends RuntimeException {
    /** The adjustments recorded before this exception was thrown; empty if none. */
    private final WriteReport report;

    /**
     * Constructs a write exception with no recorded adjustments.
     *
     * @param message a human-readable description of the failure
     */
    public WriteException(String message) {
        super(message);
        this.report = new WriteReport();
    }

    /**
     * Constructs a write exception carrying the adjustments that led to it
     * (typically used in strict mode, where any adjustment is itself the failure).
     *
     * @param message a human-readable description of the failure
     * @param report  the adjustments recorded before this exception was thrown
     */
    public WriteException(String message, WriteReport report) {
        super(message);
        this.report = report;
    }

    /**
     * Returns the adjustments recorded before this exception was thrown.
     */
    public WriteReport report() {
        return report;
    }
}
