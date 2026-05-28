package wev.smt.validate;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of an {@link InputValidator#validate} run: whether the event structure
 * is well-formed enough to encode, plus the diagnostics found.
 *
 * <p>An {@code error} is a structural defect that makes the SMT verdict meaningless —
 * a {@code po} cycle, a read with no write to justify it, conflicting initial values,
 * a relation referencing an event that is not in the structure. Any error makes the
 * structure {@linkplain #valid() invalid}; {@link InputValidator}'s callers throw an
 * {@link InvalidEventStructureException} rather than hand a corrupt structure to Z3
 * (which would silently report it UNSAT, indistinguishable from a real verdict).
 *
 * <p>A {@code warning} is a benign irregularity that does <em>not</em> invalidate the
 * structure — e.g. two events in the same thread that program order leaves incomparable.
 * Warnings are surfaced for the reviewer's benefit but never halt the analysis. (The
 * thread-0 initial writes are legitimately po-incomparable, so the "po total order
 * per-thread" check exempts thread 0 and is a warning, never an error — see
 * {@link InputValidator}.)
 *
 * <p>{@code valid} is derived: it is {@code true} iff {@link #errors()} is empty.
 * Build instances through {@link #of(List, List)} so the invariant always holds.
 */
public record ValidationReport(boolean valid, List<String> errors, List<String> warnings) {

    public ValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /** A report whose {@code valid} flag is {@code true} exactly when {@code errors} is empty. */
    public static ValidationReport of(List<String> errors, List<String> warnings) {
        return new ValidationReport(errors.isEmpty(), errors, warnings);
    }

    /** A clean report: valid, no errors, no warnings. */
    public static ValidationReport ok() {
        return new ValidationReport(true, List.of(), List.of());
    }

    /** A human-readable, multi-line rendering of every error and warning. */
    public String describe() {
        List<String> lines = new ArrayList<>();
        lines.add(valid ? "valid (" + warnings.size() + " warning(s))"
                        : "INVALID (" + errors.size() + " error(s), "
                                + warnings.size() + " warning(s))");
        for (String e : errors) lines.add("  ERROR:   " + e);
        for (String w : warnings) lines.add("  WARNING: " + w);
        return String.join(System.lineSeparator(), lines);
    }
}
