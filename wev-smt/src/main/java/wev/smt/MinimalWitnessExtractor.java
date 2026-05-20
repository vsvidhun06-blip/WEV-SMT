package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import com.weakest.model.MemoryOrder;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final SolverContext ctx;
    private final EventStructureEncoder enc;
    private final AxiomaticConsistency axioms;
    private final EventStructure es;
    private final BooleanFormulaManager bmgr;
    private final IntegerFormulaManager imgr;
    private final Map<Event, BooleanFormula> activeVars = new LinkedHashMap<>();
    private final AtomicInteger layerTag = new AtomicInteger();

    public MinimalWitnessExtractor(SolverContext ctx, EventStructureEncoder enc,
                                   AxiomaticConsistency axioms) {
        this.ctx = ctx;
        this.enc = enc;
        this.axioms = axioms;
        this.es = enc.getEventStructure();
        this.bmgr = enc.getBmgr();
        this.imgr = enc.getImgr();
        for (Event e : es.getEvents()) {
            activeVars.put(e, bmgr.makeVariable("active_e" + e.getId()));
        }
    }

    public Optional<MinimalWitness> findMinimalConsistent(MemoryModel m) {
        if (es.getEvents().isEmpty()) return Optional.empty();
        long start = System.currentTimeMillis();
        try (OptimizationProverEnvironment opt =
                     ctx.newOptimizationProverEnvironment(ProverOptions.GENERATE_MODELS)) {
            opt.addConstraint(gatedWellFormedness());
            opt.addConstraint(gatedConsistency(m));
            opt.addConstraint(atLeastOneActive());
            opt.minimize(cardinality());
            OptStatus s = opt.check();
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
        // po edges between active events.
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
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
                cs.add(bmgr.implication(
                        bmgr.and(rfVar, bothActive(w, r)),
                        imgr.lessThan(enc.getPosVar(w), enc.getPosVar(r))));
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

    private BooleanFormula gatedConsistency(MemoryModel m) {
        Map<Event, IntegerFormula> layer = freshLayer(m);
        List<BooleanFormula> cs = new ArrayList<>();
        switch (m) {
            case SC -> {
                addGatedPo(cs, layer, false, false);
                addGatedRf(cs, layer, false, false);
                addGatedCo(cs, layer);
                addGatedFr(cs, layer);
            }
            case TSO -> {
                addGatedPo(cs, layer, true, false);
                addGatedRf(cs, layer, true, false);
                addGatedCo(cs, layer);
                addGatedFr(cs, layer);
            }
            case PSO -> {
                addGatedPo(cs, layer, true, true);
                addGatedRf(cs, layer, true, false);
                addGatedCo(cs, layer);
                addGatedFr(cs, layer);
            }
            case RA -> {
                addGatedPo(cs, layer, false, false);
                addGatedRf(cs, layer, false, true);
                addGatedCo(cs, layer);
                addGatedFr(cs, layer);
            }
            case WEAKEST -> {
                addGatedPo(cs, layer, false, false);
                addGatedRf(cs, layer, false, false);
                addGatedWeakestCoherence(cs, layer);
            }
        }
        return cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
    }

    private void addGatedPo(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                            boolean skipWR, boolean skipWW) {
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
                if (skipWR && isWriteOrInit(a) && b instanceof ReadEvent) continue;
                if (skipWW && isWriteOrInit(a) && isWriteOrInit(b)) continue;
                cs.add(bmgr.implication(bothActive(a, b),
                        imgr.lessThan(layer.get(a), layer.get(b))));
            }
        }
    }

    private void addGatedRf(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                            boolean externalOnly, boolean releaseAcquireOnly) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            if (externalOnly && w.getThreadId() == r.getThreadId()) continue;
            if (releaseAcquireOnly && !(isReleaseLike(w) && isAcquireLike(r))) continue;
            cs.add(bmgr.implication(
                    bmgr.and(e.getValue(), bothActive(w, r)),
                    imgr.lessThan(layer.get(w), layer.get(r))));
        }
    }

    private void addGatedCo(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getCoVars().entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            cs.add(bmgr.implication(
                    bmgr.and(e.getValue(), bothActive(a, b)),
                    imgr.lessThan(layer.get(a), layer.get(b))));
        }
    }

    private void addGatedFr(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer) {
        for (Map.Entry<EventPair, BooleanFormula> rf : enc.getRfVars().entrySet()) {
            Event w = rf.getKey().from();
            Event r = rf.getKey().to();
            for (Map.Entry<EventPair, BooleanFormula> co : enc.getCoVars().entrySet()) {
                if (co.getKey().from().getId() != w.getId()) continue;
                Event wp = co.getKey().to();
                BooleanFormula gate = bmgr.and(List.of(
                        rf.getValue(),
                        co.getValue(),
                        activeVars.get(w),
                        activeVars.get(r),
                        activeVars.get(wp)));
                cs.add(bmgr.implication(gate,
                        imgr.lessThan(layer.get(r), layer.get(wp))));
            }
        }
    }

    private void addGatedWeakestCoherence(List<BooleanFormula> cs,
                                          Map<Event, IntegerFormula> layer) {
        for (Event re : es.getEvents()) {
            if (!(re instanceof ReadEvent r)) continue;
            if (r.getMemoryOrder() == MemoryOrder.RELAXED) continue;
            for (Event w : writesToVar(r.getVariable())) {
                BooleanFormula rfVar = enc.getRfVars().get(new EventPair(w, r));
                if (rfVar == null) continue;
                for (Event wp : writesToVar(r.getVariable())) {
                    if (wp.getId() == w.getId()) continue;
                    if (!poReaches(wp.getId(), r.getId())) continue;
                    BooleanFormula gate = bmgr.and(List.of(
                            rfVar,
                            activeVars.get(w),
                            activeVars.get(r),
                            activeVars.get(wp)));
                    cs.add(bmgr.implication(gate,
                            imgr.greaterOrEquals(layer.get(w), layer.get(wp))));
                }
            }
        }
    }

    // ── Static utilities ──────────────────────────────────────────────────

    private BooleanFormula bothActive(Event a, Event b) {
        return bmgr.and(activeVars.get(a), activeVars.get(b));
    }

    private Map<Event, IntegerFormula> freshLayer(MemoryModel m) {
        int id = layerTag.incrementAndGet();
        Map<Event, IntegerFormula> out = new LinkedHashMap<>();
        for (Event e : es.getEvents()) {
            out.put(e, imgr.makeVariable(
                    "mwlayer_" + m.name().toLowerCase() + id + "_e" + e.getId()));
        }
        return out;
    }

    private boolean isWriteOrInit(Event e) {
        return e instanceof WriteEvent || e.getType() == EventType.INIT;
    }

    private boolean isReleaseLike(Event e) {
        MemoryOrder mo = e.getMemoryOrder();
        return mo == MemoryOrder.RELEASE || mo == MemoryOrder.SC;
    }

    private boolean isAcquireLike(Event e) {
        MemoryOrder mo = e.getMemoryOrder();
        return mo == MemoryOrder.ACQUIRE || mo == MemoryOrder.SC;
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

    private boolean poReaches(int fromId, int toId) {
        Map<Integer, List<Integer>> po = es.getProgramOrder();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(fromId);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (!visited.add(cur)) continue;
            for (Integer nxt : po.getOrDefault(cur, List.of())) {
                if (nxt == toId) return true;
                queue.add(nxt);
            }
        }
        return false;
    }
}
