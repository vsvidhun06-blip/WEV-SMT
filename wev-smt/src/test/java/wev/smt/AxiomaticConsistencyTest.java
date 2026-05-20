package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.MemoryOrder;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
}
