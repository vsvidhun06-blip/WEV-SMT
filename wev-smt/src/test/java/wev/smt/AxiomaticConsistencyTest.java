package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.FenceEvent;
import com.weakest.model.FenceEvent.FenceKind;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.RMWEvent;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AxiomaticConsistency} against five classical weak-memory litmus
 * tests under SC, TSO, PSO, RA, and WEAKEST. Each {@code EventStructure} encodes the
 * forbidden-under-SC outcome; the table reports UNSAT (model forbids this outcome)
 * vs SAT (model permits it).
 *
 * <p>The only hard assertion is that all five litmus outcomes are UNSAT under SC —
 * that is the unambiguous ground truth across the memory-model literature. Other
 * cells are printed as a "expected vs actual" comparison without forcing failure,
 * since the precise SAT/UNSAT verdict under TSO/PSO/RA/WEAKEST depends on subtle
 * axiom choices and is part of what this scaffolding is meant to surface.
 */
class AxiomaticConsistencyTest {

    private static final List<String> MODELS =
            List.of("SC", "TSO", "PSO", "RA", "WEAKEST");

    private record Litmus(String name, Supplier<EventStructure> build,
                          Map<String, Boolean> expectedUnsat) { }

    @Test
    void litmusGrid() throws Exception {
        List<Litmus> tests = List.of(
                new Litmus("IRIW", AxiomaticConsistencyTest::buildIRIW,
                        Map.of("SC", true, "TSO", false, "PSO", false,
                                "RA", false, "WEAKEST", false)),
                new Litmus("LB", AxiomaticConsistencyTest::buildLB,
                        Map.of("SC", true, "TSO", false, "PSO", false,
                                "RA", false, "WEAKEST", false)),
                new Litmus("SB", AxiomaticConsistencyTest::buildSB,
                        Map.of("SC", true, "TSO", false, "PSO", false,
                                "RA", false, "WEAKEST", false)),
                new Litmus("MP-relaxed", AxiomaticConsistencyTest::buildMPrelaxed,
                        Map.of("SC", true, "TSO", false, "PSO", false,
                                "RA", true, "WEAKEST", false)),
                new Litmus("WRC", AxiomaticConsistencyTest::buildWRC,
                        Map.of("SC", true, "TSO", false, "PSO", false,
                                "RA", false, "WEAKEST", false)));

        Map<String, Map<String, Boolean>> actual = new LinkedHashMap<>();

        for (Litmus l : tests) {
            Map<String, Boolean> row = new LinkedHashMap<>();
            actual.put(l.name(), row);
            for (String model : MODELS) {
                Configuration cfg = Configuration.defaultConfiguration();
                LogManager log = BasicLogManager.create(cfg);
                SolverContext c = SolverContextFactory.createSolverContext(
                        cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
                try {
                    Event.resetCounter();
                    EventStructure es = l.build().get();
                    EventStructureEncoder enc = new EventStructureEncoder(c, es);
                    AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                    BooleanFormula wf = enc.encodeWellFormedness();
                    BooleanFormula cons = switch (model) {
                        case "SC" -> ax.consistencySC();
                        case "TSO" -> ax.consistencyTSO();
                        case "PSO" -> ax.consistencyPSO();
                        case "RA" -> ax.consistencyRA();
                        case "WEAKEST" -> ax.consistencyWEAKEST();
                        default -> throw new IllegalStateException(model);
                    };
                    try (ProverEnvironment p = c.newProverEnvironment()) {
                        p.addConstraint(enc.getBmgr().and(wf, cons));
                        row.put(model, p.isUnsat());
                    }
                } finally {
                    c.close();
                }
            }
        }

        printTable(tests, actual);

        for (Litmus l : tests) {
            assertTrue(actual.get(l.name()).get("SC"),
                    "Bad outcome of " + l.name() + " must be forbidden under SC");
        }
    }

    private static void printTable(List<Litmus> tests,
                                   Map<String, Map<String, Boolean>> actual) {
        String sep =
                "+--------------+----------------+----------------+----------------+----------------+----------------+";
        System.out.println();
        System.out.println("Consistency table (UNSAT = forbidden, SAT = allowed):");
        System.out.println(sep);
        System.out.printf("| %-12s | %-14s | %-14s | %-14s | %-14s | %-14s |%n",
                "Litmus", "SC", "TSO", "PSO", "RA", "WEAKEST");
        System.out.println(sep);
        for (Litmus l : tests) {
            StringBuilder row = new StringBuilder();
            row.append(String.format("| %-12s ", l.name()));
            for (String m : MODELS) {
                String exp = l.expectedUnsat().get(m) ? "UNSAT" : "SAT";
                String act = actual.get(l.name()).get(m) ? "UNSAT" : "SAT";
                String cell = exp.equals(act)
                        ? String.format("%s (= exp)", act)
                        : String.format("%s (exp %s)", act, exp);
                row.append(String.format("| %-14s ", cell));
            }
            row.append("|");
            System.out.println(row);
        }
        System.out.println(sep);
    }

    // ── Litmus builders ───────────────────────────────────────────────────

    private static EventStructure buildIRIW() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.SC, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.SC, 0, "0");
        WriteEvent w1 = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        WriteEvent w2 = new WriteEvent(2, "y", MemoryOrder.SC, 1, "1");
        ReadEvent r1 = new ReadEvent(3, "x", MemoryOrder.SC, "r1");
        ReadEvent r2 = new ReadEvent(3, "y", MemoryOrder.SC, "r2");
        ReadEvent r3 = new ReadEvent(4, "y", MemoryOrder.SC, "r3");
        ReadEvent r4 = new ReadEvent(4, "x", MemoryOrder.SC, "r4");
        for (Event e : new Event[]{ix, iy, w1, w2, r1, r2, r3, r4}) es.addEvent(e);
        es.addProgramOrder(r1, r2);
        es.addProgramOrder(r3, r4);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", w1);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", w2);
        es.addReadsFrom(r1, w1);
        es.addReadsFrom(r2, iy);
        es.addReadsFrom(r3, w2);
        es.addReadsFrom(r4, ix);
        return es;
    }

    private static EventStructure buildLB() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.SC, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.SC, 0, "0");
        ReadEvent r1 = new ReadEvent(1, "x", MemoryOrder.SC, "r1");
        WriteEvent wy = new WriteEvent(1, "y", MemoryOrder.SC, 1, "1");
        ReadEvent r2 = new ReadEvent(2, "y", MemoryOrder.SC, "r2");
        WriteEvent wx = new WriteEvent(2, "x", MemoryOrder.SC, 1, "1");
        for (Event e : new Event[]{ix, iy, r1, wy, r2, wx}) es.addEvent(e);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, wx);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", wy);
        es.addReadsFrom(r1, wx);
        es.addReadsFrom(r2, wy);
        return es;
    }

    private static EventStructure buildSB() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.SC, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.SC, 0, "0");
        WriteEvent wx = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        ReadEvent r1 = new ReadEvent(1, "y", MemoryOrder.SC, "r1");
        WriteEvent wy = new WriteEvent(2, "y", MemoryOrder.SC, 1, "1");
        ReadEvent r2 = new ReadEvent(2, "x", MemoryOrder.SC, "r2");
        for (Event e : new Event[]{ix, iy, wx, r1, wy, r2}) es.addEvent(e);
        es.addProgramOrder(wx, r1);
        es.addProgramOrder(wy, r2);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", wy);
        es.addReadsFrom(r1, iy);
        es.addReadsFrom(r2, ix);
        return es;
    }

    private static EventStructure buildMPrelaxed() {
        EventStructure es = new EventStructure();
        // Note: ALL accesses RELAXED — the diagnostic case for RA. The data write
        // races with the data read; the flag write races with the flag read; no
        // release/acquire pairing exists to import the data store's visibility.
        WriteEvent idata = new WriteEvent(0, "data", MemoryOrder.RELAXED, 0, "0");
        WriteEvent iflag = new WriteEvent(0, "flag", MemoryOrder.RELAXED, 0, "0");
        WriteEvent wdata = new WriteEvent(1, "data", MemoryOrder.RELAXED, 1, "1");
        WriteEvent wflag = new WriteEvent(1, "flag", MemoryOrder.RELAXED, 1, "1");
        ReadEvent rflag = new ReadEvent(2, "flag", MemoryOrder.RELAXED, "r1");
        ReadEvent rdata = new ReadEvent(2, "data", MemoryOrder.RELAXED, "r2");
        for (Event e : new Event[]{idata, iflag, wdata, wflag, rflag, rdata})
            es.addEvent(e);
        es.addProgramOrder(wdata, wflag);
        es.addProgramOrder(rflag, rdata);
        es.addCoherenceOrder("data", idata); es.addCoherenceOrder("data", wdata);
        es.addCoherenceOrder("flag", iflag); es.addCoherenceOrder("flag", wflag);
        es.addReadsFrom(rflag, wflag);
        es.addReadsFrom(rdata, idata);
        return es;
    }

    private static EventStructure buildWRC() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.SC, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.SC, 0, "0");
        WriteEvent wx = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        ReadEvent r1 = new ReadEvent(2, "x", MemoryOrder.SC, "r1");
        WriteEvent wy = new WriteEvent(2, "y", MemoryOrder.SC, 1, "1");
        ReadEvent r2 = new ReadEvent(3, "y", MemoryOrder.SC, "r2");
        ReadEvent r3 = new ReadEvent(3, "x", MemoryOrder.SC, "r3");
        for (Event e : new Event[]{ix, iy, wx, r1, wy, r2, r3}) es.addEvent(e);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, r3);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", wy);
        es.addReadsFrom(r1, wx);
        es.addReadsFrom(r2, wy);
        es.addReadsFrom(r3, ix);
        return es;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Day 12: fence + RMW encoding (FULL/ACQ/REL fences, RMW atomicity & as-fence)
    // ════════════════════════════════════════════════════════════════════════════

    private static final MemoryOrder RLX = MemoryOrder.RELAXED;
    private static final MemoryOrder ACQ = MemoryOrder.ACQUIRE;
    private static final MemoryOrder REL = MemoryOrder.RELEASE;

    /** Decide UNSAT (model forbids the wired outcome) for a freshly-built structure. */
    private static boolean unsat(Supplier<EventStructure> build, String model) throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        SolverContext c = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
        try {
            Event.resetCounter();
            EventStructure es = build.get();
            EventStructureEncoder enc = new EventStructureEncoder(c, es);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            BooleanFormula cons = switch (model) {
                case "SC" -> ax.consistencySC();
                case "TSO" -> ax.consistencyTSO();
                case "PSO" -> ax.consistencyPSO();
                case "RA" -> ax.consistencyRA();
                case "WEAKEST" -> ax.consistencyWEAKEST();
                default -> throw new IllegalStateException(model);
            };
            try (ProverEnvironment p = c.newProverEnvironment()) {
                p.addConstraint(enc.getBmgr().and(wf, cons));
                return p.isUnsat();
            }
        } finally {
            c.close();
        }
    }

    /**
     * A FULL fence between the store and load of each SB thread restores the W→R order
     * TSO relaxes, so SB+mfence is forbidden under TSO exactly as under SC — while plain
     * SB stays allowed under TSO. (STOP-trigger: SB+mfence/TSO must equal the SC verdict.)
     */
    @Test
    void fenceForbidsReordering() throws Exception {
        assertTrue(unsat(AxiomaticConsistencyTest::buildSBfence, "SC"),
                "SB+mfence is forbidden under SC");
        assertTrue(unsat(AxiomaticConsistencyTest::buildSBfence, "TSO"),
                "SB+mfence is forbidden under TSO (the full fence drains the store buffer)");
        // Control: without the fence, TSO permits the store-buffering outcome.
        assertFalse(unsat(AxiomaticConsistencyTest::buildSB, "TSO"),
                "plain SB is allowed under TSO");
    }

    /**
     * RMW atomicity: a write co-between the value an RMW reads and the RMW itself is
     * forbidden under every model; the same structure with no intervening write is
     * allowed. (The RMW's read/write share one event, so per-location coherence enforces
     * this; the explicit rmwAtomicity axiom is the textbook statement of the same fact.)
     */
    @Test
    void rmwAtomicity() throws Exception {
        for (String m : MODELS) {
            assertTrue(unsat(AxiomaticConsistencyTest::buildRmwAtomicViolating, m),
                    "an intervening write breaks RMW atomicity under " + m);
        }
        assertFalse(unsat(AxiomaticConsistencyTest::buildRmwAtomicClean, "WEAKEST"),
                "with no intervening write the RMW execution is allowed");
    }

    /**
     * An x86 {@code LOCK XCHG} acts as a full fence: a strongly-ordered RMW between each
     * SB thread's store and load restores the W→R order, so SB-with-RMW-fence is
     * forbidden under TSO just like SB+mfence.
     */
    @Test
    void rmwAsFence() throws Exception {
        assertTrue(unsat(AxiomaticConsistencyTest::buildSBrmwFence, "SC"),
                "SB with a full-fence RMW is forbidden under SC");
        assertTrue(unsat(AxiomaticConsistencyTest::buildSBrmwFence, "TSO"),
                "a locked RMW drains the store buffer, forbidding SB under TSO");
        assertFalse(unsat(AxiomaticConsistencyTest::buildSB, "TSO"),
                "plain SB (no RMW fence) is allowed under TSO");
    }

    /**
     * An acquire fence after a relaxed flag-load upgrades it to an acquire read: with a
     * release flag-store, that completes the release/acquire synchronisation, so MP is
     * forbidden under RA. Without the fence (a plain relaxed read) RA allows MP.
     */
    @Test
    void acquireFenceUpgradesRead() throws Exception {
        assertFalse(unsat(() -> buildMPfence(REL, RLX, false, false), "RA"),
                "MP with a relaxed flag-read is allowed under RA");
        assertTrue(unsat(() -> buildMPfence(REL, RLX, true, false), "RA"),
                "an acquire fence after the flag-read upgrades it to acquire, forbidding MP under RA");
    }

    /**
     * A release fence before a relaxed flag-store upgrades it to a release write: with an
     * acquire flag-load, that completes the synchronisation, so MP is forbidden under RA.
     * Without the fence (a plain relaxed write) RA allows MP.
     */
    @Test
    void releaseFenceUpgradesWrite() throws Exception {
        assertFalse(unsat(() -> buildMPfence(RLX, ACQ, false, false), "RA"),
                "MP with a relaxed flag-write is allowed under RA");
        assertTrue(unsat(() -> buildMPfence(RLX, ACQ, false, true), "RA"),
                "a release fence before the flag-write upgrades it to release, forbidding MP under RA");
    }

    // ── Day-12 builders ───────────────────────────────────────────────────────────

    /** SB with a FULL fence between each thread's store and load. */
    private static EventStructure buildSBfence() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", RLX, 0, "0");
        WriteEvent wx = new WriteEvent(1, "x", RLX, 1, "1");
        FenceEvent f1 = new FenceEvent(1, FenceKind.FULL);
        ReadEvent r1 = new ReadEvent(1, "y", RLX, "r1");
        WriteEvent wy = new WriteEvent(2, "y", RLX, 1, "1");
        FenceEvent f2 = new FenceEvent(2, FenceKind.FULL);
        ReadEvent r2 = new ReadEvent(2, "x", RLX, "r2");
        for (Event e : new Event[]{ix, iy, wx, f1, r1, wy, f2, r2}) es.addEvent(e);
        es.addProgramOrder(wx, f1); es.addProgramOrder(f1, r1);
        es.addProgramOrder(wy, f2); es.addProgramOrder(f2, r2);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", wy);
        es.addReadsFrom(r1, iy);
        es.addReadsFrom(r2, ix);
        return es;
    }

    /** SB with a strongly-ordered (full-fence) RMW between each store and load. */
    private static EventStructure buildSBrmwFence() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", RLX, 0, "0");
        WriteEvent is1 = new WriteEvent(0, "s1", RLX, 0, "0");
        WriteEvent is2 = new WriteEvent(0, "s2", RLX, 0, "0");
        WriteEvent wx = new WriteEvent(1, "x", RLX, 1, "1");
        RMWEvent u1 = new RMWEvent(1, "s1", MemoryOrder.SC, 0, 1);   // full fence
        ReadEvent r1 = new ReadEvent(1, "y", RLX, "r1");
        WriteEvent wy = new WriteEvent(2, "y", RLX, 1, "1");
        RMWEvent u2 = new RMWEvent(2, "s2", MemoryOrder.SC, 0, 1);
        ReadEvent r2 = new ReadEvent(2, "x", RLX, "r2");
        for (Event e : new Event[]{ix, iy, is1, is2, wx, u1, r1, wy, u2, r2}) es.addEvent(e);
        es.addProgramOrder(wx, u1); es.addProgramOrder(u1, r1);
        es.addProgramOrder(wy, u2); es.addProgramOrder(u2, r2);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", wy);
        es.addCoherenceOrder("s1", is1); es.addCoherenceOrder("s1", u1);
        es.addCoherenceOrder("s2", is2); es.addCoherenceOrder("s2", u2);
        es.addReadsFrom(r1, iy);
        es.addReadsFrom(r2, ix);
        return es;
    }

    /** An RMW reads x=0 but a co-intervening write to x sits between the init and the RMW. */
    private static EventStructure buildRmwAtomicViolating() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        RMWEvent u = new RMWEvent(1, "x", MemoryOrder.SC, 0, 2);   // reads 0, writes 2
        WriteEvent w2 = new WriteEvent(2, "x", RLX, 1, "1");
        for (Event e : new Event[]{ix, u, w2}) es.addEvent(e);
        // co: init < w2 < RMW — w2 is wedged between the value the RMW reads and the RMW.
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", w2);
        es.addCoherenceOrder("x", u);
        return es;
    }

    /** The same structure with no write between the RMW's read source and the RMW. */
    private static EventStructure buildRmwAtomicClean() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        RMWEvent u = new RMWEvent(1, "x", MemoryOrder.SC, 0, 2);
        WriteEvent w2 = new WriteEvent(2, "x", RLX, 1, "1");
        for (Event e : new Event[]{ix, u, w2}) es.addEvent(e);
        // co: init < RMW < w2 — nothing intervenes between the init and the RMW.
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", u);
        es.addCoherenceOrder("x", w2);
        return es;
    }

    /**
     * MP with optional fences. {@code wFlagMo}/{@code rFlagMo} set the flag store/load
     * memory order; {@code acqAfterRead} inserts an ACQ fence between the flag-read and
     * the data-read; {@code relBeforeWrite} inserts a REL fence between the data-write and
     * the flag-write. The wired outcome is the MP violation (flag seen, data stale).
     */
    private static EventStructure buildMPfence(MemoryOrder wFlagMo, MemoryOrder rFlagMo,
                                               boolean acqAfterRead, boolean relBeforeWrite) {
        EventStructure es = new EventStructure();
        WriteEvent idata = new WriteEvent(0, "data", RLX, 0, "0");
        WriteEvent iflag = new WriteEvent(0, "flag", RLX, 0, "0");
        WriteEvent wdata = new WriteEvent(1, "data", RLX, 1, "1");
        FenceEvent frel = relBeforeWrite ? new FenceEvent(1, FenceKind.REL) : null;
        WriteEvent wflag = new WriteEvent(1, "flag", wFlagMo, 1, "1");
        ReadEvent rflag = new ReadEvent(2, "flag", rFlagMo, "r1");
        FenceEvent facq = acqAfterRead ? new FenceEvent(2, FenceKind.ACQ) : null;
        ReadEvent rdata = new ReadEvent(2, "data", RLX, "r2");
        es.addEvent(idata); es.addEvent(iflag); es.addEvent(wdata);
        if (frel != null) es.addEvent(frel);
        es.addEvent(wflag); es.addEvent(rflag);
        if (facq != null) es.addEvent(facq);
        es.addEvent(rdata);
        if (frel != null) { es.addProgramOrder(wdata, frel); es.addProgramOrder(frel, wflag); }
        else es.addProgramOrder(wdata, wflag);
        if (facq != null) { es.addProgramOrder(rflag, facq); es.addProgramOrder(facq, rdata); }
        else es.addProgramOrder(rflag, rdata);
        es.addCoherenceOrder("data", idata); es.addCoherenceOrder("data", wdata);
        es.addCoherenceOrder("flag", iflag); es.addCoherenceOrder("flag", wflag);
        es.addReadsFrom(rflag, wflag);   // sees flag = 1
        es.addReadsFrom(rdata, idata);   // but data = 0
        return es;
    }
}
