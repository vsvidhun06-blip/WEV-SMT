package wev.smt.bench;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import wev.smt.EventStructureEncoder;
import wev.smt.LitmusCorpus;
import wev.smt.LitmusCorpus.LitmusCase;
import wev.smt.bench.ParametricPrograms.Program;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity assertions for the parametric scalability generators: that the small-{@code n}
 * chains reproduce the hand-crafted corpus classics, and that the WEAKEST verdict tracks
 * dependency content (fake ⇒ allowed, real ⇒ forbidden) across {@code n}. These run
 * alongside the existing 12 tests (16 total).
 */
class ParametricProgramsTest {

    /** A few representative chain lengths — kept small so the suite stays fast. */
    private static final int[] NS = {2, 3, 4, 5, 6};

    private SolverContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        Event.resetCounter();
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        ShutdownNotifier sd = ShutdownNotifier.createDummy();
        ctx = SolverContextFactory.createSolverContext(cfg, log, sd, Solvers.Z3);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Test
    void lbChain2MatchesHandcraftedLB() {
        assertStructurallyEqual(corpus("LB"), ParametricPrograms.buildLBChain(2).es(),
                "LBChain(2) vs corpus LB");
    }

    @Test
    void lbChain3MatchesHandcrafted3LB() {
        assertStructurallyEqual(corpus("3.LB"), ParametricPrograms.buildLBChain(3).es(),
                "LBChain(3) vs corpus 3.LB");
    }

    @Test
    void lbFakeChainAllowedUnderWeakestForAllN() throws Exception {
        for (int n : NS) {
            Program p = ParametricPrograms.buildLBFakeChain(n);
            assertTrue(weakestAllows(p),
                    "LBFakeChain(" + n + ") must be ALLOWED under WEAKEST "
                            + "(fake deps carry no semantic edge ⇒ no thin-air cycle)");
        }
    }

    @Test
    void lbChainForbiddenUnderWeakestForNGe2() throws Exception {
        for (int n : NS) {
            Program p = ParametricPrograms.buildLBChain(n);
            assertFalse(weakestAllows(p),
                    "LBChain(" + n + ") must be FORBIDDEN under WEAKEST "
                            + "(real data deps close a thin-air cycle)");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Whether the full wired execution is consistent under WEAKEST (ALLOWED). */
    private boolean weakestAllows(Program p) throws Exception {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, p.es(), p.deps());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula phi = enc.getBmgr().and(
                enc.encodeWellFormedness(), ax.consistencyWEAKEST());
        try (ProverEnvironment prover = ctx.newProverEnvironment()) {
            prover.addConstraint(phi);
            return !prover.isUnsat();
        }
    }

    private static EventStructure corpus(String name) {
        List<LitmusCase> cases = LitmusCorpus.classics();
        return cases.stream()
                .filter(lc -> lc.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("corpus case not found: " + name))
                .es();
    }

    /**
     * Structural (id-independent) equality: same event-type composition and same
     * program-order / reads-from / coherence-order cardinalities. Event ids differ
     * between the two builders, so we compare shape, not identity.
     */
    private static void assertStructurallyEqual(EventStructure expected,
                                                EventStructure actual, String what) {
        assertEquals(expected.getEvents().size(), actual.getEvents().size(),
                what + ": event count");
        assertEquals(typeCount(expected, EventType.READ), typeCount(actual, EventType.READ),
                what + ": read count");
        assertEquals(typeCount(expected, EventType.WRITE), typeCount(actual, EventType.WRITE),
                what + ": write count");
        assertEquals(edgeCount(expected), edgeCount(actual),
                what + ": program-order edge count");
        assertEquals(expected.getReadsFrom().size(), actual.getReadsFrom().size(),
                what + ": reads-from edge count");
        assertEquals(coEntries(expected), coEntries(actual),
                what + ": coherence-order entry count");
        assertEquals(expected.getCoherenceOrder().size(), actual.getCoherenceOrder().size(),
                what + ": location count");
    }

    private static long typeCount(EventStructure es, EventType t) {
        return es.getEvents().stream().filter(e -> e.getType() == t).count();
    }

    private static int edgeCount(EventStructure es) {
        return es.getProgramOrder().values().stream().mapToInt(List::size).sum();
    }

    private static int coEntries(EventStructure es) {
        return es.getCoherenceOrder().values().stream().mapToInt(List::size).sum();
    }
}
