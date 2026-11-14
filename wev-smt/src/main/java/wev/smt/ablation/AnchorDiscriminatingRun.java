package wev.smt.ablation;

import com.weakest.model.EventStructure;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;

import wev.smt.AxiomaticConsistency;
import wev.smt.DependencyInfo;
import wev.smt.DependencyInfo.DepKind;
import wev.smt.DependencyInfo.KindedEdge;
import wev.smt.EventStructureEncoder;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.LitmusParser.DepDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * <b>External-anchor discriminating-test sweep</b> (evaluation-only).
 *
 * <p>Answers one question over the published source-paper validation set — the
 * Weakestmo (Chakraborty &amp; Vafeiadis, POPL 2019), PwT / Leaky-Semicolon
 * (Jeffrey et&nbsp;al., POPL 2022) and JMM causality (Pugh 2004) named examples:
 * <em>for how many of them does the fake/real dependency detector actually decide the
 * verdict?</em> A published example only anchors the §6 contribution externally if
 * relabelling its dependency edges changes what WEAKEST says; on every other example
 * the agreement is produced by coherence, by the absence of a cycle, or by a real
 * dependency that any {@code sdep = dep} model would also keep.
 *
 * <p>Two criteria are recorded per example, both from the frozen machinery:
 * <ul>
 *   <li><b>structural</b> — the example carries a fake ({@code isSemantic = false})
 *       DATA edge that lies on a cycle of {@code dep ∪ rf} (Kosaraju SCC over all
 *       dependency edges plus the wired reads-from, i.e. the load-buffering shape a
 *       fake-vs-real relabelling can open or close). Column
 *       {@code fake_data_on_lb_cycle}. {@code fake_any_on_lb_cycle} is the same test
 *       widened to DATA/ADDR/CTRL.</li>
 *   <li><b>behavioural</b> — the WEAKEST verdict differs between
 *       {@link SdepConfig#CURRENT} ({@code sdep} = detector output) and
 *       {@link SdepConfig#ALL_DEPS_SEMANTIC} ({@code sdep = dep}). Column
 *       {@code discriminating}. This is the definition of "the detector decides this
 *       verdict"; {@code verdict_none} is reported alongside for context but is not
 *       part of the criterion.</li>
 * </ul>
 *
 * <p>Verdict machinery is bit-for-bit identical to {@link PaperExamplesRun} and
 * {@link SdepFakeDiscriminatingRun}: frozen encoder + {@code AxiomaticConsistency}, one
 * fresh {@link SolverContext} per solve under a per-solve shutdown timeout,
 * {@code maxEvents=64}. No parser, detector or semantic logic is touched.
 *
 * <p>Args (all optional, key=value): {@code dirs=eval/examples/weakestmo:weakestmo,…}
 * (colon-separated {@code path:label} pairs, comma-separated list),
 * {@code out=eval/anchor-discriminating.csv}, {@code timeoutSec=30}, {@code maxEvents=64}.
 */
public final class AnchorDiscriminatingRun {

    private AnchorDiscriminatingRun() { }

    enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT, ERROR }

    private record Opts(List<String[]> dirs, Path out, int timeoutSec, int maxEvents) { }

    private record Row(String source, String test,
                       int dataDeps, int addrDeps, int ctrlDeps,
                       int dataFake, int addrFake, int ctrlFake,
                       String fakePatterns,
                       boolean fakeDataOnCycle, boolean fakeAnyOnCycle,
                       Verdict none, Verdict all, Verdict current) {

        /** Verdict changes when sdep is replaced by dep. */
        boolean discriminates() { return all != current; }
    }

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "anchor-timeout");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) throws Exception {
        Opts opt = parseOpts(args);

        StringBuilder sb = new StringBuilder(
                "source,test,dataDeps,addrDeps,ctrlDeps,dataFake,addrFake,ctrlFake,"
                + "fake_patterns,fake_data_on_lb_cycle,fake_any_on_lb_cycle,"
                + "verdict_none,verdict_all_deps_semantic,verdict_current,discriminating\n");

        List<Row> rows = new ArrayList<>();
        List<DepDecision> decisions = new ArrayList<>();
        LitmusParser.DEP_TRACE = decisions::add;
        try {
            for (String[] d : opt.dirs()) {
                Path dir = Path.of(d[0]);
                String label = d[1];
                List<Path> files = new ArrayList<>();
                try (var s = Files.list(dir)) {
                    s.filter(p -> p.toString().endsWith(".litmus")).sorted().forEach(files::add);
                }
                System.out.printf(Locale.ROOT, "%n[%s] %s — %d files%n", label, dir, files.size());
                for (Path f : files) {
                    decisions.clear();
                    Row row = solveFile(label, f, decisions, opt);
                    rows.add(row);
                    System.out.printf(Locale.ROOT,
                            "  %-14s data=%d(fake %d) addr=%d(fake %d) ctrl=%d(fake %d)  "
                            + "fakeDataOnCycle=%-5s  none=%-9s all=%-9s current=%-9s  %s%n",
                            row.test(), row.dataDeps(), row.dataFake(),
                            row.addrDeps(), row.addrFake(), row.ctrlDeps(), row.ctrlFake(),
                            row.fakeDataOnCycle(), row.none(), row.all(), row.current(),
                            row.discriminates() ? "*** DISCRIMINATING ***" : "");
                }
            }
        } finally {
            LitmusParser.DEP_TRACE = null;
            SCHED.shutdownNow();
        }

        for (Row r : rows) {
            sb.append(r.source()).append(',')
              .append(r.test()).append(',')
              .append(r.dataDeps()).append(',').append(r.addrDeps()).append(',').append(r.ctrlDeps()).append(',')
              .append(r.dataFake()).append(',').append(r.addrFake()).append(',').append(r.ctrlFake()).append(',')
              .append('"').append(r.fakePatterns()).append('"').append(',')
              .append(r.fakeDataOnCycle()).append(',').append(r.fakeAnyOnCycle()).append(',')
              .append(r.none()).append(',').append(r.all()).append(',').append(r.current()).append(',')
              .append(r.discriminates()).append('\n');
        }
        Files.createDirectories(opt.out().toAbsolutePath().getParent());
        Files.writeString(opt.out(), sb.toString());

        // ── Roll-up ──────────────────────────────────────────────────────────────
        System.out.printf(Locale.ROOT, "%n=== external anchor: discriminating count ===%n");
        System.out.printf(Locale.ROOT, "total examples: %d%n", rows.size());
        Map<String, int[]> perSource = new java.util.LinkedHashMap<>();   // [total, discriminating]
        for (Row r : rows) {
            int[] c = perSource.computeIfAbsent(r.source(), k -> new int[2]);
            c[0]++;
            if (r.discriminates()) c[1]++;
        }
        for (Map.Entry<String, int[]> e : perSource.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-16s %d / %d discriminating%n",
                    e.getKey(), e.getValue()[1], e.getValue()[0]);
        }
        List<Row> disc = rows.stream().filter(Row::discriminates).toList();
        System.out.printf(Locale.ROOT, "  %-16s %d / %d discriminating%n",
                "TOTAL", disc.size(), rows.size());
        System.out.println("discriminating tests:");
        for (Row r : disc) {
            System.out.printf(Locale.ROOT, "  - %s/%s  (all-deps-semantic=%s vs current=%s)%n",
                    r.source(), r.test(), r.all(), r.current());
        }
        long structural = rows.stream().filter(Row::fakeDataOnCycle).count();
        long structuralAny = rows.stream().filter(Row::fakeAnyOnCycle).count();
        System.out.printf(Locale.ROOT,
                "structural: fake DATA edge on a dep∪rf cycle: %d; any fake edge on a cycle: %d%n",
                structural, structuralAny);
        System.out.printf(Locale.ROOT, "%nWrote: %s%n", opt.out().toAbsolutePath());
    }

    private static Row solveFile(String source, Path f, List<DepDecision> decisions, Opts opt) {
        String name = f.getFileName().toString().replaceFirst("\\.litmus$", "");
        LitmusCase lc;
        try {
            lc = LitmusParser.parse(Files.readString(f), f.getFileName().toString());
        } catch (Exception e) {
            return new Row(source, name, 0, 0, 0, 0, 0, 0, "parse-error",
                    false, false, Verdict.ERROR, Verdict.ERROR, Verdict.ERROR);
        }
        EventStructure es = lc.es();
        DependencyInfo deps = lc.deps();

        int data = 0, addr = 0, ctrl = 0, dataFake = 0, addrFake = 0, ctrlFake = 0;
        for (KindedEdge ke : deps.allEdges()) {
            boolean fake = !ke.edge().isSemantic();
            switch (ke.kind()) {
                case DATA -> { data++; if (fake) dataFake++; }
                case ADDR -> { addr++; if (fake) addrFake++; }
                case CTRL -> { ctrl++; if (fake) ctrlFake++; }
                default -> { }
            }
        }

        // Fake-pattern attribution (same scheme as SdepFakeDiscriminatingRun).
        Map<Integer, LinkedHashSet<String>> fakePat = new HashMap<>();
        for (DepDecision d : decisions) {
            if (d.real()) continue;
            for (Integer id : d.producerReadIds()) {
                fakePat.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(d.pattern());
            }
        }
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        for (KindedEdge ke : deps.allEdges()) {
            if (ke.edge().isSemantic()) continue;
            LinkedHashSet<String> p = fakePat.get(ke.edge().producer().getId());
            if (p != null) patterns.addAll(p); else patterns.add("UNKNOWN");
        }

        // Structural: is a fake edge on a dep∪rf (load-buffering) cycle?
        Set<Long> cyclic = candidateCycleEdges(lc);
        boolean fakeDataOnCycle = false, fakeAnyOnCycle = false;
        for (KindedEdge ke : deps.allEdges()) {
            if (ke.edge().isSemantic()) continue;
            if (!cyclic.contains(edgeKey(ke.edge().producer().getId(),
                                         ke.edge().consumer().getId()))) continue;
            fakeAnyOnCycle = true;
            if (ke.kind() == DepKind.DATA) fakeDataOnCycle = true;
        }

        Verdict n, a, c;
        if (es.getEvents().size() > opt.maxEvents()) {
            n = a = c = Verdict.ERROR;
        } else {
            n = solve(es, SdepConfig.NONE.transform(deps), opt);
            a = solve(es, SdepConfig.ALL_DEPS_SEMANTIC.transform(deps), opt);
            c = solve(es, SdepConfig.CURRENT.transform(deps), opt);
        }
        return new Row(source, name, data, addr, ctrl, dataFake, addrFake, ctrlFake,
                String.join("+", patterns), fakeDataOnCycle, fakeAnyOnCycle, n, a, c);
    }

    // ── dep∪rf cycle detection (Kosaraju SCC), as in SdepFakeDiscriminatingRun ────

    private static Set<Long> candidateCycleEdges(LitmusCase lc) {
        Map<Integer, List<Integer>> adj = new HashMap<>();
        Set<Integer> nodes = new HashSet<>();
        List<int[]> depEdges = new ArrayList<>();
        for (KindedEdge ke : lc.deps().allEdges()) {
            int p = ke.edge().producer().getId();
            int c = ke.edge().consumer().getId();
            addArc(adj, nodes, p, c);
            depEdges.add(new int[]{p, c});
        }
        for (Map.Entry<Integer, Integer> rf : lc.es().getReadsFrom().entrySet()) {
            addArc(adj, nodes, rf.getValue(), rf.getKey());   // write → read
        }
        Map<Integer, Integer> comp = kosaraju(adj, nodes);
        Map<Integer, Integer> compSize = new HashMap<>();
        for (int cc : comp.values()) compSize.merge(cc, 1, Integer::sum);

        Set<Long> cyclic = new HashSet<>();
        for (int[] e : depEdges) {
            Integer cp = comp.get(e[0]), cc = comp.get(e[1]);
            if (cp != null && cp.equals(cc) && compSize.getOrDefault(cp, 1) > 1) {
                cyclic.add(edgeKey(e[0], e[1]));
            }
        }
        return cyclic;
    }

    private static void addArc(Map<Integer, List<Integer>> adj, Set<Integer> nodes, int from, int to) {
        adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        nodes.add(from);
        nodes.add(to);
    }

    private static long edgeKey(int from, int to) {
        return ((long) from << 32) | (to & 0xffffffffL);
    }

    private static Map<Integer, Integer> kosaraju(Map<Integer, List<Integer>> adj, Set<Integer> nodes) {
        Deque<int[]> stack = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        for (Integer s : nodes) {
            if (seen.contains(s)) continue;
            stack.push(new int[]{s, 0});
            seen.add(s);
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                List<Integer> succ = adj.getOrDefault(top[0], List.of());
                if (top[1] < succ.size()) {
                    int next = succ.get(top[1]++);
                    if (seen.add(next)) stack.push(new int[]{next, 0});
                } else {
                    order.add(top[0]);
                    stack.pop();
                }
            }
        }
        Map<Integer, List<Integer>> rev = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : adj.entrySet()) {
            for (int v : e.getValue()) rev.computeIfAbsent(v, k -> new ArrayList<>()).add(e.getKey());
        }
        Map<Integer, Integer> comp = new HashMap<>();
        int cid = 0;
        for (int i = order.size() - 1; i >= 0; i--) {
            int s = order.get(i);
            if (comp.containsKey(s)) continue;
            Deque<Integer> dfs = new ArrayDeque<>();
            dfs.push(s);
            comp.put(s, cid);
            while (!dfs.isEmpty()) {
                int u = dfs.pop();
                for (int v : rev.getOrDefault(u, List.of())) {
                    if (!comp.containsKey(v)) { comp.put(v, cid); dfs.push(v); }
                }
            }
            cid++;
        }
        return comp;
    }

    // ── Solve (identical machinery to PaperExamplesRun) ───────────────────────────

    private static Verdict solve(EventStructure es, DependencyInfo deps, Opts opt) {
        ShutdownManager sm = ShutdownManager.create();
        ScheduledFuture<?> to = SCHED.schedule(
                () -> sm.requestShutdown("per-solve timeout " + opt.timeoutSec() + "s"),
                opt.timeoutSec(), TimeUnit.SECONDS);
        try {
            Configuration config = Configuration.defaultConfiguration();
            LogManager log = BasicLogManager.create(config);
            try (SolverContext ctx = SolverContextFactory.createSolverContext(
                    config, log, sm.getNotifier(), Solvers.Z3)) {
                BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
                EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                BooleanFormula cons = ax.consistencyWEAKEST();
                boolean unsat;
                try (ProverEnvironment p = ctx.newProverEnvironment()) {
                    p.addConstraint(bmgr.and(wf, cons));
                    unsat = p.isUnsat();
                }
                return unsat ? Verdict.FORBIDDEN : Verdict.ALLOWED;
            }
        } catch (InterruptedException ie) {
            return Verdict.TIMEOUT;
        } catch (Throwable ex) {
            boolean interrupted = ex instanceof InterruptedException
                    || (ex.getCause() instanceof InterruptedException);
            return interrupted ? Verdict.TIMEOUT : Verdict.ERROR;
        } finally {
            to.cancel(false);
        }
    }

    private static Opts parseOpts(String[] args) {
        int timeoutSec = 30, maxEvents = 64;
        List<String[]> dirs = new ArrayList<>(List.of(
                new String[]{"eval/examples/weakestmo", "weakestmo"},
                new String[]{"eval/examples/leaky-semicolon", "pwt"},
                new String[]{"eval/examples/jmm", "jmm"}));
        Path out = Path.of("eval", "anchor-discriminating.csv");
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "timeoutsec" -> timeoutSec = Integer.parseInt(v);
                case "maxevents" -> maxEvents = Integer.parseInt(v);
                case "out" -> out = Path.of(v);
                case "dirs" -> {
                    dirs = new ArrayList<>();
                    for (String spec : v.split(",")) {
                        int i = spec.lastIndexOf(':');
                        dirs.add(new String[]{spec.substring(0, i), spec.substring(i + 1)});
                    }
                }
                default -> System.err.println("[opt] ignoring unknown option: " + a);
            }
        }
        return new Opts(dirs, out, timeoutSec, maxEvents);
    }
}
