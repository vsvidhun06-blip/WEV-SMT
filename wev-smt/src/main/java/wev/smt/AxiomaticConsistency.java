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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
 * <p><b>Single source of truth.</b> Every model has two public forms: an unconditional
 * {@code consistencyX()} used by the full-execution validation atlas, and a gated
 * {@code consistencyX(Function<Event,BooleanFormula> active)} that ANDs an {@code active}
 * predicate into every edge's antecedent. The unconditional form is exactly
 * {@code consistencyX(e -> true)}. {@link MinimalWitnessExtractor} drives its
 * sub-execution search through the gated form (passing its {@code active_e} variables),
 * so the two analyses can never disagree about what a model forbids.
 *
 * <p>Per-model relation sets (all additionally subject to {@link #coherencePerLocation}):
 * <ul>
 *   <li>SC: (po ∪ rf ∪ co ∪ fr) acyclic.</li>
 *   <li>TSO: (ppo ∪ rfe ∪ co ∪ fr), ppo = po \ (W→R same-thread), rfe = rf cross-thread.</li>
 *   <li>PSO: ppo = po \ (W→R ∪ W→W same-thread), <em>plus</em> RC11 release/acquire
 *       coherence {@code irreflexive(hb;eco?)} (Pass 2c). The ppo layer alone ignores
 *       release/acquire fences, so it under-forbade the synchronised store-buffering of
 *       CO-MP / MP-relacq (a release store read by an acquire load orders the prior
 *       store). The {@code hb;eco?} term absorbs that {@code sw} edge into
 *       happens-before, collapsing the cycle to a single {@code eco} segment; plain
 *       relaxed {@code rf} keeps {@code rf} as its own {@code eco} segment, so ordinary
 *       MP / S / 2+2W stay allowed.</li>
 *   <li>RA: RC11 release/acquire coherence {@code irreflexive(hb ; eco?)}, with
 *       {@code hb = (po ∪ sw)+} and {@code eco = (rf ∪ co ∪ fr)+}; see
 *       {@link #irreflexiveHbEco}.</li>
 *   <li>WEAKEST (Pass 3 Stage 2): {@link #coherencePerLocation} plus the Weakestmo
 *       no-thin-air axiom {@link #jfCoherence} = {@code acyclic(sdep ∪ jf)}, where
 *       {@code sdep} is the semantic dependency relation and {@code jf} (= {@code rf})
 *       the justification (Chakraborty &amp; Vafeiadis, POPL 2019, §3). The old
 *       {@code acyclic(po ∪ rf)} over-approximation is dropped — it forbade the
 *       well-justified load buffering WEAKEST permits. A legacy non-relaxed-read guard
 *       (ConsistencyChecker.isJustifiableGiven mirror) is retained but inert.</li>
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

    // ── Unconditional (full-execution) consistency ────────────────────────

    public BooleanFormula consistencySC() { return consistencySC(noGate()); }
    public BooleanFormula consistencyTSO() { return consistencyTSO(noGate()); }
    public BooleanFormula consistencyPSO() { return consistencyPSO(noGate()); }
    public BooleanFormula consistencyRA() { return consistencyRA(noGate()); }
    public BooleanFormula consistencyWEAKEST() { return consistencyWEAKEST(noGate()); }

    // ── Gated consistency (one source of truth for sub-executions too) ─────

    public BooleanFormula consistencySC(Function<Event, BooleanFormula> active) {
        Map<Event, IntegerFormula> layer = freshLayer("sc");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, false, false, active);
        addRf(cs, layer, false, false, active);
        addCo(cs, layer, active);
        addFr(cs, layer, active);
        cs.add(coherencePerLocation(active));
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyTSO(Function<Event, BooleanFormula> active) {
        Map<Event, IntegerFormula> layer = freshLayer("tso");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, true, false, active);
        addRf(cs, layer, true, false, active);
        addCo(cs, layer, active);
        addFr(cs, layer, active);
        cs.add(coherencePerLocation(active));
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyPSO(Function<Event, BooleanFormula> active) {
        Map<Event, IntegerFormula> layer = freshLayer("pso");
        List<BooleanFormula> cs = new ArrayList<>();
        addPo(cs, layer, true, true, active);
        addRf(cs, layer, true, false, active);
        addCo(cs, layer, active);
        addFr(cs, layer, active);
        cs.add(coherencePerLocation(active));
        // Release/acquire fence ordering the ppo layer misses (Pass 2c): a release
        // store read by an acquire load synchronises, ordering the prior store. The
        // sw edge enters hb, so CO-MP / MP-relacq become hb;eco (one eco segment) and
        // are forbidden, while plain relaxed MP keeps two eco segments and is allowed.
        cs.add(irreflexiveHbEco(active));
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyRA(Function<Event, BooleanFormula> active) {
        List<BooleanFormula> cs = new ArrayList<>();
        cs.add(irreflexiveHbEco(active));
        // Per-location coherence is subsumed by irreflexive(hb;eco?) but kept as a
        // cheap, independent safety net (and shared with the other models).
        cs.add(coherencePerLocation(active));
        return bmgr.and(cs);
    }

    public BooleanFormula consistencyWEAKEST(Function<Event, BooleanFormula> active) {
        Map<Event, IntegerFormula> layer = freshLayer("weakest");
        List<BooleanFormula> cs = new ArrayList<>();

        // Pass 3 Stage 2: the crude acyclic(po ∪ rf) over-approximation (addPo + addRf
        // in one layer) is REMOVED. It forbade *all* load buffering, including the
        // well-justified LB that WEAKEST permits. The dependency-sensitive no-thin-air
        // condition is now jfCoherence(active), added below (docs/pass-3-plan.md §2).

        // Legacy non-relaxed-read justification guard (mirror of
        // ConsistencyChecker.isJustifiableGiven): for a non-relaxed read r reading from
        // w, no other same-location write w' that is po-before r may sit above w in the
        // po∪rf order. It relied on the removed addPo+addRf layer for that ordering, so
        // with the layer no longer grounded it is now subsumed by jfCoherence +
        // coherencePerLocation (and adds no constraint — all ≥, satisfiable by equal
        // layers). Retained per docs/pass-3-plan.md §2.5; harmless.
        for (Event re : es.getEvents()) {
            if (!(re instanceof ReadEvent r)) continue;
            if (r.getMemoryOrder() == MemoryOrder.RELAXED) continue;
            for (Event w : writesToVar(r.getVariable())) {
                BooleanFormula rfVar = enc.getRfVars().get(new EventPair(w, r));
                if (rfVar == null) continue;
                for (Event wp : writesToVar(r.getVariable())) {
                    if (wp.getId() == w.getId()) continue;
                    if (!poReaches(wp.getId(), r.getId())) continue;
                    cs.add(bmgr.implication(
                            bmgr.and(List.of(rfVar, active.apply(w),
                                    active.apply(r), active.apply(wp))),
                            imgr.greaterOrEquals(layer.get(w), layer.get(wp))));
                }
            }
        }
        cs.add(coherencePerLocation(active));
        cs.add(jfCoherence(active));
        return bmgr.and(cs);
    }

    /**
     * Pass 3 Stage 2 — the Weakestmo no-thin-air axiom: {@code acyclic(sdep ∪ jf)}.
     *
     * <p>Following Chakraborty &amp; Vafeiadis, "Grounding Thin-Air Reads with Event
     * Structures" (POPL 2019, §3): a read must be justifiable through writes whose
     * existence does not transitively depend on that read. {@code sdep} is the union of
     * <em>semantic</em> dependency edges (data/addr/ctrl with {@code isSemantic=true};
     * see {@link DependencyInfo#semanticEdges()}); {@code jf} (justified-from) coincides
     * with {@code rf} in this single-execution encoding. A cycle through
     * {@code sdep ∪ jf} is the thin-air pattern and is forbidden; a <em>fake</em>
     * (identity) dependency contributes no semantic edge and so cannot close one — which
     * is precisely what separates {@code LBdep-fake} (allowed) from {@code LBdep-real}
     * /{@code LBdep-addr} (forbidden).
     *
     * <p>Encoded with the integer-layer trick (as in {@link #coherencePerLocation}): a
     * fresh {@code layer} per event, each edge forcing a strict {@code <} ordering with
     * the value <em>producer</em> before the value <em>consumer</em> in both cases —
     * <ul>
     *   <li>semantic dep stored {@code consumer ← producer}: {@code layer(producer) < layer(consumer)};</li>
     *   <li>{@code jf_w_r} (≡ {@code rf_w_r}): {@code layer(w) < layer(r)}.</li>
     * </ul>
     * A strict integer order admits no cycle, so this is exactly
     * {@code irreflexive((sdep ∪ jf)⁺)}. Base edges are gated by {@code active}.
     *
     * <p><b>Polarity</b> (verified against the LB cycle): with these directions the
     * real-dependency LB closes {@code wx→r1→wy→r2→wx} into a contradiction
     * ({@code layer(wx) < layer(r1) < layer(wy) < layer(r2) < layer(wx)}), so it is
     * UNSAT/forbidden; the fake-dependency LB has no {@code sdep} edges and stays SAT.
     * (The naive {@code jf_w_r ⇒ layer(r) < layer(w)} would leave it satisfiable and is
     * wrong.)
     */
    public BooleanFormula jfCoherence(Function<Event, BooleanFormula> active) {
        Map<Event, IntegerFormula> layer = freshLayer("jfco");
        List<BooleanFormula> cs = new ArrayList<>();

        // Semantic dependency edges: producer → consumer (value source before sink).
        for (DependencyInfo.DepEdge e : enc.getDependencyInfo().semanticEdges()) {
            IntegerFormula lp = layer.get(e.producer());
            IntegerFormula lc = layer.get(e.consumer());
            if (lp == null || lc == null) continue;
            cs.add(bmgr.implication(both(active, e.producer(), e.consumer()),
                    imgr.lessThan(lp, lc)));
        }

        // jf = rf (committed justification), and jf_w_r ⇒ layer(w) < layer(r).
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            BooleanFormula rfVar = e.getValue();
            BooleanFormula jfVar = enc.getJfVars().get(e.getKey());
            BooleanFormula edge = (jfVar != null) ? jfVar : rfVar;
            if (jfVar != null) cs.add(bmgr.equivalence(jfVar, rfVar));
            cs.add(bmgr.implication(bmgr.and(edge, both(active, w, r)),
                    imgr.lessThan(layer.get(w), layer.get(r))));
        }

        return cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
    }

    // ── RC11 release/acquire coherence: irreflexive(hb ; eco?) ────────────

    /**
     * The release/acquire coherence axiom of RC11: {@code irreflexive(hb ; eco?)}.
     *
     * <p>Following Lahav, Vafeiadis, Kang, Hur &amp; Dreyer, "Repairing Sequential
     * Consistency in C/C++11" (PLDI 2017) §3, with
     * <ul>
     *   <li>{@code hb  = (po ∪ sw)+}, happens-before — program order unioned with
     *       synchronises-with ({@code sw}: a release write read by an acquire load),
     *       transitively closed;</li>
     *   <li>{@code eco = (rf ∪ co ∪ fr)+}, the extended coherence order;</li>
     *   <li>{@code eco?} the reflexive closure, so {@code hb ; eco?} = {@code hb ∪ (hb ; eco)}.</li>
     * </ul>
     * A cycle is forbidden only if it is a happens-before path followed by <em>at most
     * one</em> coherence step. A cycle that threads two or more {@code eco} segments
     * between {@code hb} segments (SB, R, IRIW under non-multicopy-atomic RA) is
     * <em>not</em> of that form and stays allowed — unlike the previous single-layer
     * {@code acyclic(po ∪ sw ∪ co ∪ fr)} encoding, which conflated them. MP-relacq /
     * CO-MP (one {@code fr} segment, i.e. {@code hb ; eco}) remain forbidden.
     *
     * <p>{@code hb} and {@code eco} need genuine reachability (not just acyclicity, so
     * the layer trick is insufficient), so each is encoded as a per-pair boolean
     * reachability relation closed under its base edges and transitivity. The base
     * edges are gated by {@code active}. Definite (Horn) closure clauses force each
     * relation to be <em>at least</em> its true closure; the irreflexivity clauses are
     * negative and force no extra edges, so the solver's minimal model is exactly the
     * true closure — making the check both sound and complete for the wired execution.
     * Reachability variables are tagged per call so repeated invocations never collide.
     */
    private BooleanFormula irreflexiveHbEco(Function<Event, BooleanFormula> active) {
        int tag = layerTag.incrementAndGet();
        List<Event> evs = es.getEvents();

        Map<EventPair, BooleanFormula> hb = new LinkedHashMap<>();
        Map<EventPair, BooleanFormula> eco = new LinkedHashMap<>();
        for (Event x : evs) {
            for (Event y : evs) {
                hb.put(new EventPair(x, y), bmgr.makeVariable(
                        "hb" + tag + "_e" + x.getId() + "_e" + y.getId()));
                eco.put(new EventPair(x, y), bmgr.makeVariable(
                        "eco" + tag + "_e" + x.getId() + "_e" + y.getId()));
            }
        }

        List<BooleanFormula> cs = new ArrayList<>();

        // hb base: po⁺ (intra-thread) and sw (release write → acquire read rf).
        for (Event a : evs) {
            for (Event b : evs) {
                if (a.getId() == b.getId()) continue;
                if (poReaches(a.getId(), b.getId())) {
                    cs.add(bmgr.implication(both(active, a, b), hb.get(new EventPair(a, b))));
                }
            }
        }
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            if (!(isReleaseLike(w) && isAcquireLike(r))) continue;
            cs.add(bmgr.implication(bmgr.and(e.getValue(), both(active, w, r)),
                    hb.get(new EventPair(w, r))));
        }

        // eco base: rf, co, fr = rf⁻¹ ; co.
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            cs.add(bmgr.implication(bmgr.and(e.getValue(), both(active, w, r)),
                    eco.get(new EventPair(w, r))));
        }
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getCoVars().entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            cs.add(bmgr.implication(bmgr.and(e.getValue(), both(active, a, b)),
                    eco.get(new EventPair(a, b))));
        }
        for (Map.Entry<EventPair, BooleanFormula> rf : enc.getRfVars().entrySet()) {
            Event w = rf.getKey().from();
            Event r = rf.getKey().to();
            for (Map.Entry<EventPair, BooleanFormula> co : enc.getCoVars().entrySet()) {
                if (co.getKey().from().getId() != w.getId()) continue;
                Event wp = co.getKey().to();
                if (r.getId() == wp.getId()) continue;
                cs.add(bmgr.implication(
                        bmgr.and(List.of(rf.getValue(), co.getValue(),
                                active.apply(w), active.apply(r), active.apply(wp))),
                        eco.get(new EventPair(r, wp))));
            }
        }

        // Transitive closure of hb and eco.
        for (Event x : evs) {
            for (Event z : evs) {
                for (Event y : evs) {
                    cs.add(bmgr.implication(
                            bmgr.and(hb.get(new EventPair(x, z)), hb.get(new EventPair(z, y))),
                            hb.get(new EventPair(x, y))));
                    cs.add(bmgr.implication(
                            bmgr.and(eco.get(new EventPair(x, z)), eco.get(new EventPair(z, y))),
                            eco.get(new EventPair(x, y))));
                }
            }
        }

        // irreflexive(hb ; eco?) = irreflexive(hb) ∧ irreflexive(hb ; eco).
        for (Event x : evs) {
            cs.add(bmgr.not(hb.get(new EventPair(x, x))));
            List<BooleanFormula> comp = new ArrayList<>();
            for (Event z : evs) {
                comp.add(bmgr.and(hb.get(new EventPair(x, z)), eco.get(new EventPair(z, x))));
            }
            cs.add(bmgr.not(bmgr.or(comp)));
        }

        return cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
    }

    // ── Per-location coherence (SC-per-location) ──────────────────────────

    public BooleanFormula coherencePerLocation() { return coherencePerLocation(noGate()); }

    /**
     * SC-per-location coherence, enforced unconditionally by every memory model.
     * For each shared location {@code L} the relation
     * {@code po-loc(L) ∪ rf(L) ∪ co(L) ∪ fr(L)} must be acyclic, where po-loc(L)
     * is program order restricted to the events that access {@code L} — so a store
     * and a po-later load of the same location are ordered even when the model's
     * preserved program order otherwise drops W→R (TSO) or W→W (PSO) pairs, and even
     * under WEAKEST which carries no global co/fr at all. Each location gets its own
     * family of layer variables, so a single-location cycle is forbidden without
     * coupling the orders of distinct locations (that coupling would collapse the
     * model back to SC). See Alglave, Maranget &amp; Tautschnig, "Herding Cats"
     * (TOPLAS 2014) §4.2, axiom SC-PER-LOCATION (a.k.a. coherence).
     */
    public BooleanFormula coherencePerLocation(Function<Event, BooleanFormula> active) {
        List<BooleanFormula> cs = new ArrayList<>();
        for (String loc : accessedLocations()) {
            Map<Event, IntegerFormula> layer = freshLocLayer(loc);
            addPoLoc(cs, layer, loc, active);
            addRfLoc(cs, layer, loc, active);
            addCoLoc(cs, layer, loc, active);
            addFrLoc(cs, layer, loc, active);
        }
        return cs.isEmpty() ? bmgr.makeTrue() : bmgr.and(cs);
    }

    /** po restricted to same-location accesses: layer(a) < layer(b) when a po-before b. */
    private void addPoLoc(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                          String loc, Function<Event, BooleanFormula> active) {
        List<Event> acc = accessorsOf(loc);
        for (Event a : acc) {
            for (Event b : acc) {
                if (a.getId() == b.getId()) continue;
                if (poReaches(a.getId(), b.getId())) {
                    cs.add(bmgr.implication(both(active, a, b),
                            imgr.lessThan(layer.get(a), layer.get(b))));
                }
            }
        }
    }

    private void addRfLoc(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                          String loc, Function<Event, BooleanFormula> active) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            if (!loc.equals(r.getVariable())) continue;
            cs.add(bmgr.implication(
                    bmgr.and(e.getValue(), both(active, w, r)),
                    imgr.lessThan(layer.get(w), layer.get(r))));
        }
    }

    private void addCoLoc(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                          String loc, Function<Event, BooleanFormula> active) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getCoVars().entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            if (!loc.equals(a.getVariable())) continue;
            cs.add(bmgr.implication(
                    bmgr.and(e.getValue(), both(active, a, b)),
                    imgr.lessThan(layer.get(a), layer.get(b))));
        }
    }

    /** fr(L) = rf(L)⁻¹ ; co(L): if r reads w and w co-before w', then r fr-before w'. */
    private void addFrLoc(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                          String loc, Function<Event, BooleanFormula> active) {
        for (Map.Entry<EventPair, BooleanFormula> rf : enc.getRfVars().entrySet()) {
            Event w = rf.getKey().from();
            Event r = rf.getKey().to();
            if (!loc.equals(r.getVariable())) continue;
            for (Map.Entry<EventPair, BooleanFormula> co : enc.getCoVars().entrySet()) {
                if (co.getKey().from().getId() != w.getId()) continue;
                Event wp = co.getKey().to();
                cs.add(bmgr.implication(
                        bmgr.and(List.of(rf.getValue(), co.getValue(),
                                active.apply(w), active.apply(r), active.apply(wp))),
                        imgr.lessThan(layer.get(r), layer.get(wp))));
            }
        }
    }

    // ── Relation-to-layer helpers ─────────────────────────────────────────

    private void addPo(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                       boolean skipWriteRead, boolean skipWriteWrite,
                       Function<Event, BooleanFormula> active) {
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            Event a = es.getEventById(entry.getKey());
            if (a == null) continue;
            for (Integer toId : entry.getValue()) {
                Event b = es.getEventById(toId);
                if (b == null) continue;
                if (skipWriteRead && isWriteOrInit(a) && b instanceof ReadEvent) continue;
                if (skipWriteWrite && isWriteOrInit(a) && isWriteOrInit(b)) continue;
                cs.add(bmgr.implication(both(active, a, b),
                        imgr.lessThan(layer.get(a), layer.get(b))));
            }
        }
    }

    private void addRf(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                       boolean externalOnly, boolean releaseAcquireOnly,
                       Function<Event, BooleanFormula> active) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getRfVars().entrySet()) {
            Event w = e.getKey().from();
            Event r = e.getKey().to();
            if (externalOnly && w.getThreadId() == r.getThreadId()) continue;
            if (releaseAcquireOnly && !(isReleaseLike(w) && isAcquireLike(r))) continue;
            cs.add(bmgr.implication(
                    bmgr.and(e.getValue(), both(active, w, r)),
                    imgr.lessThan(layer.get(w), layer.get(r))));
        }
    }

    private void addCo(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                       Function<Event, BooleanFormula> active) {
        for (Map.Entry<EventPair, BooleanFormula> e : enc.getCoVars().entrySet()) {
            Event a = e.getKey().from();
            Event b = e.getKey().to();
            cs.add(bmgr.implication(
                    bmgr.and(e.getValue(), both(active, a, b)),
                    imgr.lessThan(layer.get(a), layer.get(b))));
        }
    }

    /** fr = rf⁻¹ ; co. For every candidate rf(w,r) and every co(w,w'), emit fr(r,w'). */
    private void addFr(List<BooleanFormula> cs, Map<Event, IntegerFormula> layer,
                       Function<Event, BooleanFormula> active) {
        for (Map.Entry<EventPair, BooleanFormula> rf : enc.getRfVars().entrySet()) {
            Event w = rf.getKey().from();
            Event r = rf.getKey().to();
            for (Map.Entry<EventPair, BooleanFormula> co : enc.getCoVars().entrySet()) {
                if (co.getKey().from().getId() != w.getId()) continue;
                Event wp = co.getKey().to();
                cs.add(bmgr.implication(
                        bmgr.and(List.of(rf.getValue(), co.getValue(),
                                active.apply(w), active.apply(r), active.apply(wp))),
                        imgr.lessThan(layer.get(r), layer.get(wp))));
            }
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────

    /** The unconditional ("everything active") gate; makes gated == full-execution. */
    private Function<Event, BooleanFormula> noGate() {
        return e -> bmgr.makeTrue();
    }

    /** Conjunction of the gate predicate over a pair of endpoints. */
    private BooleanFormula both(Function<Event, BooleanFormula> active, Event a, Event b) {
        return bmgr.and(active.apply(a), active.apply(b));
    }

    private Map<Event, IntegerFormula> freshLayer(String tag) {
        int id = layerTag.incrementAndGet();
        Map<Event, IntegerFormula> m = new LinkedHashMap<>();
        for (Event e : es.getEvents()) {
            m.put(e, imgr.makeVariable("layer_" + tag + id + "_e" + e.getId()));
        }
        return m;
    }

    /** A fresh per-location layer family, defined only over the events that touch {@code loc}. */
    private Map<Event, IntegerFormula> freshLocLayer(String loc) {
        int id = layerTag.incrementAndGet();
        String safe = sanitize(loc);
        Map<Event, IntegerFormula> m = new LinkedHashMap<>();
        for (Event e : accessorsOf(loc)) {
            m.put(e, imgr.makeVariable("colayer_" + safe + id + "_e" + e.getId()));
        }
        return m;
    }

    private Set<String> accessedLocations() {
        Set<String> locs = new LinkedHashSet<>();
        for (Event e : es.getEvents()) {
            if (accesses(e) && e.getVariable() != null) locs.add(e.getVariable());
        }
        return locs;
    }

    private List<Event> accessorsOf(String loc) {
        List<Event> out = new ArrayList<>();
        for (Event e : es.getEvents()) {
            if (accesses(e) && loc.equals(e.getVariable())) out.add(e);
        }
        return out;
    }

    private boolean accesses(Event e) {
        return e instanceof ReadEvent || e instanceof WriteEvent
                || e.getType() == EventType.INIT;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
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
