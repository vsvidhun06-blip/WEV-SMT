package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.SolverContext;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimalWitnessExtractorTest {

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

    @Test
    void minimalConsistentScOnLB() {
        EventStructure es = buildLB();
        MinimalWitnessExtractor mw = newExtractor(es);
        Optional<MinimalWitness> r = mw.findMinimalConsistent(MemoryModel.SC);
        report("minimalConsistentSC-on-LB", r);
        assertTrue(r.isPresent(), "An SC-consistent sub-execution exists (empty is SC).");
        // The smallest non-empty SC-consistent sub-execution drops every read, leaving
        // one or two writes — assert it's strictly less than the full bad-outcome 6.
        assertTrue(r.get().cardinality() < 6,
                "minimum SC-consistent sub-execution should be smaller than full LB");
    }

    @Test
    void separatingTsoMinusScOnSB() {
        EventStructure es = buildSB();
        MinimalWitnessExtractor mw = newExtractor(es);
        Optional<MinimalWitness> r =
                mw.findMinimalSeparating(MemoryModel.TSO, MemoryModel.SC);
        report("separating-TSO\\SC-on-SB", r);
        assertTrue(r.isPresent(), "SB has a TSO-allowed, SC-forbidden execution");
        // The bad SB outcome needs both writes, both reads, and both inits to read 0.
        assertTrue(r.get().cardinality() >= 4,
                "SB separating witness needs at least 4 events");
    }

    @Test
    void separatingWeakestMinusRaOnWRC() {
        EventStructure es = buildWRC();
        MinimalWitnessExtractor mw = newExtractor(es);
        Optional<MinimalWitness> r =
                mw.findMinimalSeparating(MemoryModel.WEAKEST, MemoryModel.RA);
        report("separating-WEAKEST\\RA-on-WRC", r);
        assertTrue(r.isPresent(),
                "WRC bad outcome is WEAKEST-allowed but RA-forbidden");
    }

    @Test
    void separatingScMinusTsoIsEmpty() {
        // SC ⊆ TSO in this encoding (TSO drops constraints; never adds them),
        // so no SC-consistent execution is forbidden by TSO.
        EventStructure es = buildSmallWR();
        MinimalWitnessExtractor mw = newExtractor(es);
        Optional<MinimalWitness> r =
                mw.findMinimalSeparating(MemoryModel.SC, MemoryModel.TSO);
        report("separating-SC\\TSO", r);
        assertFalse(r.isPresent(), "SC ⊆ TSO: no separating witness possible");
    }

    @Test
    void emptyEventStructureIsEmpty() {
        EventStructure es = new EventStructure();
        MinimalWitnessExtractor mw = newExtractor(es);
        Optional<MinimalWitness> a = mw.findMinimalConsistent(MemoryModel.SC);
        Optional<MinimalWitness> b =
                mw.findMinimalSeparating(MemoryModel.TSO, MemoryModel.SC);
        report("empty-consistent", a);
        report("empty-separating", b);
        assertFalse(a.isPresent());
        assertFalse(b.isPresent());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private MinimalWitnessExtractor newExtractor(EventStructure es) {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, es);
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        return new MinimalWitnessExtractor(ctx, enc, ax);
    }

    private static void report(String test, Optional<MinimalWitness> r) {
        if (r.isEmpty()) {
            System.out.printf("[%s] no witness%n", test);
        } else {
            MinimalWitness w = r.get();
            System.out.printf("[%s] %s -- %d events, %d ms%n",
                    test, w.summary(), w.cardinality(), w.solveTimeMs());
        }
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

    /** Small 2-event program: one write, one read of same value. */
    private static EventStructure buildSmallWR() {
        EventStructure es = new EventStructure();
        WriteEvent w = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        ReadEvent r = new ReadEvent(2, "x", MemoryOrder.SC, "r");
        es.addEvent(w);
        es.addEvent(r);
        es.addReadsFrom(r, w);
        return es;
    }
}
