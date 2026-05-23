package wev.smt.parse;

/**
 * A recoverable failure while parsing a {@code .litmus} file. Carries the source
 * name and (where known) the 1-based line number so a corpus run can log a precise,
 * actionable warning and move on rather than aborting on a single bad file.
 *
 * <p>The {@link Kind} distinguishes a genuinely <em>malformed</em> file (a structural
 * parse error) from one this prototype simply <em>does not support</em> (unknown
 * architecture, an instruction outside the modelled subset, or an empty file). Both
 * are non-fatal to {@link LitmusParser#parseDirectory}; the distinction only changes
 * the wording of the warning (see {@code wev.smt.cli.CorpusValidation}).
 */
public final class ParseException extends RuntimeException {

    /** Why a file could not be turned into a {@link LitmusCase}. */
    public enum Kind {
        /** A structural parse error: the file does not follow the .litmus grammar. */
        MALFORMED,
        /** The architecture token is not one this parser maps to WEV events. */
        UNSUPPORTED_ARCH,
        /** An instruction is outside the modelled read/write/fence/RMW subset. */
        UNSUPPORTED_INSTRUCTION,
        /** The file is empty or contains only comments/whitespace. */
        EMPTY
    }

    private final String sourceName;
    private final int line;
    private final Kind kind;

    public ParseException(String sourceName, int line, Kind kind, String message) {
        super(format(sourceName, line, kind, message));
        this.sourceName = sourceName;
        this.line = line;
        this.kind = kind;
    }

    public String sourceName() { return sourceName; }

    /** 1-based line number, or {@code -1} when not tied to a specific line. */
    public int line() { return line; }

    public Kind kind() { return kind; }

    /** Whether this is a "skip with a warning" condition rather than a hard error. */
    public boolean isSkippable() {
        return kind != Kind.MALFORMED;
    }

    private static String format(String sourceName, int line, Kind kind, String message) {
        String at = (line > 0) ? sourceName + ":" + line : sourceName;
        return "[" + at + "] " + kind + ": " + message;
    }
}
