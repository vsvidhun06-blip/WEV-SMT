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
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the well-formedness correction (corrected v1, finding F-01).
 *
 * <p>The minimal counterexample CX-1 is {@code 2+2W+rfis}, built by hand (no parser):
 * <pre>
 *   init: ix = W(x,0)  iy = W(y,0)
 *   T1:   a = W(x,1) ; b = R(x) (reads a) ; c = W(y,1)
 *   T2:   d = W(y,2) ; e = R(y) (reads d) ; f = W(x,2)
 *   co:   ix &lt; f &lt; a  (final x = 1)      iy &lt; c &lt; d  (final y = 2)
 * </pre>
 * No PSO/RA/WEAKEST/RC11 axiom forbids this candidate; SC and TSO do (cycle
 * a →po c →co d →po f →co a with W→W preserved). The submitted build's well-formedness
 * alone was UNSAT on it. Every check fixes rf and co to the candidate, so a verdict here
 * is about this one candidate, not about the program.
 *
 * <p>The remaining tests check that the correction keeps the properties well-formedness
 * is meant to enforce: co is a strict order per location, same-location program order is
 * respected, each read has exactly one value-matching rf source, and the per-model
 * constraints still separate the models on standard shapes.
 */
class WellFormednessAdequacyTest {

    private static final MemoryOrder RLX = MemoryOrder.RELAXED;

    private SolverContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        Event.resetCounter();
        Configuration cfg = Configuration.defaultConfiguration();
        ctx = SolverContextFactory.createSolverContext(
                cfg, BasicLogManager.create(cfg), ShutdownNotifier.createDummy(), Solvers.Z3);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────

    /** A hand-built candidate: the structure plus the rf and co edges asserted true. */
    private record Cand(EventStructure es, List<Event[]> rf, List<Event[]> co) { }

    /** CX-1, {@code 2+2W+rfis}; see the class comment. */
    private static Cand cx1() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", RLX, 0, "0");
        WriteEvent a = new WriteEvent(1, "x", RLX, 1, "1");
        ReadEvent b = new ReadEvent(1, "x", RLX, "r0");
        WriteEvent c = new WriteEvent(1, "y", RLX, 1, "1");
        WriteEvent d = new WriteEvent(2, "y", RLX, 2, "2");
        ReadEvent e = new ReadEvent(2, "y", RLX, "r1");
        WriteEvent f = new WriteEvent(2, "x", RLX, 2, "2");
        for (Event ev : new Event[]{ix, iy, a, b, c, d, e, f}) es.addEvent(ev);
        es.addProgramOrder(a, b); es.addProgramOrder(b, c);
        es.addProgramOrder(d, e); es.addProgramOrder(e, f);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", f); es.addCoherenceOrder("x", a);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", c); es.addCoherenceOrder("y", d);
        es.addReadsFrom(b, a);
        es.addReadsFrom(e, d);
        return new Cand(es,
                List.of(new Event[]{a, b}, new Event[]{d, e}),
                List.of(new Event[]{ix, f}, new Event[]{f, a}, new Event[]{ix, a},
                        new Event[]{iy, c}, new Event[]{c, d}, new Event[]{iy, d}));
    }

    /** Plain 2+2W (no internal reads): W x=1; W y=1 ∥ W y=2; W x=2, co ix&lt;f&lt;a, iy&lt;c&lt;d. */
    private static Cand plain2plus2W() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", RLX, 0, "0");
        WriteEvent a = new WriteEvent(1, "x", RLX, 1, "1");
        WriteEvent c = new WriteEvent(1, "y", RLX, 1, "1");
        WriteEvent d = new WriteEvent(2, "y", RLX, 2, "2");
        WriteEvent f = new WriteEvent(2, "x", RLX, 2, "2");
        for (Event ev : new Event[]{ix, iy, a, c, d, f}) es.addEvent(ev);
        es.addProgramOrder(a, c); es.addProgramOrder(d, f);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", f); es.addCoherenceOrder("x", a);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", c); es.addCoherenceOrder("y", d);
        return new Cand(es, List.of(),
                List.of(new Event[]{ix, f}, new Event[]{f, a}, new Event[]{ix, a},
                        new Event[]{iy, c}, new Event[]{c, d}, new Event[]{iy, d}));
    }

    /** SB with both reads from the initial writes: W x=1; R y ∥ W y=1; R x. */
    private static Cand sbReadsInit() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", RLX, 0, "0");
        WriteEvent a = new WriteEvent(1, "x", RLX, 1, "1");
        ReadEvent b = new ReadEvent(1, "y", RLX, "r0");
        WriteEvent c = new WriteEvent(2, "y", RLX, 1, "1");
        ReadEvent d = new ReadEvent(2, "x", RLX, "r1");
        for (Event ev : new Event[]{ix, iy, a, b, c, d}) es.addEvent(ev);
        es.addProgramOrder(a, b); es.addProgramOrder(c, d);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", a);
        es.addCoherenceOrder("y", iy); es.addCoherenceOrder("y", c);
        es.addReadsFrom(b, iy);
        es.addReadsFrom(d, ix);
        return new Cand(es,
                List.of(new Event[]{iy, b}, new Event[]{ix, d}),
                List.of(new Event[]{ix, a}, new Event[]{iy, c}));
    }

    /** CoWW against program order: W x=1; W x=2 on one thread with co 2 before 1. */
    private static Cand coWWReversed() {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent a = new WriteEvent(1, "x", RLX, 1, "1");
        WriteEvent b = new WriteEvent(1, "x", RLX, 2, "2");
        for (Event ev : new Event[]{ix, a, b}) es.addEvent(ev);
        es.addProgramOrder(a, b);
        es.addCoherenceOrder("x", ix); es.addCoherenceOrder("x", b); es.addCoherenceOrder("x", a);
        return new Cand(es, List.of(),
                List.of(new Event[]{ix, b}, new Event[]{b, a}, new Event[]{ix, a}));
    }

    // ── Query helpers ────────────────────────────────────────────────────────────

    private static BooleanFormula consistency(AxiomaticConsistency ax, MemoryModel m) {
        return switch (m) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
            case PSO -> ax.consistencyPSO();
            case RA -> ax.consistencyRA();
            case WEAKEST -> ax.consistencyWEAKEST();
            case RC11 -> ax.consistencyRC11();
        };
    }

    /** rf and co variables fixed to the candidate: listed edges true, every other false. */
    private static List<BooleanFormula> fixCandidate(EventStructureEncoder enc, Cand cand) {
        BooleanFormulaManager b = enc.getBmgr();
        List<BooleanFormula> cs = new ArrayList<>();
        for (Map.Entry<EventPair, BooleanFormula> en : enc.getRfVars().entrySet()) {
            boolean on = cand.rf().stream().anyMatch(
                    p -> p[0] == en.getKey().from() && p[1] == en.getKey().to());
            cs.add(on ? en.getValue() : b.not(en.getValue()));
        }
        for (Map.Entry<EventPair, BooleanFormula> en : enc.getCoVars().entrySet()) {
            boolean on = cand.co().stream().anyMatch(
                    p -> p[0] == en.getKey().from() && p[1] == en.getKey().to());
            cs.add(on ? en.getValue() : b.not(en.getValue()));
        }
        return cs;
    }

    private boolean sat(List<BooleanFormula> cs, BooleanFormulaManager b) throws Exception {
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(b.and(cs));
            return !p.isUnsat();
        }
    }

    /** SAT of [well-formedness] ∧ [consistency of m] ∧ [candidate fixed], per the flags. */
    private boolean query(Cand cand, MemoryModel m, boolean withWf, boolean fix) throws Exception {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, cand.es());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        List<BooleanFormula> cs = new ArrayList<>();
        if (withWf) cs.add(enc.encodeWellFormedness());
        if (m != null) cs.add(consistency(ax, m));
        if (fix) cs.addAll(fixCandidate(enc, cand));
        return sat(cs, enc.getBmgr());
    }

    private static String verdicts(Map<MemoryModel, Boolean> allowed) {
        StringBuilder sb = new StringBuilder();
        for (MemoryModel m : MemoryModel.values()) {
            sb.append(m).append('=').append(allowed.get(m) ? "A" : "F").append(' ');
        }
        return sb.toString().trim();
    }

    private Map<MemoryModel, Boolean> fixedCandidateVerdicts(Cand cand) throws Exception {
        Map<MemoryModel, Boolean> out = new java.util.EnumMap<>(MemoryModel.class);
        for (MemoryModel m : MemoryModel.values()) out.put(m, query(cand, m, true, true));
        return out;
    }

    // ── CX-1: the minimal F-01 counterexample ────────────────────────────────────

    @Test
    void cx1WellFormednessAloneAdmitsTheLegalCandidate() throws Exception {
        // Submitted build: UNSAT (6-constraint pos core). Corrected: SAT.
        assertTrue(query(cx1(), null, true, true),
                "well-formedness alone must admit the legal 2+2W+rfis candidate");
    }

    @Test
    void cx1ConsistencyAloneMatchesTheRelationalAnalysis() throws Exception {
        // Unaffected by the correction: the consistency conjunct (Prop. 1's object).
        for (MemoryModel m : MemoryModel.values()) {
            boolean expected = m != MemoryModel.SC && m != MemoryModel.TSO;
            assertEquals(expected, query(cx1(), m, false, true), "consistency alone, " + m);
        }
    }

    @Test
    void cx1VerdictsWithWellFormednessMatchTheRelationalAnalysis() throws Exception {
        // Submitted build: all six FORBIDDEN. Corrected: SC/TSO F, PSO/RA/WEAKEST/RC11 A.
        assertEquals("SC=F TSO=F PSO=A RA=A WEAKEST=A RC11=A",
                verdicts(fixedCandidateVerdicts(cx1())));
    }

    @Test
    void cx1QueryWithRfAndCoUnfixed() throws Exception {
        // Wired-mode query (co pinned to the loader's textual order, rf free).
        Map<MemoryModel, Boolean> out = new java.util.EnumMap<>(MemoryModel.class);
        for (MemoryModel m : MemoryModel.values()) out.put(m, query(cx1(), m, true, false));
        assertEquals("SC=F TSO=F PSO=A RA=A WEAKEST=A RC11=A", verdicts(out));
    }

    // ── Preservation: what well-formedness must still enforce ────────────────────

    @Test
    void plain2plus2WIsUnchangedByTheCorrection() throws Exception {
        // Never affected (no internal read): same verdicts in both builds.
        assertEquals("SC=F TSO=F PSO=A RA=A WEAKEST=A RC11=A",
                verdicts(fixedCandidateVerdicts(plain2plus2W())));
    }

    @Test
    void storeBufferingStillSeparatesScFromTso() throws Exception {
        assertEquals("SC=F TSO=A PSO=A RA=A WEAKEST=A RC11=A",
                verdicts(fixedCandidateVerdicts(sbReadsInit())));
    }

    @Test
    void sameLocationProgramOrderStillBindsCo() throws Exception {
        // co against same-location po: rejected by well-formedness alone and by every model.
        assertTrue(!query(coWWReversed(), null, true, true),
                "well-formedness must reject co reversing same-location W→W program order");
        for (MemoryModel m : MemoryModel.values()) {
            assertTrue(!query(coWWReversed(), m, false, true), "coherence must reject it, " + m);
        }
    }

    @Test
    void coIsStillAStrictOrderPerLocation() throws Exception {
        // co(f,a) and co(a,f) together contradict pos totality.
        Cand cand = cx1();
        EventStructureEncoder enc = new EventStructureEncoder(ctx, cand.es());
        BooleanFormulaManager b = enc.getBmgr();
        List<BooleanFormula> cs = new ArrayList<>();
        cs.add(enc.encodeWellFormedness());
        Event a = cand.co().get(1)[1], f = cand.co().get(1)[0];
        cs.add(enc.getCoVars().get(new EventPair(f, a)));
        cs.add(enc.getCoVars().get(new EventPair(a, f)));
        assertTrue(!sat(cs, b), "co must stay antisymmetric");
    }

    @Test
    void readsStillNeedOneValueMatchingSource() throws Exception {
        Cand cand = cx1();
        EventStructureEncoder enc = new EventStructureEncoder(ctx, cand.es());
        BooleanFormulaManager b = enc.getBmgr();
        Event a = cand.rf().get(0)[0], read = cand.rf().get(0)[1], f = cand.co().get(1)[0];
        // value mismatch: b expects 1 (its wired value), f writes 2
        List<BooleanFormula> mismatch = new ArrayList<>(List.of(enc.encodeWellFormedness(),
                enc.getRfVars().get(new EventPair(f, read))));
        assertTrue(!sat(mismatch, b), "rf from a write of a different value must be rejected");
        // two sources at once
        List<BooleanFormula> two = new ArrayList<>(List.of(enc.encodeWellFormedness(),
                enc.getRfVars().get(new EventPair(a, read)), enc.getRfVars().get(new EventPair(f, read))));
        assertTrue(!sat(two, b), "a read must have exactly one rf source");
    }

    // ── Gated (sub-execution) well-formedness in MinimalWitnessExtractor ─────────

    /** Gated well-formedness with every event active and the CX-1 candidate fixed. */
    private boolean gatedAllActive(Cand cand) throws Exception {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, cand.es());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        MinimalWitnessExtractor mw = new MinimalWitnessExtractor(ctx, enc, ax);
        Method gwf = MinimalWitnessExtractor.class.getDeclaredMethod("gatedWellFormedness");
        gwf.setAccessible(true);
        Field act = MinimalWitnessExtractor.class.getDeclaredField("activeVars");
        act.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Event, BooleanFormula> active = (Map<Event, BooleanFormula>) act.get(mw);
        List<BooleanFormula> cs = new ArrayList<>();
        cs.add((BooleanFormula) gwf.invoke(mw));
        cs.addAll(active.values());
        cs.addAll(fixCandidate(enc, cand));
        return sat(cs, enc.getBmgr());
    }

    @Test
    void gatedWellFormednessAdmitsCx1WhenAllEventsAreActive() throws Exception {
        // Submitted build: UNSAT (same leak, gated). Corrected: SAT.
        assertTrue(gatedAllActive(cx1()),
                "gated well-formedness must admit the full CX-1 candidate");
    }

    @Test
    void gatedWellFormednessStillRejectsReversedSameLocationCo() throws Exception {
        assertTrue(!gatedAllActive(coWWReversed()));
    }

    @Test
    void separatingPsoTsoWitnessExistsForCx1() throws Exception {
        // The witness extractor pins co to the loader order (ix<f<a, iy<c<d) and matches
        // folded values. A PSO-allowed, TSO-forbidden sub-execution exists in both builds
        // (dropping the reads leaves plain 2+2W); this guards the search end to end.
        Cand cand = cx1();
        EventStructureEncoder enc = new EventStructureEncoder(ctx, cand.es());
        MinimalWitnessExtractor mw = new MinimalWitnessExtractor(ctx, enc, new AxiomaticConsistency(enc));
        Optional<MinimalWitness> sep = mw.findMinimalSeparating(MemoryModel.PSO, MemoryModel.TSO);
        assertTrue(sep.isPresent(), "CX-1 has a PSO-allowed, TSO-forbidden sub-execution");
    }
}
