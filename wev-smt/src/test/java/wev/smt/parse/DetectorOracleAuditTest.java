package wev.smt.parse;

import org.junit.jupiter.api.Test;

import wev.smt.QfbvConstancyOracle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Differential detector audit — build gate.</b>
 *
 * <p>This audit checks that the shipped detector and the exact SMT oracle agree on every
 * audited expression. Any divergence fails the build.
 *
 * <p>Precisely: for each audited pair {@code (expr, reg)} it compares the shipped
 * {@code LitmusParser.Job.classify} path (no oracle installed — idiom folding plus
 * linear-cancellation normalisation) against {@link QfbvConstancyOracle#isConstant}. Two
 * divergence directions exist and they are not equivalent:
 *
 * <ul>
 *   <li><b>UNSOUND</b> — detector {@code FAKE}, oracle proves the expression <em>varies</em>.
 *       A genuinely value-carrying dependency has been dropped from {@code sdep}; removing
 *       an edge can break a {@code jf ∪ sdep} cycle and turn FORBIDDEN into ALLOWED.
 *       <b>Fails the build.</b></li>
 *   <li><b>CONSERVATIVE</b> — detector {@code REAL}, oracle proves the expression is
 *       <em>constant</em>. The documented behaviour of the solver-free fallback, which
 *       bails to {@code REAL} on anything the linear pass cannot normalise (e.g.
 *       {@code r0 & 1 & 0}). Sound, so it does not fail the build; the count is pinned so a
 *       silent precision regression is still caught.</li>
 * </ul>
 *
 * <p>This is the fast gate that runs on every {@code mvn test}: the pinned regression
 * witnesses plus a small deterministic enumeration. The full sweep — a larger enumeration
 * plus every {@code (expr, reg)} pair the 2972-test corpus actually produces — runs as the
 * {@code detector_oracle_audit} step of {@code eval/run_all.py}
 * ({@link wev.smt.cli.DetectorOracleAudit}).
 *
 * <p>{@code classify} is private to a private nested class, so it is reached by
 * reflection. The audit <em>observes</em> the shipped method; no production code is
 * modified and no accessor is added to the parser for the benefit of a test.
 */
class DetectorOracleAuditTest {

    private static final String REG = "r0";

    /**
     * Witnesses of the precedence-spanning folding defect fixed on 2026-07-28. Each is
     * genuinely non-constant in {@code r0}; before the fix the folder rewrote the
     * expression string across an operand boundary and reported them FAKE.
     */
    private static final String[][] WITNESSES = {
            { "~r0 - r0",           "(~r0) - r0 == -2*r0 - 1" },
            { "r0 ^ r0 + 1",        "r0 ^ (r0 + 1)" },
            { "r0 & ~r0 + 1",       "r0 & (~r0 + 1) == r0 & -r0" },
            { "r0 & r0 - r0 + 3",   "r0 & ((r0 - r0) + 3) == r0 & 3" },
            { "~7 - r0 ^ r0",       "(~7 - r0) ^ r0" },
            { "~r0 ^ r0 + r0 ^ r0", "(~r0) ^ (r0 + r0) ^ r0" },
            { "~r0 & r0 ^ r0",      "(~r0 & r0) ^ r0 == r0" },
    };

    /** Every pinned witness must be classified REAL, and the oracle must agree. */
    @Test
    void foldingRegressionWitnessesAgreeWithOracle() throws Exception {
        Method classify = reflectClassify();
        try (QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            List<String> unsound = new ArrayList<>();
            for (String[] w : WITNESSES) {
                Boolean constant = oracle.isConstant(w[0], REG);
                assertTrue(constant != null && !constant,
                        "precondition: `" + w[0] + "` must genuinely vary — " + w[1]);
                if (!detectorSaysReal(classify, w[0], REG)) unsound.add(w[0] + "   (" + w[1] + ")");
            }
            assertTrue(unsound.isEmpty(),
                    "detector reports FAKE where the oracle proves the value VARIES —\n"
                    + "a real dependency would be dropped from sdep:\n  "
                    + String.join("\n  ", unsound));
        }
    }

    /**
     * Deterministic enumeration smoke gate. No expression may be classified FAKE when the
     * oracle proves it varies. The conservative count is pinned separately so a precision
     * regression also surfaces.
     */
    @Test
    void enumeratedExpressionsHaveNoUnsoundDivergence() throws Exception {
        Method classify = reflectClassify();
        int checked = 0, conservative = 0;
        List<String> unsound = new ArrayList<>();
        try (QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            for (String e : enumerate(ENUM_LIMIT)) {
                Boolean constant = oracle.isConstant(e, REG);
                if (constant == null) continue;             // outside the modelled grammar
                checked++;
                boolean real = detectorSaysReal(classify, e, REG);
                if (!real && !constant) unsound.add(e);      // FAKE but varies  -> defect
                else if (real && constant) conservative++;   // REAL but constant -> sound
            }
        }
        assertEquals(ENUM_LIMIT, checked,
                "enumeration size changed — re-pin ENUM_LIMIT and CONSERVATIVE_PIN together");
        assertTrue(unsound.isEmpty(),
                "UNSOUND divergences (detector=FAKE, oracle=VARIES): " + unsound);
        assertEquals(CONSERVATIVE_PIN, conservative,
                "conservative (sound over-approximation) count changed from the pinned value. "
                + "The detector's PRECISION moved. This is not a soundness failure, but it is "
                + "unintended unless someone deliberately changed the fallback — re-pin "
                + "CONSERVATIVE_PIN if the change is wanted.");
    }

    // Pinned on 2026-07-29 against the post-fix detector. Both numbers move together:
    // changing ENUM_LIMIT requires re-measuring CONSERVATIVE_PIN.
    private static final int ENUM_LIMIT = 600;
    private static final int CONSERVATIVE_PIN = 95;

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Method reflectClassify() throws Exception {
        Class<?> job = Class.forName("wev.smt.parse.LitmusParser$Job");
        Method m = job.getDeclaredMethod("classify", String.class, String.class);
        m.setAccessible(true);
        return m;
    }

    private static boolean detectorSaysReal(Method classify, String expr, String reg)
            throws Exception {
        Object verdict = classify.invoke(null, expr, reg);
        Method real = verdict.getClass().getDeclaredMethod("real");
        real.setAccessible(true);
        return (Boolean) real.invoke(verdict);
    }

    /** Same alphabet and deterministic order as {@link wev.smt.cli.DetectorOracleAudit}. */
    private static LinkedHashSet<String> enumerate(int limit) {
        String[] consts = { "0", "1", "2", "3", "7", "8", "255" };
        String[] bin = { "+", "-", "&", "^", "|", "*" };
        List<String> atoms = new ArrayList<>();
        atoms.add(REG);
        atoms.add("~" + REG);
        for (String c : consts) { atoms.add(c); atoms.add("~" + c); }

        LinkedHashSet<String> cur = new LinkedHashSet<>(atoms);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int d = 1; d <= 2; d++) {
            LinkedHashSet<String> next = new LinkedHashSet<>();
            for (String left : cur) {
                for (String op : bin) {
                    for (String a : atoms) {
                        String e = left + " " + op + " " + a;
                        next.add(e);
                        if (e.matches(".*(?<![A-Za-z0-9_])" + REG + "(?![A-Za-z0-9_]).*")) {
                            out.add(e);
                            if (limit > 0 && out.size() >= limit) return out;
                        }
                    }
                }
            }
            cur = next;
        }
        return out;
    }
}
