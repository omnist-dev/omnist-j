package dev.omnist.codec;

import java.util.Set;

/**
 * A raw-text scan for the two data-XML profile refusals that must be decided before the XML
 * parser sees the input (omnist-spec {@code docs/formats/xml.md}, "The data-XML profile").
 *
 * <p>The profile refuses a {@code DOCTYPE} declaration of any kind
 * ({@code format.dtd-forbidden}) and any entity reference other than the five predefined ones
 * ({@code format.entity-forbidden}), including inside attribute values, on sight. Both are
 * well-formed XML that Omnist declines, so neither may be reported as a syntax error, and a
 * genuinely malformed document must still be reported as one. This scanner therefore does not
 * decide anything by itself: it finds the first violation and produces a <em>sanitized</em> copy
 * of the text (the {@code DOCTYPE} declaration blanked out, each refused entity reference replaced by
 * same-length filler, so positions are unchanged) that no longer contains anything the profile refuses. The caller parses the
 * sanitized copy: a failure there is malformed XML ({@code parse.codec-syntax}); a success means
 * the input was well-formed and the recorded violation is the refusal to report.
 *
 * <p>Comments, CDATA sections and processing instructions (which include the XML declaration) are
 * inert: nothing inside them is a violation. Numeric character references and the five predefined
 * entities stay legal. Unterminated constructs are left untouched so the real parser reports them.
 */
final class XmlProfileScan {

    enum Kind { NONE, DTD, ENTITY }

    private static final Set<String> PREDEFINED = Set.of("lt", "gt", "amp", "quot", "apos");

    /** The first refusal found, in document order. */
    final Kind kind;
    /** The text to hand the XML parser: no {@code DOCTYPE}, no refused entity reference. */
    final String sanitized;

    private XmlProfileScan(Kind kind, String sanitized) {
        this.kind = kind;
        this.sanitized = sanitized;
    }

    private static final class Cursor {
        final String t;
        final StringBuilder out;
        Kind first = Kind.NONE;
        int i = 0;

        Cursor(String t) {
            this.t = t;
            this.out = new StringBuilder(t.length());
        }

        void note(Kind k) {
            if (first == Kind.NONE) {
                first = k;
            }
        }
    }

    static XmlProfileScan scan(String text) {
        return run(text);
    }

    private static XmlProfileScan run(String text) {
        Cursor c = new Cursor(text);
        int n = text.length();
        while (c.i < n) {
            char ch = text.charAt(c.i);
            if (ch == '<') {
                if (text.startsWith("<!--", c.i)) {
                    copyThrough(c, "-->", 4);
                } else if (text.startsWith("<![CDATA[", c.i)) {
                    copyThrough(c, "]]>", 9);
                } else if (text.startsWith("<?", c.i)) {
                    copyThrough(c, "?>", 2);
                } else if (text.startsWith("<!DOCTYPE", c.i)) {
                    int end = doctypeEnd(text, c.i);
                    if (end < 0) {
                        // Unterminated: not a well-formed DOCTYPE, so not ours to refuse.
                        c.out.append(text, c.i, n);
                        c.i = n;
                    } else {
                        c.note(Kind.DTD);
                        // Blank the declaration out rather than delete it, keeping every later
                        // line and column where the original text has it (E-11 positions).
                        for (int k = c.i; k < end; k++) {
                            char q = text.charAt(k);
                            c.out.append(q == '\n' || q == '\r' ? q : ' ');
                        }
                        c.i = end;
                    }
                } else {
                    tag(c);
                }
            } else if (ch == '&') {
                reference(c);
            } else {
                c.out.append(ch);
                c.i++;
            }
        }
        return new XmlProfileScan(c.first, c.out.toString());
    }

    /** Copies an inert construct through its terminator (or to the end, if unterminated). */
    private static void copyThrough(Cursor c, String terminator, int openerLength) {
        int end = c.t.indexOf(terminator, c.i + openerLength);
        int stop = end < 0 ? c.t.length() : end + terminator.length();
        c.out.append(c.t, c.i, stop);
        c.i = stop;
    }

    /** Copies a start or end tag, checking entity references inside quoted attribute values. */
    private static void tag(Cursor c) {
        int n = c.t.length();
        c.out.append('<');
        c.i++;
        char quote = 0;
        while (c.i < n) {
            char d = c.t.charAt(c.i);
            if (quote != 0) {
                if (d == quote) {
                    quote = 0;
                } else if (d == '&') {
                    reference(c);
                    continue;
                }
            } else if (d == '"' || d == '\'') {
                quote = d;
            } else if (d == '<') {
                return; // malformed: leave it to the parser
            } else if (d == '>') {
                c.out.append(d);
                c.i++;
                return;
            }
            c.out.append(d);
            c.i++;
        }
    }

    /** Handles an {@code &} at the cursor: keeps legal references, replaces refused ones. */
    private static void reference(Cursor c) {
        int n = c.t.length();
        int start = c.i + 1;
        if (start < n && c.t.charAt(start) == '#') {
            c.out.append('&');
            c.i++;
            return;
        }
        int end = start;
        while (end < n && isNameChar(c.t.charAt(end))) {
            end++;
        }
        if (end > start && end < n && c.t.charAt(end) == ';'
                && !PREDEFINED.contains(c.t.substring(start, end))) {
            c.note(Kind.ENTITY);
            c.out.append("x".repeat(end + 1 - c.i)); // same length: positions stay exact
            c.i = end + 1;
            return;
        }
        c.out.append('&');
        c.i++;
    }

    private static boolean isNameChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == ':' || ch == '.' || ch == '-' || ch > 0x7F;
    }

    /**
     * Returns the index just past a {@code DOCTYPE} declaration that starts at {@code from},
     * honouring its internal subset, quoted literals, comments and processing instructions, or
     * {@code -1} if it is never terminated.
     */
    private static int doctypeEnd(String t, int from) {
        int n = t.length();
        int depth = 0;
        int i = from + "<!DOCTYPE".length();
        while (i < n) {
            char ch = t.charAt(i);
            if (ch == '"' || ch == '\'') {
                int close = t.indexOf(ch, i + 1);
                if (close < 0) {
                    return -1;
                }
                i = close + 1;
            } else if (t.startsWith("<!--", i)) {
                int close = t.indexOf("-->", i + 4);
                if (close < 0) {
                    return -1;
                }
                i = close + 3;
            } else if (t.startsWith("<?", i)) {
                int close = t.indexOf("?>", i + 2);
                if (close < 0) {
                    return -1;
                }
                i = close + 2;
            } else if (ch == '[') {
                depth++;
                i++;
            } else if (ch == ']') {
                depth--;
                i++;
            } else if (ch == '>' && depth <= 0) {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }
}
