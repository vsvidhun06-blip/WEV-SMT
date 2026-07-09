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
import wev.smt.DependencyInfo.DepEdge;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * §6 sdep <em>fake-edge discriminating-tests</em> sweep — the fake-dependency-focused
 * companion to {@link SdepDiscriminatingRun}.
 *
 * <p>Sweeps the entire validated §6.2 WEAKEST corpus
 * ({@code eval/corpus-weakest-manifest.csv}, 2 998 files) and keeps only tests where the
 * shipped detector classifies <b>at least one dependency edge as fake</b>
 * ({@code isSemantic = false}). For each such test it records the architecture, the
 * per-kind fake/semantic edge counts, and the idiom {@code pattern}(s) the classifier
 * matched (via {@link LitmusParser#DEP_TRACE}); solves the WEAKEST verdict under all
 * three {@link SdepConfig} configurations ({@code none} / {@code all-deps-semantic} /
 * {@code current}); and flags a test <b>discriminating</b> when
 * {@code none != current OR all-deps-semantic != current}. It additionally marks whether
 * a fake edge is <em>verdict-relevant</em> — lies on a cycle of the combined
 * {@code dep ∪ rf} graph (Kosaraju SCC over all dep edges plus rf), the load-buffering
 * shape a fake-vs-real relabelling can open or close.
 *
 * <p>Verdict machinery, bounds and timeout handling are identical to
 * {@link SdepDiscriminatingRun}: frozen encoder + {@code AxiomaticConsistency}, one fresh
 * {@link SolverContext} per solve, {@code perFileSec=30}, {@code maxEvents=64}. This is an
 * evaluation driver only — it modifies no parser, detector, or semantic logic.
 *
 * <p>Outputs (to {@code out}, default {@code eval}):
 * <ul>
 *   <li>{@code sdep-discriminating-all-fake-tests.csv} — one row per fake-carrying test;</li>
 *   <li>{@code sdep-fake-summary.md} — the roll-up report (totals, breakdowns, the
 *       discriminating-test list with per-test one-sentence explanations).</li>
 * </ul>
 *
 * <p>Args (all optional, key=value): {@code manifest=…}, {@code corpusRoot=…},
 * {@code out=eval}, {@code timeoutSec=30}, {@code maxEvents=64}, {@code limit=-1}.
 */
public final class SdepFakeDiscriminatingRun {

    private SdepFakeDiscriminatingRun() { }

    enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT, ERROR, SKIPPED_LARGE }

    private record Opts(Path manifest, Path corpusRoot, Path out,
                        int timeoutSec, int maxEvents, int limit) { }

    /** One fake-carrying test with its per-config verdicts and fake-edge metadata. */
    private record Row(String file, String arch,
                       int dataFake, int addrFake, int ctrlFake,
                       int dataSem, int addrSem, int ctrlSem,
                       String fakePatterns, boolean fakeVerdictRelevant,
                       Verdict none, Verdict all, Verdict current) {

        int fakeEdges()     { return dataFake + addrFake + ctrlFake; }
        int semanticEdges() { return dataSem + addrSem + ctrlSem; }

        /** The kinds (DATA/ADDR/CTRL) that carry at least one fake edge, '|'-joined. */
        String fakeKinds() {
            List<String> k = new ArrayList<>();
            if (dataFake > 0) k.add("DATA");
            if (addrFake > 0) k.add("ADDR");
            if (ctrlFake > 0) k.add("CTRL");
            return String.join("|", k);
        }

        /** none != current OR all-deps-semantic != current. */
        boolean discriminates() { return none != current || all != current; }

        /** Any verdict differs across the three configurations. */
        boolean anyVerdictChange() { return !(none == all && all == current); }
    }

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sdep-fake-timeout");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) throws Exception {
        Opts opt = parseOpts(args);
        Files.createDirectories(opt.out());

        System.out.printf(Locale.ROOT,
                "§6 sdep fake-edge discriminating sweep: manifest=%s timeoutSec=%d maxEvents=%d limit=%d%n",
                opt.manifest(), opt.timeoutSec(), opt.maxEvents(), opt.limit());

        List<String> rels = new ArrayList<>();
        for (String line : Files.readAllLines(opt.manifest())) {
            if (line.isBlank()) continue;
            rels.add(line.split(",", 2)[0]);
        }
        System.out.printf(Locale.ROOT, "manifest: %d files%n", rels.size());

        List<Row> rows = new ArrayList<>();
        int parsed = 0, skipped = 0, noFake = 0, fakeCarrying = 0;

        // Trace sink drained per file for fake-pattern attribution.
        List<DepDecision> decisions = new ArrayList<>();
        LitmusParser.DEP_TRACE = decisions::add;
        try {
            for (String rel : rels) {
                decisions.clear();
                LitmusCase lc;
                try {
                    lc = LitmusParser.parse(Files.readString(opt.corpusRoot().resolve(rel)), rel);
                } catch (Exception e) {
                    skipped++;
                    continue;
                }
                parsed++;

                List<KindedEdge> edges = lc.deps().allEdges();
                List<KindedEdge> fake = edges.stream().filter(e -> !e.edge().isSemantic()).toList();
                if (fake.isEmpty()) { noFake++; continue; }
                fakeCarrying++;
                if (opt.limit() >= 0 && fakeCarrying > opt.limit()) { fakeCarrying--; break; }

                Row row = analyse(lc, edges, fake, decisions, opt);
                rows.add(row);
                if (row.discriminates()) {
                    System.out.printf(Locale.ROOT,
                            "  DISCRIMINATES  %s  none=%s all=%s current=%s%n",
                            rel, row.none(), row.all(), row.current());
                } else if (fakeCarrying % 50 == 0) {
                    System.out.printf(Locale.ROOT, "  [%d] ... %s%n", fakeCarrying, rel);
                }
            }
        } finally {
            LitmusParser.DEP_TRACE = null;
            SCHED.shutdownNow();
        }

        System.out.printf(Locale.ROOT,
                "parsed=%d skipped=%d no-fake=%d  ->  fake-carrying=%d%n",
                parsed, skipped, noFake, rows.size());

        writeCsv(opt.out().resolve("sdep-discriminating-all-fake-tests.csv"), rows);
        String report = buildSummary(rows);
        Files.writeString(opt.out().resolve("sdep-fake-summary.md"), report);
        System.out.println();
        System.out.println(report);
        System.out.printf(Locale.ROOT, "%nWrote: %s%nWrote: %s%n",
                opt.out().resolve("sdep-discriminating-all-fake-tests.csv").toAbsolutePath(),
                opt.out().resolve("sdep-fake-summary.md").toAbsolutePath());
    }

    // ── Per-file analysis ────────────────────────────────────────────────────────

    private static Row analyse(LitmusCase lc, List<KindedEdge> edges, List<KindedEdge> fake,
                               List<DepDecision> decisions, Opts opt) {
        // Per-kind fake / semantic counts.
        Map<DepKind, int[]> byKind = new EnumMap<>(DepKind.class);   // [fake, semantic]
        for (DepKind k : DepKind.values()) byKind.put(k, new int[2]);
        for (KindedEdge ke : edges) byKind.get(ke.kind())[ke.edge().isSemantic() ? 1 : 0]++;

        // Fake-pattern attribution: producer read id -> the cancellation patterns the
        // detector matched for that read (fake decisions only), same scheme as
        // DetectorQualitySweep.
        Map<Integer, LinkedHashSet<String>> fakePat = new HashMap<>();
        for (DepDecision d : decisions) {
            if (d.real()) continue;
            for (Integer id : d.producerReadIds()) {
                fakePat.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(d.pattern());
            }
        }
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        for (KindedEdge ke : fake) {
            LinkedHashSet<String> p = fakePat.get(ke.edge().producer().getId());
            if (p != null) patterns.addAll(p); else patterns.add("UNKNOWN");
        }

        // Verdict-relevance: does any fake edge lie on a dep∪rf cycle?
        Set<Long> cyclic = candidateCycleEdges(lc);
        boolean fakeRelevant = fake.stream().anyMatch(ke ->
                cyclic.contains(edgeKey(ke.edge().producer().getId(), ke.edge().consumer().getId())));

        Verdict n, a, c;
        if (lc.es().getEvents().size() > opt.maxEvents()
                || lc.existsClause() == null || lc.existsClause().isBlank()) {
            n = a = c = Verdict.SKIPPED_LARGE;
        } else {
            n = solve(lc.es(), SdepConfig.NONE.transform(lc.deps()), opt);
            a = solve(lc.es(), SdepConfig.ALL_DEPS_SEMANTIC.transform(lc.deps()), opt);
            c = solve(lc.es(), SdepConfig.CURRENT.transform(lc.deps()), opt);
        }

        int[] d = byKind.get(DepKind.DATA), ad = byKind.get(DepKind.ADDR), ct = byKind.get(DepKind.CTRL);
        return new Row(lc.sourceName(), lc.arch().name(),
                d[0], ad[0], ct[0], d[1], ad[1], ct[1],
                String.join("+", patterns), fakeRelevant, n, a, c);
    }

    // ── dep∪rf cycle detection (Kosaraju SCC), as in DetectorQualitySweep ─────────

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

    // ── Solve (identical machinery to SdepDiscriminatingRun) ──────────────────────

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

    // ── Output ────────────────────────────────────────────────────────────────────

    private static void writeCsv(Path path, List<Row> rows) throws Exception {
        StringBuilder sb = new StringBuilder(
                "file,arch,fake_kinds,fake_patterns,fake_edges,semantic_edges,"
                + "data_fake,addr_fake,ctrl_fake,data_sem,addr_sem,ctrl_sem,"
                + "verdict_none,verdict_all_deps_semantic,verdict_current,"
                + "any_verdict_change,discriminating,fake_edge_verdict_relevant\n");
        for (Row r : rows) {
            sb.append('"').append(r.file()).append('"').append(',')
              .append(r.arch()).append(',')
              .append(r.fakeKinds()).append(',')
              .append('"').append(r.fakePatterns()).append('"').append(',')
              .append(r.fakeEdges()).append(',')
              .append(r.semanticEdges()).append(',')
              .append(r.dataFake()).append(',').append(r.addrFake()).append(',').append(r.ctrlFake()).append(',')
              .append(r.dataSem()).append(',').append(r.addrSem()).append(',').append(r.ctrlSem()).append(',')
              .append(r.none()).append(',').append(r.all()).append(',').append(r.current()).append(',')
              .append(r.anyVerdictChange()).append(',')
              .append(r.discriminates()).append(',')
              .append(r.fakeVerdictRelevant()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static String buildSummary(List<Row> rows) {
        long totalFakeEdges = rows.stream().mapToLong(Row::fakeEdges).sum();
        List<Row> disc = rows.stream().filter(Row::discriminates).toList();
        long verdictChanging = rows.stream().filter(Row::anyVerdictChange).count();

        // Breakdown by dependency type (fake edges per kind).
        long dataFake = rows.stream().mapToLong(Row::dataFake).sum();
        long addrFake = rows.stream().mapToLong(Row::addrFake).sum();
        long ctrlFake = rows.stream().mapToLong(Row::ctrlFake).sum();
        long dataFiles = rows.stream().filter(r -> r.dataFake() > 0).count();
        long addrFiles = rows.stream().filter(r -> r.addrFake() > 0).count();
        long ctrlFiles = rows.stream().filter(r -> r.ctrlFake() > 0).count();

        // Breakdown by fake-pattern kind (per test: count each pattern appearing).
        Map<String, Integer> patFiles = new TreeMap<>();
        for (Row r : rows) {
            for (String p : r.fakePatterns().split("\\+")) {
                if (!p.isEmpty()) patFiles.merge(p, 1, Integer::sum);
            }
        }
        // Breakdown by architecture.
        Map<String, Integer> archFiles = new TreeMap<>();
        for (Row r : rows) archFiles.merge(r.arch(), 1, Integer::sum);

        StringBuilder sb = new StringBuilder();
        sb.append("# §6 sdep fake-edge discriminating sweep — summary\n\n");
        sb.append("Corpus: `eval/corpus-weakest-manifest.csv` (2 998 supported-syntax tests). ")
          .append("A test is included iff the shipped detector classifies ≥1 dependency edge ")
          .append("as fake (`isSemantic=false`). Verdicts are WEAKEST under three sdep ")
          .append("configurations; **discriminating** = `none != current OR all-deps-semantic != current`.\n\n");

        sb.append("## Totals\n\n");
        sb.append("| metric | value |\n|---|---|\n");
        sb.append("| tests with fake dependencies | ").append(rows.size()).append(" |\n");
        sb.append("| total fake dependency edges | ").append(totalFakeEdges).append(" |\n");
        sb.append("| discriminating tests | ").append(disc.size()).append(" |\n");
        sb.append("| verdict-changing tests | ").append(verdictChanging).append(" |\n\n");

        sb.append("## Breakdown by dependency type (fake edges)\n\n");
        sb.append("| kind | fake edges | tests carrying |\n|---|---|---|\n");
        sb.append("| DATA | ").append(dataFake).append(" | ").append(dataFiles).append(" |\n");
        sb.append("| ADDR | ").append(addrFake).append(" | ").append(addrFiles).append(" |\n");
        sb.append("| CTRL | ").append(ctrlFake).append(" | ").append(ctrlFiles).append(" |\n\n");

        sb.append("## Breakdown by fake-pattern kind (tests matching)\n\n");
        sb.append("| pattern | tests |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : patFiles.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n## Breakdown by architecture\n\n");
        sb.append("| arch | tests |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : archFiles.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }

        sb.append("\n## Discriminating tests (verdicts + why the verdict changes)\n\n");
        if (disc.isEmpty()) {
            sb.append("_No fake-carrying test discriminates: for every such test the WEAKEST ")
              .append("verdict is identical under none / all-deps-semantic / current._\n");
        } else {
            sb.append("| test | arch | none | all-deps-semantic | current | why |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (Row r : disc) {
                sb.append("| `").append(r.file()).append("` | ").append(r.arch()).append(" | ")
                  .append(r.none()).append(" | ").append(r.all()).append(" | ").append(r.current())
                  .append(" | ").append(explain(r)).append(" |\n");
            }
        }
        return sb.toString();
    }

    /** One-sentence account of why a discriminating test's verdict moves across configs. */
    private static String explain(Row r) {
        String kinds = r.fakeKinds().isEmpty() ? "dependency" : r.fakeKinds();
        String pat = r.fakePatterns().isEmpty() ? "identity" : r.fakePatterns();
        String rel = r.fakeVerdictRelevant()
                ? "on a dep∪rf (load-buffering) cycle"
                : "off any dep∪rf cycle";
        boolean allDiffers = r.all() != r.current();
        boolean noneDiffers = r.none() != r.current();
        if (allDiffers && !noneDiffers) {
            return "treating the fake " + pat + " " + kinds + " edge (" + rel
                    + ") as semantic under all-deps-semantic adds a jf∪sdep ordering that closes a "
                    + "thin-air cycle, flipping " + r.current() + "→" + r.all()
                    + "; current keeps it fake so the outcome stays " + r.current() + ".";
        }
        if (noneDiffers && !allDiffers) {
            return "the test also carries a real semantic dependency that current keeps in sdep "
                    + "(verdict " + r.current() + "); stripping all deps under none removes that "
                    + "ordering and flips the outcome to " + r.none() + ".";
        }
        // Both differ from current.
        return "current (" + r.current() + ") sits between none (" + r.none() + ") and "
                + "all-deps-semantic (" + r.all() + "): the fake " + pat + " " + kinds + " edge ("
                + rel + ") is decisive — excluding vs. including it in sdep changes which "
                + "jf∪sdep cycles exist, and current's stratification lands on a distinct verdict.";
    }

    private static Opts parseOpts(String[] args) {
        int timeoutSec = 30, maxEvents = 64, limit = -1;
        Path manifest = Path.of("eval", "corpus-weakest-manifest.csv");
        Path corpusRoot = Path.of("eval", "corpus");
        Path out = Path.of("eval");
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "timeoutsec" -> timeoutSec = Integer.parseInt(v);
                case "maxevents" -> maxEvents = Integer.parseInt(v);
                case "limit" -> limit = Integer.parseInt(v);
                case "manifest" -> manifest = Path.of(v);
                case "corpusroot" -> corpusRoot = Path.of(v);
                case "out" -> out = Path.of(v);
                default -> System.err.println("[opt] ignoring unknown option: " + a);
            }
        }
        return new Opts(manifest, corpusRoot, out, timeoutSec, maxEvents, limit);
    }
}
