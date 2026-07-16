package wev.smt.edgecase;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.FenceEvent;
import com.weakest.model.FenceEvent.FenceKind;
import com.weakest.model.MemoryOrder;
import com.weakest.model.RMWEvent;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;

import wev.smt.AxiomaticConsistency;
import wev.smt.DependencyInfo;
import wev.smt.EventStructureEncoder;
import wev.smt.MemoryModel;
import wev.smt.MinimalWitness;
import wev.smt.MinimalWitnessExtractor;
import wev.smt.bench.ParametricPrograms;
import wev.smt.bench.ParametricPrograms.Program;
import wev.smt.validate.InputValidator;
import wev.smt.validate.InvalidEventStructureException;
import wev.smt.validate.ValidationReport;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day-14 edge-case / robustness suite (cases A–K). The goal is the §6.4 invariant:
 * <em>graceful errors or correct results — never a crash, a hang, or a silent wrong
 * answer</em>. Each test feeds the back end a corner case a POPL reviewer would probe
 * for an assertion failure, an infinite loop, or a verdict pretending UNSAT is a real
 * "forbidden" answer.
 *
 * <p>Structural defects (cases C/D/E) are caught by {@link InputValidator} and surface as
 * {@link InvalidEventStructureException}; oversized inputs (case F) are refused by the
 * {@link MinimalWitnessExtractor} resource ceiling and return {@link Optional#empty()};
 * the remaining cases assert the <em>verdict</em> is the correct one (consistency =
 * SAT/allowed, UNSAT/forbidden).
 */
class EdgeCaseTest {

    private SolverContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        Event.resetCounter();
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    // ── A. Empty event structure ──────────────────────────────────────────

    @Test
    void emptyStructureYieldsNoWitnessNotCrash() {
        EventStructure es = new EventStructure();
        MinimalWitnessExtractor mw = newExtractor(es);
        // Zero events: no consistent (and no separating) sub-execution; empty, not a crash.
        assertFalse(mw.findMinimalConsistent(MemoryModel.SC).isPresent());
        assertFalse(mw.findMinimalConsistent(MemoryModel.WEAKEST).isPresent());
        assertFalse(mw.findMinimalSeparating(MemoryModel.TSO, MemoryModel.SC).isPresent());
        assertTrue(InputValidator.validate(es).valid(), "an empty structure is trivially valid");
    }

    // ── B. Single-thread program ──────────────────────────────────────────

    @Test
    void singleThreadProgramBehavesIdenticallyAcrossModels() {
        // One thread: write x=1 then read it back. No inter-thread interaction, so no
        // relaxation applies — every model must agree the execution is consistent.
        EventStructure es = new EventStructure();
        WriteEvent init = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        WriteEvent w = new WriteEvent(1, "x", MemoryOrder.RELAXED, 1, "1");
        ReadEvent r = new ReadEvent(1, "x", MemoryOrder.RELAXED, "r");
        for (Event e : new Event[]{init, w, r}) es.addEvent(e);
        es.addProgramOrder(w, r);
        es.addCoherenceOrder("x", init);
        es.addCoherenceOrder("x", w);
        es.addReadsFrom(r, w);                              // r observes its own thread's write (=1)

        for (MemoryModel m : MemoryModel.values()) {
            assertFalse(isForbidden(es, DependencyInfo.empty(), m),
                    "single-thread W;R must be allowed (consistent) under " + m);
        }
    }

    // ── C. Pathological po (cyclic) ───────────────────────────────────────

    @Test
    void cyclicProgramOrderRejectedNotSilentlyUnsat() {
        EventStructure es = new EventStructure();
        WriteEvent a = new WriteEvent(1, "x", MemoryOrder.RELAXED, 1, "1");
        WriteEvent b = new WriteEvent(1, "y", MemoryOrder.RELAXED, 1, "1");
        es.addEvent(a);
        es.addEvent(b);
        es.addProgramOrder(a, b);
        es.addProgramOrder(b, a);                          // illegal: a po-cycle

        ValidationReport report = InputValidator.validate(es);
        assertFalse(report.valid(), "a po cycle is a structural error");
        assertTrue(report.errors().stream().anyMatch(s -> s.contains("cycle")),
                "the error must name the cycle, not be silently swallowed: " + report.errors());
        // And the extractor refuses it rather than encoding a vacuous UNSAT.
        assertThrows(InvalidEventStructureException.class, () -> newExtractor(es));
    }

    // ── D. Pathological rf (read with no matching write) ──────────────────

    @Test
    void readWithNoWriteSurfacedAsNoValidExecution() {
        EventStructure es = new EventStructure();
        ReadEvent r = new ReadEvent(1, "x", MemoryOrder.RELAXED, "r");
        es.addEvent(r);                                    // a read of x; no write to x anywhere

        ValidationReport report = InputValidator.validate(es);
        assertFalse(report.valid());
        assertTrue(report.errors().stream().anyMatch(s -> s.contains("no valid execution exists")),
                "a read with no write must be surfaced as 'no valid execution exists': "
                        + report.errors());
        InvalidEventStructureException ex =
                assertThrows(InvalidEventStructureException.class, () -> newExtractor(es));
        assertFalse(ex.report().valid());
    }

    // ── E. Conflicting initial values ─────────────────────────────────────

    @Test
    void conflictingInitialValuesRejectedWithDiagnostic() {
        EventStructure es = new EventStructure();
        WriteEvent i1 = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        WriteEvent i2 = new WriteEvent(0, "x", MemoryOrder.RELAXED, 5, "5");  // x=0 and x=5
        es.addEvent(i1);
        es.addEvent(i2);

        ValidationReport report = InputValidator.validate(es);
        assertFalse(report.valid());
        assertTrue(report.errors().stream().anyMatch(s -> s.contains("conflicting initial values")),
                "conflicting inits must produce a clear diagnostic: " + report.errors());
        assertThrows(InvalidEventStructureException.class, () -> newExtractor(es));
    }

    // ── F. Extremely large event count (n=64 → 192 events, n=128 → 384) ───

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void largeEventCountsRefusedGracefullyNotCrash() {
        // LBChain(n) is 3n events (n inits + n reads + n writes); 64 → 192, 128 → 384.
        Program lb64 = ParametricPrograms.buildLBChain(64);
        Program lb128 = ParametricPrograms.buildLBChain(128);
        assertEquals(192, lb64.eventCount());
        assertEquals(384, lb128.eventCount());

        // With a ceiling below both sizes, the extractor refuses the oversized problem and
        // returns empty (witness extraction sound under pressure) — no OutOfMemoryError, no
        // multi-minute hang. This is the timeout-surrogate the spec calls for.
        String saved = System.getProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY);
        try {
            System.setProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY, "64");
            MinimalWitnessExtractor mw128 = newExtractor(lb128.es(), lb128.deps());
            assertFalse(mw128.findMinimalConsistent(MemoryModel.WEAKEST).isPresent(),
                    "256-event problem over the ceiling must return empty, not crash");
            assertFalse(mw128.findMinimalSeparating(MemoryModel.WEAKEST, MemoryModel.RA).isPresent());

            MinimalWitnessExtractor mw64 = newExtractor(lb64.es(), lb64.deps());
            assertFalse(mw64.findMinimalConsistent(MemoryModel.WEAKEST).isPresent(),
                    "128-event problem over the ceiling must return empty too");
        } finally {
            if (saved == null) System.clearProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY);
            else System.setProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY, saved);
        }
    }

    // ── G. Many concurrent locations (m=20 locations, n=4 threads) ────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void manyConcurrentLocationsCompleteWithinBudget() {
        // 4 threads, 20 locations: thread t owns the 5 locations x_{5(t-1)..5(t-1)+4},
        // writing each =t and reading it back. Per-location coherence gets 20 independent
        // layer families — the stress this case targets — and the verdict is plainly
        // consistent (each read sees its own thread's write).
        EventStructure es = new EventStructure();
        int threads = 4, perThread = 5, locs = threads * perThread;   // 20 locations
        WriteEvent[] inits = new WriteEvent[locs];
        for (int i = 0; i < locs; i++) {
            inits[i] = new WriteEvent(0, "x" + i, MemoryOrder.RELAXED, 0, "0");
            es.addEvent(inits[i]);
            es.addCoherenceOrder("x" + i, inits[i]);
        }
        for (int t = 1; t <= threads; t++) {
            Event prev = null;
            for (int k = 0; k < perThread; k++) {
                int loc = (t - 1) * perThread + k;
                WriteEvent w = new WriteEvent(t, "x" + loc, MemoryOrder.RELAXED, t, Integer.toString(t));
                ReadEvent r = new ReadEvent(t, "x" + loc, MemoryOrder.RELAXED, "r" + t + "_" + k);
                es.addEvent(w);
                es.addEvent(r);
                es.addCoherenceOrder("x" + loc, w);
                es.addReadsFrom(r, w);                     // read observes own write (=t)
                if (prev != null) es.addProgramOrder(prev, w);
                es.addProgramOrder(w, r);
                prev = r;
            }
        }
        assertEquals(locs + threads * perThread * 2, es.getEvents().size());  // 20 + 40 = 60
        // The headline requirement: it finishes (the @Timeout enforces the 60s budget) and
        // returns a coherent verdict for every model.
        for (MemoryModel m : MemoryModel.values()) {
            assertFalse(isForbidden(es, DependencyInfo.empty(), m),
                    "20-location / 4-thread own-write program is consistent under " + m);
        }
    }

    // ── H. Deep dependency chain (length 32) ──────────────────────────────

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void deepDependencyChainForbidsThinAir() {
        // A length-32 semantic dependency+jf cycle (LBChain(32)) must be forbidden under
        // WEAKEST — the layer ordering propagates transitively across all 32 edges. The
        // shape-identical fake chain (no semantic edges) is allowed: the cycle never closes.
        Program real = ParametricPrograms.buildLBChain(32);
        Program fake = ParametricPrograms.buildLBFakeChain(32);
        assertTrue(isForbidden(real.es(), real.deps(), MemoryModel.WEAKEST),
                "a 32-deep real dependency chain closes a thin-air cycle (forbidden)");
        assertFalse(isForbidden(fake.es(), fake.deps(), MemoryModel.WEAKEST),
                "the same-shape fake chain has no semantic edges → allowed");
    }

    // ── I. Mixed semantic + non-semantic deps ─────────────────────────────

    @Test
    void onlySemanticDepsParticipateInJfCoherence() {
        // A 2-thread load-buffering shape. With both data deps semantic the sdep∪jf cycle
        // closes (forbidden); flip just ONE edge to fake and the cycle is broken (allowed),
        // proving only isSemantic=true edges feed jf-coherence.
        Program both = buildLbPair(true, true);
        Program mixed = buildLbPair(true, false);
        assertTrue(isForbidden(both.es(), both.deps(), MemoryModel.WEAKEST),
                "both deps semantic → thin-air cycle forbidden");
        assertFalse(isForbidden(mixed.es(), mixed.deps(), MemoryModel.WEAKEST),
                "one fake dep breaks the cycle → allowed (only semantic deps participate)");
    }

    // ── J. RMW with same-location conflict ────────────────────────────────

    @Test
    void rmwSameLocationConflictLinearizes() {
        // The atomic increment chain (read p → write p+1) serialises via coherence and is
        // consistent under every model.
        Program chain = ParametricPrograms.buildRMWChain(2);
        for (MemoryModel m : MemoryModel.values()) {
            assertFalse(isForbidden(chain.es(), chain.deps(), m),
                    "two serialised RMWs (atomic counter) are consistent under " + m);
        }
        // The non-linearizable variant — two RMWs both reading the initial 0 and both
        // writing 1 — cannot both "win": coherence + RMW atomicity forbid it everywhere.
        EventStructure conflict = new EventStructure();
        WriteEvent init = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        RMWEvent u0 = new RMWEvent(1, "x", MemoryOrder.RELAXED, 0, 1);   // read 0 → write 1
        RMWEvent u1 = new RMWEvent(2, "x", MemoryOrder.RELAXED, 0, 1);   // read 0 → write 1
        for (Event e : new Event[]{init, u0, u1}) conflict.addEvent(e);
        conflict.addCoherenceOrder("x", init);
        conflict.addCoherenceOrder("x", u0);
        conflict.addCoherenceOrder("x", u1);
        assertTrue(isForbidden(conflict, DependencyInfo.empty(), MemoryModel.SC),
                "both RMWs reading the initial value violates atomicity → forbidden");
        assertTrue(isForbidden(conflict, DependencyInfo.empty(), MemoryModel.WEAKEST),
                "same conflict forbidden even under the weakest model (coherence is universal)");
    }

    // ── K. Mixed fences (REL on one thread, ACQ on another) ───────────────

    @Test
    void releaseAcquireFencesForbidMpUnderRa() {
        // MP with a release fence before the flag store and an acquire fence after the flag
        // load: the fences upgrade the relaxed accesses to release/acquire, establishing
        // synchronizes-with, so the message-passing bad outcome (flag=1, data=0) is
        // forbidden under RA. Without the fences there is no sw and RA permits it.
        assertTrue(isForbidden(buildMp(true), DependencyInfo.empty(), MemoryModel.RA),
                "REL/ACQ fences synchronise → MP bad outcome forbidden under RA");
        assertFalse(isForbidden(buildMp(false), DependencyInfo.empty(), MemoryModel.RA),
                "no fences → no synchronizes-with → RA allows the MP bad outcome");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private MinimalWitnessExtractor newExtractor(EventStructure es) {
        return newExtractor(es, DependencyInfo.empty());
    }

    private MinimalWitnessExtractor newExtractor(EventStructure es, DependencyInfo deps) {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        return new MinimalWitnessExtractor(ctx, enc, ax);
    }

    /** Full-execution consistency: true iff the wired outcome is UNSAT (forbidden) under {@code m}. */
    private boolean isForbidden(EventStructure es, DependencyInfo deps, MemoryModel m) {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormulaManager bmgr = enc.getBmgr();
        BooleanFormula cons = switch (m) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
            case PSO -> ax.consistencyPSO();
            case RA -> ax.consistencyRA();
            case WEAKEST -> ax.consistencyWEAKEST();
            case RC11 -> ax.consistencyRC11();
        };
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(enc.encodeWellFormedness(), cons));
            return p.isUnsat();
        } catch (Exception e) {
            throw new RuntimeException("consistency check failed for " + m, e);
        }
    }

    /**
     * A 2-thread load-buffering structure with per-edge semantic flags: thread 1 reads x
     * and writes y (dep wy←r1), thread 2 reads y and writes x (dep wx←r2); each read
     * observes the other thread's "future" write (the thin-air outcome).
     */
    private Program buildLbPair(boolean sem1, boolean sem2) {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.RELAXED, 0, "0");
        ReadEvent r1 = new ReadEvent(1, "x", MemoryOrder.RELAXED, "r1");
        WriteEvent wy = new WriteEvent(1, "y", MemoryOrder.RELAXED, 1, "1");
        ReadEvent r2 = new ReadEvent(2, "y", MemoryOrder.RELAXED, "r2");
        WriteEvent wx = new WriteEvent(2, "x", MemoryOrder.RELAXED, 1, "1");
        for (Event e : new Event[]{ix, iy, r1, wy, r2, wx}) es.addEvent(e);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, wx);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy);
        es.addCoherenceOrder("y", wy);
        es.addReadsFrom(r1, wx);                           // r1 reads x=1 (future write of thread 2)
        es.addReadsFrom(r2, wy);                           // r2 reads y=1 (future write of thread 1)
        DependencyInfo deps = new DependencyInfo();
        deps.addDataDep(wy, r1, sem1);
        deps.addDataDep(wx, r2, sem2);
        return new Program("LBPair", 2, es, deps);
    }

    /**
     * Message-passing: thread 1 writes data=1 then flag=1; thread 2 reads flag=1 then
     * data=0 (the bad outcome). When {@code fenced}, a release fence precedes the flag
     * store and an acquire fence follows the flag load.
     */
    private EventStructure buildMp(boolean fenced) {
        EventStructure es = new EventStructure();
        WriteEvent iData = new WriteEvent(0, "data", MemoryOrder.RELAXED, 0, "0");
        WriteEvent iFlag = new WriteEvent(0, "flag", MemoryOrder.RELAXED, 0, "0");
        WriteEvent wData = new WriteEvent(1, "data", MemoryOrder.RELAXED, 1, "1");
        WriteEvent wFlag = new WriteEvent(1, "flag", MemoryOrder.RELAXED, 1, "1");
        ReadEvent rFlag = new ReadEvent(2, "flag", MemoryOrder.RELAXED, "rf");
        ReadEvent rData = new ReadEvent(2, "data", MemoryOrder.RELAXED, "rd");
        es.addEvent(iData);
        es.addEvent(iFlag);
        es.addEvent(wData);
        if (fenced) {
            FenceEvent fRel = new FenceEvent(1, FenceKind.REL);
            es.addEvent(fRel);
            es.addEvent(wFlag);
            es.addProgramOrder(wData, fRel);
            es.addProgramOrder(fRel, wFlag);
        } else {
            es.addEvent(wFlag);
            es.addProgramOrder(wData, wFlag);
        }
        es.addEvent(rFlag);
        if (fenced) {
            FenceEvent fAcq = new FenceEvent(2, FenceKind.ACQ);
            es.addEvent(fAcq);
            es.addEvent(rData);
            es.addProgramOrder(rFlag, fAcq);
            es.addProgramOrder(fAcq, rData);
        } else {
            es.addEvent(rData);
            es.addProgramOrder(rFlag, rData);
        }
        es.addCoherenceOrder("data", iData);
        es.addCoherenceOrder("data", wData);
        es.addCoherenceOrder("flag", iFlag);
        es.addCoherenceOrder("flag", wFlag);
        es.addReadsFrom(rFlag, wFlag);                     // flag=1
        es.addReadsFrom(rData, iData);                     // data=0 (the bad outcome)
        return es;
    }
}
