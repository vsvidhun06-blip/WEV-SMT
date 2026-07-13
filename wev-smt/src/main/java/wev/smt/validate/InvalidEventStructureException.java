package wev.smt.validate;

/**
 * Thrown when an {@link wev.smt.validate.InputValidator} check finds a structural
 * defect that makes a candidate execution meaningless to encode — a program-order
 * cycle, a read with no write to read from, conflicting initial values, or a relation
 * pointing at an event that is not in the structure.
 *
 * <p>The point is to surface such inputs as a <em>clear, diagnosable error</em> rather
 * than letting them reach Z3, where well-formedness would come back UNSAT and be
 * mistaken for a genuine "this outcome is forbidden" verdict. The full
 * {@link ValidationReport} (every error and warning) is carried on the exception so the
 * caller — or a reviewer — can see exactly what was wrong.
 *
 * <p>It extends {@link RuntimeException} so it threads through the existing
 * {@code LitmusParser.parseDirectory} catch-and-skip path (a single malformed corpus
 * file is skipped, never fatal to the whole run).
 */
public class InvalidEventStructureException extends RuntimeException {

    private final transient ValidationReport report;

    public InvalidEventStructureException(ValidationReport report) {
        super("invalid event structure: " + System.lineSeparator() + report.describe());
        this.report = report;
    }

    public InvalidEventStructureException(String context, ValidationReport report) {
        super(context + ": " + System.lineSeparator() + report.describe());
        this.report = report;
    }

    /** The full validation report (errors + warnings) that triggered this exception. */
    public ValidationReport report() {
        return report;
    }
}
