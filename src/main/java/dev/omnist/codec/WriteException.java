package dev.omnist.codec;

public class WriteException extends RuntimeException {
    private final WriteReport report;

    public WriteException(String message) {
        super(message);
        this.report = new WriteReport();
    }

    public WriteException(String message, WriteReport report) {
        super(message);
        this.report = report;
    }

    public WriteReport report() {
        return report;
    }
}
