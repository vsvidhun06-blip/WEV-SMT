package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.FormulaManager;
import org.sosy_lab.java_smt.api.IntegerFormulaManager;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.SolverContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encodes a WEV {@link EventStructure} as SMT.
 *
 * <p>Each event {@code e} is mapped to an integer position variable {@code pos_e<id>}
 * (its slot in a strict total execution order) and an integer ew-group variable
 * {@code ewg_e<id>} (its event-wise equivalence class). Relation decision variables:
 * {@code rf_w<wid>_r<rid>}, {@code jf_w<wid>_r<rid>}, {@code co_w<aid>_w<bid>}.
 *
 * <p>Decision variables are exposed via {@link #getRfVars()}, {@link #getJfVars()},
 * {@link #getCoVars()} so downstream consistency layers can constrain specific edges.
 *
 * <p>Pass 3 (Stage 1) additionally mints one boolean var per syntactic dependency
 * edge supplied in a {@link DependencyInfo} sidecar — {@code dep_data_e<c>_e<p>},
 * {@code dep_addr_…}, {@code dep_ctrl_…} for each consumer→producer pair — exposed
 * via {@link #getDataDepVars()}, {@link #getAddrDepVars()}, {@link #getCtrlDepVars()}.
 * These vars are <strong>inert</strong> in Stage 1: nothing in well-formedness or any
 * consistency layer references them, so the SAT/UNSAT verdict is identical to before
 * dependencies existed. {@link #dependencyDefinitions()} packages "each dep var is
 * TRUE" for the Stage-2 jf-coherence axiom to pull in; it is intentionally not wired
 * in here.
 */
public final class EventStructureEncoder {

    private final EventStructure es;
    private final DependencyInfo deps;
    private final SolverContext ctx;
    private final BooleanFormulaManager bmgr;
    private final IntegerFormulaManager imgr;

    private final Map<Event, IntegerFormula> eventVars = new LinkedHashMap<>();
    private final Map<Event, IntegerFormula> ewGroupVars = new LinkedHashMap<>();
    private final Map<EventPair, BooleanFormula> rfVars = new LinkedHashMap<>();
    private final Map<EventPair, BooleanFormula> jfVars = new LinkedHashMap<>();
    private final Map<EventPair, BooleanFormula> coVars = new LinkedHashMap<>();
    private final Map<EventPair, BooleanFormula> dataDepVars = new LinkedHashMap<>();
    private final Map<EventPair, BooleanFormula> addrDepVars = new LinkedHashMap<>();
    private final Map<EventPair, BooleanFormula> ctrlDepVars = new LinkedHashMap<>();

    /** Encode a dependency-free event structure (the common case). */
    public EventStructureEncoder(SolverContext ctx, EventStructure es) {
        this(ctx, es, DependencyInfo.empty());
    }

    public EventStructureEncoder(SolverContext ctx, EventStructure es, DependencyInfo deps) {
        this.es = es;
        this.deps = deps;
        this.ctx = ctx;
        FormulaManager fmgr = ctx.getFormulaManager();
        this.bmgr = fmgr.getBooleanFormulaManager();
        this.imgr = fmgr.getIntegerFormulaManager();

        for (Event e : es.getEvents()) {
            eventVars.put(e, imgr.makeVariable("pos_e" + e.getId()));
            ewGroupVars.put(e, imgr.makeVariable("ewg_e" + e.getId()));
        }

        for (Event r : es.getEvents()) {
            if (!(r instanceof ReadEvent)) continue;
            for (Event w : writesSharingLocation(r.getVariable())) {
                EventPair k = new EventPair(w, r);
                rfVars.put(k, bmgr.makeVariable("rf_w" + w.getId() + "_r" + r.getId()));
                jfVars.put(k, bmgr.makeVariable("jf_w" + w.getId() + "_r" + r.getId()));
            }
        }

        // Pass 3 (Stage 1): one inert decision var per syntactic dependency edge.
        // Distinct prefixes keep the three kinds from aliasing onto the same SMT var
        // when a (consumer, producer) pair carries more than one kind of dependency.
        buildDepVars(deps.dataDepMap(), dataDepVars, "dep_data_");
        buildDepVars(deps.addrDepMap(), addrDepVars, "dep_addr_");
        buildDepVars(deps.ctrlDepMap(), ctrlDepVars, "dep_ctrl_");

        for (String var : collectLocations()) {
            List<Event> writes = writesSharingLocation(var);
            for (int i = 0; i < writes.size(); i++) {
                for (int j = 0; j < writes.size(); j++) {
                    if (i == j) continue;
                    Event a = writes.get(i);
                    Event b = writes.get(j);
                    coVars.put(new EventPair(a, b),
                            bmgr.makeVariable("co_w" + a.getId() + "_w" + b.getId()));
                }
            }
        }
    }

    public EventStructure getEventStructure() { return es; }
    /** The syntactic dependency sidecar; {@code DependencyInfo.empty()} if none. */
    public DependencyInfo getDependencyInfo() { return deps; }
    public SolverContext getContext() { return ctx; }
    public BooleanFormulaManager getBmgr() { return bmgr; }
    public IntegerFormulaManager getImgr() { return imgr; }

    public Map<Event, IntegerFormula> getEventVars() {
        return Collections.unmodifiableMap(eventVars);
    }

    public Map<Event, IntegerFormula> getEwGroupVars() {
        return Collections.unmodifiableMap(ewGroupVars);
    }

    public Map<EventPair, BooleanFormula> getRfVars() {
        return Collections.unmodifiableMap(rfVars);
    }

    public Map<EventPair, BooleanFormula> getJfVars() {
        return Collections.unmodifiableMap(jfVars);
    }

    public Map<EventPair, BooleanFormula> getCoVars() {
        return Collections.unmodifiableMap(coVars);
    }

    /** Decision vars (consumer→producer) for syntactic data dependencies. */
    public Map<EventPair, BooleanFormula> getDataDepVars() {
        return Collections.unmodifiableMap(dataDepVars);
    }

    /** Decision vars (consumer→producer) for syntactic address dependencies. */
    public Map<EventPair, BooleanFormula> getAddrDepVars() {
        return Collections.unmodifiableMap(addrDepVars);
    }

    /** Decision vars (consumer→producer) for syntactic control dependencies. */
    public Map<EventPair, BooleanFormula> getCtrlDepVars() {
        return Collections.unmodifiableMap(ctrlDepVars);
    }

    /**
     * "Every dependency var is TRUE" — the definitional constraint that ties each
     * {@code dep_*} var to the fact that its edge exists in the {@link DependencyInfo}.
     * Stage 1 does <strong>not</strong> conjoin this into well-formedness or any
     * consistency layer, so the dep vars stay inert and the verdict is unchanged; the
     * Stage-2 jf-coherence axiom is the intended (and only) caller.
     */
    public BooleanFormula dependencyDefinitions() {
        List<BooleanFormula> cs = new ArrayList<>();
        cs.addAll(dataDepVars.values());
        cs.addAll(addrDepVars.values());
        cs.addAll(ctrlDepVars.values());
        return cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
    }

    public IntegerFormula getPosVar(Event e) { return eventVars.get(e); }
    public IntegerFormula getEwGroupVar(Event e) { return ewGroupVars.get(e); }

    public BooleanFormula encodeWellFormedness() {
        List<BooleanFormula> cs = new ArrayList<>();

        int n = es.getEvents().size();
        IntegerFormula zero = imgr.makeNumber(0);
        IntegerFormula bound = imgr.makeNumber(n);
        List<IntegerFormula> positions = new ArrayList<>(eventVars.values());
        for (IntegerFormula p : positions) {
            cs.add(imgr.greaterOrEquals(p, zero));
            cs.add(imgr.lessThan(p, bound));
        }
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                cs.add(bmgr.not(imgr.equal(positions.get(i), positions.get(j))));
            }
        }

        cs.addAll(relaxedPoConstraints());

        for (Event e : es.getEvents()) {
            if (!(e instanceof ReadEvent r)) continue;
            List<BooleanFormula> choices = new ArrayList<>();
            for (Event w : writesSharingLocation(r.getVariable())) {
                BooleanFormula rf = rfVars.get(new EventPair(w, r));
                if (rf == null) continue;
                choices.add(rf);
                // Pass 3 Stage 2: the global rf-forward edge (rf ⇒ pos(w) < pos(r))
                // is intentionally NOT emitted here. It made well-formedness an
                // SC-strength backstop (acyclic(po∪rf∪co)) on every model, locking the
                // load-buffering shape that RA/WEAKEST permit (docs/pass-3-plan.md §1).
                // Per-location read-after-write is re-imposed for every model by
                // AxiomaticConsistency.coherencePerLocation (addRfLoc); SC/TSO/PSO
                // re-impose the global rf ordering in their own acyclic layers (addRf).
                cs.add(bmgr.implication(rf,
                        bmgr.makeBoolean(w.getValue() == r.getValue())));
            }
            if (choices.isEmpty()) {
                cs.add(bmgr.makeBoolean(false));
            } else {
                cs.add(bmgr.or(choices));
                for (int i = 0; i < choices.size(); i++) {
                    for (int j = i + 1; j < choices.size(); j++) {
                        cs.add(bmgr.not(bmgr.and(choices.get(i), choices.get(j))));
                    }
                }
            }
        }

        // co decision vars bound to positions: co(a,b) <=> pos(a) < pos(b).
        for (Map.Entry<EventPair, BooleanFormula> e : coVars.entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            cs.add(bmgr.equivalence(e.getValue(),
                    imgr.lessThan(eventVars.get(a), eventVars.get(b))));
        }

        cs.addAll(coConstraints());

        return bmgr.and(cs);
    }

    public BooleanFormula encodeRelation(String relName) {
        return switch (relName) {
            case "po" -> {
                List<BooleanFormula> cs = poConstraints();
                yield cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
            }
            case "rf" -> rfVars.isEmpty()
                    ? bmgr.makeTrue()
                    : bmgr.or(new ArrayList<>(rfVars.values()));
            case "co" -> {
                List<BooleanFormula> cs = coConstraints();
                yield cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
            }
            case "jf" -> {
                List<BooleanFormula> cs = jfImplications();
                if (jfVars.isEmpty()) yield bmgr.makeTrue();
                cs.add(bmgr.or(new ArrayList<>(jfVars.values())));
                yield bmgr.and(cs);
            }
            case "ew" -> {
                List<BooleanFormula> cs = ewConstraints();
                yield cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown relation: " + relName);
        };
    }

    private List<BooleanFormula> poConstraints() {
        List<BooleanFormula> cs = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            IntegerFormula posA = eventVars.get(a);
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
                cs.add(imgr.lessThan(posA, eventVars.get(b)));
            }
        }
        return cs;
    }

    /**
     * Program-order constraints for the global execution order (Pass 2b-min), with
     * <em>cross-location</em> store→store and store→load pairs dropped. Those pairs are
     * not in preserved program order under PSO/RA/WEAKEST (Alglave et al., Herding Cats,
     * TOPLAS 2014, §4.4: PSO relaxes W→W; TSO relaxes W→R), so forcing them into the
     * single global {@code pos} order made well-formedness an SC-strength backstop that
     * over-forbade S and 2+2W. Kept: all R→W / R→R pairs, and <em>same-location</em>
     * W→W / W→R pairs. Same-location ordering is independently re-imposed by
     * {@link AxiomaticConsistency#coherencePerLocation} (SC-per-location), and SC/TSO
     * re-impose the cross-location W→W they need via their own consistency layers, so
     * dropping these edges here does not weaken any model below its textbook strength.
     * Unlike {@link #poConstraints()}, this is used only by well-formedness, never by
     * relation extraction.
     */
    private List<BooleanFormula> relaxedPoConstraints() {
        List<BooleanFormula> cs = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            IntegerFormula posA = eventVars.get(a);
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
                if (droppedCrossLocation(a, b)) continue;
                cs.add(imgr.lessThan(posA, eventVars.get(b)));
            }
        }
        return cs;
    }

    /** Whether {@code a→b} is a cross-location store→store or store→load po edge. */
    private boolean droppedCrossLocation(Event a, Event b) {
        boolean aWrite = a instanceof WriteEvent || a.getType() == EventType.INIT;
        boolean bAccess = b instanceof WriteEvent || b instanceof ReadEvent
                || b.getType() == EventType.INIT;
        if (!(aWrite && bAccess)) return false;
        return a.getVariable() != null && !a.getVariable().equals(b.getVariable());
    }

    private List<BooleanFormula> coConstraints() {
        List<BooleanFormula> cs = new ArrayList<>();
        for (List<Integer> order : es.getCoherenceOrder().values()) {
            for (int i = 0; i + 1 < order.size(); i++) {
                Event a = es.getEventById(order.get(i));
                Event b = es.getEventById(order.get(i + 1));
                if (a == null || b == null) continue;
                cs.add(imgr.lessThan(eventVars.get(a), eventVars.get(b)));
            }
        }
        return cs;
    }

    private List<BooleanFormula> jfImplications() {
        List<BooleanFormula> cs = new ArrayList<>();
        for (Event e : es.getEvents()) {
            if (!(e instanceof ReadEvent r)) continue;
            for (Event w : writesSharingLocation(r.getVariable())) {
                BooleanFormula jf = jfVars.get(new EventPair(w, r));
                if (jf == null) continue;
                cs.add(bmgr.implication(jf,
                        bmgr.makeBoolean(w.getValue() == r.getValue())));
            }
        }
        return cs;
    }

    private List<BooleanFormula> ewConstraints() {
        List<BooleanFormula> cs = new ArrayList<>();
        for (Set<Event> cls : es.getEventWise()) {
            if (cls.size() < 2) continue;
            Event pivot = cls.iterator().next();
            IntegerFormula pivotGroup = ewGroupVars.get(pivot);
            if (pivotGroup == null) continue;
            for (Event e : cls) {
                if (e == pivot) continue;
                IntegerFormula gE = ewGroupVars.get(e);
                if (gE == null) continue;
                cs.add(imgr.equal(pivotGroup, gE));
            }
        }
        return cs;
    }

    /**
     * Mint one boolean var per (consumer, producer) edge of a dependency relation,
     * keyed by {@code EventPair(consumer, producer)} and named {@code prefix + "e" +
     * consumerId + "_e" + producerId}. The var is created but not constrained here;
     * see {@link #dependencyDefinitions()}.
     */
    private void buildDepVars(Map<Event, Set<Event>> rel,
                              Map<EventPair, BooleanFormula> out, String prefix) {
        for (Map.Entry<Event, Set<Event>> e : rel.entrySet()) {
            Event consumer = e.getKey();
            for (Event producer : e.getValue()) {
                out.put(new EventPair(consumer, producer), bmgr.makeVariable(
                        prefix + "e" + consumer.getId() + "_e" + producer.getId()));
            }
        }
    }

    private List<Event> writesSharingLocation(String var) {
        List<Event> ws = new ArrayList<>();
        for (Event e : es.getEvents()) {
            if ((e instanceof WriteEvent || e.getType() == EventType.INIT)
                    && var.equals(e.getVariable())) {
                ws.add(e);
            }
        }
        return ws;
    }

    private Set<String> collectLocations() {
        Set<String> locs = new java.util.LinkedHashSet<>();
        for (Event e : es.getEvents()) {
            if (e instanceof WriteEvent || e.getType() == EventType.INIT
                    || e instanceof ReadEvent) {
                if (e.getVariable() != null) locs.add(e.getVariable());
            }
        }
        return locs;
    }
}
