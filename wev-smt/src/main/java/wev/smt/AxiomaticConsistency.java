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
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes weak-memory-model consistency axioms over a {@link EventStructureEncoder}.
 *
 * <p>Acyclicity of any candidate relation is encoded via the layer-variable trick from
 * Gavrilenko, Furbach, Hahn &amp; Ponce-de-Leon, "BMC for Weak Memory Models: Relation
 * Analysis for Compact SMT Encodings" (CAV 2019, Dat3M). For each model we create a
 * fresh integer {@code layer} variable per event and emit, for every candidate edge
 * {@code (a,b)} in the relation under check, an implication
 * {@code active(a,b) => layer(a) < layer(b)}. This is sufficient for acyclicity and is
 * quantifier-free, so Z3 dispatches it efficiently.
 *
 * <p>Per-model relation sets:
 * <ul>
 *   <li>SC: (po ∪ rf ∪ co ∪ fr) acyclic.</li>
 *   <li>TSO: (ppo ∪ rfe ∪ co ∪ fr), ppo = po \ (W→R same-thread), rfe = rf cross-thread.</li>
 *   <li>PSO: ppo = po \ (W→R ∪ W→W same-thread).</li>
 *   <li>RA: per-location SC plus release/acquire sw edges from rel-acq rf pairs.</li>
 *   <li>WEAKEST: (po ∪ rf) acyclic plus the jf-coherence axiom from
 *       {@code com.weakest.checker.ConsistencyChecker.isJustifiableGiven} — for any
 *       non-relaxed read {@code r} reading from {@code w}, no other write {@code w'}
 *       to the same location may be po-before {@code r} and reachable from {@code w}.</li>
 * </ul>
 */
public final class AxiomaticConsistency {

    private final EventStructureEncoder enc;
    private final EventStructure es;
    private final BooleanFormulaManager bmgr;
    private final IntegerFormulaManager imgr;

    private final AtomicInteger layerTag = new AtomicInteger();

    public AxiomaticConsistency(EventStructureEncoder encoder) {
        this.enc = encoder;
        this.es = encoder.getEventStructure();
        this.bmgr = encoder.getBmgr();
        this.imgr = encoder.getImgr();
    }

    public BooleanFormula consistencySC() {
        Map<Event, IntegerFormula> layer = freshLayer("sc");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, false, false);
        addRf(cs, layer, false, false);
        addCo(cs, layer);
        addFr(cs, layer);
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyTSO() {
        Map<Event, IntegerFormula> layer = freshLayer("tso");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, true, false);
        addRf(cs, layer, true, false);
        addCo(cs, layer);
        addFr(cs, layer);
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyPSO() {
        Map<Event, IntegerFormula> layer = freshLayer("pso");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, true, true);
        addRf(cs, layer, true, false);
        addCo(cs, layer);
        addFr(cs, layer);
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyRA() {
        Map<Event, IntegerFormula> layer = freshLayer("ra");
        List<BooleanFormula> cs = new ArrayList<>();
        // hb = po ∪ sw, where sw is the release-acquire restriction of rf.
        addPo(cs, layer, false, false);
        addRf(cs, layer, false, true);
        // Coherence: per-location SC.
        addCo(cs, layer);
        addFr(cs, layer);
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyWEAKEST() {
        Map<Event, IntegerFormula> layer = freshLayer("weakest");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, false, false);
        addRf(cs, layer, false, false);

        // Mirror ConsistencyChecker.isJustifiableGiven: for any non-relaxed read r
        // reading from w, no other write w' to the same variable may be po-reachable
        // to r AND reach-after w in (po ∪ rf). Layer monotonicity already enforces
        // the latter, so the SMT condition reduces to: rfVar(w,r) ⇒ layer(w) ≥ layer(w').
        for (Event re : es.getEvents()) {
            if (!(re instanceof ReadEvent r)) continue;
            if (r.getMemoryOrder() == MemoryOrder.RELAXED) continue;
            for (Event w : writesToVar(r.getVariable())) {
                BooleanFormula rfVar = enc.getRfVars().get(new EventPair(w, r));
                if (rfVar == null) continue;
                for (Event wp : writesToVar(r.getVariable())) {
                    if (wp.getId() == w.getId()) continue;
                    if (!poReaches(wp.getId(), r.getId())) continue;
                    cs.add(bmgr.implication(rfVar,
                            imgr.greaterOrEquals(layer.get(w), layer.get(wp))));
                }
            }
        }
        return bmgr.and(cs);
    }

    // ── Relation-to-layer helpers ─────────────────────────────────────────

    private void addPo(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                       boolean skipWriteRead, boolean skipWriteWrite) {
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
                if (skipWriteRead && isWriteOrInit(a) && b instanceof ReadEvent) continue;
                if (skipWriteWrite && isWriteOrInit(a) && isWriteOrInit(b)) continue;
                cs.add(imgr.lessThan(layer.get(a), layer.get(b)));
            }
        }
    }

    private void addRf(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                       boolean externalOnly, boolean releaseAcquireOnly) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            if (externalOnly && w.getThreadId() == r.getThreadId()) continue;
            if (releaseAcquireOnly && !(isReleaseLike(w) && isAcquireLike(r))) continue;
            cs.add(bmgr.implication(e.getValue(),
                    imgr.lessThan(layer.get(w), layer.get(r))));
        }
    }

    private void addCo(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getCoVars().entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            cs.add(bmgr.implication(e.getValue(),
                    imgr.lessThan(layer.get(a), layer.get(b))));
        }
    }

    /** fr = rf⁻¹ ; co. For every candidate rf(w,r) and every co(w,w'), emit fr(r,w'). */
    private void addFr(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer) {
        for (Map.Entry<EventPair, BooleanFormula> rf : enc.getRfVars().entrySet()) {
            Event w = rf.getKey().from();
            Event r = rf.getKey().to();
            for (Map.Entry<EventPair, BooleanFormula> co : enc.getCoVars().entrySet()) {
                if (co.getKey().from().getId() != w.getId()) continue;
                Event wp = co.getKey().to();
                cs.add(bmgr.implication(
                        bmgr.and(rf.getValue(), co.getValue()),
                        imgr.lessThan(layer.get(r), layer.get(wp))));
            }
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────

    private Map<Event, IntegerFormula> freshLayer(String tag) {
        int id = layerTag.incrementAndGet();
        Map<Event, IntegerFormula> m = new LinkedHashMap<>();
        for (Event e : es.getEvents()) {
            m.put(e, imgr.makeVariable("layer_" + tag + id + "_e" + e.getId()));
        }
        return m;
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

    /** BFS over static program order: does {@code fromId} po-reach {@code toId}? */
    private boolean poReaches(int fromId, int toId) {
        Map<Integer, List<Integer>> po = es.getProgramOrder();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(fromId);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (!visited.add(cur)) continue;
            if (cur == toId && cur != fromId) return true;
            for (Integer nxt : po.getOrDefault(cur, List.of())) {
                if (nxt == toId) return true;
                queue.add(nxt);
            }
        }
        return false;
    }
}
