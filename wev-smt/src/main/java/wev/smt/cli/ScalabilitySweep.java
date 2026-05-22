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

import wev.smt.AxiomaticConsistency;
import wev.smt.EventStructureEncoder;
import wev.smt.MemoryModel;
import wev.smt.MinimalWitness;
import wev.smt.MinimalWitnessExtractor;
import wev.smt.bench.ParametricPrograms;
import wev.smt.bench.ParametricPrograms.Program;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Day-9 scalability evaluation sweep over the parametric program families in
 * {@link ParametricPrograms}. For four families × nine sizes × five models it times the
 * decision procedure and writes plot-ready CSVs to the output directory (default
 * {@code eval/}).
 *
 * <h2>What is measured</h2>
 * <ul>
 *   <li><b>Full-execution consistency</b> — is the wired execution consistent under the
 *       model? This is the headline scaling metric and the verdict the sanity
 *       assertions/stop-triggers care about. It is the same check as
 *       {@code AtlasReconstruct} validation: {@code isUnsat(wf ∧ consistency(M))}, timed
 *       end-to-end (formula build + solve).</li>
 *   <li><b>Minimum-witness size/time</b> — {@link MinimalWitnessExtractor#findMinimalConsistent}.
 *       Recorded for completeness; under the gated encoding the minimum consistent
 *       sub-execution collapses to a trivial singleton (documented in
 *       {@code AtlasReconstruct}), so it is <em>not</em> the scaling headline.</li>
 *   <li><b>Separation</b> — {@link MinimalWitnessExtractor#findMinimalSeparating} for each
 *       ordered model pair. The CEGAR search is expensive; it is budget-limited and many
 *       cells are recorded {@code TIMEOUT}.</li>
 * </ul>
 *
 * <h2>Budgeting</h2>
 * Each call has a {@value #CONS_CAP_MS}-ms (consistency) / {@value #SEP_SOFT_MS}-ms
 * (separation) cap; the whole run stops launching new work after
 * {@value #TOTAL_BUDGET_MS} ms and records the remainder as {@code TIMEOUT}, so the tool
 * never runs away. Consistency for <em>all</em> families is computed first (it is cheap
 * and is the deliverable), then separation small-{@code n}-first until the budget is spent.
 * Each {@code (family, n)} case owns a {@link SolverContext} (reused across that case's
 * calls); a cap that fires interrupts the solver via a {@link ShutdownManager} and the
 * case context is rebuilt before the next call.
 *
 * <p>Note on dependencies and event counts: the LB families carry a {@link wev.smt.DependencyInfo}
 * sidecar; all families wire one initial write per location, so real event totals are
 * {@code 3n} (LB/SB) or {@code 4n} (IRIW) — see {@link ParametricPrograms}.
 */
public final class ScalabilitySweep {

    private static final int[] SIZES = {2, 3, 4, 5, 6, 8, 10, 12, 16};
    private static final MemoryModel[] MODELS = MemoryModel.values();
    private static final String[] FAMILIES = {"LBChain", "SBNThread", "IRIWFan", "LBFakeChain"};

    /** Spec defaults (overridable via CLI args for constrained run windows). */
    private static final double DEFAULT_BUDGET_MIN = 30.0;     // total runtime budget
    private static final long DEFAULT_PER_CALL_MS = 60_000L;   // per-call cap / CEGAR budget
    /** The minimum consistent witness is trivially size-1, so its probe is capped tight. */
    private static final long MINWIT_CAP_MS = 10_000L;

    /** Below this, a timing is dominated by noise and ratio analysis is unreliable. */
    private static final double NOISE_FLOOR_MS = 3.0;

    private ScalabilitySweep() { }

    private enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT }
    private enum SepStatus { SOLVED, NO_SEPARATION, TIMEOUT }

    /**
     * Wall-clock caps for one run. {@code minWitDeadlineMs} is the point past which the
     * (optional, trivially-small) minimum-witness probe is skipped so the headline
     * full-execution sweep always finishes; {@code totalBudgetMs} stops new work entirely.
     */
    private record Caps(long consCapMs, long minWitCapMs, long sepSoftMs, long sepHardMs,
                        long minWitDeadlineMs, long totalBudgetMs) {
        static Caps from(double budgetMin, long perCallMs) {
            long total = (long) (budgetMin * 60_000);
            return new Caps(perCallMs, Math.min(perCallMs, MINWIT_CAP_MS), perCallMs,
                    perCallMs + 15_000, total / 2, total);
        }
    }

    private record ConsRow(String family, int n, int events, MemoryModel model,
                           Verdict verdict, long fullExecMs,
                           int minWitnessSize, long minWitnessMs, long usedMemMb) { }

    private record SepRow(String family, int n, int events, MemoryModel allow,
                          MemoryModel forbid, SepStatus status, Integer witnessSize, long ms) { }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "eval");
        double budgetMin = args.length > 1 ? Double.parseDouble(args[1]) : DEFAULT_BUDGET_MIN;
        long perCallMs = args.length > 2
                ? (long) (Double.parseDouble(args[2]) * 1000) : DEFAULT_PER_CALL_MS;
        Caps caps = Caps.from(budgetMin, perCallMs);
        Files.createDirectories(outDir);

        long start = System.currentTimeMillis();
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sweep-watchdog");
            t.setDaemon(true);
            return t;
        });

        // Build every program once up front (unique ids; each case still gets its own ctx).
        com.weakest.model.Event.resetCounter();
        List<Program> programs = new ArrayList<>();
        for (int n : SIZES) {
            programs.add(ParametricPrograms.buildLBChain(n));
            programs.add(ParametricPrograms.buildSBNThread(n));
            programs.add(ParametricPrograms.buildIRIWFan(n));
            programs.add(ParametricPrograms.buildLBFakeChain(n));
        }

        System.out.printf("Scalability sweep: %d families x %d sizes x %d models%n",
                FAMILIES.length, SIZES.length, MODELS.length);
        System.out.printf("Sizes: %s   models: %s%n",
                Arrays.toString(SIZES), Arrays.toString(MODELS));
        System.out.printf("Caps: consistency %ds, min-witness %ds, separation %ds; "
                        + "total budget %.1f min%n%n",
                caps.consCapMs() / 1000, caps.minWitCapMs() / 1000,
                caps.sepSoftMs() / 1000, caps.totalBudgetMs() / 60000.0);

        try {
            // Both phases write their CSV after every case, so a hard kill at any point
            // leaves a valid, partial CSV on disk.
            List<ConsRow> consRows = sweepConsistency(programs, cfg, log, watchdog, caps, start, outDir);
            printConsistencyTables(consRows);
            checkStopTriggers(consRows);

            List<SepRow> sepRows = sweepSeparation(programs, cfg, log, watchdog, caps, start, outDir);
            writeSeparationCsv(outDir, sepRows);   // final write: persist not-started cells too
            printSeparationSummary(sepRows);

            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("%nTotal wall-clock: %.1f s (budget %.0f s). CSVs in %s%n",
                    elapsed / 1000.0, caps.totalBudgetMs() / 1000.0, outDir.toAbsolutePath());
        } finally {
            watchdog.shutdownNow();
        }
    }

    // ── Consistency phase ───────────────────────────────────────────────────

    private static List<ConsRow> sweepConsistency(List<Program> programs, Configuration cfg,
                                                  LogManager log, ScheduledExecutorService watchdog,
                                                  Caps caps, long start, Path outDir)
            throws Exception {
        List<ConsRow> rows = new ArrayList<>();
        for (Program prog : programs) {
            Case c = new Case(prog, cfg, log);
            try {
                for (MemoryModel m : MODELS) {
                    // Full-execution consistency (build + solve), the headline metric —
                    // always run, even past the budget, since it is cheap and is the deliverable.
                    Timed<Boolean> full = runCapped(c, watchdog, caps.consCapMs(), () -> {
                        BooleanFormula cons = consistencyOf(c.ax, m);
                        try (ProverEnvironment p = c.ctx.newProverEnvironment()) {
                            p.addConstraint(c.bmgr().and(c.wf, cons));
                            return p.isUnsat();
                        }
                    });
                    long usedMb = usedHeapMb();
                    Verdict verdict = full.timedOut() ? Verdict.TIMEOUT
                            : (full.value() ? Verdict.FORBIDDEN : Verdict.ALLOWED);
                    if (full.timedOut() || c.latched()) c.rebuild();

                    // Minimum consistent witness (optimization backend); trivially size-1 and
                    // costly at large n — capped tight, and skipped (size -2) past the deadline.
                    int minSize;
                    long minMs;
                    if (System.currentTimeMillis() - start > caps.minWitDeadlineMs()) {
                        minSize = -2;
                        minMs = 0;
                    } else {
                        Timed<Optional<MinimalWitness>> minw = runCapped(
                                c, watchdog, caps.minWitCapMs(), () -> c.mw.findMinimalConsistent(m));
                        minSize = minw.timedOut() ? -1
                                : minw.value().map(MinimalWitness::cardinality).orElse(-1);
                        minMs = minw.ms();
                        if (minw.timedOut() || c.latched()) c.rebuild();
                    }

                    rows.add(new ConsRow(prog.family(), prog.n(), prog.eventCount(), m,
                            verdict, full.ms(), minSize, minMs, usedMb));
                    System.out.printf("  [cons] %-12s n=%-2d %-8s %-9s full=%5dms minWit=%d (%dms)%n",
                            prog.family(), prog.n(), m, verdict, full.ms(), minSize, minMs);
                }
                writeConsistencyCsv(outDir, rows);   // incremental: survive a hard kill
            } finally {
                c.close();
            }
        }
        return rows;
    }

    // ── Separation phase ─────────────────────────────────────────────────────

    private static List<SepRow> sweepSeparation(List<Program> programs, Configuration cfg,
                                                LogManager log, ScheduledExecutorService watchdog,
                                                Caps caps, long start, Path outDir) throws Exception {
        // Small-n first so the most cells complete before the budget is spent.
        List<Program> ordered = new ArrayList<>(programs);
        ordered.sort((a, b) -> Integer.compare(a.n(), b.n()));

        List<SepRow> rows = new ArrayList<>();
        boolean budgetSpent = false;
        for (Program prog : ordered) {
            boolean started = !budgetSpent
                    && System.currentTimeMillis() - start <= caps.totalBudgetMs();
            if (!started) {
                budgetSpent = true;
                for (MemoryModel a : MODELS) {
                    for (MemoryModel f : MODELS) {
                        if (a == f) continue;
                        rows.add(new SepRow(prog.family(), prog.n(), prog.eventCount(),
                                a, f, SepStatus.TIMEOUT, null, 0));
                    }
                }
                continue;
            }
            Case c = new Case(prog, cfg, log);
            try {
                for (MemoryModel a : MODELS) {
                    for (MemoryModel f : MODELS) {
                        if (a == f) continue;
                        if (System.currentTimeMillis() - start > caps.totalBudgetMs()) {
                            rows.add(new SepRow(prog.family(), prog.n(), prog.eventCount(),
                                    a, f, SepStatus.TIMEOUT, null, 0));
                            continue;
                        }
                        final MemoryModel allow = a;
                        final MemoryModel forbid = f;
                        Timed<Optional<MinimalWitness>> sep = runCapped(c, watchdog, caps.sepHardMs(),
                                () -> c.mw.findMinimalSeparating(allow, forbid, caps.sepSoftMs()));
                        SepStatus status;
                        Integer size = null;
                        if (sep.timedOut()) {
                            status = SepStatus.TIMEOUT;
                        } else if (sep.value().isPresent()) {
                            status = SepStatus.SOLVED;
                            size = sep.value().get().cardinality();
                        } else {
                            // empty: an exhausted search (NO_SEPARATION) or a soft-budget cut-off.
                            status = sep.ms() >= (long) (caps.sepSoftMs() * 0.9)
                                    ? SepStatus.TIMEOUT : SepStatus.NO_SEPARATION;
                        }
                        if (sep.timedOut() || c.latched()) c.rebuild();
                        rows.add(new SepRow(prog.family(), prog.n(), prog.eventCount(),
                                allow, forbid, status, size, sep.ms()));
                    }
                }
                writeSeparationCsv(outDir, rows);   // incremental: survive a hard kill
                System.out.printf("  [sep ] %-12s n=%-2d done (elapsed %.0fs)%n",
                        prog.family(), prog.n(), (System.currentTimeMillis() - start) / 1000.0);
            } finally {
                c.close();
            }
        }
        return rows;
    }

    // ── Capped execution ──────────────────────────────────────────────────

    private record Timed<T>(T value, long ms, boolean timedOut) { }

    /**
     * Run {@code task} on the calling thread with a watchdog that requests a solver
     * shutdown after {@code capMs}. Returns {@code timedOut = true} (and {@code value =
     * null}) if the cap fired or the solver was interrupted; the caller must then
     * {@link Case#rebuild()} the latched context. Any other exception is a genuine error.
     */
    private static <T> Timed<T> runCapped(Case c, ScheduledExecutorService watchdog,
                                          long capMs, Callable<T> task) {
        long t0 = System.nanoTime();
        ScheduledFuture<?> alarm = watchdog.schedule(
                () -> c.sm.requestShutdown("per-call cap " + capMs + " ms"),
                capMs, TimeUnit.MILLISECONDS);
        try {
            T v = task.call();
            return new Timed<>(v, elapsedMs(t0), false);
        } catch (Exception e) {
            long ms = elapsedMs(t0);
            if (c.sm.getNotifier().shouldShutdown() || ms >= capMs) {
                return new Timed<>(null, ms, true);
            }
            throw new RuntimeException("unexpected solver failure in " + c.label(), e);
        } finally {
            alarm.cancel(false);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static BooleanFormula consistencyOf(AxiomaticConsistency ax, MemoryModel m) {
        return switch (m) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
            case PSO -> ax.consistencyPSO();
            case RA -> ax.consistencyRA();
            case WEAKEST -> ax.consistencyWEAKEST();
        };
    }

    // ── Per-case SMT scaffolding ─────────────────────────────────────────────

    private static final class Case implements AutoCloseable {
        private final Program prog;
        private final Configuration cfg;
        private final LogManager log;
        ShutdownManager sm;
        SolverContext ctx;
        EventStructureEncoder enc;
        AxiomaticConsistency ax;
        MinimalWitnessExtractor mw;
        BooleanFormula wf;

        Case(Program prog, Configuration cfg, LogManager log) {
            this.prog = prog;
            this.cfg = cfg;
            this.log = log;
            build();
        }

        private void build() {
            try {
                sm = ShutdownManager.create();
                ctx = SolverContextFactory.createSolverContext(cfg, log, sm.getNotifier(), Solvers.Z3);
                enc = new EventStructureEncoder(ctx, prog.es(), prog.deps());
                ax = new AxiomaticConsistency(enc);
                mw = new MinimalWitnessExtractor(ctx, enc, ax);
                wf = enc.encodeWellFormedness();
            } catch (Exception e) {
                throw new RuntimeException("failed to build SMT context for " + label(), e);
            }
        }

        /** Whether a shutdown has been requested on this case's context (latched). */
        boolean latched() {
            return sm.getNotifier().shouldShutdown();
        }

        /** Discard the latched (shut-down) context and start a fresh one for this case. */
        void rebuild() {
            close();
            build();
        }

        BooleanFormulaManager bmgr() {
            return enc.getBmgr();
        }

        String label() {
            return prog.family() + "/n=" + prog.n();
        }

        @Override
        public void close() {
            if (ctx != null) {
                ctx.close();
                ctx = null;
            }
        }
    }

    // ── CSV output ────────────────────────────────────────────────────────

    private static void writeConsistencyCsv(Path dir, List<ConsRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder(
                "family,n,events,model,verdict,fullexec_ms,minwitness_size,minwitness_ms,used_mem_mb\n");
        for (ConsRow r : rows) {
            sb.append(r.family()).append(',')
              .append(r.n()).append(',')
              .append(r.events()).append(',')
              .append(r.model()).append(',')
              .append(r.verdict()).append(',')
              .append(r.fullExecMs()).append(',')
              .append(r.minWitnessSize()).append(',')
              .append(r.minWitnessMs()).append(',')
              .append(r.usedMemMb()).append('\n');
        }
        Files.writeString(dir.resolve("scalability-consistency.csv"), sb.toString());
    }

    private static void writeSeparationCsv(Path dir, List<SepRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder(
                "family,n,events,allowedBy,forbiddenBy,status,witnessSize,ms\n");
        for (SepRow r : rows) {
            sb.append(r.family()).append(',')
              .append(r.n()).append(',')
              .append(r.events()).append(',')
              .append(r.allow()).append(',')
              .append(r.forbid()).append(',')
              .append(r.status()).append(',')
              .append(r.witnessSize() == null ? "" : r.witnessSize()).append(',')
              .append(r.ms()).append('\n');
        }
        Files.writeString(dir.resolve("scalability-separation.csv"), sb.toString());
    }

    // ── Console summary tables (TASK 3) ──────────────────────────────────────

    private static void printConsistencyTables(List<ConsRow> rows) {
        System.out.println();
        System.out.println("== Consistency scaling (full-execution decision time, ms) ==");
        for (String family : FAMILIES) {
            System.out.printf("%n### %s%n%n", family);
            System.out.println("| n | events | SC ms | TSO ms | PSO ms | RA ms | WEAKEST ms |");
            System.out.println("|---|--------|-------|--------|--------|-------|------------|");
            // verdict legend row helps the reader read the times in context.
            for (int n : SIZES) {
                StringBuilder line = new StringBuilder();
                int events = -1;
                line.append("| ").append(n).append(" | ");
                StringBuilder cells = new StringBuilder();
                for (MemoryModel m : MODELS) {
                    ConsRow r = find(rows, family, n, m);
                    if (r == null) {
                        cells.append(" - |");
                        continue;
                    }
                    events = r.events();
                    cells.append(' ').append(cell(r)).append(" |");
                }
                line.append(events).append(" | ").append(cells);
                System.out.println(line);
            }
            printCurveShapes(rows, family);
        }
        System.out.println();
        System.out.println("Legend: a trailing * marks FORBIDDEN (UNSAT), ! marks a per-call "
                + "TIMEOUT; otherwise ALLOWED. Steps in n are non-uniform "
                + "(2,3,4,5,6,8,10,12,16), so per-step ratios below cover unequal gaps.");
    }

    private static String cell(ConsRow r) {
        String suffix = switch (r.verdict()) {
            case FORBIDDEN -> "*";
            case TIMEOUT -> "!";
            case ALLOWED -> "";
        };
        return r.fullExecMs() + suffix;
    }

    private static void printCurveShapes(List<ConsRow> rows, String family) {
        System.out.println();
        for (MemoryModel m : MODELS) {
            double[] times = new double[SIZES.length];
            boolean any = false;
            for (int i = 0; i < SIZES.length; i++) {
                ConsRow r = find(rows, family, SIZES[i], m);
                times[i] = (r == null) ? Double.NaN : r.fullExecMs();
                if (r != null) any = true;
            }
            if (!any) continue;
            System.out.printf("  %-8s curve: %s%n", m, classifyCurve(times));
        }
    }

    /** Per-step ratios t(n_{i+1})/t(n_i) plus a coarse shape label. */
    private static String classifyCurve(double[] times) {
        List<Double> ratios = new ArrayList<>();
        StringBuilder seq = new StringBuilder();
        double maxTime = 0;
        for (int i = 0; i < times.length; i++) {
            if (!Double.isNaN(times[i])) maxTime = Math.max(maxTime, times[i]);
            if (i > 0 && !Double.isNaN(times[i - 1]) && !Double.isNaN(times[i])
                    && times[i - 1] >= NOISE_FLOOR_MS) {
                double ratio = times[i] / Math.max(times[i - 1], 1e-9);
                ratios.add(ratio);
                if (seq.length() > 0) seq.append(' ');
                seq.append(String.format("%.1fx", ratio));
            }
        }
        if (ratios.isEmpty()) {
            return String.format("sub-%.0fms throughout (below timer resolution; peak %.0fms)",
                    NOISE_FLOOR_MS, maxTime);
        }
        double median = median(ratios);
        String shape;
        if (maxTime < 50) {
            shape = "flat";
        } else if (median < 1.6) {
            shape = "~linear";
        } else if (median < 2.5) {
            shape = "polynomial";
        } else {
            shape = "EXPONENTIAL";
        }
        return String.format("%s (median %.2fx/step, peak %.0fms; per-step: %s)",
                shape, median, maxTime, seq);
    }

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    // ── Stop-trigger checks ───────────────────────────────────────────────

    private static void checkStopTriggers(List<ConsRow> rows) {
        System.out.println();
        System.out.println("== Stop-trigger checks ==");
        boolean ok = true;

        for (ConsRow r : rows) {
            if (r.family().equals("LBFakeChain") && r.model() == MemoryModel.WEAKEST
                    && r.verdict() == Verdict.FORBIDDEN) {
                System.out.printf("  !! BUG: LBFakeChain(n=%d)/WEAKEST = FORBIDDEN "
                        + "(fake deps must not close a thin-air cycle)%n", r.n());
                ok = false;
            }
        }
        ConsRow lb2 = find(rows, "LBChain", 2, MemoryModel.WEAKEST);
        if (lb2 != null && lb2.verdict() == Verdict.ALLOWED) {
            System.out.println("  !! BUG: LBChain(n=2)/WEAKEST = ALLOWED "
                    + "(real deps must close a thin-air cycle)");
            ok = false;
        }

        // Exponential-blowup flag for the paper's honest §6 discussion.
        for (String family : FAMILIES) {
            for (MemoryModel m : MODELS) {
                if (exponentialBeyond4(rows, family, m)) {
                    System.out.printf("  ~~ FLAG (paper §6): %s/%s shows >=2.5x/step growth "
                            + "beyond n=4 — discuss honestly.%n", family, m);
                }
            }
        }
        if (ok) System.out.println("  verdict stop-triggers clear (no LBFakeChain/WEAKEST "
                + "FORBIDDEN; LBChain(2)/WEAKEST FORBIDDEN as required).");
    }

    private static boolean exponentialBeyond4(List<ConsRow> rows, String family, MemoryModel m) {
        List<Double> ratios = new ArrayList<>();
        double maxTime = 0;
        Double prev = null;
        Integer prevN = null;
        for (int n : SIZES) {
            ConsRow r = find(rows, family, n, m);
            if (r == null || r.verdict() == Verdict.TIMEOUT) {
                prev = null;
                prevN = null;
                continue;
            }
            double t = r.fullExecMs();
            maxTime = Math.max(maxTime, t);
            if (prev != null && prevN != null && prevN > 4 && prev >= NOISE_FLOOR_MS) {
                ratios.add(t / Math.max(prev, 1e-9));
            }
            prev = t;
            prevN = n;
        }
        if (ratios.isEmpty() || maxTime < 100) return false;   // ignore timer-noise regimes
        for (double rt : ratios) {
            if (rt < 2.5) return false;
        }
        return true;
    }

    private static void printSeparationSummary(List<SepRow> rows) {
        System.out.println();
        System.out.println("== Separation sweep summary (per family) ==");
        for (String family : FAMILIES) {
            int solved = 0, noSep = 0, timeout = 0;
            for (SepRow r : rows) {
                if (!r.family().equals(family)) continue;
                switch (r.status()) {
                    case SOLVED -> solved++;
                    case NO_SEPARATION -> noSep++;
                    case TIMEOUT -> timeout++;
                }
            }
            System.out.printf("  %-12s solved=%-4d no-sep=%-4d timeout=%-4d%n",
                    family, solved, noSep, timeout);
        }
    }

    private static ConsRow find(List<ConsRow> rows, String family, int n, MemoryModel m) {
        for (ConsRow r : rows) {
            if (r.family().equals(family) && r.n() == n && r.model() == m) return r;
        }
        return null;
    }
}
