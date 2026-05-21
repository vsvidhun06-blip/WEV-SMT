package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A catalogue of canonical weak-memory litmus tests, each paired with its
 * <em>textbook</em>-expected outcome under SC, TSO, PSO, RA, and WEAKEST.
 *
 * <p>References for the expected verdicts:
 * <ul>
 *   <li>Alglave, Maranget &amp; Tautschnig, "Herding Cats" (TOPLAS 2014) — SC/TSO/PSO
 *       and the coherence / SC-per-location axioms.</li>
 *   <li>Lahav, Vafeiadis, Kang, Hur &amp; Dreyer, "Repairing Sequential Consistency
 *       in C/C++11" (PLDI 2017) — the RA (release/acquire) fragment of RC11.</li>
 *   <li>Chakraborty &amp; Vafeiadis, "Grounding Thin-Air Reads with Event Structures"
 *       (POPL 2019) — the WEAKEST model; in particular LB is <em>allowed</em> while
 *       out-of-thin-air (OOTA) cycles are <em>forbidden</em>.</li>
 * </ul>
 *
 * <p>Each {@link LitmusCase} wires the canonical <em>forbidden-under-SC</em> outcome
 * directly into its {@link EventStructure} via {@code addReadsFrom} (which fixes each
 * read's observed value) and {@code addCoherenceOrder} (which fixes the modification
 * order). A model "ALLOWS" the test iff that wired execution is consistent under it.
 *
 * <p>Outcomes are the literature's verdicts, <strong>not</strong> this prototype's
 * encoding output. {@code wev.smt.cli.AtlasReconstruct} compares the two; deviations
 * are expected and are themselves the research artifact (encoding incompleteness vs.
 * genuine model behaviour). {@link Outcome#UNKNOWN} marks cells the literature treats
 * as model-specific or where this catalogue declines to assert a verdict.
 *
 * <p>Event ids deliberately accumulate across cases (no {@code resetCounter} between
 * builders) so that, under a single shared {@code SolverContext}, every event maps to
 * a uniquely-named SMT variable.
 */
public final class LitmusCorpus {

    private LitmusCorpus() { }

    /** Whether a memory model permits a litmus test's forbidden-under-SC outcome. */
    public enum Outcome { ALLOWED, FORBIDDEN, UNKNOWN }

    /**
     * A named litmus test with its event structure, per-model expected outcome, and
     * (Pass 3) syntactic {@link DependencyInfo} sidecar. Dependency-free cases carry
     * {@link DependencyInfo#empty()}.
     */
    public record LitmusCase(String name, EventStructure es,
                             Map<MemoryModel, Outcome> expected, DependencyInfo deps) { }

    // Shorthand for building the expected maps below.
    private static final Outcome A = Outcome.ALLOWED;
    private static final Outcome F = Outcome.FORBIDDEN;
    private static final Outcome U = Outcome.UNKNOWN;

    private static final MemoryOrder RLX = MemoryOrder.RELAXED;
    private static final MemoryOrder ACQ = MemoryOrder.ACQUIRE;
    private static final MemoryOrder REL = MemoryOrder.RELEASE;
    private static final MemoryOrder SC  = MemoryOrder.SC;

    /**
     * The full catalogue of ~25 canonical tests. Resets the global event-id counter
     * once at entry so ids are deterministic, then lets them accumulate so every
     * event across every case carries a globally-unique id.
     */
    public static List<LitmusCase> classics() {
        Event.resetCounter();
        List<LitmusCase> cs = new ArrayList<>();

        // ── Coherence (SC-per-location): forbidden under every model ──────────
        cs.add(c("CoRR", buildCoRR(), exp(F, F, F, F, F)));
        cs.add(c("CoRW", buildCoRW(), exp(F, F, F, F, F)));
        cs.add(c("CoWR", buildCoWR(), exp(F, F, F, F, F)));
        cs.add(c("CoWW", buildCoWW(), exp(F, F, F, F, F)));

        // ── Classic relaxed-behaviour litmus tests ────────────────────────────
        cs.add(c("SB",   buildSB(RLX),  exp(F, A, A, A, A)));
        cs.add(c("MP",   buildMP(RLX, RLX), exp(F, F, A, A, A)));
        cs.add(c("LB",   buildLB(RLX, RLX), exp(F, F, F, A, A)));
        cs.add(c("IRIW", buildIRIW(RLX, RLX), exp(F, F, F, A, A)));
        cs.add(c("WRC",  buildWRC(RLX, RLX), exp(F, F, F, A, A)));
        cs.add(c("ISA2", buildISA2(),   exp(F, F, A, A, A)));
        cs.add(c("R",    buildR(),      exp(F, A, A, A, A)));
        cs.add(c("S",    buildS(),      exp(F, F, A, A, A)));
        cs.add(c("2+2W", build2plus2W(), exp(F, F, A, A, A)));
        cs.add(c("RWC",  buildRWC(),    exp(F, A, A, U, A)));

        // ── Synchronised (release/acquire) variants ───────────────────────────
        cs.add(c("CO-MP",   buildMP(REL, ACQ),   exp(F, F, F, F, U)));
        cs.add(c("CO-WRC",  buildWRC(REL, ACQ),  exp(F, F, F, F, A)));
        cs.add(c("CO-IRIW", buildIRIW(REL, ACQ), exp(F, F, F, A, A)));

        // ── Multi-thread chains ───────────────────────────────────────────────
        cs.add(c("3.LB", build3LB(), exp(F, F, F, A, A)));
        cs.add(c("3.SB", build3SB(), exp(F, U, U, U, A)));

        // ── WEAKEST-specific: out-of-thin-air, forbidden by WEAKEST ───────────
        cs.add(c("LB-fake-dep", buildLB(RLX, RLX), exp(F, F, F, F, F)));
        cs.add(c("OOTA-cycle",  buildLB(RLX, RLX), exp(F, F, F, F, F)));

        // ── Synchronisation gradations on MP / LB ─────────────────────────────
        cs.add(c("MP-rel",    buildMP(REL, RLX), exp(F, F, A, A, A)));
        cs.add(c("MP-acq",    buildMP(RLX, ACQ), exp(F, F, A, A, A)));
        cs.add(c("MP-relacq", buildMP(REL, ACQ), exp(F, F, F, F, U)));
        cs.add(c("LB-acqrel", buildLB(REL, ACQ), exp(F, F, F, U, A)));

        // ── Pass 3 (Stage 1): dependency-carrying LB variants ─────────────────
        // These three carry syntactic addr/data dependency edges in a DependencyInfo
        // sidecar; no consistency axiom reads them yet, so every cell is UNKNOWN (not
        // compared) and they add no mismatch. They are the litmus the Stage-2
        // jf-coherence axiom must separate: LBdep-fake should end up ALLOWED (the dep
        // is the identity, no real causality), LBdep-real/LBdep-addr FORBIDDEN (a true
        // causal cycle). Named distinctly from the existing no-dep OOTA twins
        // (LB-fake-dep, OOTA-cycle) to avoid colliding with their wired verdicts.
        EsDeps lbFake = buildLBFakeDep();
        cs.add(c("LBdep-fake", lbFake.es(), exp(U, U, U, U, U), lbFake.deps()));
        EsDeps lbReal = buildLBRealDep();
        cs.add(c("LBdep-real", lbReal.es(), exp(U, U, U, U, U), lbReal.deps()));
        EsDeps lbAddr = buildLBAddrDep();
        cs.add(c("LBdep-addr", lbAddr.es(), exp(U, U, U, U, U), lbAddr.deps()));

        return cs;
    }

    /**
     * Parse a herd7 {@code .litmus} file into a {@link LitmusCase}. Not yet
     * implemented; the {@link #classics()} catalogue is the current source.
     */
    public static List<LitmusCase> loadFrom(Path litmus) {
        throw new UnsupportedOperationException(
                ".litmus parsing is not implemented yet; use LitmusCorpus.classics()");
    }

    // ── Construction helpers ───────────────────────────────────────────────

    private static LitmusCase c(String name, EventStructure es,
                                Map<MemoryModel, Outcome> expected) {
        return new LitmusCase(name, es, expected, DependencyInfo.empty());
    }

    private static LitmusCase c(String name, EventStructure es,
                                Map<MemoryModel, Outcome> expected, DependencyInfo deps) {
        return new LitmusCase(name, es, expected, deps);
    }

    /** An event structure paired with its Pass-3 syntactic dependency sidecar. */
    private record EsDeps(EventStructure es, DependencyInfo deps) { }

    private static Map<MemoryModel, Outcome> exp(Outcome sc, Outcome tso,
                                                 Outcome pso, Outcome ra,
                                                 Outcome weakest) {
        Map<MemoryModel, Outcome> m = new EnumMap<>(MemoryModel.class);
        m.put(MemoryModel.SC, sc);
        m.put(MemoryModel.TSO, tso);
        m.put(MemoryModel.PSO, pso);
        m.put(MemoryModel.RA, ra);
        m.put(MemoryModel.WEAKEST, weakest);
        return m;
    }

    private static WriteEvent w(int thread, String var, MemoryOrder mo, int value) {
        return new WriteEvent(thread, var, mo, value, Integer.toString(value));
    }

    private static ReadEvent r(int thread, String var, MemoryOrder mo, String local) {
        return new ReadEvent(thread, var, mo, local);
    }

    // ── Coherence builders (single location) ────────────────────────────────

    /** CoRR: one thread reads x twice and sees 1 then 0 (a stale re-read). */
    private static EventStructure buildCoRR() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent wx = w(1, "x", SC, 1);
        ReadEvent r1 = r(2, "x", SC, "r1");
        ReadEvent r2 = r(2, "x", SC, "r2");
        add(es, ix, wx, r1, r2);
        es.addProgramOrder(r1, r2);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", wx);
        es.addReadsFrom(r1, wx);   // sees 1
        es.addReadsFrom(r2, ix);   // then sees 0 — coherence violation
        return es;
    }

    /** CoRW: a thread reads x then writes x, but reads from its own later write. */
    private static EventStructure buildCoRW() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent w1 = w(1, "x", SC, 1);
        ReadEvent ra = r(2, "x", SC, "ra");
        WriteEvent w2 = w(2, "x", SC, 2);
        add(es, ix, w1, ra, w2);
        es.addProgramOrder(ra, w2);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", w1);
        es.addCoherenceOrder("x", w2);
        es.addReadsFrom(ra, w2);   // reads from a program-order-later write
        return es;
    }

    /** CoWR: a thread writes x then reads a co-earlier value of x. */
    private static EventStructure buildCoWR() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent w1 = w(1, "x", SC, 1);
        ReadEvent ra = r(1, "x", SC, "ra");
        add(es, ix, w1, ra);
        es.addProgramOrder(w1, ra);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", w1);
        es.addReadsFrom(ra, ix);   // reads 0 despite the program-order-earlier W(x,1)
        return es;
    }

    /** CoWW: two same-thread writes whose modification order contradicts po. */
    private static EventStructure buildCoWW() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent w1 = w(1, "x", SC, 1);
        WriteEvent w2 = w(1, "x", SC, 2);
        add(es, ix, w1, w2);
        es.addProgramOrder(w1, w2);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", w2);   // co reversed relative to po: W2 before W1
        es.addCoherenceOrder("x", w1);
        return es;
    }

    // ── Classic litmus builders ─────────────────────────────────────────────

    /** SB: store buffering — both threads write then read the other's location. */
    private static EventStructure buildSB(MemoryOrder mo) {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent wx = w(1, "x", mo, 1);
        ReadEvent r1 = r(1, "y", mo, "r1");
        WriteEvent wy = w(2, "y", mo, 1);
        ReadEvent r2 = r(2, "x", mo, "r2");
        add(es, ix, iy, wx, r1, wy, r2);
        es.addProgramOrder(wx, r1);
        es.addProgramOrder(wy, r2);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r1, iy);   // both reads miss the other thread's write
        es.addReadsFrom(r2, ix);
        return es;
    }

    /** MP: message passing — writer sets data then flag; reader sees flag but stale data. */
    private static EventStructure buildMP(MemoryOrder wmo, MemoryOrder rmo) {
        EventStructure es = new EventStructure();
        WriteEvent idata = w(0, "data", RLX, 0);
        WriteEvent iflag = w(0, "flag", RLX, 0);
        WriteEvent wdata = w(1, "data", wmo, 1);
        WriteEvent wflag = w(1, "flag", wmo, 1);
        ReadEvent rflag = r(2, "flag", rmo, "r1");
        ReadEvent rdata = r(2, "data", rmo, "r2");
        add(es, idata, iflag, wdata, wflag, rflag, rdata);
        es.addProgramOrder(wdata, wflag);
        es.addProgramOrder(rflag, rdata);
        co(es, "data", idata, wdata);
        co(es, "flag", iflag, wflag);
        es.addReadsFrom(rflag, wflag);   // sees flag = 1
        es.addReadsFrom(rdata, idata);   // but data = 0
        return es;
    }

    /** LB: load buffering — each thread reads then writes; both reads see the future. */
    private static EventStructure buildLB(MemoryOrder rmo, MemoryOrder wmo) {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        ReadEvent r1 = r(1, "x", rmo, "r1");
        WriteEvent wy = w(1, "y", wmo, 1);
        ReadEvent r2 = r(2, "y", rmo, "r2");
        WriteEvent wx = w(2, "x", wmo, 1);
        add(es, ix, iy, r1, wy, r2, wx);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, wx);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r1, wx);   // reads the value the other thread writes "later"
        es.addReadsFrom(r2, wy);
        return es;
    }

    /** IRIW: independent reads of independent writes seen in opposite orders. */
    private static EventStructure buildIRIW(MemoryOrder wmo, MemoryOrder rmo) {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent w1 = w(1, "x", wmo, 1);
        WriteEvent w2 = w(2, "y", wmo, 1);
        ReadEvent r1 = r(3, "x", rmo, "r1");
        ReadEvent r2 = r(3, "y", rmo, "r2");
        ReadEvent r3 = r(4, "y", rmo, "r3");
        ReadEvent r4 = r(4, "x", rmo, "r4");
        add(es, ix, iy, w1, w2, r1, r2, r3, r4);
        es.addProgramOrder(r1, r2);
        es.addProgramOrder(r3, r4);
        co(es, "x", ix, w1);
        co(es, "y", iy, w2);
        es.addReadsFrom(r1, w1);   // T3 sees x=1 then y=0
        es.addReadsFrom(r2, iy);
        es.addReadsFrom(r3, w2);   // T4 sees y=1 then x=0
        es.addReadsFrom(r4, ix);
        return es;
    }

    /** WRC: write-to-read causality across three threads. */
    private static EventStructure buildWRC(MemoryOrder wmo, MemoryOrder rmo) {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent wx = w(1, "x", wmo, 1);
        ReadEvent r1 = r(2, "x", rmo, "r1");
        WriteEvent wy = w(2, "y", wmo, 1);
        ReadEvent r2 = r(3, "y", rmo, "r2");
        ReadEvent r3 = r(3, "x", rmo, "r3");
        add(es, ix, iy, wx, r1, wy, r2, r3);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, r3);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r1, wx);   // T2 reads x=1, republishes y=1
        es.addReadsFrom(r2, wy);   // T3 reads y=1
        es.addReadsFrom(r3, ix);   // but x=0
        return es;
    }

    /** ISA2: a three-thread message-passing chain (x,y then z). */
    private static EventStructure buildISA2() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent iz = w(0, "z", RLX, 0);
        WriteEvent wx = w(1, "x", RLX, 1);
        WriteEvent wy = w(1, "y", RLX, 1);
        ReadEvent ry = r(2, "y", RLX, "ry");
        WriteEvent wz = w(2, "z", RLX, 1);
        ReadEvent rz = r(3, "z", RLX, "rz");
        ReadEvent rx = r(3, "x", RLX, "rx");
        add(es, ix, iy, iz, wx, wy, ry, wz, rz, rx);
        es.addProgramOrder(wx, wy);
        es.addProgramOrder(ry, wz);
        es.addProgramOrder(rz, rx);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        co(es, "z", iz, wz);
        es.addReadsFrom(ry, wy);   // sees y=1
        es.addReadsFrom(rz, wz);   // sees z=1
        es.addReadsFrom(rx, ix);   // but x=0
        return es;
    }

    /** R: a write-write thread races a write-read thread (final y=2, read x=0). */
    private static EventStructure buildR() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent wx = w(1, "x", RLX, 1);
        WriteEvent wy1 = w(1, "y", RLX, 1);
        WriteEvent wy2 = w(2, "y", RLX, 2);
        ReadEvent rx = r(2, "x", RLX, "rx");
        add(es, ix, iy, wx, wy1, wy2, rx);
        es.addProgramOrder(wx, wy1);
        es.addProgramOrder(wy2, rx);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy1, wy2);   // y final = 2 (T1's write loses to T2's)
        es.addReadsFrom(rx, ix);     // but x read = 0
        return es;
    }

    /** S: write-write thread races a read-write thread (read y=1, final x=2). */
    private static EventStructure buildS() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent wx2 = w(1, "x", RLX, 2);
        WriteEvent wy1 = w(1, "y", RLX, 1);
        ReadEvent ry = r(2, "y", RLX, "ry");
        WriteEvent wx1 = w(2, "x", RLX, 1);
        add(es, ix, iy, wx2, wy1, ry, wx1);
        es.addProgramOrder(wx2, wy1);
        es.addProgramOrder(ry, wx1);
        co(es, "x", ix, wx1, wx2);   // x final = 2 (T2's write loses)
        co(es, "y", iy, wy1);
        es.addReadsFrom(ry, wy1);    // y read = 1
        return es;
    }

    /** 2+2W: two threads each issue two writes; both modification orders flip. */
    private static EventStructure build2plus2W() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent wx1 = w(1, "x", RLX, 1);
        WriteEvent wy1 = w(1, "y", RLX, 1);
        WriteEvent wy2 = w(2, "y", RLX, 2);
        WriteEvent wx2 = w(2, "x", RLX, 2);
        add(es, ix, iy, wx1, wy1, wy2, wx2);
        es.addProgramOrder(wx1, wy1);
        es.addProgramOrder(wy2, wx2);
        co(es, "x", ix, wx2, wx1);   // x final = 1 (T1's write wins)
        co(es, "y", iy, wy1, wy2);   // y final = 2 (T2's write wins)
        return es;
    }

    /** RWC: read-write causality — a WRC read-chain crossed with an SB-style pair. */
    private static EventStructure buildRWC() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent wx = w(1, "x", RLX, 1);
        ReadEvent r1 = r(2, "x", RLX, "r1");
        ReadEvent r2 = r(2, "y", RLX, "r2");
        WriteEvent wy = w(3, "y", RLX, 1);
        ReadEvent r3 = r(3, "x", RLX, "r3");
        add(es, ix, iy, wx, r1, r2, wy, r3);
        es.addProgramOrder(r1, r2);
        es.addProgramOrder(wy, r3);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r1, wx);   // T2 sees x=1
        es.addReadsFrom(r2, iy);   // but y=0
        es.addReadsFrom(r3, ix);   // T3 writes y=1 then sees x=0
        return es;
    }

    /** 3.LB: a three-thread load-buffering cycle. */
    private static EventStructure build3LB() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent iz = w(0, "z", RLX, 0);
        ReadEvent rx = r(1, "x", RLX, "rx");
        WriteEvent wy = w(1, "y", RLX, 1);
        ReadEvent ry = r(2, "y", RLX, "ry");
        WriteEvent wz = w(2, "z", RLX, 1);
        ReadEvent rz = r(3, "z", RLX, "rz");
        WriteEvent wx = w(3, "x", RLX, 1);
        add(es, ix, iy, iz, rx, wy, ry, wz, rz, wx);
        es.addProgramOrder(rx, wy);
        es.addProgramOrder(ry, wz);
        es.addProgramOrder(rz, wx);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        co(es, "z", iz, wz);
        es.addReadsFrom(rx, wx);   // each read sees the next thread's "future" write
        es.addReadsFrom(ry, wy);
        es.addReadsFrom(rz, wz);
        return es;
    }

    /** 3.SB: a three-thread store-buffering cycle. */
    private static EventStructure build3SB() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        WriteEvent iz = w(0, "z", RLX, 0);
        WriteEvent wx = w(1, "x", RLX, 1);
        ReadEvent ry = r(1, "y", RLX, "ry");
        WriteEvent wy = w(2, "y", RLX, 1);
        ReadEvent rz = r(2, "z", RLX, "rz");
        WriteEvent wz = w(3, "z", RLX, 1);
        ReadEvent rx = r(3, "x", RLX, "rx");
        add(es, ix, iy, iz, wx, ry, wy, rz, wz, rx);
        es.addProgramOrder(wx, ry);
        es.addProgramOrder(wy, rz);
        es.addProgramOrder(wz, rx);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        co(es, "z", iz, wz);
        es.addReadsFrom(ry, iy);   // every read misses the others' writes
        es.addReadsFrom(rz, iz);
        es.addReadsFrom(rx, ix);
        return es;
    }

    // ── Pass 3 dependency-carrying builders ──────────────────────────────────

    /**
     * The shared LB skeleton for the dependency variants: T1 reads x then writes y,
     * T2 reads y then writes x, with each read seeing the other thread's "future"
     * write (the load-buffering cycle). Identical shape to {@link #buildLB}, but it
     * hands back the individual events so the caller can attach dependency edges.
     */
    private static EsDeps lbSkeleton() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", RLX, 0);
        WriteEvent iy = w(0, "y", RLX, 0);
        ReadEvent  r1 = r(1, "x", RLX, "r1");
        WriteEvent wy = w(1, "y", RLX, 1);
        ReadEvent  r2 = r(2, "y", RLX, "r2");
        WriteEvent wx = w(2, "x", RLX, 1);
        add(es, ix, iy, r1, wy, r2, wx);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, wx);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r1, wx);
        es.addReadsFrom(r2, wy);
        // The dependency-free skeleton; callers populate deps and keep handles via the
        // returned event structure (events are looked up below by program-order shape).
        return new EsDeps(es, DependencyInfo.empty());
    }

    /**
     * LB with a <em>fake</em> data dependency, e.g. {@code x = r ^ r + 1}: the value
     * written syntactically mentions the read but is in fact constant, so there is no
     * real causal cycle and WEAKEST should ALLOW it. Stage 1 records the same data-dep
     * edges as the real case below — telling them apart needs Stage-2 value reasoning.
     */
    private static EsDeps buildLBFakeDep() {
        return lbWithDeps(DependencyInfo::addDataDep);
    }

    /**
     * LB with a <em>real</em> data dependency, e.g. {@code x = r + 1}: the written
     * value genuinely varies with the read, closing a true causal cycle that WEAKEST
     * should FORBID (out-of-thin-air).
     */
    private static EsDeps buildLBRealDep() {
        return lbWithDeps(DependencyInfo::addDataDep);
    }

    /**
     * LB where the read feeds the <em>address</em> of the subsequent write rather than
     * its value — a real {@code addr} dependency, likewise a true causal cycle.
     */
    private static EsDeps buildLBAddrDep() {
        return lbWithDeps(DependencyInfo::addAddrDep);
    }

    /**
     * Build the LB skeleton and wire each thread's write to depend on its own prior
     * read, using the supplied edge kind ({@code addDataDep} / {@code addAddrDep}).
     * Convention is consumer→producer: the write (consumer) depends on the read
     * (producer) whose value flows into it.
     */
    private static EsDeps lbWithDeps(DepEdge edge) {
        EsDeps skel = lbSkeleton();
        EventStructure es = skel.es();
        // Recover the events by their role in the skeleton's program order: each
        // thread is r(read) →po w(write).
        DependencyInfo deps = new DependencyInfo();
        for (Map.Entry<Integer, List<Integer>> po : es.getProgramOrder().entrySet()) {
            Event from = es.getEventById(po.getKey());
            if (!(from instanceof ReadEvent)) continue;
            for (Integer toId : po.getValue()) {
                Event to = es.getEventById(toId);
                if (to instanceof WriteEvent w) edge.add(deps, w, from); // dep(write, read)
            }
        }
        return new EsDeps(es, deps);
    }

    /** A method reference to one of {@link DependencyInfo}'s {@code add*Dep} adders. */
    @FunctionalInterface
    private interface DepEdge {
        void add(DependencyInfo deps, Event consumer, Event producer);
    }

    // ── Small varargs helpers ──────────────────────────────────────────────

    private static void add(EventStructure es, Event... events) {
        for (Event e : events) es.addEvent(e);
    }

    private static void co(EventStructure es, String var, WriteEvent... order) {
        for (WriteEvent wr : order) es.addCoherenceOrder(var, wr);
    }
}
