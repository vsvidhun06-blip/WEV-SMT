package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.junit.jupiter.api.Test;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;

import wev.smt.AxiomaticConsistency;
import wev.smt.DependencyInfo;
import wev.smt.EventStructureEncoder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the paper §4 worked example. The triplet under
 * {@code eval/examples/paper/} demonstrates the {@code isSemantic} flag's role
 * in the WEAKEST verdict:
 *
 * <ul>
 *   <li>{@code LB-real.litmus} — store value {@code r+1} is a real data dep
 *       (isSemantic = true); WEAKEST FORBIDS the LB cycle r0=r1=1 via
 *       {@link AxiomaticConsistency#jfCoherence}.</li>
 *   <li>{@code LB-fake-xor-cycle.litmus} — store value {@code r^r^1} is a fake
 *       data dep that {@code dependsReally} folds to constant 1
 *       (isSemantic = false); WEAKEST ALLOWS the same wired candidate r0=r1=1
 *       because the fake dep is excluded from {@code sdep}, so jfCoherence
 *       sees no cycle in {@code sdep ∪ jf}. <b>This is the contrast figure:</b>
 *       identical structure and identical wired candidate to {@code LB-real},
 *       only {@code isSemantic} flips — and only WEAKEST's verdict flips.</li>
 *   <li>{@code LB-fake-xor.litmus} — store value {@code r^r} folds to 0, so the
 *       program writes are 0 and the parser cannot wire rf to a write of 1;
 *       the wired candidate is the degenerate r0=r1=0, not the LB cycle. The
 *       file exists to document this wiring caveat: WEAKEST returns SAT but
 *       only trivially-SAT, not as a jfCoherence verdict on the LB cycle.</li>
 * </ul>
 *
 * <p>Assertions per file cover what the paper states: the {@code isSemantic}
 * classification, the constant the write carries after {@code evalConst}, the
 * wired-rf candidate outcome (read values), and the WEAKEST verdict.
 */
class PaperExampleTest {

    private static final Path EXAMPLES = Path.of("eval", "examples", "paper");

    /** LB-real: real {@code r+1} dep → isSemantic=true → WEAKEST FORBIDS LB. */
    @Test
    void lbRealForbiddenUnderWeakest() throws Exception {
        LitmusCase lc = parse("LB-real.litmus");

        // Two data deps, both genuinely semantic.
        assertEquals(2, lc.deps().semanticEdges().size(),
                "two genuine r+1 data deps, both isSemantic=true");

        // Program writes carry constant 1, wired rf points at the program writes,
        // so the candidate execution is the LB cycle r0=r1=1.
        assertProgramWrite(lc, "x", 1);
        assertProgramWrite(lc, "y", 1);
        assertReadValue(lc, /*thread*/ 1, "r0", "y", 1);
        assertReadValue(lc, /*thread*/ 2, "r1", "x", 1);

        // jfCoherence kills the sdep∪jf cycle: LB cycle r0=r1=1 is FORBIDDEN.
        assertTrue(weakestUnsat(lc),
                "WEAKEST forbids LB with a real data dep — jfCoherence axiom fires");
    }

    /**
     * LB-fake-xor-cycle: fake {@code r^r^1} dep → isSemantic=false → WEAKEST
     * ALLOWS the same wired candidate. Paired with {@link #lbRealForbiddenUnderWeakest}
     * this is the paper's contrast figure.
     */
    @Test
    void lbFakeXorCycleAllowedUnderWeakest() throws Exception {
        LitmusCase lc = parse("LB-fake-xor-cycle.litmus");

        // Two data deps recorded but both folded to fake by dependsReally
        // (r^r^1 cancels to constant 1).
        assertEquals(0, lc.deps().semanticEdges().size(),
                "r^r^1 cancels to constant 1; both data deps are isSemantic=false");
        assertEquals(2, totalDataDeps(lc),
                "the syntactic dep is still recorded (the store mentions the read)");

        // evalConst genuinely produces 1, so the wired candidate is r0=r1=1,
        // identical to LB-real — only isSemantic differs.
        assertProgramWrite(lc, "x", 1);
        assertProgramWrite(lc, "y", 1);
        assertReadValue(lc, 1, "r0", "y", 1);
        assertReadValue(lc, 2, "r1", "x", 1);

        // With sdep empty, jfCoherence's sdep∪jf relation has no cycle: LB is ALLOWED.
        assertFalse(weakestUnsat(lc),
                "WEAKEST allows LB with a fake dep — jfCoherence finds no sdep∪jf cycle");
    }

    /**
     * LB-fake-xor: literal {@code r^r} folds to 0, so the program writes are
     * value 0 and the wired candidate degenerates to r0=r1=0 (not the LB
     * cycle). Documents the wiring caveat — WEAKEST=SAT here is trivially-SAT,
     * not the jfCoherence verdict the paper §4 main figure relies on.
     */
    @Test
    void lbFakeXorClassifiesAsFakeButWiringIsDegenerate() throws Exception {
        LitmusCase lc = parse("LB-fake-xor.litmus");

        // dependsReally folds r^r → 0 and reclassifies both data deps as fake.
        assertEquals(0, lc.deps().semanticEdges().size(),
                "r^r cancels; both data deps are isSemantic=false");
        assertEquals(2, totalDataDeps(lc),
                "the syntactic dep is still recorded");

        // BUT evalConst(r^r) = 0, so the program write value is 0 — not 1 as
        // the exists clause demands. The wired-rf wiring degrades to the
        // initial writes (parser emits a stderr warning), so the candidate
        // execution under consistency is r0=r1=0, not the intended LB cycle.
        assertProgramWrite(lc, "x", 0);
        assertProgramWrite(lc, "y", 0);
        assertReadValue(lc, 1, "r0", "y", /*from init*/ 0);
        assertReadValue(lc, 2, "r1", "x", 0);

        // WEAKEST is SAT here too, but only because the all-zeros candidate is
        // trivially SC-consistent — NOT because of jfCoherence on a LB cycle.
        // The cycle version is LB-fake-xor-cycle.litmus.
        assertFalse(weakestUnsat(lc),
                "WEAKEST trivially allows the degenerate r=r=0 candidate");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static LitmusCase parse(String filename) throws Exception {
        Path file = EXAMPLES.resolve(filename);
        return LitmusParser.parse(Files.readString(file), filename);
    }

    private static int totalDataDeps(LitmusCase lc) {
        int n = 0;
        for (Event e : lc.es().getEvents()) n += lc.deps().getDataDeps(e).size();
        return n;
    }

    private static void assertProgramWrite(LitmusCase lc, String var, int expected) {
        WriteEvent w = lc.es().getEvents().stream()
                .filter(e -> e instanceof WriteEvent ww
                        && ww.getThreadId() != 0 && ww.getVariable().equals(var))
                .map(e -> (WriteEvent) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no program write to " + var));
        assertEquals(expected, w.getValue(),
                "program write to " + var + " carries value " + expected);
    }

    /**
     * The wired rf for ({@code threadId}:{@code register} reading from
     * {@code var}) targets a write of value {@code expectedValue}.
     */
    private static void assertReadValue(LitmusCase lc, int threadId,
                                        String register, String var, int expectedValue) {
        ReadEvent r = lc.es().getEvents().stream()
                .filter(e -> e instanceof ReadEvent rr
                        && rr.getThreadId() == threadId
                        && rr.getLocalVar().equals(register)
                        && rr.getVariable().equals(var))
                .map(e -> (ReadEvent) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no read " + threadId + ":" + register + " of " + var));
        Integer srcId = lc.es().getReadsFrom().get(r.getId());
        assertNotNull(srcId, "read " + threadId + ":" + register + " has no rf source");
        Event src = lc.es().getEventById(srcId);
        assertTrue(src instanceof WriteEvent,
                "rf source is a write event");
        assertEquals(expectedValue, ((WriteEvent) src).getValue(),
                "wired rf for " + threadId + ":" + register
                        + " reads value " + expectedValue);
    }

    private static boolean weakestUnsat(LitmusCase lc) throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        SolverContext c = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
        try {
            EventStructureEncoder enc = new EventStructureEncoder(c, lc.es(), lc.deps());
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            BooleanFormula cons = ax.consistencyWEAKEST();
            try (ProverEnvironment p = c.newProverEnvironment()) {
                p.addConstraint(enc.getBmgr().and(wf, cons));
                return p.isUnsat();
            }
        } finally {
            c.close();
        }
    }
}
