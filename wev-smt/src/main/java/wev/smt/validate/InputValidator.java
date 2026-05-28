package wev.smt.validate;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import com.weakest.model.ReadEvent;
import com.weakest.model.RMWEvent;
import com.weakest.model.WriteEvent;

import wev.smt.DependencyInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pre-encoding sanity checks for a candidate {@link EventStructure} (Day 14). A
 * weak-memory back end built on an SMT solver has a dangerous failure mode: hand Z3 a
 * <em>structurally broken</em> execution — a program-order cycle, a read with nothing
 * to read from, two contradictory initial writes — and well-formedness comes back
 * UNSAT, which is indistinguishable from a legitimate "this outcome is forbidden"
 * verdict. The analysis then reports a confident, wrong answer with no crash to flag it.
 *
 * <p>{@code InputValidator} closes that hole. It runs cheap structural checks over the
 * raw {@link EventStructure} (and its {@link DependencyInfo} sidecar) <em>before</em>
 * any encoding, classifying each finding as an {@code error} (defect that voids the
 * verdict) or a {@code warning} (benign irregularity). It is wired in two places —
 * {@code LitmusParser} just before it produces a {@code LitmusCase}, and the
 * {@code MinimalWitnessExtractor} constructor before it builds its activation literals —
 * each of which throws {@link InvalidEventStructureException} on an invalid report.
 *
 * <h2>Checks</h2>
 * <ul>
 *   <li><b>po acyclic (ERROR).</b> Program order is a (union of per-thread) strict
 *       order; a cycle through {@code po} is illegal and would make the position
 *       well-formedness vacuously UNSAT.</li>
 *   <li><b>po total per-thread (WARNING).</b> Within a single thread the accesses
 *       should be linearly ordered by {@code po⁺}. Incomparable same-thread events are
 *       reported as a warning — never an error, because thread&nbsp;0's initial writes
 *       are <em>intentionally</em> po-incomparable (they are the parallel initial state),
 *       and erroring on them would reject every well-formed litmus test. Thread&nbsp;0 is
 *       therefore exempt.</li>
 *   <li><b>read has a write (ERROR).</b> Every read (plain load or RMW read side) needs
 *       at least one write to its location to justify a value — otherwise no valid
 *       execution exists. A read with no same-location write and no recorded {@code rf}
 *       candidate is an error.</li>
 *   <li><b>initial writes consistent (ERROR).</b> The thread-0 initial writes fix the
 *       starting memory; two of them to the same location with different values is a
 *       contradiction.</li>
 *   <li><b>relations reference present events (ERROR).</b> Every id named by
 *       {@code po}, {@code rf}, {@code co}, or a dependency edge must belong to an event
 *       in the structure; a dangling id is the fingerprint of an event removed without
 *       cleaning up its relations.</li>
 * </ul>
 *
 * <p>By construction these checks never fire on a well-formed litmus test — the parser
 * and the parametric builders always wire one initial write per location, straight-line
 * per-thread program order, and an {@code rf} target for every read — so the existing
 * corpus, atlas, and scalability sweeps validate cleanly.
 */
public final class InputValidator {

    private InputValidator() { }

    /** Validate an event structure with no dependency sidecar. */
    public static ValidationReport validate(EventStructure es) {
        return validate(es, DependencyInfo.empty());
    }

    /** Validate an event structure together with its syntactic dependency sidecar. */
    public static ValidationReport validate(EventStructure es, DependencyInfo deps) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Set<Integer> ids = new HashSet<>();
        for (Event e : es.getEvents()) ids.add(e.getId());

        checkReferencesPresent(es, ids, errors);
        checkDependenciesPresent(deps, ids, errors);
        checkProgramOrderAcyclic(es, errors);
        checkProgramOrderTotalPerThread(es, warnings);
        checkReadsHaveWrite(es, errors);
        checkInitialWritesConsistent(es, errors);

        return ValidationReport.of(errors, warnings);
    }

    // ── po acyclicity (ERROR) ──────────────────────────────────────────────

    /**
     * DFS three-colour cycle detection over the {@code programOrder} graph. A grey
     * (on-stack) successor is a back edge, hence a cycle. Reported once with the offending
     * edge; the structure is invalid regardless of how many cycles exist.
     */
    private static void checkProgramOrderAcyclic(EventStructure es, List<String> errors) {
        Map<Integer, List<Integer>> po = es.getProgramOrder();
        Set<Integer> done = new HashSet<>();
        for (Integer start : po.keySet()) {
            if (done.contains(start)) continue;
            // Iterative DFS carrying the on-stack (grey) set explicitly.
            Set<Integer> onStack = new LinkedHashSet<>();
            Deque<int[]> stack = new ArrayDeque<>();   // {node, nextChildIndex}
            stack.push(new int[]{start, 0});
            onStack.add(start);
            while (!stack.isEmpty()) {
                int[] frame = stack.peek();
                int node = frame[0];
                List<Integer> succ = po.getOrDefault(node, List.of());
                if (frame[1] < succ.size()) {
                    int child = succ.get(frame[1]++);
                    if (onStack.contains(child)) {
                        errors.add("program order has a cycle (e" + node + " → e" + child
                                + " closes a loop); po must be a strict per-thread order");
                        return;
                    }
                    if (!done.contains(child)) {
                        stack.push(new int[]{child, 0});
                        onStack.add(child);
                    }
                } else {
                    stack.pop();
                    onStack.remove(node);
                    done.add(node);
                }
            }
        }
    }

    // ── po totality per-thread (WARNING, thread 0 exempt) ──────────────────

    private static void checkProgramOrderTotalPerThread(EventStructure es, List<String> warnings) {
        Map<Integer, List<Event>> byThread = new LinkedHashMap<>();
        for (Event e : es.getEvents()) {
            if (e.getThreadId() == 0) continue;            // init writes are meant to be incomparable
            byThread.computeIfAbsent(e.getThreadId(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<Integer, List<Event>> entry : byThread.entrySet()) {
            List<Event> evs = entry.getValue();
            for (int i = 0; i < evs.size(); i++) {
                for (int j = i + 1; j < evs.size(); j++) {
                    int a = evs.get(i).getId();
                    int b = evs.get(j).getId();
                    if (!poReaches(es, a, b) && !poReaches(es, b, a)) {
                        warnings.add("thread " + entry.getKey() + " has po-incomparable events e"
                                + a + " and e" + b + " (program order is not total within the thread)");
                    }
                }
            }
        }
    }

    // ── reads must have a write to read from (ERROR) ───────────────────────

    private static void checkReadsHaveWrite(EventStructure es, List<String> errors) {
        Map<Integer, Integer> rf = es.getReadsFrom();
        for (Event e : es.getEvents()) {
            if (!isReader(e)) continue;
            String var = e.getVariable();
            if (var == null) continue;                     // defensive; readers always name a location
            boolean hasWrite = false;
            for (Event w : es.getEvents()) {
                if (w.getId() == e.getId()) continue;      // an RMW cannot read from itself
                if (isWrite(w) && var.equals(w.getVariable())) { hasWrite = true; break; }
            }
            boolean hasRfCandidate = rf.containsKey(e.getId());
            if (!hasWrite && !hasRfCandidate) {
                errors.add("read e" + e.getId() + " of location '" + var
                        + "' has no matching write and no rf candidate — no valid execution exists");
            }
        }
    }

    // ── initial writes must not conflict (ERROR) ───────────────────────────

    private static void checkInitialWritesConsistent(EventStructure es, List<String> errors) {
        Map<String, Set<Integer>> initValues = new LinkedHashMap<>();
        for (Event e : es.getEvents()) {
            if (e.getThreadId() != 0) continue;            // initial writes live on thread 0
            if (!isWrite(e) || e.getVariable() == null) continue;
            initValues.computeIfAbsent(e.getVariable(), k -> new TreeSet<>()).add(e.getValue());
        }
        for (Map.Entry<String, Set<Integer>> entry : initValues.entrySet()) {
            if (entry.getValue().size() > 1) {
                errors.add("conflicting initial values for location '" + entry.getKey()
                        + "': " + entry.getValue() + " (the initial state must fix one value)");
            }
        }
    }

    // ── relation references must resolve to present events (ERROR) ─────────

    private static void checkReferencesPresent(EventStructure es, Set<Integer> ids,
                                               List<String> errors) {
        // program order: keys and successors.
        for (Map.Entry<Integer, List<Integer>> e : es.getProgramOrder().entrySet()) {
            if (!ids.contains(e.getKey())) {
                errors.add("program order references absent event e" + e.getKey());
            }
            for (Integer to : e.getValue()) {
                if (!ids.contains(to)) {
                    errors.add("program order edge points at absent event e" + to);
                }
            }
        }
        // reads-from: read id and write id.
        for (Map.Entry<Integer, Integer> e : es.getReadsFrom().entrySet()) {
            if (!ids.contains(e.getKey())) {
                errors.add("rf references absent reader e" + e.getKey());
            }
            if (!ids.contains(e.getValue())) {
                errors.add("rf references absent writer e" + e.getValue());
            }
        }
        // coherence order: every write id in every per-location chain.
        for (Map.Entry<String, List<Integer>> e : es.getCoherenceOrder().entrySet()) {
            for (Integer w : e.getValue()) {
                if (!ids.contains(w)) {
                    errors.add("coherence order for '" + e.getKey()
                            + "' references absent write e" + w);
                }
            }
        }
    }

    private static void checkDependenciesPresent(DependencyInfo deps, Set<Integer> ids,
                                                  List<String> errors) {
        if (deps == null || deps.isEmpty()) return;
        for (Map<Event, Set<Event>> rel : List.of(
                deps.dataDepMap(), deps.addrDepMap(), deps.ctrlDepMap())) {
            for (Map.Entry<Event, Set<Event>> e : rel.entrySet()) {
                if (!ids.contains(e.getKey().getId())) {
                    errors.add("dependency references absent consumer e" + e.getKey().getId());
                }
                for (Event producer : e.getValue()) {
                    if (!ids.contains(producer.getId())) {
                        errors.add("dependency references absent producer e" + producer.getId());
                    }
                }
            }
        }
    }

    // ── shared helpers ──────────────────────────────────────────────────────

    private static boolean isReader(Event e) {
        return e instanceof ReadEvent || e instanceof RMWEvent;
    }

    private static boolean isWrite(Event e) {
        return e instanceof WriteEvent || e.getType() == EventType.INIT;
    }

    /** BFS over static program order: does {@code fromId} po-reach {@code toId}? */
    private static boolean poReaches(EventStructure es, int fromId, int toId) {
        Map<Integer, List<Integer>> po = es.getProgramOrder();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(fromId);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (!visited.add(cur)) continue;
            if (cur == toId && cur != fromId) return true;
            for (Integer next : po.getOrDefault(cur, List.of())) {
                if (next == toId) return true;
                queue.add(next);
            }
        }
        return false;
    }
}
