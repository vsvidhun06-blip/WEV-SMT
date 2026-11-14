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
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Two opt-in options leave the default behaviour above unchanged unless given:
 * {@code mode=wired|symbolic} (default wired; symbolic applies
 * {@link EventStructureEncoder#enableSymbolic} — free rf/co, value propagation, pinned
 * outcome reads and memory-final values — identically for every model) and
 * {@code manifest=<csv>}, which replaces the directory scan by exactly the files the manifest
 * lists, each verified against its SHA-256 before anything is solved (see the
 * explicit-manifest section below for its output format).
 */
public final class CorpusValidation {

    private CorpusValidation() { }

    private record Row(String file, String arch, String model, String expected,
                       String actual, int witnessSize, long solveMs,
                       String status, String note) { }

    private record Options(String csvName, long budgetMs, int perFileSec, int maxFiles,
                           int maxEvents, boolean minwit, Set<MemoryModel> archModels,
                           Path manifest, boolean symbolic) { }

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

        if (!Files.isDirectory(litmusDir)) {
            System.err.println("not a directory: " + litmusDir.toAbsolutePath());
            System.exit(2);
            return;
        }
        if (opt.manifest() != null) {
            // Explicit-manifest mode: exactly the listed files, hash-verified before any solving.
            List<ManifestEntry> entries;
            try {
                if (opt.maxFiles() != 0) {
                    throw new ManifestException(List.of(
                            "maxFiles is not allowed with manifest= (a pinned population is never sampled)"));
                }
                entries = loadManifest(litmusDir, opt.manifest());
            } catch (ManifestException me) {
                System.err.println("manifest rejected; nothing evaluated:");
                me.problems().forEach(p -> System.err.println("  " + p));
                System.exit(2);
                return;
            }
            Files.createDirectories(outDir);
            runManifest(litmusDir, entries, outDir, opt);
            return;
        }
        Files.createDirectories(outDir);

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
            if (opt.symbolic()) {
                enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
            }
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

    // ── Explicit-manifest mode ───────────────────────────────────────────────────
    //
    // manifest=<csv> evaluates exactly the listed files: a header row naming a "path" and a
    // "sha256" column, paths relative to <litmus-dir>. Every entry is checked before any
    // solving — present, inside <litmus-dir>, listed once, and byte-identical to its
    // recorded SHA-256 — and any problem rejects the whole manifest. There is no directory
    // traversal and no sampling. Each (entry × model) gets exactly one row, whose result is
    // SAT / UNSAT / UNKNOWN / TIMEOUT / ERROR / UNSUPPORTED; failures are never turned into
    // verdicts. With mode=symbolic the established symbolic encoding
    // (EventStructureEncoder.enableSymbolic) is applied once per file, so every model sees
    // the same candidate, value and outcome constraints and only the consistency predicate
    // varies.

    /** One verified manifest entry; {@code content} is the exact bytes that were hashed. */
    record ManifestEntry(String path, String sha256, String content) { }

    /** A manifest that cannot be evaluated as pinned; lists every problem found. */
    static final class ManifestException extends Exception {
        private final List<String> problems;

        ManifestException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        List<String> problems() { return problems; }
    }

    private record ManifestRow(String file, String sha256, String arch, String mode, String model,
                               String result, String verdict, long solveMs, String note) { }

    /** Symbolic-encoding gaps found during a manifest run: "file,sha256,gap" lines. */
    private static final List<String> GAP_LOG = new ArrayList<>();

    static List<ManifestEntry> loadManifest(Path root, Path manifest) throws ManifestException {
        List<String> problems = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        } catch (IOException io) {
            throw new ManifestException(List.of("cannot read manifest " + manifest + ": " + io.getMessage()));
        }
        int ip = -1, is = -1, first = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] h = lines.get(i).split(",", -1);
            for (int c = 0; c < h.length; c++) {
                String name = h[c].strip().toLowerCase(Locale.ROOT);
                if (name.equals("path")) ip = c;
                if (name.equals("sha256")) is = c;
            }
            first = i;
            break;
        }
        if (ip < 0 || is < 0) {
            throw new ManifestException(List.of("manifest header must name a path and a sha256 column"));
        }
        Path base = root.toAbsolutePath().normalize();
        Set<String> seen = new java.util.HashSet<>();
        List<ManifestEntry> out = new ArrayList<>();
        for (int i = first + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] c = line.split(",", -1);
            String rel = c.length > ip ? c[ip].strip() : "";
            String sha = c.length > is ? c[is].strip().toLowerCase(Locale.ROOT) : "";
            String where = "line " + (i + 1) + " (" + rel + ")";
            if (rel.isEmpty() || !sha.matches("[0-9a-f]{64}")) {
                problems.add(where + ": missing path or malformed sha256");
                continue;
            }
            if (!seen.add(rel)) {
                problems.add(where + ": duplicate entry");
                continue;
            }
            Path p = base.resolve(rel).normalize();
            if (!p.startsWith(base)) {
                problems.add(where + ": path escapes " + base);
                continue;
            }
            if (!Files.isRegularFile(p)) {
                problems.add(where + ": missing file");
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(p);
            } catch (IOException io) {
                problems.add(where + ": unreadable: " + io.getMessage());
                continue;
            }
            String actual = sha256(bytes);
            if (!actual.equals(sha)) {
                problems.add(where + ": content changed (sha256 " + actual + ")");
                continue;
            }
            out.add(new ManifestEntry(rel, sha, new String(bytes, StandardCharsets.UTF_8)));
        }
        if (out.isEmpty() && problems.isEmpty()) problems.add("manifest lists no entries");
        if (!problems.isEmpty()) throw new ManifestException(problems);
        return out;
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void runManifest(Path root, List<ManifestEntry> entries, Path outDir, Options opt)
            throws Exception {
        long start = System.currentTimeMillis();
        String mode = opt.symbolic() ? "symbolic" : "wired";
        MemoryModel[] models = MemoryModel.values();
        synchronized (GAP_LOG) { GAP_LOG.clear(); }
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        String solverVersion;
        try (SolverContext probe = SolverContextFactory.createSolverContext(cfg, log,
                ShutdownManager.create().getNotifier(), Solvers.Z3)) {
            solverVersion = probe.getVersion();
        }
        Path csvPath = outDir.resolve(opt.csvName());
        Path configPath = outDir.resolve(opt.csvName() + ".config.txt");
        StringBuilder config = new StringBuilder();
        config.append("driver=wev.smt.cli.CorpusValidation (explicit-manifest mode)\n")
              .append("manifest=").append(opt.manifest().toAbsolutePath()).append('\n')
              .append("manifest_sha256=").append(sha256(Files.readAllBytes(opt.manifest()))).append('\n')
              .append("entries=").append(entries.size()).append('\n')
              .append("root=").append(root.toAbsolutePath().normalize()).append('\n')
              .append("mode=").append(mode).append('\n')
              .append("models=").append(java.util.Arrays.toString(models)).append('\n')
              .append("perFileSec=").append(opt.perFileSec()).append('\n')
              .append("budgetMin=").append(opt.budgetMs() / 60000.0).append('\n')
              .append("maxEvents=").append(opt.maxEvents()).append('\n')
              .append("minwit=not used in manifest mode\n")
              .append("solver=").append(solverVersion).append('\n')
              .append("java_smt=").append(SolverContextFactory.class.getPackage().getImplementationVersion()).append('\n')
              .append("java=").append(System.getProperty("java.version")).append(' ')
              .append(System.getProperty("java.vendor")).append('\n')
              .append("started=").append(java.time.Instant.ofEpochMilli(start)).append('\n');
        Files.writeString(configPath, config.toString());
        System.out.printf(Locale.ROOT, "Manifest %s: %d verified entries; mode %s; models %s%n",
                opt.manifest(), entries.size(), mode, java.util.Arrays.toString(models));

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "manifest-timeout");
            t.setDaemon(true);
            return t;
        });
        Map<String, int[]> tally = new LinkedHashMap<>();
        List<String> results = List.of("SAT", "UNSAT", "UNKNOWN", "TIMEOUT", "ERROR", "UNSUPPORTED");
        long rows = 0;
        try (var w = Files.newBufferedWriter(csvPath)) {
            w.write("file,sha256,arch,mode,model,result,verdict,solve_ms,note\n");
            try {
                for (ManifestEntry e : entries) {
                    List<ManifestRow> rs;
                    if (System.currentTimeMillis() - start > opt.budgetMs()) {
                        rs = allModels(e, sniffArch(e.content()), mode, models, "TIMEOUT",
                                "budget-exhausted:not-attempted");
                    } else {
                        rs = evaluateEntry(e, cfg, log, scheduler, opt, mode, models);
                    }
                    if (rs.size() != models.length) {
                        throw new IllegalStateException("row accounting broken for " + e.path());
                    }
                    for (ManifestRow r : rs) {
                        w.write(String.join(",", r.file(), r.sha256(), r.arch(), r.mode(), r.model(),
                                r.result(), r.verdict(), Long.toString(r.solveMs()), clean(r.note())) + "\n");
                        tally.computeIfAbsent(r.model(), k -> new int[results.size()])[results.indexOf(r.result())]++;
                        rows++;
                    }
                    w.flush();
                }
            } finally {
                scheduler.shutdownNow();
            }
        }
        Path gapsPath = outDir.resolve(opt.csvName() + ".encoding-gaps.csv");
        List<String> gapLines;
        synchronized (GAP_LOG) { gapLines = new ArrayList<>(GAP_LOG); }
        Files.writeString(gapsPath, "file,sha256,gap\n" + String.join("\n", gapLines) + (gapLines.isEmpty() ? "" : "\n"));
        long gapFiles = gapLines.stream().map(l -> l.substring(0, l.indexOf(','))).distinct().count();
        long elapsed = System.currentTimeMillis() - start;
        StringBuilder sum = new StringBuilder("encoding_gap_files=").append(gapFiles)
                .append(" (gaps ").append(gapLines.size()).append("; checked only in symbolic mode)\n")
                .append("finished=").append(java.time.Instant.now())
                .append("\nelapsed_ms=").append(elapsed).append("\nrows=").append(rows)
                .append(" (expected ").append((long) entries.size() * models.length).append(")\n");
        System.out.printf(Locale.ROOT, "%n%-8s %s%n", "model", results);
        for (Map.Entry<String, int[]> t : tally.entrySet()) {
            System.out.printf(Locale.ROOT, "%-8s %s%n", t.getKey(), java.util.Arrays.toString(t.getValue()));
            sum.append("result_counts.").append(t.getKey()).append('=')
               .append(java.util.Arrays.toString(t.getValue())).append(' ').append(results).append('\n');
        }
        Files.writeString(configPath, sum.toString(), java.nio.file.StandardOpenOption.APPEND);
        System.out.printf(Locale.ROOT, "rows=%d; wall-clock %.1f s; CSV %s%n", rows, elapsed / 1000.0,
                csvPath.toAbsolutePath());
    }

    /**
     * Where {@link EventStructureEncoder}'s symbolic encoding would fall back silently for this
     * case, using the encoder's own predicates (value variables for {@code WriteEvent}/INIT
     * events, read variables for {@code ReadEvent}/{@code RMWEvent}) and the same
     * {@link QfbvConstancyOracle#buildTerm} call at width 32:
     * a write with no retained value expression (folded-constant fallback); a write expression
     * outside the grammar (left unconstrained); an RMW (placeholder value); an outcome pin on
     * an event that is not a read (pin skipped); a memory-final outcome on a location with no
     * write (constraint skipped). Detection only — the encoding itself is not changed.
     */
    static List<String> symbolicEncodingGaps(LitmusCase lc, SolverContext ctx) {
        List<String> gaps = new ArrayList<>();
        var bv = ctx.getFormulaManager().getBitvectorFormulaManager();
        var bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
        Map<String, org.sosy_lab.java_smt.api.BitvectorFormula> env = new LinkedHashMap<>();
        for (com.weakest.model.Event ev : lc.es().getEvents()) {
            if (ev instanceof com.weakest.model.ReadEvent || ev instanceof com.weakest.model.RMWEvent) {
                env.put("R" + ev.getId(), bv.makeVariable(32, "gap_r" + ev.getId()));
            }
        }
        for (com.weakest.model.Event ev : lc.es().getEvents()) {
            if (!(ev instanceof com.weakest.model.WriteEvent
                    || ev.getType() == com.weakest.model.EventType.INIT)) continue;
            String where = "event " + ev.getId() + " (T" + ev.getThreadId() + " " + ev.getVariable() + ")";
            if (ev instanceof com.weakest.model.RMWEvent) {
                gaps.add(where + ": RMW written value is a placeholder");
            }
            String expr = lc.writeValueExprs().get(ev.getId());
            if (expr == null) {
                gaps.add(where + ": no value expression (folded-constant fallback)");
                continue;
            }
            try {
                wev.smt.QfbvConstancyOracle.buildTerm(expr, 32, bv, bmgr, env);
            } catch (RuntimeException ex) {
                gaps.add(where + ": write expression '" + expr + "' outside the grammar ("
                        + ex.getMessage() + "): value would be unconstrained");
            }
        }
        for (Integer id : lc.existsReadValues().keySet()) {
            com.weakest.model.Event ev = lc.es().getEventById(id);
            if (!(ev instanceof com.weakest.model.ReadEvent || ev instanceof com.weakest.model.RMWEvent)) {
                gaps.add("outcome pin on event " + id + " which is not a read: pin would be skipped");
            }
        }
        for (String loc : lc.existsMemFinal().keySet()) {
            boolean hasWrite = lc.es().getEvents().stream().anyMatch(ev ->
                    (ev instanceof com.weakest.model.WriteEvent || ev.getType() == com.weakest.model.EventType.INIT)
                            && loc.equals(ev.getVariable()));
            if (!hasWrite) gaps.add("memory outcome on '" + loc + "' which has no write: constraint would be skipped");
        }
        return gaps;
    }

    private static List<ManifestRow> allModels(ManifestEntry e, String arch, String mode,
            MemoryModel[] models, String result, String note) {
        List<ManifestRow> rs = new ArrayList<>();
        for (MemoryModel m : models) rs.add(new ManifestRow(e.path(), e.sha256(), arch, mode, m.name(), result, "", 0, note));
        return rs;
    }

    private static List<ManifestRow> evaluateEntry(ManifestEntry e, Configuration cfg, LogManager log,
            ScheduledExecutorService scheduler, Options opt, String mode, MemoryModel[] models) {
        LitmusCase lc;
        try {
            lc = LitmusParser.parse(e.content(), e.path());
        } catch (ParseException pe) {
            return allModels(e, sniffArch(e.content()), mode, models, "UNSUPPORTED",
                    "parse:" + pe.kind() + ":" + pe.getMessage());
        } catch (RuntimeException ex) {
            return allModels(e, sniffArch(e.content()), mode, models, "ERROR", "parse-crash:" + ex);
        }
        String arch = lc.arch().name();
        if (lc.existsClause() == null || lc.existsClause().isBlank()) {
            return allModels(e, arch, mode, models, "UNSUPPORTED", "no-exists-clause");
        }
        int nEvents = lc.es().getEvents().size();
        if (nEvents > opt.maxEvents()) {
            return allModels(e, arch, mode, models, "UNSUPPORTED",
                    "too-large:" + nEvents + "-events(>" + opt.maxEvents() + ")");
        }
        Map<MemoryModel, ManifestRow> rows = new java.util.EnumMap<>(MemoryModel.class);
        ShutdownManager sm = ShutdownManager.create();
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> sm.requestShutdown("per-file timeout " + opt.perFileSec() + "s"),
                opt.perFileSec(), TimeUnit.SECONDS);
        String failure = null, failureResult = "ERROR";
        try (SolverContext ctx = SolverContextFactory.createSolverContext(cfg, log, sm.getNotifier(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            if (opt.symbolic()) {
                // An incomplete symbolic encoding never yields a verdict.
                List<String> gaps = symbolicEncodingGaps(lc, ctx);
                if (!gaps.isEmpty()) {
                    synchronized (GAP_LOG) {
                        for (String g : gaps) {
                            GAP_LOG.add(e.path() + "," + e.sha256() + ","
                                    + g.replace(',', ';').replace('\n', ' ').replace('\r', ' '));
                        }
                    }
                    timeout.cancel(false);
                    return allModels(e, arch, mode, models, "UNSUPPORTED",
                            "incomplete-symbolic-encoding:" + gaps.size() + "-gap(s): " + gaps.get(0));
                }
            }
            EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
            if (opt.symbolic()) {
                enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
            }
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();      // shared by every model
            boolean timedOut = false;
            for (MemoryModel m : models) {
                if (timedOut) {
                    rows.put(m, new ManifestRow(e.path(), e.sha256(), arch, mode, m.name(), "TIMEOUT", "", 0,
                            "per-file " + opt.perFileSec() + "s"));
                    continue;
                }
                long t0 = System.currentTimeMillis();
                String result, verdict = "", note = "";
                try (ProverEnvironment p = ctx.newProverEnvironment()) {
                    p.addConstraint(bmgr.and(wf, consistencyOf(ax, m)));
                    boolean unsat = p.isUnsat();
                    result = unsat ? "UNSAT" : "SAT";
                    verdict = unsat ? "FORBIDDEN" : "ALLOWED";
                } catch (InterruptedException ie) {
                    timedOut = true;
                    result = "TIMEOUT";
                    note = "per-file " + opt.perFileSec() + "s";
                } catch (SolverException se) {
                    String msg = String.valueOf(se.getMessage());
                    result = msg.toLowerCase(Locale.ROOT).contains("unknown") ? "UNKNOWN" : "ERROR";
                    note = "solver:" + msg;
                } catch (RuntimeException re) {
                    result = "ERROR";
                    note = "solver:" + re;
                }
                rows.put(m, new ManifestRow(e.path(), e.sha256(), arch, mode, m.name(), result, verdict,
                        System.currentTimeMillis() - t0, note));
            }
        } catch (Exception ex) {
            failure = "encode:" + ex;
            if (sm.getNotifier().shouldShutdown()) {
                failureResult = "TIMEOUT";
                failure = "per-file " + opt.perFileSec() + "s (encoding)";
            }
        } finally {
            timeout.cancel(false);
        }
        List<ManifestRow> out = new ArrayList<>();
        for (MemoryModel m : models) {
            ManifestRow r = rows.get(m);
            out.add(r != null ? r : new ManifestRow(e.path(), e.sha256(), arch, mode, m.name(), failureResult, "", 0,
                    failure == null ? "no-result" : failure));
        }
        return out;
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
        Path manifest = null;
        boolean symbolic = false;
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
                case "manifest" -> manifest = Path.of(v);
                case "mode" -> {
                    if (v.equalsIgnoreCase("symbolic")) symbolic = true;
                    else if (v.equalsIgnoreCase("wired")) symbolic = false;
                    else throw new IllegalArgumentException("mode must be wired or symbolic: " + v);
                }
                default -> System.err.println("[opt] ignoring unknown option: " + args[i]);
            }
        }
        return new Options(csv, budgetMs, perFileSec, maxFiles, maxEvents, minwit, archModels,
                manifest, symbolic);
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
