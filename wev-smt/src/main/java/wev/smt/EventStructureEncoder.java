package wev.smt;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import com.weakest.model.FenceEvent;
import com.weakest.model.ReadEvent;
import com.weakest.model.RMWEvent;
import com.weakest.model.WriteEvent;
import org.sosy_lab.java_smt.api.BitvectorFormula;
import org.sosy_lab.java_smt.api.BitvectorFormulaManager;
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

    // ── C1 symbolic rf + value propagation (opt-in via enableSymbolic; inert otherwise) ──
    private static final int VW = 32;                    // bitvector width, matches QfbvConstancyOracle
    private boolean symbolic = false;
    private BitvectorFormulaManager bv;                  // lazily set when symbolic enabled
    private Map<Integer, String> writeValueExprs = Map.of();   // writeId -> symbolic value string
    private Map<Integer, Integer> existsReadValues = Map.of(); // exists-pinned readId -> value
    private Map<String, Integer> existsMemFinal = Map.of();    // memory-final loc -> value
    private final Map<Event, BitvectorFormula> valReadVars = new LinkedHashMap<>();
    private final Map<Event, BitvectorFormula> valWriteVars = new LinkedHashMap<>();

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
            if (!isReader(r)) continue;
            for (Event w : writesSharingLocation(r.getVariable())) {
                if (w.getId() == r.getId()) continue;   // an RMW cannot read from itself
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

    /**
     * C1: switch this encoder to <b>symbolic rf + value-propagation</b> mode. Instead of
     * pinning each read to a write whose <em>folded constant</em> value matches, it mints a
     * fresh BV32 {@code val_read} per reader and {@code val_write} per write, defines each
     * {@code val_write} as the real function of the reads it depends on (from
     * {@code writeValueExprs}, over the shared {@link QfbvConstancyOracle} grammar), couples
     * {@code rf(w,r) ⇒ val_read[r] = val_write[w]}, pins only the reads the {@code exists}
     * clause names ({@code existsReadValues}), and lets {@code co} range freely. The verdict
     * becomes program-level: UNSAT ⇒ FORBIDDEN over all rf/co/value fixpoints. Must be called
     * before {@link #encodeWellFormedness()}; {@code AxiomaticConsistency} is unchanged (it
     * consumes the same {@code rf}/{@code co} decision vars either way).
     */
    public void enableSymbolic(Map<Integer, String> writeValueExprs,
                               Map<Integer, Integer> existsReadValues,
                               Map<String, Integer> existsMemFinal) {
        this.symbolic = true;
        this.writeValueExprs = writeValueExprs;
        this.existsReadValues = existsReadValues;
        this.existsMemFinal = existsMemFinal;
        this.bv = ctx.getFormulaManager().getBitvectorFormulaManager();
        for (Event e : es.getEvents()) {
            if (isReader(e)) {
                valReadVars.put(e, bv.makeVariable(VW, "vr_e" + e.getId()));
            }
            if (e instanceof WriteEvent || e.getType() == EventType.INIT) {
                valWriteVars.put(e, bv.makeVariable(VW, "vw_e" + e.getId()));
            }
        }
    }

    public boolean isSymbolic() { return symbolic; }

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
            if (!isReader(e)) continue;
            int want = readValueOf(e);
            List<BooleanFormula> choices = new ArrayList<>();
            for (Event w : writesSharingLocation(e.getVariable())) {
                if (w.getId() == e.getId()) continue;   // an RMW cannot read from itself
                BooleanFormula rf = rfVars.get(new EventPair(w, e));
                if (rf == null) continue;
                choices.add(rf);
                // Pass 3 Stage 2: the global rf-forward edge (rf ⇒ pos(w) < pos(r))
                // is intentionally NOT emitted here. It made well-formedness an
                // SC-strength backstop (acyclic(po∪rf∪co)) on every model, locking the
                // load-buffering shape that RA/WEAKEST permit (docs/pass-3-plan.md §1).
                // Per-location read-after-write is re-imposed for every model by
                // AxiomaticConsistency.coherencePerLocation (addRfLoc); SC/TSO/PSO
                // re-impose the global rf ordering in their own acyclic layers (addRf).
                // For an RMW the read side consumes its oldVal (readValueOf), not the
                // value it writes.
                if (symbolic) {
                    // C1: couple observed values symbolically instead of matching a folded
                    // constant — rf(w,r) ⇒ val_read[r] = val_write[w].
                    cs.add(bmgr.implication(rf,
                            bv.equal(valReadVars.get(e), valWriteVars.get(w))));
                } else {
                    cs.add(bmgr.implication(rf, bmgr.makeBoolean(w.getValue() == want)));
                }
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

        if (symbolic) {
            // co is left FREE (any total order per location, via the pos order) — do NOT pin
            // it to the loader's single textual order; add symbolic value definitions + the
            // exists pinning instead of the wired coherence order.
            cs.addAll(symbolicValueConstraints());
        } else {
            cs.addAll(coConstraints());
        }

        return bmgr.and(cs);
    }

    /**
     * C1 symbolic-mode constraints (only when {@link #enableSymbolic} was called): each
     * write's {@code val_write} equals the real function of the reads it depends on (built
     * over the shared {@link QfbvConstancyOracle} BV grammar from {@code writeValueExprs},
     * with {@code R<readId>} mapped to that read's {@code val_read}); and each read the
     * {@code exists} clause names is pinned to the required value. Reads the clause does not
     * name stay free. A write with no retained expression (e.g. a hand-built structure)
     * falls back to its folded constant, so symbolic mode degrades gracefully to wired values.
     */
    private List<BooleanFormula> symbolicValueConstraints() {
        List<BooleanFormula> cs = new ArrayList<>();
        Map<String, BitvectorFormula> env = new LinkedHashMap<>();
        for (Map.Entry<Event, BitvectorFormula> e : valReadVars.entrySet()) {
            env.put("R" + e.getKey().getId(), e.getValue());
        }
        for (Map.Entry<Event, BitvectorFormula> e : valWriteVars.entrySet()) {
            Event w = e.getKey();
            String expr = writeValueExprs.get(w.getId());
            BitvectorFormula def;
            if (expr == null) {
                def = bv.makeBitvector(VW, w.getValue());          // no expr: folded constant
            } else {
                try {
                    def = QfbvConstancyOracle.buildTerm(expr, VW, bv, bmgr, env);
                } catch (RuntimeException outsideGrammar) {
                    // Value expression outside the modelled grammar: leave val_write
                    // unconstrained (a fresh BV var) — a sound over-approximation that never
                    // fabricates a value, so it can only ALLOW, never wrongly FORBID.
                    def = e.getValue();
                }
            }
            cs.add(bv.equal(e.getValue(), def));
        }
        for (Map.Entry<Integer, Integer> e : existsReadValues.entrySet()) {
            Event r = es.getEventById(e.getKey());
            BitvectorFormula vr = (r == null) ? null : valReadVars.get(r);
            if (vr != null) cs.add(bv.equal(vr, bv.makeBitvector(VW, e.getValue())));
        }

        // co is free among program writes, but the INITIAL write of each location must stay
        // co-minimal (it is the initial state — a program write can never be overwritten *by*
        // the init). Without this, freeing co lets the init sit co-after a program write and a
        // coherence violation (e.g. CoWR) escapes. pos(init) < pos(w) for every other write w.
        for (String loc : collectLocations()) {
            List<Event> ws = writesSharingLocation(loc);
            Event init = null;
            // The initial write is the one on thread 0 (the parser reserves thread 0 for
            // inits); WriteEvent carries EventType.WRITE, not INIT, so match on the thread.
            for (Event w : ws) if (w.getThreadId() == 0) { init = w; break; }
            if (init == null) continue;
            for (Event w : ws) {
                if (w.getId() == init.getId()) continue;
                cs.add(imgr.lessThan(eventVars.get(init), eventVars.get(w)));
            }
        }

        // Memory-final constraints (loc=val): the co-maximal write of loc has value val.
        // "w is co-maximal" = its position is the largest among loc's writes.
        for (Map.Entry<String, Integer> e : existsMemFinal.entrySet()) {
            List<Event> ws = writesSharingLocation(e.getKey());
            for (Event w : ws) {
                BitvectorFormula vw = valWriteVars.get(w);
                if (vw == null) continue;
                List<BooleanFormula> isMax = new ArrayList<>();
                for (Event other : ws) {
                    if (other.getId() == w.getId()) continue;
                    isMax.add(imgr.lessThan(eventVars.get(other), eventVars.get(w)));
                }
                BooleanFormula coMax = isMax.isEmpty() ? bmgr.makeTrue() : bmgr.and(isMax);
                cs.add(bmgr.implication(coMax,
                        bv.equal(vw, bv.makeBitvector(VW, e.getValue()))));
            }
        }
        return cs;
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
     * Program-order constraints for the global witness order {@code pos}, restricted to
     * <em>same-location</em> po edges (corrected v1, 2026-10-19).
     *
     * <p>{@code pos} is one strict total order over all events, and co is read off it, so
     * any po edge kept here between events of <em>different</em> locations composes with
     * other kept edges. In the submitted build, cross-location R→W / R→R edges were kept
     * and only cross-location W→W / W→R edges were dropped; {@code W x; R x; W y} then
     * re-imposed the dropped {@code W x < W y} through the intermediate read, and the
     * co edges of 2+2W+rfi-shaped programs closed a {@code pos} cycle. That wrongly forbade
     * 3 eligible corpus files under PSO, RA, WEAKEST and RC11, although no model axiom
     * requires the order (see CORRECTION-NOTICE.md, finding F-01).
     *
     * <p>Every relaxation of program order belongs to the per-model consistency layers
     * ({@link AxiomaticConsistency}), never to well-formedness. Same-location order is
     * re-imposed by {@link AxiomaticConsistency#coherencePerLocation}, which every model
     * conjoins, so the edges kept here are implied by it; they are retained only to keep
     * the change minimal. With every constraint relating two events of one location, the
     * well-formedness constraints split per location, and per-location orders on disjoint
     * event sets always extend to one global order. Fences have no location and are
     * skipped. Unlike {@link #poConstraints()}, this is used only by well-formedness, never
     * by relation extraction.
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
                // A fence carries no co/rf, so its slot in the global witness order is
                // irrelevant; ordering an access to/from it would re-impose, through the
                // back door, the very cross-location W→W / W→R the relaxation drops (a
                // fence between two writes is not itself a write, so droppedCrossLocation
                // misses it). Per-model fence ordering lives entirely in the layer
                // encoding (AxiomaticConsistency.addPo); here we simply skip fence edges.
                if (a instanceof FenceEvent || b instanceof FenceEvent) continue;
                if (droppedCrossLocation(a, b)) continue;
                // Corrected v1: keep only same-location edges (see the Javadoc above).
                if (!sameLocation(a, b)) continue;
                cs.add(imgr.lessThan(posA, eventVars.get(b)));
            }
        }
        return cs;
    }

    /** Whether both events access the same (non-null) location; false for fences. */
    static boolean sameLocation(Event a, Event b) {
        return a.getVariable() != null && a.getVariable().equals(b.getVariable());
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
            if (!isReader(e)) continue;
            int want = readValueOf(e);
            for (Event w : writesSharingLocation(e.getVariable())) {
                if (w.getId() == e.getId()) continue;
                BooleanFormula jf = jfVars.get(new EventPair(w, e));
                if (jf == null) continue;
                cs.add(bmgr.implication(jf, bmgr.makeBoolean(w.getValue() == want)));
            }
        }
        return cs;
    }

    /** A reader is a plain load or the read side of an RMW. */
    static boolean isReader(Event e) {
        return e instanceof ReadEvent || e instanceof RMWEvent;
    }

    /** The value a reader consumes: an RMW reads its oldVal; a plain read its value. */
    static int readValueOf(Event e) {
        return (e instanceof RMWEvent rmw) ? rmw.getReadValue() : e.getValue();
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
