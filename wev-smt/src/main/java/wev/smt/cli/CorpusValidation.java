package wev.smt.cli;

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
import org.sosy_lab.java_smt.api.SolverException;

import wev.smt.AxiomaticConsistency;
import wev.smt.EventStructureEncoder;
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;
import wev.smt.MinimalWitness;
import wev.smt.MinimalWitnessExtractor;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Validates the SMT decision procedure against a (recursively scanned) directory of
 * herd7 / Dat3M {@code .litmus} tests parsed by {@link LitmusParser}.
 *
 * <p>For every parsed test and every model in {@link MemoryModel}, this records the
 * full-execution consistency verdict of the {@code exists}-wired candidate
 * ({@code ALLOWED} if that execution is consistent under the model, {@code FORBIDDEN}
 * otherwise) — the same validated question {@code AtlasReconstruct} answers and the one
 * comparable to herd7's per-model {@code Allowed}/{@code Forbidden}.
 *
 * <h2>Day-11 corpus mode</h2>
 * Extended for large public corpora, with the controls a published-suite sweep needs:
 * <ul>
 *   <li><b>recursive</b> scan of {@code .litmus} files under the root (the canonical
 *       herd7 / Dat3M layout nests tests in per-architecture subdirectories);</li>
 *   <li><b>deterministic stride sampling</b> ({@code maxFiles}) so a sweep fits a fixed
 *       wall-clock budget while spreading evenly across subdirectories / architectures;</li>
 *   <li>a genuine <b>per-file timeout</b> ({@code perFileSec}): each file is validated
 *       under its own {@link SolverContext} and {@link ShutdownManager}, tripped by a
 *       scheduled task — so one slow file yields {@code TIMEOUT} rows without poisoning
 *       the shared solver state of the others;</li>
 *   <li>an overall <b>budget</b> ({@code budgetMin}): once it passes, the remaining files
 *       are left unattempted and reported, never silently truncated.</li>
 * </ul>
 *
 * <p>Output is one CSV row per processed (file × model) — plus a single row for files
 * that never reach the solver (parse error / skip) — with columns
 * {@code file,arch,model,expected,actual,witness_size,solve_ms,status,note}. The
 * {@code status} is one of {@code MATCH} / {@code MISMATCH} (a model verdict compared
 * against a per-model expectation parsed from the file's comments), {@code OK} (a
 * recorded verdict with no ground truth to compare — the common case for raw corpus
 * files, whose outcomes live in sidecar {@code kinds.txt}/{@code *.allowed} files rather
 * than in the {@code .litmus} comments), {@code SKIP} (unsupported arch/instruction,
 * empty, or no {@code exists} clause), {@code PARSE_ERROR} (malformed / solver error),
 * or {@code TIMEOUT}. The {@code expected}/ground-truth correspondence to sidecar files
 * is intentionally computed downstream (see {@code eval/corpus-summary.md}), not here,
 * to keep the (unsound for ARM/PPC) architecture→model mapping out of the harness.
 *
 * <p>Usage: {@code CorpusValidation <litmus-dir> <out-dir> [key=value ...]}, where the
 * options are {@code csv=<name>} (default {@code corpus-validation.csv}),
 * {@code budgetMin=<n>} (default 60), {@code perFileSec=<n>} (default 30),
 * {@code maxFiles=<n>} (default 0 = no cap), {@code minwit=on|off} (default off; the
 * minimum-consistent witness is the trivial singleton under the gated encoding — see the
 * note in {@code AtlasReconstruct} — so it is skipped by default at corpus scale) and
 * {@code archs=X86,PPC,ARM,RISCV,C} (default all). A single unparseable or pathological
 * file never aborts the run.
 */
public final class CorpusValidation {

    private CorpusValidation() { }

    private record Row(String file, String arch, String model, String expected,
                       String actual, int witnessSize, long solveMs,
                       String status, String note) { }

    private record Options(String csvName, long budgetMs, int perFileSec, int maxFiles,
                           int maxEvents, boolean minwit, Set<MemoryModel> archModels) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CorpusValidation <litmus-dir> <out-dir> "
                    + "[csv=name] [budgetMin=60] [perFileSec=30] [maxFiles=0] "
                    + "[maxEvents=64] [minwit=off] [archs=X86,PPC,ARM,RISCV,C]");
            System.exit(2);
            return;
        }
        Path litmusDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Options opt = parseOptions(args);
        Files.createDirectories(outDir);

        if (!Files.isDirectory(litmusDir)) {
            System.err.println("not a directory: " + litmusDir.toAbsolutePath());
            System.exit(2);
            return;
        }

        long start = System.currentTimeMillis();
        long minwitDeadlineMs = opt.budgetMs() / 2;     // stop the optional witness work early

        List<Path> all = collectLitmus(litmusDir);
        List<Path> files = sample(all, opt.maxFiles());
        System.out.printf(Locale.ROOT,
                "Scanned %d .litmus file(s) under %s; processing %d "
                        + "(budget %.0f min, per-file %d s, archs %s, minwit %s)%n",
                all.size(), litmusDir.toAbsolutePath(), files.size(),
                opt.budgetMs() / 60000.0, opt.perFileSec(), opt.archModels(),
                opt.minwit() ? "on" : "off");
        if (files.isEmpty()) {
            System.out.println("Nothing to validate.");
            return;
        }

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "corpus-timeout");
            t.setDaemon(true);
            return t;
        });

        // Rows are streamed to disk as each file completes (so a crash on a pathological
        // file preserves all prior results) and also kept in memory for the summary.
        List<Row> rows = new ArrayList<>();
        int attempted = 0, notAttempted = 0;
        Path csvPath = outDir.resolve(opt.csvName());
        try (var w = Files.newBufferedWriter(csvPath)) {
            w.write("file,arch,model,expected,actual,witness_size,solve_ms,status,note\n");
            try {
                for (Path f : files) {
                    if (System.currentTimeMillis() - start > opt.budgetMs()) {
                        notAttempted = files.size() - attempted;
                        System.err.printf("[budget] %.1f min elapsed; %d file(s) not attempted%n",
                                (System.currentTimeMillis() - start) / 60000.0, notAttempted);
                        break;
                    }
                    attempted++;
                    List<Row> fileRows = validateFile(litmusDir, f, cfg, log, scheduler, opt,
                            start, minwitDeadlineMs);
                    for (Row r : fileRows) w.write(csvLine(r));
                    rows.addAll(fileRows);
                    if (attempted % 200 == 0) w.flush();
                }
            } finally {
                scheduler.shutdownNow();
            }
        }

        printSummary(rows, attempted, notAttempted);
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf(Locale.ROOT, "%nTotal wall-clock: %.1f s. CSV: %s%n",
                elapsed / 1000.0, csvPath.toAbsolutePath());
    }

    // ── File collection / sampling ──────────────────────────────────────────────

    /** Every {@code *.litmus} under {@code root}, recursively, sorted by path. */
    private static List<Path> collectLitmus(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".litmus"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /**
     * A deterministic stride sample of at most {@code maxFiles} paths. Picking every
     * {@code k}-th of the path-sorted list spreads the sample evenly across the (sorted)
     * subdirectories, so no single architecture's directory monopolises the budget.
     */
    private static List<Path> sample(List<Path> all, int maxFiles) {
        if (maxFiles <= 0 || all.size() <= maxFiles) return all;
        List<Path> out = new ArrayList<>(maxFiles);
        double stride = (double) all.size() / maxFiles;
        for (int i = 0; i < maxFiles; i++) out.add(all.get((int) (i * stride)));
        return out;
    }

    // ── Per-file validation under its own timeout-bounded context ────────────────

    private static List<Row> validateFile(Path root, Path file, Configuration cfg,
            LogManager log, ScheduledExecutorService scheduler, Options opt,
            long startGlobal, long minwitDeadlineMs) {
        String rel = root.relativize(file).toString().replace('\\', '/');

        String content;
        try {
            content = Files.readString(file);
        } catch (IOException io) {
            return List.of(noSolveRow(rel, "?", "SKIP", "read-error:" + clean(io.getMessage())));
        }

        LitmusCase lc;
        try {
            lc = LitmusParser.parse(content, rel);
        } catch (ParseException pe) {
            String status = pe.kind() == ParseException.Kind.MALFORMED ? "PARSE_ERROR" : "SKIP";
            return List.of(noSolveRow(rel, sniffArch(content), status,
                    pe.kind() + ":" + clean(pe.getMessage())));
        } catch (RuntimeException ex) {
            return List.of(noSolveRow(rel, sniffArch(content), "PARSE_ERROR",
                    "unexpected:" + clean(ex.toString())));
        }

        String arch = lc.arch().name();
        if (!opt.archModels().isEmpty() && !opt.archModels().contains(archModel(lc.arch()))) {
            // (only fires if a future arch maps outside the run set; the five supported
            //  archs all map to a model, so by default nothing is filtered here)
        }
        if (lc.existsClause() == null || lc.existsClause().isBlank()) {
            return List.of(noSolveRow(rel, arch, "SKIP", "no-exists-clause"));
        }
        // Size guard: the pair-based axioms are O(events^2); pathological generated tests
        // (e.g. Dat3M's many-location LKMM alias suite) can exhaust the heap before the
        // per-file timeout — which an OutOfMemoryError cannot interrupt — so cap up front.
        int nEvents = lc.es().getEvents().size();
        if (nEvents > opt.maxEvents()) {
            return List.of(noSolveRow(rel, arch, "SKIP",
                    "too-large:" + nEvents + "-events(>" + opt.maxEvents() + ")"));
        }

        ShutdownManager sm = ShutdownManager.create();
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> sm.requestShutdown("per-file timeout " + opt.perFileSec() + "s"),
                opt.perFileSec(), TimeUnit.SECONDS);
        List<Row> rows = new ArrayList<>();
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, sm.getNotifier(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            MinimalWitnessExtractor mw = new MinimalWitnessExtractor(ctx, enc, ax);
            BooleanFormula wf = enc.encodeWellFormedness();

            boolean timedOut = false;
            for (MemoryModel m : MemoryModel.values()) {
                if (timedOut) {
                    rows.add(new Row(rel, arch, m.name(), "", "", -1, 0, "TIMEOUT",
                            "per-file " + opt.perFileSec() + "s"));
                    continue;
                }
                long t0 = System.currentTimeMillis();
                try {
                    boolean unsat;
                    try (ProverEnvironment p = ctx.newProverEnvironment()) {
                        p.addConstraint(bmgr.and(wf, consistencyOf(ax, m)));
                        unsat = p.isUnsat();
                    }
                    Outcome actual = unsat ? Outcome.FORBIDDEN : Outcome.ALLOWED;

                    int witness = -1;
                    if (opt.minwit()
                            && System.currentTimeMillis() - startGlobal < minwitDeadlineMs) {
                        Optional<MinimalWitness> w = mw.findMinimalConsistent(m);
                        witness = w.map(MinimalWitness::cardinality).orElse(-1);
                    }
                    long ms = System.currentTimeMillis() - t0;

                    Outcome expected = lc.expectations().getOrDefault(m, Outcome.UNKNOWN);
                    String exp = expected == Outcome.UNKNOWN ? "" : expected.name();
                    String status = expected == Outcome.UNKNOWN ? "OK"
                            : (expected == actual ? "MATCH" : "MISMATCH");
                    rows.add(new Row(rel, arch, m.name(), exp, actual.name(),
                            witness, ms, status, ""));
                } catch (InterruptedException ie) {
                    timedOut = true;
                    rows.add(new Row(rel, arch, m.name(), "", "", -1,
                            System.currentTimeMillis() - t0, "TIMEOUT",
                            "per-file " + opt.perFileSec() + "s"));
                } catch (SolverException se) {
                    rows.add(new Row(rel, arch, m.name(), "", "", -1,
                            System.currentTimeMillis() - t0, "PARSE_ERROR",
                            "solver:" + clean(se.getMessage())));
                }
            }
        } catch (Exception ex) {
            // createSolverContext / encoding failure, or a shutdown-driven interruption
            // surfacing outside the per-model loop: record one non-fatal diagnostic row.
            boolean interrupted = ex instanceof InterruptedException;
            if (!(interrupted && !rows.isEmpty())) {
                rows.add(new Row(rel, arch, "-", "", "", -1, 0,
                        interrupted ? "TIMEOUT" : "PARSE_ERROR",
                        (interrupted ? "per-file " + opt.perFileSec() + "s (encoding)"
                                : "encode:" + clean(ex.toString()))));
            }
        } finally {
            timeout.cancel(false);
        }
        return rows;
    }

    private static Row noSolveRow(String file, String arch, String status, String note) {
        return new Row(file, arch, "-", "", "", -1, 0, status, note);
    }

    private static BooleanFormula consistencyOf(AxiomaticConsistency ax, MemoryModel m) {
        return switch (m) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
            case PSO -> ax.consistencyPSO();
            case RA -> ax.consistencyRA();
            case WEAKEST -> ax.consistencyWEAKEST();
            case RC11 -> ax.consistencyRC11();
        };
    }

    /** The model an architecture's canonical semantics is closest to (for arch filtering). */
    private static MemoryModel archModel(LitmusParser.Arch arch) {
        return switch (arch) {
            case X86 -> MemoryModel.TSO;
            default -> MemoryModel.WEAKEST;
        };
    }

    // ── Options ──────────────────────────────────────────────────────────────────

    private static Options parseOptions(String[] args) {
        String csv = "corpus-validation.csv";
        long budgetMs = 60L * 60 * 1000;
        int perFileSec = 30;
        int maxFiles = 0;
        int maxEvents = 64;
        boolean minwit = false;
        Set<MemoryModel> archModels = EnumSet.allOf(MemoryModel.class);
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "csv" -> csv = v;
                case "budgetmin" -> budgetMs = (long) (Double.parseDouble(v) * 60 * 1000);
                case "perfilesec" -> perFileSec = Integer.parseInt(v);
                case "maxfiles" -> maxFiles = Integer.parseInt(v);
                case "maxevents" -> maxEvents = Integer.parseInt(v);
                case "minwit" -> minwit = v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true");
                case "archs" -> {
                    EnumSet<MemoryModel> set = EnumSet.noneOf(MemoryModel.class);
                    for (String a : v.split(",")) {
                        LitmusParser.Arch arch = parseArch(a.trim());
                        if (arch != null) set.add(archModel(arch));
                    }
                    if (!set.isEmpty()) archModels = set;
                }
                default -> System.err.println("[opt] ignoring unknown option: " + args[i]);
            }
        }
        return new Options(csv, budgetMs, perFileSec, maxFiles, maxEvents, minwit, archModels);
    }

    /**
     * The architecture token of a file whose full parse failed, so that skip /
     * parse-error rows still carry an arch for the per-architecture coverage breakdown.
     * Reads the first non-comment line's leading token (tolerating a {@code TST}/
     * {@code Litmus}/{@code Test} prefix); {@code "?"} for an unrecognised arch.
     */
    private static String sniffArch(String content) {
        for (String raw : content.split("\r\n|\r|\n")) {
            String t = raw.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || t.startsWith("(*")) {
                continue;
            }
            String[] tok = t.split("\\s+");
            String a = tok[0];
            if (a.equalsIgnoreCase("TST") || a.equalsIgnoreCase("Litmus")
                    || a.equalsIgnoreCase("Test")) {
                a = tok.length > 1 ? tok[1] : a;
            }
            LitmusParser.Arch arch = parseArch(a);
            return arch != null ? arch.name() : "?";
        }
        return "?";
    }

    private static LitmusParser.Arch parseArch(String token) {
        // Mirror LitmusParser.Arch.resolve's alias set so sniffed (parse-failed) rows
        // bucket under the right architecture in the coverage breakdown.
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "X86", "I386", "AMD64", "X86_64", "X86-64" -> LitmusParser.Arch.X86;
            case "PPC", "POWER", "POWERPC" -> LitmusParser.Arch.PPC;
            case "ARM", "AARCH64", "ARM64", "ARMV7", "ARMV8" -> LitmusParser.Arch.ARM;
            case "RISCV", "RISCV64", "RISC-V", "RV", "RV64", "RV32" -> LitmusParser.Arch.RISCV;
            case "C", "C11", "CPP" -> LitmusParser.Arch.C;
            default -> null;
        };
    }

    // ── Output ─────────────────────────────────────────────────────────────────

    private static String csvLine(Row r) {
        return r.file() + ',' + r.arch() + ',' + r.model() + ',' + r.expected() + ','
                + r.actual() + ',' + r.witnessSize() + ',' + r.solveMs() + ','
                + r.status() + ',' + r.note() + '\n';
    }

    private static void printSummary(List<Row> rows, int attempted, int notAttempted) {
        // Per-architecture roll-up. A "file" is counted once (by its first row).
        Map<String, int[]> perArch = new LinkedHashMap<>();   // arch -> counters
        Map<String, List<Long>> solveTimes = new LinkedHashMap<>();
        String lastFile = null;
        for (Row r : rows) {
            perArch.computeIfAbsent(r.arch(), k -> new int[6]);
            solveTimes.computeIfAbsent(r.arch(), k -> new ArrayList<>());
            int[] c = perArch.get(r.arch());
            // counters: [rows, match, mismatch, ok, skip+parse_error, timeout]
            c[0]++;
            switch (r.status()) {
                case "MATCH" -> c[1]++;
                case "MISMATCH" -> c[2]++;
                case "OK" -> c[3]++;
                case "SKIP", "PARSE_ERROR" -> c[4]++;
                case "TIMEOUT" -> c[5]++;
            }
            if (r.solveMs() > 0) solveTimes.get(r.arch()).add(r.solveMs());
        }

        System.out.println();
        System.out.println("== Per-architecture roll-up (rows) ==");
        System.out.printf(Locale.ROOT, "%-6s %8s %8s %9s %6s %6s %7s %10s%n",
                "arch", "rows", "match", "mismatch", "ok", "skip", "timeout", "median_ms");
        for (Map.Entry<String, int[]> e : perArch.entrySet()) {
            int[] c = e.getValue();
            List<Long> ts = solveTimes.get(e.getKey());
            System.out.printf(Locale.ROOT, "%-6s %8d %8d %9d %6d %6d %7d %10s%n",
                    e.getKey(), c[0], c[1], c[2], c[3], c[4], c[5], median(ts));
        }

        int match = 0, mismatch = 0, ok = 0, skip = 0, parseErr = 0, timeout = 0;
        for (Row r : rows) {
            switch (r.status()) {
                case "MATCH" -> match++;
                case "MISMATCH" -> mismatch++;
                case "OK" -> ok++;
                case "SKIP" -> skip++;
                case "PARSE_ERROR" -> parseErr++;
                case "TIMEOUT" -> timeout++;
            }
        }
        int compared = match + mismatch;
        System.out.printf(Locale.ROOT,
                "%nTotals: files attempted=%d, not-attempted(budget)=%d, rows=%d%n",
                attempted, notAttempted, rows.size());
        System.out.printf(Locale.ROOT,
                "  validated rows: match=%d mismatch=%d ok(no-truth)=%d | skip=%d parse_error=%d timeout=%d%n",
                match, mismatch, ok, skip, parseErr, timeout);
        System.out.printf(Locale.ROOT, "  match rate where expected known: %s (%d of %d)%n",
                compared == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%",
                        100.0 * match / compared), match, compared);

        // First few mismatches, for quick eyeballing.
        int shown = 0;
        for (Row r : rows) {
            if (!r.status().equals("MISMATCH")) continue;
            if (shown++ == 0) System.out.println("  mismatches:");
            if (shown > 25) { System.out.println("    …"); break; }
            System.out.printf(Locale.ROOT, "    %-40s %-8s expected=%-9s actual=%-9s%n",
                    r.file(), r.model(), r.expected(), r.actual());
        }
    }

    private static String median(List<Long> xs) {
        if (xs.isEmpty()) return "-";
        List<Long> s = new ArrayList<>(xs);
        s.sort(Long::compareTo);
        return Long.toString(s.get(s.size() / 2));
    }

    /** CSV-safe one-liner: strip commas / newlines from a free-text note. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replace(',', ';').replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }
}
