package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.IntegerFormulaManager;
import org.sosy_lab.java_smt.api.Model;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment.OptStatus;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import org.sosy_lab.java_smt.api.SolverException;

import wev.smt.validate.InputValidator;
import wev.smt.validate.InvalidEventStructureException;
import wev.smt.validate.ValidationReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds minimum-cardinality consistent (and separating) executions of an
 * {@link EventStructure} under one or more memory models.
 *
 * <p>The encoding gates every well-formedness and consistency constraint by a fresh
 * boolean variable {@code active_e<id>} per event. The objective
 * {@code cardinality = sum_e (active_e ? 1 : 0)} is minimised by Z3's optimisation
 * backend ({@link OptimizationProverEnvironment}).
 *
 * <p>For separating witnesses ("allowed by M_allow, forbidden by M_forbid") the
 * extractor runs a CEGAR loop: minimise cardinality subject to
 * {@code consistency(M_allow)}, then check whether {@code consistency(M_forbid)}
 * also holds for the resulting active set; if it does, block that active set and
 * iterate; otherwise return the witness.
 */
public final class MinimalWitnessExtractor {

    private static final Logger LOG =
            Logger.getLogger(MinimalWitnessExtractor.class.getName());

    /**
     * Resource ceilings (Day 14, paper §6.4). A pathological or simply oversized input
     * could make Z3 run for minutes or exhaust the heap; rather than crash, the extractor
     * pre-flights the problem size and returns {@link Optional#empty()} above the ceiling,
     * logging the reason. Both are overridable at the JVM level so a reviewer can probe
     * the limit ({@code -Dwev.smt.maxEvents=…}, {@code -Dwev.smt.maxVars=…}); they are read
     * per call, so a test can tighten them temporarily. See {@code wev-smt/docs/limits.md}.
     */
    public static final String MAX_EVENTS_PROPERTY = "wev.smt.maxEvents";
    public static final String MAX_VARS_PROPERTY = "wev.smt.maxVars";
    public static final int DEFAULT_MAX_EVENTS = 256;
    public static final int DEFAULT_MAX_VARS = 100_000;

    private final SolverContext ctx;
    private final EventStructureEncoder enc;
    private final AxiomaticConsistency axioms;
    private final EventStructure es;
    private final BooleanFormulaManager bmgr;
    private final IntegerFormulaManager imgr;
    private final Map<Event, BooleanFormula> activeVars = new LinkedHashMap<>();

    public MinimalWitnessExtractor(SolverContext ctx, EventStructureEncoder enc,
                                   AxiomaticConsistency axioms) {
        this.ctx = ctx;
        this.enc = enc;
        this.axioms = axioms;
        this.es = enc.getEventStructure();
        this.bmgr = enc.getBmgr();
        this.imgr = enc.getImgr();
        // Day 14: validate the structure before encoding. A po cycle / read-without-write /
        // conflicting init would otherwise reach Z3 as a vacuous UNSAT, masquerading as a
        // real "forbidden" verdict; fail loud and diagnosable instead.
        ValidationReport report = InputValidator.validate(es, enc.getDependencyInfo());
        if (!report.valid()) {
            throw new InvalidEventStructureException(
                    "MinimalWitnessExtractor refuses to encode", report);
        }
        for (Event e : es.getEvents()) {
            activeVars.put(e, bmgr.makeVariable("active_e" + e.getId()));
        }
    }

    public Optional<MinimalWitness> findMinimalConsistent(MemoryModel m) {
        if (es.getEvents().isEmpty()) return Optional.empty();
        String breach = resourceBreach();
        if (breach != null) {
            LOG.info(() -> "findMinimalConsistent(" + m + ") skipped: " + breach
                    + " — returning empty (no crash)");
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        try (OptimizationProverEnvironment opt =
                     ctx.newOptimizationProverEnvironment(ProverOptions.GENERATE_MODELS)) {
            opt.addConstraint(gatedWellFormedness());
            opt.addConstraint(gatedConsistency(m));
            opt.addConstraint(atLeastOneActive());
            opt.minimize(cardinality());
            logMemory("before solve (findMinimalConsistent " + m + ")");
            OptStatus s = opt.check();
            logMemory("after solve (findMinimalConsistent " + m + ")");
            if (s != OptStatus.OPT) return Optional.empty();
            try (Model model = opt.getModel()) {
                return Optional.of(buildWitness(model, m, null,
                        System.currentTimeMillis() - start));
            }
        } catch (SolverException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    public Optional<MinimalWitness> findMinimalSeparating(MemoryModel allow,
                                                          MemoryModel forbid) {
        return findMinimalSeparating(allow, forbid, Long.MAX_VALUE);
    }

    /**
     * As {@link #findMinimalSeparating(MemoryModel, MemoryModel)} but abandons the
     * CEGAR refinement once {@code perCallBudgetMs} of wall-clock time have elapsed,
     * returning {@link Optional#empty()}. Useful for batch atlas runs where a single
     * no-separation pair could otherwise exhaust an exponential number of subsets.
     *
     * <p>A returned empty means "no separating witness found within the budget"; once
     * the search is genuinely exhausted that coincides with "no separation exists".
     * Passing {@link Long#MAX_VALUE} disables the cap and is exactly the unbounded
     * two-argument behaviour.
     */
    public Optional<MinimalWitness> findMinimalSeparating(MemoryModel allow,
                                                          MemoryModel forbid,
                                                          long perCallBudgetMs) {
        if (es.getEvents().isEmpty()) return Optional.empty();
        String breach = resourceBreach();
        if (breach != null) {
            LOG.info(() -> "findMinimalSeparating(" + allow + "\\" + forbid + ") skipped: "
                    + breach + " — returning empty (no crash)");
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        long deadline = (perCallBudgetMs == Long.MAX_VALUE)
                ? Long.MAX_VALUE : start + perCallBudgetMs;
        List<BooleanFormula> blocking = new ArrayList<>();
        while (true) {
            if (System.currentTimeMillis() > deadline) return Optional.empty();
            Set<Event> activeSet;
            MinimalWitness candidate;
            try (OptimizationProverEnvironment opt =
                         ctx.newOptimizationProverEnvironment(ProverOptions.GENERATE_MODELS)) {
                opt.addConstraint(gatedWellFormedness());
                opt.addConstraint(gatedConsistency(allow));
                opt.addConstraint(atLeastOneActive());
                for (BooleanFormula bc : blocking) opt.addConstraint(bc);
                opt.minimize(cardinality());
                OptStatus s = opt.check();
                if (s != OptStatus.OPT) return Optional.empty();
                try (Model model = opt.getModel()) {
                    activeSet = extractActiveSet(model);
                    candidate = buildWitness(model, allow, forbid,
                            System.currentTimeMillis() - start);
                }
            } catch (SolverException | InterruptedException ex) {
                throw new RuntimeException(ex);
            }

            if (!forbidHoldsOn(activeSet, forbid)) {
                return Optional.of(candidate);
            }
            blocking.add(blockActiveSet(activeSet));
        }
    }

    // ── Witness extraction ────────────────────────────────────────────────

    private MinimalWitness buildWitness(Model model, MemoryModel allow,
                                        MemoryModel forbid, long ms) {
        Set<Event> active = extractActiveSet(model);
        Map<EventPair, Boolean> rfMap = extractEdges(model, enc.getRfVars());
        Map<EventPair, Boolean> jfMap = extractEdges(model, enc.getJfVars());
        Map<EventPair, Boolean> coMap = extractEdges(model, enc.getCoVars());
        String summary = forbid == null
                ? String.format("%s witness, %d active events", allow, active.size())
                : String.format("%s\\%s witness, %d active events",
                        allow, forbid, active.size());
        return new MinimalWitness(active, rfMap, jfMap, coMap, active.size(),
                ms, summary);
    }

    private Set<Event> extractActiveSet(Model model) {
        Set<Event> out = new LinkedHashSet<>();
        for (Event e : es.getEvents()) {
            Boolean v = model.evaluate(activeVars.get(e));
            if (v != null && v) out.add(e);
        }
        return out;
    }

    private Map<EventPair, Boolean> extractEdges(
            Model model, Map<EventPair, BooleanFormula> vars) {
        Map<EventPair, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<EventPair, BooleanFormula> e : vars.entrySet()) {
            Boolean v = model.evaluate(e.getValue());
            out.put(e.getKey(), v != null && v);
        }
        return out;
    }

    // ── CEGAR helpers ─────────────────────────────────────────────────────

    private boolean forbidHoldsOn(Set<Event> activeSet, MemoryModel forbid) {
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            List<BooleanFormula> cs = new ArrayList<>();
            cs.add(gatedWellFormedness());
            cs.add(gatedConsistency(forbid));
            for (Event e : es.getEvents()) {
                BooleanFormula av = activeVars.get(e);
                cs.add(activeSet.contains(e) ? av : bmgr.not(av));
            }
            p.addConstraint(bmgr.and(cs));
            return !p.isUnsat();
        } catch (SolverException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private BooleanFormula blockActiveSet(Set<Event> activeSet) {
        List<BooleanFormula> conj = new ArrayList<>();
        for (Event e : es.getEvents()) {
            BooleanFormula av = activeVars.get(e);
            conj.add(activeSet.contains(e) ? av : bmgr.not(av));
        }
        return bmgr.not(bmgr.and(conj));
    }

    // ── Cardinality ───────────────────────────────────────────────────────

    private IntegerFormula cardinality() {
        IntegerFormula sum = imgr.makeNumber(0);
        IntegerFormula one = imgr.makeNumber(1);
        IntegerFormula zero = imgr.makeNumber(0);
        for (Event e : es.getEvents()) {
            IntegerFormula contrib = bmgr.ifThenElse(activeVars.get(e), one, zero);
            sum = imgr.add(sum, contrib);
        }
        return sum;
    }

    private BooleanFormula atLeastOneActive() {
        return bmgr.or(new ArrayList<>(activeVars.values()));
    }

    // ── Gated well-formedness ─────────────────────────────────────────────

    private BooleanFormula gatedWellFormedness() {
        List<BooleanFormula> cs = new ArrayList<>();
        List<Event> events = es.getEvents();
        int n = events.size();
        IntegerFormula zero = imgr.makeNumber(0);
        IntegerFormula bound = imgr.makeNumber(Math.max(n, 1));

        for (Event e : events) {
            IntegerFormula p = enc.getPosVar(e);
            cs.add(imgr.greaterOrEquals(p, zero));
            cs.add(imgr.lessThan(p, bound));
        }
        // Distinct positions among active events.
        for (int i = 0; i < events.size(); i++) {
            for (int j = i + 1; j < events.size(); j++) {
                Event a = events.get(i), b = events.get(j);
                cs.add(bmgr.implication(bothActive(a, b),
                        bmgr.not(imgr.equal(enc.getPosVar(a), enc.getPosVar(b)))));
            }
        }
        // po edges between active events, with cross-location W→W / W→R dropped to
        // mirror EventStructureEncoder.relaxedPoConstraints (Pass 2b-min). The two
        // well-formedness encoders must relax identically or the validation atlas and
        // the sub-execution search would disagree about what is well-formed.
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
                if (droppedCrossLocation(a, b)) continue;
                cs.add(bmgr.implication(bothActive(a, b),
                        imgr.lessThan(enc.getPosVar(a), enc.getPosVar(b))));
            }
        }
        // rf: per active read, one active rf predecessor with matching value.
        for (Event re : events) {
            if (!(re instanceof ReadEvent r)) continue;
            List<BooleanFormula> activeChoices = new ArrayList<>();
            List<BooleanFormula> rfChoices = new ArrayList<>();
            for (Event w : writesToVar(r.getVariable())) {
                BooleanFormula rfVar = enc.getRfVars().get(new EventPair(w, r));
                if (rfVar == null) continue;
                rfChoices.add(rfVar);
                activeChoices.add(bmgr.and(rfVar, activeVars.get(w)));
                cs.add(bmgr.implication(rfVar,
                        bmgr.makeBoolean(w.getValue() == r.getValue())));
                // Pass 3 Stage 2: rf-forward edge (rf ⇒ pos(w) < pos(r)) dropped to
                // match EventStructureEncoder.encodeWellFormedness (the two
                // well-formedness encoders must relax in lockstep). Per-location order
                // is re-imposed by coherencePerLocation; see docs/pass-3-plan.md §1.
            }
            if (activeChoices.isEmpty()) {
                cs.add(bmgr.not(activeVars.get(r)));
            } else {
                cs.add(bmgr.implication(activeVars.get(r), bmgr.or(activeChoices)));
                for (int i = 0; i < rfChoices.size(); i++) {
                    for (int j = i + 1; j < rfChoices.size(); j++) {
                        cs.add(bmgr.not(bmgr.and(rfChoices.get(i), rfChoices.get(j))));
                    }
                }
            }
        }
        // co biconditional, gated.
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getCoVars().entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            cs.add(bmgr.implication(bothActive(a, b),
                    bmgr.equivalence(e.getValue(),
                            imgr.lessThan(enc.getPosVar(a), enc.getPosVar(b)))));
        }
        // co consecutive (from coherenceOrder), gated.
        for (List<Integer> order : es.getCoherenceOrder().values()) {
            for (int i = 0; i + 1 < order.size(); i++) {
                Event a = es.getEventById(order.get(i));
                Event b = es.getEventById(order.get(i + 1));
                if (a == null || b == null) continue;
                cs.add(bmgr.implication(bothActive(a, b),
                        imgr.lessThan(enc.getPosVar(a), enc.getPosVar(b))));
            }
        }
        return cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
    }

    // ── Gated consistency ─────────────────────────────────────────────────

    /**
     * The model's consistency axioms, gated by this extractor's {@code active_e}
     * variables. Delegates to {@link AxiomaticConsistency} so the sub-execution
     * search and the full-execution validation atlas share one definition of every
     * model — including {@link AxiomaticConsistency#coherencePerLocation}. The gate
     * {@code activeVars::get} maps each event to its activation literal; an axiom edge
     * is only enforced when its endpoints are all active.
     */
    private BooleanFormula gatedConsistency(MemoryModel m) {
        Function<Event, BooleanFormula> active = activeVars::get;
        return switch (m) {
            case SC -> axioms.consistencySC(active);
            case TSO -> axioms.consistencyTSO(active);
            case PSO -> axioms.consistencyPSO(active);
            case RA -> axioms.consistencyRA(active);
            case WEAKEST -> axioms.consistencyWEAKEST(active);
        };
    }

    // ── Resource limits (Day 14, paper §6.4) ──────────────────────────────

    /**
     * The reason this problem exceeds a configured resource ceiling, or {@code null} if it
     * is within limits. Read per call so {@code -Dwev.smt.maxEvents} / {@code -Dwev.smt.maxVars}
     * (and a test setting them) take effect dynamically. Checked once up front in each
     * search entry point; a breach yields {@link Optional#empty()}, never a crash.
     */
    private String resourceBreach() {
        int n = es.getEvents().size();
        int maxEvents = Integer.getInteger(MAX_EVENTS_PROPERTY, DEFAULT_MAX_EVENTS);
        if (n > maxEvents) {
            return "event count " + n + " exceeds " + MAX_EVENTS_PROPERTY + "=" + maxEvents;
        }
        long vars = estimatedVars();
        long maxVars = Integer.getInteger(MAX_VARS_PROPERTY, DEFAULT_MAX_VARS);
        if (vars > maxVars) {
            return "estimated SMT variable count " + vars + " exceeds "
                    + MAX_VARS_PROPERTY + "=" + maxVars;
        }
        return null;
    }

    /**
     * A cheap static estimate of the decision-variable footprint: two integer vars per
     * event (position + ew-group), one boolean per {@code rf}/{@code jf}/{@code co} edge,
     * and one per syntactic dependency edge. The per-model layer/reachability vars minted
     * inside each consistency call are not counted (they vary by model), so this is a
     * conservative lower bound — a screening heuristic, not an exact count.
     */
    long estimatedVars() {
        long n = es.getEvents().size();
        return 2 * n
                + enc.getRfVars().size() + enc.getJfVars().size() + enc.getCoVars().size()
                + enc.getDataDepVars().size() + enc.getAddrDepVars().size()
                + enc.getCtrlDepVars().size();
    }

    /** Log a heap snapshot around a solve (FINE; off by default, on for resource debugging). */
    private void logMemory(String when) {
        if (!LOG.isLoggable(Level.FINE)) return;
        Runtime rt = Runtime.getRuntime();
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        LOG.fine("[mem] " + when + ": heap total=" + totalMb + "MB used=" + usedMb + "MB");
    }

    // ── Static utilities ──────────────────────────────────────────────────

    private BooleanFormula bothActive(Event a, Event b) {
        return bmgr.and(activeVars.get(a), activeVars.get(b));
    }

    /** Whether {@code a→b} is a cross-location store→store or store→load po edge. */
    private boolean droppedCrossLocation(Event a, Event b) {
        boolean aWrite = a instanceof WriteEvent || a.getType() == EventType.INIT;
        boolean bAccess = b instanceof WriteEvent || b instanceof ReadEvent
                || b.getType() == EventType.INIT;
        if (!(aWrite && bAccess)) return false;
        return a.getVariable() != null && !a.getVariable().equals(b.getVariable());
    }

    private List<Event> writesToVar(String var) {
        List<Event> out = new ArrayList<>();
        for (Event e : es.getEvents()) {
            if ((e instanceof WriteEvent || e.getType() == EventType.INIT)
                    && var.equals(e.getVariable())) {
                out.add(e);
            }
        }
        return out;
    }
}
