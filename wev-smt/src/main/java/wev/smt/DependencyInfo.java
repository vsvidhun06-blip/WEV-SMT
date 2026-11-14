package wev.smt;

import com.weakest.model.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syntactic dependency edges for a litmus test — the data model added by Pass 3
 * (Chakraborty &amp; Vafeiadis, "Grounding Thin-Air Reads with Event Structures",
 * POPL 2019, §3). A <em>sidecar</em>: it lives entirely in {@code wev.smt} and
 * carries no reference back into the read-only {@code com.weakest.model.EventStructure},
 * which is therefore left untouched.
 *
 * <p>Three relations, each mapping a <strong>consumer</strong> event to the set of
 * {@link DepEdge}s recording the <strong>producer</strong> events whose value it
 * syntactically uses:
 * <ul>
 *   <li>{@code data} — the value the consumer writes uses the producer's value;</li>
 *   <li>{@code addr} — the consumer's address uses the producer's value;</li>
 *   <li>{@code ctrl} — the consumer's control flow uses the producer's value.</li>
 * </ul>
 *
 * <p>Convention: {@code addDataDep(w, r)} means "the value written by {@code w}
 * syntactically depends on the value read by {@code r}" — i.e. the map points from
 * the consumer ({@code w}) to the producer ({@code r}).
 *
 * <p>Pass 3 Stage 2 adds the {@link DepEdge#isSemantic()} flag. Only <em>semantic</em>
 * dependencies — those that survive compiler optimisation, i.e. the written value
 * genuinely varies with the read — participate in the WEAKEST jf-coherence axiom
 * (Chakraborty &amp; Vafeiadis §3). A <em>fake</em> dependency (e.g. {@code r ^ r},
 * the identity: syntactically mentions the read but is in fact constant) is
 * recorded with {@code isSemantic = false} and excluded from {@link #semanticEdges()},
 * so it cannot close a thin-air cycle. Edges added without an explicit flag default
 * to {@code isSemantic = true}.
 */
public final class DependencyInfo {

    /**
     * A single dependency edge: the {@code consumer}'s value/address/control uses
     * the {@code producer}'s value. {@code isSemantic} is {@code true} when the
     * dependency is real (survives optimisation) and {@code false} for a syntactic
     * but value-irrelevant ("fake") dependency. Only semantic edges feed
     * {@link #semanticEdges()} and hence the jf-coherence axiom.
     */
    public record DepEdge(Event consumer, Event producer, boolean isSemantic) { }

    private final Map<Event, Set<DepEdge>> dataDeps = new LinkedHashMap<>();
    private final Map<Event, Set<DepEdge>> addrDeps = new LinkedHashMap<>();
    private final Map<Event, Set<DepEdge>> ctrlDeps = new LinkedHashMap<>();

    /** An empty sidecar — the default for every dependency-free litmus case. */
    public static DependencyInfo empty() {
        return new DependencyInfo();
    }

    public void addDataDep(Event consumer, Event producer) {
        addDataDep(consumer, producer, true);
    }

    public void addDataDep(Event consumer, Event producer, boolean isSemantic) {
        link(dataDeps, consumer, producer, isSemantic);
    }

    public void addAddrDep(Event consumer, Event producer) {
        addAddrDep(consumer, producer, true);
    }

    public void addAddrDep(Event consumer, Event producer, boolean isSemantic) {
        link(addrDeps, consumer, producer, isSemantic);
    }

    public void addCtrlDep(Event consumer, Event producer) {
        addCtrlDep(consumer, producer, true);
    }

    public void addCtrlDep(Event consumer, Event producer, boolean isSemantic) {
        link(ctrlDeps, consumer, producer, isSemantic);
    }

    /** Producers whose value flows (as data) into {@code e}; empty if none. */
    public Set<Event> getDataDeps(Event e) {
        return producers(dataDeps, e);
    }

    /** Producers whose value forms {@code e}'s address; empty if none. */
    public Set<Event> getAddrDeps(Event e) {
        return producers(addrDeps, e);
    }

    /** Producers whose value gates {@code e}'s control flow; empty if none. */
    public Set<Event> getCtrlDeps(Event e) {
        return producers(ctrlDeps, e);
    }

    /** Union of the data, addr and ctrl producers of {@code e}; empty if none. */
    public Set<Event> getAllDeps(Event e) {
        Set<Event> all = new LinkedHashSet<>();
        all.addAll(getDataDeps(e));
        all.addAll(getAddrDeps(e));
        all.addAll(getCtrlDeps(e));
        return Collections.unmodifiableSet(all);
    }

    /**
     * Every <em>semantic</em> dependency edge across {@code data ∪ addr ∪ ctrl}
     * (those with {@code isSemantic = true}). This is the relation the Pass-3
     * Stage-2 jf-coherence axiom ({@code AxiomaticConsistency.jfCoherence}) treats
     * as {@code sdep}: each edge contributes a {@code layer(producer) < layer(consumer)}
     * ordering, so a cycle through {@code sdep ∪ jf} is forbidden (thin-air).
     */
    public List<DepEdge> semanticEdges() {
        List<DepEdge> out = new ArrayList<>();
        for (Map<Event, Set<DepEdge>> rel : List.of(dataDeps, addrDeps, ctrlDeps)) {
            for (Set<DepEdge> edges : rel.values()) {
                for (DepEdge e : edges) {
                    if (e.isSemantic()) out.add(e);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Which of the three syntactic relations an edge belongs to. */
    public enum DepKind { DATA, ADDR, CTRL }

    /** A {@link DepEdge} tagged with the relation ({@code data}/{@code addr}/{@code ctrl}) it came from. */
    public record KindedEdge(DepKind kind, DepEdge edge) { }

    /**
     * Every dependency edge across {@code data ∪ addr ∪ ctrl}, semantic and fake alike,
     * each tagged with its relation. Unlike {@link #semanticEdges()} this keeps the fake
     * edges (and their {@link DepEdge#isSemantic()} flag), for tooling that audits the
     * fake/semantic split itself rather than consuming only the jf-coherence relation.
     */
    public List<KindedEdge> allEdges() {
        List<KindedEdge> out = new ArrayList<>();
        collect(out, DepKind.DATA, dataDeps);
        collect(out, DepKind.ADDR, addrDeps);
        collect(out, DepKind.CTRL, ctrlDeps);
        return Collections.unmodifiableList(out);
    }

    private static void collect(List<KindedEdge> out, DepKind kind, Map<Event, Set<DepEdge>> rel) {
        for (Set<DepEdge> edges : rel.values()) {
            for (DepEdge e : edges) out.add(new KindedEdge(kind, e));
        }
    }

    /** Whether this sidecar carries no dependency edge of any kind. */
    public boolean isEmpty() {
        return dataDeps.isEmpty() && addrDeps.isEmpty() && ctrlDeps.isEmpty();
    }

    /**
     * The full data-dependency relation, consumer → producers (read-only, flag
     * dropped). Used by {@link EventStructureEncoder} to mint one inert decision var
     * per edge — those vars do not distinguish semantic from fake dependencies.
     */
    public Map<Event, Set<Event>> dataDepMap() {
        return producerMap(dataDeps);
    }

    public Map<Event, Set<Event>> addrDepMap() {
        return producerMap(addrDeps);
    }

    public Map<Event, Set<Event>> ctrlDepMap() {
        return producerMap(ctrlDeps);
    }

    private static void link(Map<Event, Set<DepEdge>> rel, Event consumer,
                             Event producer, boolean isSemantic) {
        rel.computeIfAbsent(consumer, k -> new LinkedHashSet<>())
                .add(new DepEdge(consumer, producer, isSemantic));
    }

    private static Set<Event> producers(Map<Event, Set<DepEdge>> rel, Event e) {
        Set<DepEdge> edges = rel.get(e);
        if (edges == null) return Collections.emptySet();
        Set<Event> out = new LinkedHashSet<>();
        for (DepEdge d : edges) out.add(d.producer());
        return Collections.unmodifiableSet(out);
    }

    private static Map<Event, Set<Event>> producerMap(Map<Event, Set<DepEdge>> rel) {
        Map<Event, Set<Event>> out = new LinkedHashMap<>();
        for (Map.Entry<Event, Set<DepEdge>> e : rel.entrySet()) {
            Set<Event> ps = new LinkedHashSet<>();
            for (DepEdge d : e.getValue()) ps.add(d.producer());
            out.put(e.getKey(), ps);
        }
        return Collections.unmodifiableMap(out);
    }
}
