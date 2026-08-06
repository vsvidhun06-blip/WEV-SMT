package wev.smt.research;

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


import java.util.List;

/**
 * GATE 3: an attack on the load-bearing claim that dropping an N4-fake control edge
 * cannot admit thin air.
 *
 * <p>The claim under attack, from the previous session's report: <em>"dropping an
 * N4-fake edge cannot admit thin air because the observable effect is constant in
 * r."</em> N4 abstracts a branch by ONE word --- the value its write deposits. This
 * run builds, by hand, event structures in which that word is constant in {@code r}
 * while some OTHER structural property visible to {@code jfCoherence} is not, and
 * checks whether dropping the control edge flips the verdict.
 *
 * <p>The event structures are constructed directly rather than parsed, because the C
 * front-end discards branch guards (see {@link ControlIntegrationRun}); building them
 * here is the only way to put a genuine two-armed or guarded branch in front of
 * {@code jfCoherence}.
 *
 * <p>Each scenario is run twice against the SAME event structure: once with the control
 * edges marked semantic (the baseline the shipped detector produces) and once with them
 * dropped (what the N4 criterion would do). A verdict flip from FORBIDDEN to ALLOWED is
 * a counterexample: the criterion admitted an execution that must stay forbidden.
 */
public final class ControlN4AttackRun {

    private static final MemoryOrder RLX = MemoryOrder.RELAXED;

    record Scenario(String name, String shape, String n4Verdict, String mustBe,
                    EventStructure es, List<Event[]> ctrlEdges, List<Event[]> dataEdges) { }

    public static void main(String[] args) throws Exception {
        List<Scenario> scenarios = List.of(
                f0Baseline(), f2OverwriteAttack(), f6ExistenceAttack(),
                f7TwoLocationAttack(), f2CoAttack());

        System.out.printf("%-26s %-46s %-9s %-10s %-10s %-10s %s%n",
                "scenario", "shape", "N4 says", "baseline", "N4-dropped", "must be", "OUTCOME");
        int counterexamples = 0;
        for (Scenario s : scenarios) {
            String baseline = verdict(s, true);
            String dropped = verdict(s, false);
            boolean broken = "FORBIDDEN".equals(baseline)
                    && "ALLOWED".equals(dropped)
                    && "FORBIDDEN".equals(s.mustBe());
            if (broken) counterexamples++;
            String outcome = broken ? "*** COUNTEREXAMPLE ***"
                    : baseline.equals(dropped) ? "no change"
                    : "flip, but permitted";
            System.out.printf("%-26s %-46s %-9s %-10s %-10s %-10s %s%n",
                    s.name(), s.shape(), s.n4Verdict(), baseline, dropped, s.mustBe(), outcome);
        }
        System.out.println();
        System.out.println("counterexamples found = " + counterexamples);
        System.out.println(counterexamples == 0
                ? "N4 SURVIVED the attack on these scenarios"
                : "N4 INSUFFICIENT FOR jfCoherence");
    }

    // ── Scenario builders ────────────────────────────────────────────────────

    /**
     * F0 control, exactly as the fragment specifies: one two-way branch, each arm writing
     * ONCE to the same location, same value. Only one write event exists after the arms
     * are merged, because the arms are indistinguishable. N4 says fake, and it is: the LB
     * outcome is genuinely admissible, so the flip to ALLOWED is correct.
     */
    private static Scenario f0Baseline() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", 0), iy = w(0, "y", 0);
        ReadEvent r0 = r(1, "y", "r0");
        WriteEvent wx = w(1, "x", 1);              // both arms write x = 1
        ReadEvent r1 = r(2, "x", "r1");
        WriteEvent wy = w(2, "y", 1);
        add(es, ix, iy, r0, wx, r1, wy);
        es.addProgramOrder(r0, wx);
        es.addProgramOrder(r1, wy);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r0, wy);
        es.addReadsFrom(r1, wx);
        // Same data edge as every attack scenario, so the only difference between this
        // positive control and the attacks is the branch shape, not the wiring.
        return new Scenario("F0-both-arms-write-1",
                "if(r0) W(x,1) else W(x,1) | r1=R(x); W(y,r1)",
                "FAKE", "ALLOWED", es,
                List.<Event[]>of(new Event[] { wx, r0 }),
                List.<Event[]>of(new Event[] { wy, r1 }));
    }

    /**
     * F2 ATTACK --- one arm writes then overwrites. Both arms leave x holding 2, so the
     * FINAL-VALUE effect is constant in r0 and N4 reports FAKE. But the true arm also
     * makes the value 1 readable at x, and P1 reads exactly that write, then writes y=1,
     * which justifies r0=1. The cycle
     *
     *   R(y)=1 -ctrl-> W(x,1) -jf-> R(x)=1 -data-> W(y,1) -jf-> R(y)=1
     *
     * is pure thin air and must stay forbidden.
     */
    private static Scenario f2OverwriteAttack() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", 0), iy = w(0, "y", 0);
        ReadEvent r0 = r(1, "y", "r0");
        WriteEvent wx1 = w(1, "x", 1);             // true arm, first write
        WriteEvent wx2 = w(1, "x", 2);             // true arm, overwrite (false arm writes 2)
        ReadEvent r1 = r(2, "x", "r1");
        WriteEvent wy = w(2, "y", 1);
        add(es, ix, iy, r0, wx1, wx2, r1, wy);
        es.addProgramOrder(r0, wx1);
        es.addProgramOrder(wx1, wx2);
        es.addProgramOrder(r1, wy);
        co(es, "x", ix, wx1, wx2);
        co(es, "y", iy, wy);
        es.addReadsFrom(r0, wy);                   // r0 = 1
        es.addReadsFrom(r1, wx1);                  // r1 = 1, from the INTERMEDIATE write
        return new Scenario("F2-write-then-overwrite",
                "if(r0){W(x,1);W(x,2)} else W(x,2) | r1=R(x); W(y,r1)",
                "FAKE", "FORBIDDEN", es,
                List.<Event[]>of(new Event[] { wx1, r0 }, new Event[] { wx2, r0 }),
                List.<Event[]>of(new Event[] { wy, r1 }));
    }

    /**
     * F6 ATTACK --- existence control. The write to x happens only on the true arm. Its
     * VALUE is the constant 1, so any value-only effect abstraction reports FAKE; but
     * whether the write exists at all is what depends on r0. This is the shape of JMM
     * causality tests TC03 and TC07.
     */
    private static Scenario f6ExistenceAttack() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", 0), iy = w(0, "y", 0);
        ReadEvent r0 = r(1, "y", "r0");
        WriteEvent wx = w(1, "x", 1);              // guarded: exists only when r0 == 1
        ReadEvent r1 = r(2, "x", "r1");
        WriteEvent wy = w(2, "y", 1);
        add(es, ix, iy, r0, wx, r1, wy);
        es.addProgramOrder(r0, wx);
        es.addProgramOrder(r1, wy);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r0, wy);
        es.addReadsFrom(r1, wx);
        return new Scenario("F6-existence-control",
                "if(r0) W(x,1) [no else] | r1=R(x); W(y,r1)",
                "FAKE", "FORBIDDEN", es,
                List.<Event[]>of(new Event[] { wx, r0 }),
                List.<Event[]>of(new Event[] { wy, r1 }));
    }

    /**
     * F7 ATTACK --- same value, different location. Both arms write the value 1, so a
     * location-blind value abstraction reports FAKE; which location becomes readable is
     * what r0 controls.
     */
    private static Scenario f7TwoLocationAttack() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", 0), iy = w(0, "y", 0), iz = w(0, "z", 0);
        ReadEvent r0 = r(1, "y", "r0");
        WriteEvent wx = w(1, "x", 1);              // true arm writes x; false arm writes z
        ReadEvent r1 = r(2, "x", "r1");
        WriteEvent wy = w(2, "y", 1);
        add(es, ix, iy, iz, r0, wx, r1, wy);
        es.addProgramOrder(r0, wx);
        es.addProgramOrder(r1, wy);
        co(es, "x", ix, wx);
        co(es, "y", iy, wy);
        es.addReadsFrom(r0, wy);
        es.addReadsFrom(r1, wx);
        return new Scenario("F7-different-locations",
                "if(r0) W(x,1) else W(z,1) | r1=R(x); W(y,r1)",
                "FAKE", "FORBIDDEN", es,
                List.<Event[]>of(new Event[] { wx, r0 }),
                List.<Event[]>of(new Event[] { wy, r1 }));
    }

    /**
     * F2 variant reaching jfCoherence through co rather than through the intermediate
     * value: both arms end at x = 2, but the true arm inserts an extra write that changes
     * the coherence order the reader can observe.
     */
    private static Scenario f2CoAttack() {
        EventStructure es = new EventStructure();
        WriteEvent ix = w(0, "x", 0), iy = w(0, "y", 0);
        ReadEvent r0 = r(1, "y", "r0");
        WriteEvent wx1 = w(1, "x", 3);
        WriteEvent wx2 = w(1, "x", 2);
        ReadEvent r1 = r(2, "x", "r1");
        WriteEvent wy = w(2, "y", 1);
        add(es, ix, iy, r0, wx1, wx2, r1, wy);
        es.addProgramOrder(r0, wx1);
        es.addProgramOrder(wx1, wx2);
        es.addProgramOrder(r1, wy);
        co(es, "x", ix, wx1, wx2);
        co(es, "y", iy, wy);
        es.addReadsFrom(r0, wy);
        es.addReadsFrom(r1, wx1);                  // reads the branch-local intermediate 3
        return new Scenario("F2-co-visible-intermediate",
                "if(r0){W(x,3);W(x,2)} else W(x,2) | r1=R(x); W(y,1)",
                "FAKE", "FORBIDDEN", es,
                List.<Event[]>of(new Event[] { wx1, r0 }, new Event[] { wx2, r0 }),
                List.<Event[]>of(new Event[] { wy, r1 }));
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /**
     * WEAKEST verdict for the scenario, with control edges kept semantic
     * ({@code keepCtrl}) or dropped (the N4-fake treatment). Data edges are always kept.
     */
    private static String verdict(Scenario s, boolean keepCtrl) throws Exception {
        DependencyInfo deps = new DependencyInfo();
        for (Event[] e : s.ctrlEdges()) deps.addCtrlDep(e[0], e[1], keepCtrl);
        for (Event[] e : s.dataEdges()) deps.addDataDep(e[0], e[1], true);

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        try (SolverContext c = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            EventStructureEncoder enc = new EventStructureEncoder(c, s.es(), deps);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            BooleanFormula cons = ax.consistencyWEAKEST();
            try (ProverEnvironment p = c.newProverEnvironment()) {
                p.addConstraint(enc.getBmgr().and(wf, cons));
                return p.isUnsat() ? "FORBIDDEN" : "ALLOWED";
            }
        }
    }

    private static WriteEvent w(int t, String var, int v) {
        return new WriteEvent(t, var, RLX, v, Integer.toString(v));
    }

    private static ReadEvent r(int t, String var, String local) {
        return new ReadEvent(t, var, RLX, local);
    }

    private static void add(EventStructure es, Event... evs) {
        for (Event e : evs) es.addEvent(e);
    }

    private static void co(EventStructure es, String var, WriteEvent... ws) {
        for (WriteEvent x : ws) es.addCoherenceOrder(var, x);
    }

    private ControlN4AttackRun() { }
}
