package wev.smt;

import com.weakest.model.Event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <strong>producer</strong> events whose value it syntactically uses:
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
 * <p>Stage 1 (this commit) only records the edges; no consistency axiom reads them.
 * The distinction between a <em>real</em> dependency and a <em>fake</em> (e.g.
 * {@code r ^ r}, the identity) one is <strong>not</strong> represented here — both
 * carry the same edges — and is left to the Stage-2 value-aware jf-coherence axiom.
 */
public final class DependencyInfo {

    private final Map<Event, Set<Event>> dataDeps = new LinkedHashMap<>();
    private final Map<Event, Set<Event>> addrDeps = new LinkedHashMap<>();
    private final Map<Event, Set<Event>> ctrlDeps = new LinkedHashMap<>();

    /** An empty sidecar — the default for every dependency-free litmus case. */
    public static DependencyInfo empty() {
        return new DependencyInfo();
    }

    public void addDataDep(Event consumer, Event producer) {
        link(dataDeps, consumer, producer);
    }

    public void addAddrDep(Event consumer, Event producer) {
        link(addrDeps, consumer, producer);
    }

    public void addCtrlDep(Event consumer, Event producer) {
        link(ctrlDeps, consumer, producer);
    }

    /** Producers whose value flows (as data) into {@code e}; empty if none. */
    public Set<Event> getDataDeps(Event e) {
        return view(dataDeps, e);
    }

    /** Producers whose value forms {@code e}'s address; empty if none. */
    public Set<Event> getAddrDeps(Event e) {
        return view(addrDeps, e);
    }

    /** Producers whose value gates {@code e}'s control flow; empty if none. */
    public Set<Event> getCtrlDeps(Event e) {
        return view(ctrlDeps, e);
    }

    /** Union of the data, addr and ctrl producers of {@code e}; empty if none. */
    public Set<Event> getAllDeps(Event e) {
        Set<Event> all = new LinkedHashSet<>();
        all.addAll(getDataDeps(e));
        all.addAll(getAddrDeps(e));
        all.addAll(getCtrlDeps(e));
        return Collections.unmodifiableSet(all);
    }

    /** Whether this sidecar carries no dependency edge of any kind. */
    public boolean isEmpty() {
        return dataDeps.isEmpty() && addrDeps.isEmpty() && ctrlDeps.isEmpty();
    }

    /**
     * The full data-dependency relation, consumer → producers (read-only). Used by
     * {@link EventStructureEncoder} to mint one decision var per edge.
     */
    public Map<Event, Set<Event>> dataDepMap() {
        return Collections.unmodifiableMap(dataDeps);
    }

    public Map<Event, Set<Event>> addrDepMap() {
        return Collections.unmodifiableMap(addrDeps);
    }

    public Map<Event, Set<Event>> ctrlDepMap() {
        return Collections.unmodifiableMap(ctrlDeps);
    }

    private static void link(Map<Event, Set<Event>> rel, Event consumer, Event producer) {
        rel.computeIfAbsent(consumer, k -> new LinkedHashSet<>()).add(producer);
    }

    private static Set<Event> view(Map<Event, Set<Event>> rel, Event e) {
        Set<Event> s = rel.get(e);
        return s == null ? Collections.emptySet() : Collections.unmodifiableSet(s);
    }
}
