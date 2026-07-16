package wev.smt.proof;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
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
import wev.smt.LitmusCorpus;
import wev.smt.LitmusCorpus.LitmusCase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only verification driver for the Theorem 4.3 (hierarchy preservation) proof
 * attempt. Does NOT modify the SMT encoding — it only invokes the existing
 * {@link AxiomaticConsistency} consistency formulas and reports SAT (model allows the
 * wired execution) / UNSAT (model forbids it) under each of the five models.
 *
 * <p>For a lattice edge M ⊒ M' (M stronger), "consistent_M ⇒ consistent_M'" is VIOLATED
 * on a wired execution iff the execution is SAT under M (M allows) but UNSAT under M'
 * (M' forbids) — i.e. M' forbids something M permits, so M' is locally stronger than M.
 */
public final class HierarchyProbe {

    private static final String[] MODELS = {"SC", "TSO", "PSO", "RA", "WEAKEST"};
    /** Lattice edges M ⊒ M' (stronger ⊒ weaker). */
    private static final String[][] EDGES =
            {{"SC", "TSO"}, {"TSO", "PSO"}, {"SC", "RA"}, {"RA", "WEAKEST"}};

    private static final MemoryOrder RLX = MemoryOrder.RELAXED;
    private static final MemoryOrder ACQ = MemoryOrder.ACQUIRE;
    private static final MemoryOrder REL = MemoryOrder.RELEASE;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Classics scan (SAT=allowed, UNSAT=forbidden) ===");
        System.out.printf("%-16s %-6s %-6s %-6s %-6s %-8s | violations%n",
                "case", "SC", "TSO", "PSO", "RA", "WEAKEST");
        Map<String, Integer> edgeViol = new LinkedHashMap<>();
        for (String[] e : EDGES) edgeViol.put(e[0] + "=>" + e[1], 0);

        for (LitmusCase lc : LitmusCorpus.classics()) {
            Map<String, Boolean> unsat = new LinkedHashMap<>();
            for (String m : MODELS) unsat.put(m, decideUnsat(lc.es(), lc.deps(), m));
            StringBuilder v = new StringBuilder();
            for (String[] e : EDGES) {
                boolean strongerAllows = !unsat.get(e[0]);   // SAT under M
                boolean weakerForbids = unsat.get(e[1]);      // UNSAT under M'
                if (strongerAllows && weakerForbids) {
                    v.append(' ').append(e[0]).append("=>").append(e[1]);
                    edgeViol.merge(e[0] + "=>" + e[1], 1, Integer::sum);
                }
            }
            System.out.printf("%-16s %-6s %-6s %-6s %-6s %-8s | %s%n",
                    lc.name(), s(unsat, "SC"), s(unsat, "TSO"), s(unsat, "PSO"),
                    s(unsat, "RA"), s(unsat, "WEAKEST"), v.toString());
        }
        System.out.println("\nedge violation counts across classics: " + edgeViol);

        System.out.println("\n=== Custom TSO⊒PSO candidate (W→R + release/acquire sync) ===");
        probe("WR-relacq-cex", buildWRrelacqCex());
    }

    private static String s(Map<String, Boolean> unsat, String m) {
        return unsat.get(m) ? "FORBID" : "allow";
    }

    private static void probe(String name, EventStructure es) throws Exception {
        Map<String, Boolean> unsat = new LinkedHashMap<>();
        for (String m : MODELS) unsat.put(m, decideUnsat(es, DependencyInfo.empty(), m));
        System.out.printf("%-16s SC=%s TSO=%s PSO=%s RA=%s WEAKEST=%s%n", name,
                s(unsat, "SC"), s(unsat, "TSO"), s(unsat, "PSO"),
                s(unsat, "RA"), s(unsat, "WEAKEST"));
        for (String[] e : EDGES) {
            if (!unsat.get(e[0]) && unsat.get(e[1])) {
                System.out.println("  VIOLATION of " + e[0] + " ⊒ " + e[1]
                        + ": " + e[0] + " allows but " + e[1] + " forbids");
            }
        }
    }

    private static boolean decideUnsat(EventStructure es, DependencyInfo deps, String model)
            throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        SolverContext c = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
        try {
            EventStructureEncoder enc = new EventStructureEncoder(c, es, deps);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            BooleanFormula cons = switch (model) {
                case "SC" -> ax.consistencySC();
                case "TSO" -> ax.consistencyTSO();
                case "PSO" -> ax.consistencyPSO();
                case "RA" -> ax.consistencyRA();
                case "WEAKEST" -> ax.consistencyWEAKEST();
                case "RC11" -> ax.consistencyRC11();
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
     * Candidate TSO⊒PSO counterexample. T1: W x=1 ; R a ; W_rel b=1. T2: R_acq b ; W x=2.
     * co fixes x to end at 1 (Wx2 co-before Wx). The release/acquire b-handshake puts
     * hb(Wx, Wx2); with co(Wx2, Wx) that is an hb;eco coherence cycle PSO/RA forbid. TSO
     * drops the same-thread W→R (Wx→Ra), so Wx has no outgoing ppo edge and the cycle is
     * broken in (ppo_T ∪ rfe ∪ co ∪ fr) — TSO should ALLOW it.
     */
    private static EventStructure buildWRrelacqCex() {
        Event.resetCounter();
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", RLX, 0, "0");
        WriteEvent ia = new WriteEvent(0, "a", RLX, 0, "0");
        WriteEvent ib = new WriteEvent(0, "b", RLX, 0, "0");
        WriteEvent wx = new WriteEvent(1, "x", RLX, 1, "1");   // T1: W x=1
        ReadEvent ra = new ReadEvent(1, "a", RLX, "ra");        // T1: R a   (W→R pair)
        WriteEvent wb = new WriteEvent(1, "b", REL, 1, "1");    // T1: W_rel b=1
        ReadEvent rb = new ReadEvent(2, "b", ACQ, "rb");        // T2: R_acq b
        WriteEvent wx2 = new WriteEvent(2, "x", RLX, 2, "2");   // T2: W x=2
        for (Event e : new Event[]{ix, ia, ib, wx, ra, wb, rb, wx2}) es.addEvent(e);
        es.addProgramOrder(wx, ra);
        es.addProgramOrder(ra, wb);
        es.addProgramOrder(rb, wx2);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", wx2);   // Wx2 co-before Wx
        es.addCoherenceOrder("x", wx);    // Wx co-last → final x = 1
        es.addCoherenceOrder("a", ia);
        es.addCoherenceOrder("b", ib);
        es.addCoherenceOrder("b", wb);
        es.addReadsFrom(ra, ia);   // R a reads 0
        es.addReadsFrom(rb, wb);   // R_acq b reads 1  → sw(wb, rb)
        return es;
    }
}
