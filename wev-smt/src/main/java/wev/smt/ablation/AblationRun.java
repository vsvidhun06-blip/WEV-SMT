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
import wev.smt.EventStructureEncoder;
import wev.smt.LitmusCorpus;
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * §6 sdep ablation driver (paper risk #3). Runs the WEAKEST checker under the three
 * {@link SdepConfig} configurations across four test groups plus the two Batty et al.
 * §4 control examples, and emits a raw CSV ({@code ablation-raw.csv}) plus a console
 * summary. The narrative {@code ablation_results.md} is authored from these numbers.
 *
 * <p>Every verdict uses the <em>frozen</em> encoder + {@code AxiomaticConsistency}; the
 * only knob is the {@link DependencyInfo} re-flagging done by {@link SdepConfig#transform}.
 * Each solve runs in its own {@link SolverContext} (so per-event SMT var names never
 * collide across cases/configs) under a per-solve {@code timeoutSec} shutdown, matching
 * the existing {@code CorpusValidation} bounds ({@code perFileSec=30}, {@code maxEvents=64}).
 *
 * <p>Args (all optional, key=value):
 * {@code slice=200} corpus slice size, {@code seed=42} shuffle seed,
 * {@code manifest=eval/corpus-weakest-manifest.csv}, {@code corpusRoot=eval/corpus},
 * {@code examples=eval/examples/paper}, {@code out=.} output dir, {@code timeoutSec=30},
 * {@code maxEvents=64}, {@code configs=none,all-deps-semantic,current}.
 */
public final class AblationRun {

    private AblationRun() { }

    enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT, ERROR }

    /** One solved cell: (config, group, case/file) -> verdict, with timing + dep stats. */
    private record Result(SdepConfig config, String group, String name, Verdict verdict,
                          long ms, int semanticEdges, int totalDeps, String note) { }

    private record Opts(int slice, long seed, Path manifest, Path corpusRoot,
                        Path examples, Path out, int timeoutSec, int maxEvents,
                        List<SdepConfig> configs) { }

    // The four ablation groups, by corpus-case name (from LitmusCorpus.classics()).
    private static final List<String> LB_FAKE_CASES = List.of("LBdep-fake", "3.LBdep-fake");
    private static final List<String> LB_REAL_CASES = List.of("LBdep-real", "LBdep-addr", "3.LBdep-real");
    private static final List<String> THREE_THREAD_CASES = List.of("ISA2", "WRC", "IRIW");

    // Paper §4 worked-example files (this branch's eval/examples/paper/ triplet),
    // plus the Leaky-Semicolon LBfd fake linear-arithmetic dep (a+1-a).
    private static final List<String> LB_FAKE_FILES =
            List.of("LB-fake-xor-cycle.litmus", "LB-fake-xor.litmus", "LBfd.litmus");
    private static final List<String> LB_REAL_FILES = List.of("LB-real.litmus");

    // Batty et al. §4 control examples (new .litmus added for this ablation).
    private static final List<String> BATTY_FILES =
            List.of("LB+ctrldata+ctrl-double.litmus", "LB+ctrldata+ctrl-single.litmus");

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ablation-timeout");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) throws Exception {
        Opts opt = parseOpts(args);
        Files.createDirectories(opt.out());

        System.out.printf(Locale.ROOT,
                "§6 sdep ablation: configs=%s slice=%d seed=%d timeoutSec=%d maxEvents=%d%n",
                opt.configs(), opt.slice(), opt.seed(), opt.timeoutSec(), opt.maxEvents());

        List<LitmusCorpus.LitmusCase> classics = LitmusCorpus.classics();
        Map<String, LitmusCorpus.LitmusCase> byName = new LinkedHashMap<>();
        for (LitmusCorpus.LitmusCase lc : classics) byName.put(lc.name(), lc);

        // Pre-parse the curated .litmus files once (es + deps reused across configs).
        Map<String, ParsedFile> paperFiles = parseFiles(opt.examples(),
                concat(LB_FAKE_FILES, LB_REAL_FILES, BATTY_FILES));

        // Pre-parse the seeded corpus slice once.
        List<String> sliceRel = pickSlice(opt);
        List<ParsedFile> slice = new ArrayList<>();
        int sliceParseErrors = 0;
        for (String rel : sliceRel) {
            ParsedFile pf = parseCorpusFile(opt.corpusRoot(), rel, opt.maxEvents());
            slice.add(pf);
            if (pf.parseError() != null) sliceParseErrors++;
        }
        System.out.printf(Locale.ROOT, "corpus slice: %d files, %d parse/skip errors%n",
                slice.size(), sliceParseErrors);

        List<Result> results = new ArrayList<>();
        for (SdepConfig cfg : opt.configs()) {
            System.out.printf(Locale.ROOT, "%n=== config=%s ===%n", cfg.token());

            // Group 1: LB-fake (corpus cases + paper files).
            for (String n : LB_FAKE_CASES) {
                results.add(solveCase("LB-fake", n, byName.get(n), cfg, opt));
            }
            for (String f : LB_FAKE_FILES) {
                results.add(solveParsed("LB-fake", f, paperFiles.get(f), cfg, opt));
            }
            // Group 2: LB-real (corpus cases + paper file).
            for (String n : LB_REAL_CASES) {
                results.add(solveCase("LB-real", n, byName.get(n), cfg, opt));
            }
            for (String f : LB_REAL_FILES) {
                results.add(solveParsed("LB-real", f, paperFiles.get(f), cfg, opt));
            }
            // Group 3: 3-thread variants (no dependency annotations in the corpus).
            for (String n : THREE_THREAD_CASES) {
                results.add(solveCase("3-thread", n, byName.get(n), cfg, opt));
            }
            // Group 4: corpus slice.
            for (ParsedFile pf : slice) {
                results.add(solveParsed("corpus-slice", pf.rel(), pf, cfg, opt));
            }
            // Batty control examples (reported separately).
            for (String f : BATTY_FILES) {
                results.add(solveParsed("batty", f, paperFiles.get(f), cfg, opt));
            }
        }

        SCHED.shutdownNow();
        writeCsv(opt.out().resolve("ablation-raw.csv"), results, sliceRel, opt);
        printSummary(results, opt);
        System.out.printf(Locale.ROOT, "%nRaw CSV: %s%n",
                opt.out().resolve("ablation-raw.csv").toAbsolutePath());
    }

    // ── Solving ────────────────────────────────────────────────────────────────

    private static Result solveCase(String group, String name, LitmusCorpus.LitmusCase lc,
                                    SdepConfig cfg, Opts opt) {
        if (lc == null) {
            return new Result(cfg, group, name, Verdict.ERROR, 0, 0, 0, "case-not-found");
        }
        DependencyInfo deps = cfg.transform(lc.deps());
        return solve(group, name, lc.es(), deps, cfg, opt, null);
    }

    private static Result solveParsed(String group, String name, ParsedFile pf,
                                      SdepConfig cfg, Opts opt) {
        if (pf == null) {
            return new Result(cfg, group, name, Verdict.ERROR, 0, 0, 0, "file-not-found");
        }
        if (pf.parseError() != null) {
            return new Result(cfg, group, name, Verdict.ERROR, 0, 0, 0, pf.parseError());
        }
        DependencyInfo deps = cfg.transform(pf.deps());
        return solve(group, name, pf.es(), deps, cfg, opt, null);
    }

    /** WEAKEST verdict for one (es, deps) in a fresh, timeout-bounded context. */
    private static Result solve(String group, String name, EventStructure es,
                                DependencyInfo deps, SdepConfig cfg, Opts opt, String unused) {
        int semantic = deps.semanticEdges().size();
        int total = totalDeps(deps, es);
        long t0 = System.currentTimeMillis();
        ShutdownManager sm = ShutdownManager.create();
        ScheduledFuture<?> to = SCHED.schedule(
                () -> sm.requestShutdown("ablation per-solve timeout " + opt.timeoutSec() + "s"),
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
                long ms = System.currentTimeMillis() - t0;
                Verdict v = unsat ? Verdict.FORBIDDEN : Verdict.ALLOWED;
                return new Result(cfg, group, name, v, ms, semantic, total, "");
            }
        } catch (InterruptedException ie) {
            return new Result(cfg, group, name, Verdict.TIMEOUT,
                    System.currentTimeMillis() - t0, semantic, total,
                    "timeout-" + opt.timeoutSec() + "s");
        } catch (Throwable ex) {
            boolean interrupted = ex instanceof InterruptedException
                    || (ex.getCause() instanceof InterruptedException);
            return new Result(cfg, group, name,
                    interrupted ? Verdict.TIMEOUT : Verdict.ERROR,
                    System.currentTimeMillis() - t0, semantic, total,
                    clean(ex.toString()));
        } finally {
            to.cancel(false);
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────

    private record ParsedFile(String rel, EventStructure es, DependencyInfo deps,
                              String parseError) { }

    private static Map<String, ParsedFile> parseFiles(Path dir, List<String> names) {
        Map<String, ParsedFile> out = new LinkedHashMap<>();
        for (String n : names) {
            Path f = dir.resolve(n);
            try {
                var lc = LitmusParser.parse(Files.readString(f), n);
                out.put(n, new ParsedFile(n, lc.es(), lc.deps(), null));
            } catch (Exception e) {
                out.put(n, new ParsedFile(n, null, null, clean(e.toString())));
            }
        }
        return out;
    }

    private static ParsedFile parseCorpusFile(Path root, String rel, int maxEvents) {
        Path f = root.resolve(rel);
        try {
            var lc = LitmusParser.parse(Files.readString(f), rel);
            if (lc.existsClause() == null || lc.existsClause().isBlank()) {
                return new ParsedFile(rel, null, null, "no-exists-clause");
            }
            if (lc.es().getEvents().size() > maxEvents) {
                return new ParsedFile(rel, null, null,
                        "too-large:" + lc.es().getEvents().size());
            }
            return new ParsedFile(rel, lc.es(), lc.deps(), null);
        } catch (Exception e) {
            return new ParsedFile(rel, null, null, clean(e.toString()));
        }
    }

    /** Read the manifest, seeded-shuffle, take {@code slice} relative paths. */
    private static List<String> pickSlice(Opts opt) throws Exception {
        List<String> all = new ArrayList<>();
        for (String line : Files.readAllLines(opt.manifest())) {
            if (line.isBlank()) continue;
            // manifest line: "<relpath>,<baselineVerdict>"
            all.add(line.split(",", 2)[0]);
        }
        Collections.shuffle(all, new Random(opt.seed()));
        return all.subList(0, Math.min(opt.slice(), all.size()));
    }

    // ── Output ─────────────────────────────────────────────────────────────────

    private static void writeCsv(Path path, List<Result> rows, List<String> sliceRel, Opts opt)
            throws Exception {
        StringBuilder sb = new StringBuilder(
                "config,group,name,verdict,ms,semanticEdges,totalDeps,note\n");
        for (Result r : rows) {
            sb.append(r.config().token()).append(',')
              .append(r.group()).append(',')
              .append('"').append(r.name()).append('"').append(',')
              .append(r.verdict()).append(',')
              .append(r.ms()).append(',')
              .append(r.semanticEdges()).append(',')
              .append(r.totalDeps()).append(',')
              .append(r.note()).append('\n');
        }
        Files.writeString(path, sb.toString());
        // Record the exact seeded slice for reproducibility.
        StringBuilder s2 = new StringBuilder("# seed=" + opt.seed()
                + " slice=" + opt.slice() + "\n");
        for (String rel : sliceRel) s2.append(rel).append('\n');
        Files.writeString(path.resolveSibling("ablation-slice.txt"), s2.toString());
    }

    private static void printSummary(List<Result> rows, Opts opt) {
        List<String> groups = List.of("LB-fake", "LB-real", "3-thread", "corpus-slice", "batty");
        for (SdepConfig cfg : opt.configs()) {
            System.out.printf(Locale.ROOT, "%n--- config=%s : (ALLOWED,FORBIDDEN,TIMEOUT,ERROR) ms ---%n",
                    cfg.token());
            for (String g : groups) {
                int[] c = new int[4];
                long ms = 0;
                for (Result r : rows) {
                    if (r.config() == cfg && r.group().equals(g)) {
                        c[r.verdict().ordinal()]++;
                        ms += r.ms();
                    }
                }
                System.out.printf(Locale.ROOT, "  %-13s (%d,%d,%d,%d)  %d ms%n",
                        g, c[0], c[1], c[2], c[3], ms);
            }
        }
        // Per-case detail for the curated groups + batty (small, individually meaningful).
        System.out.println("\n--- per-case detail (curated groups + batty) ---");
        for (Result r : rows) {
            if (r.group().equals("corpus-slice")) continue;
            System.out.printf(Locale.ROOT, "  %-16s %-22s %-9s %-10s sem=%d tot=%d %s%n",
                    r.config().token(), r.name(), r.group(), r.verdict(),
                    r.semanticEdges(), r.totalDeps(), r.note());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static int totalDeps(DependencyInfo deps, EventStructure es) {
        int n = 0;
        for (var e : es.getEvents()) {
            n += deps.getDataDeps(e).size() + deps.getAddrDeps(e).size()
                    + deps.getCtrlDeps(e).size();
        }
        return n;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) out.addAll(l);
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replace(',', ';').replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    private static Opts parseOpts(String[] args) {
        int slice = 200, timeoutSec = 30, maxEvents = 64;
        long seed = 42;
        Path manifest = Path.of("eval", "corpus-weakest-manifest.csv");
        Path corpusRoot = Path.of("eval", "corpus");
        Path examples = Path.of("eval", "examples", "paper");
        Path out = Path.of(".");
        List<SdepConfig> configs = List.of(
                SdepConfig.NONE, SdepConfig.ALL_DEPS_SEMANTIC, SdepConfig.CURRENT);
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "slice" -> slice = Integer.parseInt(v);
                case "seed" -> seed = Long.parseLong(v);
                case "timeoutsec" -> timeoutSec = Integer.parseInt(v);
                case "maxevents" -> maxEvents = Integer.parseInt(v);
                case "manifest" -> manifest = Path.of(v);
                case "corpusroot" -> corpusRoot = Path.of(v);
                case "examples" -> examples = Path.of(v);
                case "out" -> out = Path.of(v);
                case "configs" -> {
                    List<SdepConfig> cs = new ArrayList<>();
                    for (String tok : v.split(",")) cs.add(SdepConfig.parse(tok));
                    configs = cs;
                }
                default -> System.err.println("[opt] ignoring unknown option: " + a);
            }
        }
        return new Opts(slice, seed, manifest, corpusRoot, examples, out,
                timeoutSec, maxEvents, configs);
    }
}
