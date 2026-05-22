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
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Model;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStructureEncoderTest {

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
    void twoEventWriteReadIsSat() throws Exception {
        EventStructure es = new EventStructure();
        WriteEvent w = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        ReadEvent  r = new ReadEvent(2, "x", MemoryOrder.SC, "r");
        es.addEvent(w);
        es.addEvent(r);
        es.addReadsFrom(r, w);

        EventStructureEncoder enc = new EventStructureEncoder(ctx, es);
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula wf = enc.encodeWellFormedness();

        // (1) The wired W/R is well-formed.
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(wf);
            assertFalse(p.isUnsat(), "2-event W/R with rf wired must be SAT");
        }

        // (2) Pass 3 Stage 2: the global rf-forward edge (rf ⇒ pos(w) < pos(r)) was
        // removed from well-formedness, so `pos` is now only a po∪co linearization and
        // no longer encodes read-after-write — we therefore no longer assert
        // pos(w) < pos(r) here. Per-location read-after-write moved to
        // AxiomaticConsistency.coherencePerLocation (over its own colayer vars), so we
        // instead assert the wired read stays consistent under it. The negative side
        // (a coherence-violating read is rejected) is covered end-to-end by the
        // CoRR/CoRW/CoWR/CoWW corpus cases in AtlasReconstruct.
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(enc.getBmgr().and(wf, ax.coherencePerLocation()));
            assertFalse(p.isUnsat(),
                    "wired W/R must stay consistent under per-location coherence");
        }
    }

    @Test
    void iriwHasSatExecution() throws Exception {
        EventStructure es = new EventStructure();

        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.SC, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.SC, 0, "0");
        WriteEvent w1 = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        WriteEvent w2 = new WriteEvent(2, "y", MemoryOrder.SC, 1, "1");
        ReadEvent  r1 = new ReadEvent(3, "x", MemoryOrder.SC, "r1");
        ReadEvent  r2 = new ReadEvent(3, "y", MemoryOrder.SC, "r2");
        ReadEvent  r3 = new ReadEvent(4, "y", MemoryOrder.SC, "r3");
        ReadEvent  r4 = new ReadEvent(4, "x", MemoryOrder.SC, "r4");

        for (Event e : new Event[]{ix, iy, w1, w2, r1, r2, r3, r4}) es.addEvent(e);

        es.addProgramOrder(ix, iy);
        es.addProgramOrder(r1, r2);
        es.addProgramOrder(r3, r4);

        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", w1);
        es.addCoherenceOrder("y", iy);
        es.addCoherenceOrder("y", w2);

        es.addReadsFrom(r1, ix);
        es.addReadsFrom(r2, iy);
        es.addReadsFrom(r3, iy);
        es.addReadsFrom(r4, ix);

        EventStructureEncoder enc = new EventStructureEncoder(ctx, es);
        BooleanFormula phi = enc.encodeWellFormedness();

        try (ProverEnvironment p =
                     ctx.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
            p.addConstraint(phi);
            assertFalse(p.isUnsat(),
                    "IRIW with all reads reading initial 0 must be SAT");

            try (Model m = p.getModel()) {
                // co implies pos(ix) < pos(w1) and pos(iy) < pos(w2).
                BigInteger pIx = m.evaluate(enc.getEventVars().get(ix));
                BigInteger pW1 = m.evaluate(enc.getEventVars().get(w1));
                BigInteger pIy = m.evaluate(enc.getEventVars().get(iy));
                BigInteger pW2 = m.evaluate(enc.getEventVars().get(w2));
                assertTrue(pIx.compareTo(pW1) < 0, "co: init(x) < w1");
                assertTrue(pIy.compareTo(pW2) < 0, "co: init(y) < w2");
            }
        }
    }

    @Test
    void eventVarCountMatchesEventCount() {
        EventStructure es = new EventStructure();
        WriteEvent w = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        ReadEvent  r = new ReadEvent(2, "x", MemoryOrder.SC, "r");
        es.addEvent(w);
        es.addEvent(r);

        EventStructureEncoder enc = new EventStructureEncoder(ctx, es);
        assertEquals(2, enc.getEventVars().size());
        assertNotNull(enc.getEventVars().get(w));
        assertNotNull(enc.getEventVars().get(r));
    }

    @Test
    void jfEncodingMatchesRf() throws Exception {
        EventStructure es = new EventStructure();
        WriteEvent w = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        ReadEvent  r = new ReadEvent(2, "x", MemoryOrder.SC, "r");
        es.addEvent(w);
        es.addEvent(r);
        es.addReadsFrom(r, w);
        es.addJustifiesFrom(r, w);

        EventStructureEncoder enc = new EventStructureEncoder(ctx, es);
        BooleanFormula wf = enc.encodeWellFormedness();
        BooleanFormula rfRel = enc.encodeRelation("rf");
        BooleanFormula jfRel = enc.encodeRelation("jf");

        // Both relations should produce SAT alongside well-formedness when
        // they reference the same underlying (read, write) pair.
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(wf);
            p.addConstraint(rfRel);
            assertFalse(p.isUnsat(), "wf ∧ rf must be SAT");
        }
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(wf);
            p.addConstraint(jfRel);
            assertFalse(p.isUnsat(), "wf ∧ jf must be SAT");
        }
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(wf);
            p.addConstraint(rfRel);
            p.addConstraint(jfRel);
            assertFalse(p.isUnsat(),
                    "wf ∧ rf ∧ jf must be SAT when jf and rf agree");
        }
    }

    @Test
    void ewGroupingTest() throws Exception {
        EventStructure es = new EventStructure();
        WriteEvent w1 = new WriteEvent(1, "x", MemoryOrder.SC, 1, "1");
        WriteEvent w2 = new WriteEvent(1, "y", MemoryOrder.SC, 1, "1");
        WriteEvent w3 = new WriteEvent(2, "z", MemoryOrder.SC, 1, "1");
        es.addEvent(w1);
        es.addEvent(w2);
        es.addEvent(w3);
        es.addEventWiseClass(w1, w2);

        EventStructureEncoder enc = new EventStructureEncoder(ctx, es);
        BooleanFormula wf = enc.encodeWellFormedness();
        BooleanFormula ewRel = enc.encodeRelation("ew");

        try (ProverEnvironment p =
                     ctx.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
            p.addConstraint(wf);
            p.addConstraint(ewRel);
            assertFalse(p.isUnsat(), "ew classes must be satisfiable");

            try (Model m = p.getModel()) {
                BigInteger g1 = m.evaluate(enc.getEwGroupVars().get(w1));
                BigInteger g2 = m.evaluate(enc.getEwGroupVars().get(w2));
                assertNotNull(g1);
                assertNotNull(g2);
                assertEquals(g1, g2, "w1 and w2 are in the same ew-class");
            }
        }
    }
}
