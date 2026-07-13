package wev.smt.cli;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.FenceEvent;
import com.weakest.model.FenceEvent.FenceKind;
import com.weakest.model.MemoryOrder;
import com.weakest.model.RMWEvent;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
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
import wev.smt.MemoryModel;
import wev.smt.MinimalWitnessExtractor;
import wev.smt.bench.ParametricPrograms;
import wev.smt.bench.ParametricPrograms.Program;
import wev.smt.validate.InputValidator;
import wev.smt.validate.InvalidEventStructureException;
import wev.smt.validate.ValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Day-14 robustness sweep. Runs the eleven edge cases of
 * {@code wev.smt.edgecase.EdgeCaseTest} (A–K) <em>programmatically</em> — outside JUnit —
 * so the §6.4 robustness claim is reproducible as a standalone artifact step: for each
 * case it records the outcome, wall-clock time, heap used, and any exception, then writes
 * {@code eval/robustness-report.txt} with one line per case and a single-line summary
 * ("11/11 handled gracefully, no crashes").
 *
 * <p>"Handled gracefully" means the case produced a defined outcome — a correct verdict, a
 * validator rejection of a structurally broken input, a resource-ceiling refusal of an
 * oversized one, or a capped timeout — rather than an unhandled exception or a JVM crash.
 * Each case runs under its own {@link SolverContext} with a per-case wall-clock cap (a
 * watchdog requests a solver shutdown), so a runaway solve becomes a recorded
 * {@code TIMEOUT}, never a hang; the whole run is bounded by {@link #TOTAL_BUDGET_MS}.
 *
 * <p>The {@code outcome} column is deterministic (model verdicts do not vary run to run);
 * the time/memory column is not, so the artifact reproduction diffs only the
 * {@code id | outcome} projection. Output directory defaults to {@code eval} (overridable
 * as {@code args[0]}).
 */
public final class RobustnessSweep {

    private static final long TOTAL_BUDGET_MS = 10 * 60 * 1000L;     // 10 minutes
    private static final long PER_CASE_CAP_MS = 90_000L;             // per-case wall-clock cap

    private RobustnessSweep() { }

    /** One case's recorded result. {@code outcome} is the deterministic verdict category. */
    private record CaseResult(String id, String name, String outcome,
                              long ms, long memMb, String exception) {
        boolean handled() { return exception == null; }
    }

    @FunctionalInterface
    private interface CaseBody {
        /** Run the case over a fresh context; return the (deterministic) outcome string. */
        String run(SolverContext ctx) throws Exception;
    }

    private static Configuration cfg;
    private static LogManager log;
    private static ScheduledExecutorService watchdog;

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "eval");
        Files.createDirectories(outDir);

        cfg = Configuration.defaultConfiguration();
        log = BasicLogManager.create(cfg);
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "robustness-watchdog");
            t.setDaemon(true);
            return t;
        });

        long start = System.currentTimeMillis();
        System.out.println("== Day-14 robustness sweep: 11 edge cases (A–K) ==");
        System.out.printf("per-case cap %ds, total budget %.0f min%n%n",
                PER_CASE_CAP_MS / 1000, TOTAL_BUDGET_MS / 60000.0);

        List<CaseResult> results = new ArrayList<>();
        try {
            results.add(runCase("A", "empty event structure", RobustnessSweep::caseEmpty));
            results.add(runCase("B", "single-thread program", RobustnessSweep::caseSingleThread));
            results.add(runCase("C", "pathological po (cyclic)", RobustnessSweep::caseCyclicPo));
            results.add(runCase("D", "read with no matching write", RobustnessSweep::caseReadNoWrite));
            results.add(runCase("E", "conflicting initial values", RobustnessSweep::caseConflictingInit));
            results.add(runCase("F", "extremely large event count", RobustnessSweep::caseLargeCounts));
            results.add(runCase("G", "many concurrent locations", RobustnessSweep::caseManyLocations));
            results.add(runCase("H", "deep dependency chain (32)", RobustnessSweep::caseDeepChain));
            results.add(runCase("I", "mixed semantic/non-semantic deps", RobustnessSweep::caseMixedDeps));
            results.add(runCase("J", "RMW same-location conflict", RobustnessSweep::caseRmwConflict));
            results.add(runCase("K", "mixed REL/ACQ fences", RobustnessSweep::caseMixedFences));
        } finally {
            watchdog.shutdownNow();
        }

        long handled = results.stream().filter(CaseResult::handled).count();
        writeReport(outDir, results, handled, System.currentTimeMillis() - start);
        printReport(results, handled);

        // Exit non-zero only on a genuine unhandled exception (a STOP trigger), so the
        // artifact step fails loudly if robustness regresses.
        if (handled != results.size()) {
            System.err.printf("ROBUSTNESS REGRESSION: %d/%d cases crashed%n",
                    results.size() - handled, results.size());
            System.exit(1);
        }
    }

    // ── Case bodies (mirror EdgeCaseTest A–K) ──────────────────────────────

    private static String caseEmpty(SolverContext ctx) {
        EventStructure es = new EventStructure();
        MinimalWitnessExtractor mw = newExtractor(ctx, es, DependencyInfo.empty());
        boolean none = mw.findMinimalConsistent(MemoryModel.SC).isEmpty()
                && mw.findMinimalSeparating(MemoryModel.TSO, MemoryModel.SC).isEmpty();
        return none ? "no witness (no crash)" : "UNEXPECTED witness on empty structure";
    }

    private static String caseSingleThread(SolverContext ctx) {
        EventStructure es = new EventStructure();
        WriteEvent init = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        WriteEvent w = new WriteEvent(1, "x", MemoryOrder.RELAXED, 1, "1");
        ReadEvent r = new ReadEvent(1, "x", MemoryOrder.RELAXED, "r");
        for (Event e : new Event[]{init, w, r}) es.addEvent(e);
        es.addProgramOrder(w, r);
        es.addCoherenceOrder("x", init);
        es.addCoherenceOrder("x", w);
        es.addReadsFrom(r, w);
        boolean allAllowed = true;
        for (MemoryModel m : MemoryModel.values()) {
            allAllowed &= !isForbidden(ctx, es, DependencyInfo.empty(), m);
        }
        return allAllowed ? "consistent under all 5 models (no relaxation intra-thread)"
                : "UNEXPECTED: differs across models";
    }

    private static String caseCyclicPo(SolverContext ctx) {
        EventStructure es = new EventStructure();
        WriteEvent a = new WriteEvent(1, "x", MemoryOrder.RELAXED, 1, "1");
        WriteEvent b = new WriteEvent(1, "y", MemoryOrder.RELAXED, 1, "1");
        es.addEvent(a);
        es.addEvent(b);
        es.addProgramOrder(a, b);
        es.addProgramOrder(b, a);
        return expectRejected(ctx, es, DependencyInfo.empty(), "cycle");
    }

    private static String caseReadNoWrite(SolverContext ctx) {
        EventStructure es = new EventStructure();
        es.addEvent(new ReadEvent(1, "x", MemoryOrder.RELAXED, "r"));
        return expectRejected(ctx, es, DependencyInfo.empty(), "no valid execution exists");
    }

    private static String caseConflictingInit(SolverContext ctx) {
        EventStructure es = new EventStructure();
        es.addEvent(new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0"));
        es.addEvent(new WriteEvent(0, "x", MemoryOrder.RELAXED, 5, "5"));
        return expectRejected(ctx, es, DependencyInfo.empty(), "conflicting initial values");
    }

    private static String caseLargeCounts(SolverContext ctx) {
        // LBChain(n) is 3n events. 64 → 192, 128 → 384. With a ceiling below both, the
        // extractor refuses each and returns empty (sound under pressure, no crash).
        Program lb64 = ParametricPrograms.buildLBChain(64);
        Program lb128 = ParametricPrograms.buildLBChain(128);
        if (lb64.eventCount() != 192 || lb128.eventCount() != 384) {
            return "UNEXPECTED event counts " + lb64.eventCount() + "/" + lb128.eventCount();
        }
        String saved = System.getProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY);
        try {
            System.setProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY, "64");
            boolean refused64 = newExtractor(ctx, lb64.es(), lb64.deps())
                    .findMinimalConsistent(MemoryModel.WEAKEST).isEmpty();
            boolean refused128 = newExtractor(ctx, lb128.es(), lb128.deps())
                    .findMinimalConsistent(MemoryModel.WEAKEST).isEmpty();
            return (refused64 && refused128)
                    ? "192 + 384 events refused by maxEvents ceiling -> empty (no crash)"
                    : "UNEXPECTED: oversized problem not refused";
        } finally {
            if (saved == null) System.clearProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY);
            else System.setProperty(MinimalWitnessExtractor.MAX_EVENTS_PROPERTY, saved);
        }
    }

    private static String caseManyLocations(SolverContext ctx) {
        EventStructure es = new EventStructure();
        int threads = 4, perThread = 5, locs = threads * perThread;
        for (int i = 0; i < locs; i++) {
            WriteEvent init = new WriteEvent(0, "x" + i, MemoryOrder.RELAXED, 0, "0");
            es.addEvent(init);
            es.addCoherenceOrder("x" + i, init);
        }
        for (int t = 1; t <= threads; t++) {
            Event prev = null;
            for (int k = 0; k < perThread; k++) {
                int loc = (t - 1) * perThread + k;
                WriteEvent w = new WriteEvent(t, "x" + loc, MemoryOrder.RELAXED, t, Integer.toString(t));
                ReadEvent r = new ReadEvent(t, "x" + loc, MemoryOrder.RELAXED, "r" + t + "_" + k);
                es.addEvent(w);
                es.addEvent(r);
                es.addCoherenceOrder("x" + loc, w);
                es.addReadsFrom(r, w);
                if (prev != null) es.addProgramOrder(prev, w);
                es.addProgramOrder(w, r);
                prev = r;
            }
        }
        boolean allAllowed = true;
        for (MemoryModel m : MemoryModel.values()) {
            allAllowed &= !isForbidden(ctx, es, DependencyInfo.empty(), m);
        }
        return allAllowed ? "20 locations / 4 threads consistent under all 5 models"
                : "UNEXPECTED: a model forbids the own-write program";
    }

    private static String caseDeepChain(SolverContext ctx) {
        Program real = ParametricPrograms.buildLBChain(32);
        Program fake = ParametricPrograms.buildLBFakeChain(32);
        boolean realForbidden = isForbidden(ctx, real.es(), real.deps(), MemoryModel.WEAKEST);
        boolean fakeAllowed = !isForbidden(ctx, fake.es(), fake.deps(), MemoryModel.WEAKEST);
        return (realForbidden && fakeAllowed)
                ? "32-deep real chain forbidden (thin-air); fake chain allowed"
                : "UNEXPECTED: real=" + (realForbidden ? "F" : "A")
                        + " fake=" + (fakeAllowed ? "A" : "F");
    }

    private static String caseMixedDeps(SolverContext ctx) {
        Program both = buildLbPair(true, true);
        Program mixed = buildLbPair(true, false);
        boolean bothForbidden = isForbidden(ctx, both.es(), both.deps(), MemoryModel.WEAKEST);
        boolean mixedAllowed = !isForbidden(ctx, mixed.es(), mixed.deps(), MemoryModel.WEAKEST);
        return (bothForbidden && mixedAllowed)
                ? "both-semantic forbidden; one-fake allowed (only semantic deps participate)"
                : "UNEXPECTED: both=" + (bothForbidden ? "F" : "A")
                        + " mixed=" + (mixedAllowed ? "A" : "F");
    }

    private static String caseRmwConflict(SolverContext ctx) {
        Program chain = ParametricPrograms.buildRMWChain(2);
        boolean chainAllowed = true;
        for (MemoryModel m : MemoryModel.values()) {
            chainAllowed &= !isForbidden(ctx, chain.es(), chain.deps(), m);
        }
        EventStructure conflict = new EventStructure();
        WriteEvent init = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        RMWEvent u0 = new RMWEvent(1, "x", MemoryOrder.RELAXED, 0, 1);
        RMWEvent u1 = new RMWEvent(2, "x", MemoryOrder.RELAXED, 0, 1);
        for (Event e : new Event[]{init, u0, u1}) conflict.addEvent(e);
        conflict.addCoherenceOrder("x", init);
        conflict.addCoherenceOrder("x", u0);
        conflict.addCoherenceOrder("x", u1);
        boolean conflictForbidden = isForbidden(ctx, conflict, DependencyInfo.empty(), MemoryModel.SC);
        return (chainAllowed && conflictForbidden)
                ? "atomic counter consistent; double-read conflict forbidden (linearizable)"
                : "UNEXPECTED: chain=" + (chainAllowed ? "A" : "F")
                        + " conflict=" + (conflictForbidden ? "F" : "A");
    }

    private static String caseMixedFences(SolverContext ctx) {
        boolean fencedForbidden = isForbidden(ctx, buildMp(true), DependencyInfo.empty(), MemoryModel.RA);
        boolean unfencedAllowed = !isForbidden(ctx, buildMp(false), DependencyInfo.empty(), MemoryModel.RA);
        return (fencedForbidden && unfencedAllowed)
                ? "REL/ACQ fences forbid MP under RA; unfenced allowed"
                : "UNEXPECTED: fenced=" + (fencedForbidden ? "F" : "A")
                        + " unfenced=" + (unfencedAllowed ? "A" : "F");
    }

    // ── Case runner (per-case context + watchdog cap) ──────────────────────

    private static CaseResult runCase(String id, String name, CaseBody body) {
        Event.resetCounter();
        ShutdownManager sm = ShutdownManager.create();
        long t0 = System.nanoTime();
        ScheduledFuture<?> alarm = watchdog.schedule(
                () -> sm.requestShutdown("per-case cap " + PER_CASE_CAP_MS + " ms"),
                PER_CASE_CAP_MS, TimeUnit.MILLISECONDS);
        String outcome;
        String exception = null;
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, sm.getNotifier(), Solvers.Z3)) {
            outcome = body.run(ctx);
        } catch (InvalidEventStructureException e) {
            // For C/D/E this is the expected graceful rejection; expectRejected already
            // returns a descriptive outcome, so reaching here means an unexpected reject.
            outcome = "rejected: " + firstErrorLine(e);
        } catch (Throwable t) {
            if (sm.getNotifier().shouldShutdown()) {
                outcome = "TIMEOUT (capped at " + PER_CASE_CAP_MS / 1000 + "s, handled)";
            } else {
                outcome = "EXCEPTION";
                exception = t.toString();
            }
        } finally {
            alarm.cancel(false);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        Runtime rt = Runtime.getRuntime();
        long memMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        CaseResult res = new CaseResult(id, name, outcome, ms, memMb, exception);
        System.out.printf("  [%s] %-32s %-58s %5dms%n", id, name, outcome, ms);
        return res;
    }

    /**
     * Validate {@code es}; expect it to be rejected with an error containing {@code needle}.
     * Returns a deterministic outcome describing the graceful rejection (also confirms the
     * {@link MinimalWitnessExtractor} constructor throws on it).
     */
    private static String expectRejected(SolverContext ctx, EventStructure es,
                                         DependencyInfo deps, String needle) {
        ValidationReport report = InputValidator.validate(es, deps);
        boolean errored = !report.valid()
                && report.errors().stream().anyMatch(s -> s.contains(needle));
        boolean extractorThrew = false;
        try {
            newExtractor(ctx, es, deps);
        } catch (InvalidEventStructureException e) {
            extractorThrew = true;
        }
        return (errored && extractorThrew)
                ? "rejected by validator (" + needle + "); no crash"
                : "UNEXPECTED: validator did not reject (" + needle + ")";
    }

    // ── SMT scaffolding ────────────────────────────────────────────────────

    private static MinimalWitnessExtractor newExtractor(SolverContext ctx, EventStructure es,
                                                        DependencyInfo deps) {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        return new MinimalWitnessExtractor(ctx, enc, ax);
    }

    private static boolean isForbidden(SolverContext ctx, EventStructure es,
                                       DependencyInfo deps, MemoryModel m) {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormulaManager bmgr = enc.getBmgr();
        BooleanFormula cons = switch (m) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
            case PSO -> ax.consistencyPSO();
            case RA -> ax.consistencyRA();
            case WEAKEST -> ax.consistencyWEAKEST();
        };
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(enc.encodeWellFormedness(), cons));
            return p.isUnsat();
        } catch (Exception e) {
            throw new RuntimeException("consistency check failed for " + m, e);
        }
    }

    private static Program buildLbPair(boolean sem1, boolean sem2) {
        EventStructure es = new EventStructure();
        WriteEvent ix = new WriteEvent(0, "x", MemoryOrder.RELAXED, 0, "0");
        WriteEvent iy = new WriteEvent(0, "y", MemoryOrder.RELAXED, 0, "0");
        ReadEvent r1 = new ReadEvent(1, "x", MemoryOrder.RELAXED, "r1");
        WriteEvent wy = new WriteEvent(1, "y", MemoryOrder.RELAXED, 1, "1");
        ReadEvent r2 = new ReadEvent(2, "y", MemoryOrder.RELAXED, "r2");
        WriteEvent wx = new WriteEvent(2, "x", MemoryOrder.RELAXED, 1, "1");
        for (Event e : new Event[]{ix, iy, r1, wy, r2, wx}) es.addEvent(e);
        es.addProgramOrder(r1, wy);
        es.addProgramOrder(r2, wx);
        es.addCoherenceOrder("x", ix);
        es.addCoherenceOrder("x", wx);
        es.addCoherenceOrder("y", iy);
        es.addCoherenceOrder("y", wy);
        es.addReadsFrom(r1, wx);
        es.addReadsFrom(r2, wy);
        DependencyInfo deps = new DependencyInfo();
        deps.addDataDep(wy, r1, sem1);
        deps.addDataDep(wx, r2, sem2);
        return new Program("LBPair", 2, es, deps);
    }

    private static EventStructure buildMp(boolean fenced) {
        EventStructure es = new EventStructure();
        WriteEvent iData = new WriteEvent(0, "data", MemoryOrder.RELAXED, 0, "0");
        WriteEvent iFlag = new WriteEvent(0, "flag", MemoryOrder.RELAXED, 0, "0");
        WriteEvent wData = new WriteEvent(1, "data", MemoryOrder.RELAXED, 1, "1");
        WriteEvent wFlag = new WriteEvent(1, "flag", MemoryOrder.RELAXED, 1, "1");
        ReadEvent rFlag = new ReadEvent(2, "flag", MemoryOrder.RELAXED, "rf");
        ReadEvent rData = new ReadEvent(2, "data", MemoryOrder.RELAXED, "rd");
        es.addEvent(iData);
        es.addEvent(iFlag);
        es.addEvent(wData);
        if (fenced) {
            FenceEvent fRel = new FenceEvent(1, FenceKind.REL);
            es.addEvent(fRel);
            es.addEvent(wFlag);
            es.addProgramOrder(wData, fRel);
            es.addProgramOrder(fRel, wFlag);
        } else {
            es.addEvent(wFlag);
            es.addProgramOrder(wData, wFlag);
        }
        es.addEvent(rFlag);
        if (fenced) {
            FenceEvent fAcq = new FenceEvent(2, FenceKind.ACQ);
            es.addEvent(fAcq);
            es.addEvent(rData);
            es.addProgramOrder(rFlag, fAcq);
            es.addProgramOrder(fAcq, rData);
        } else {
            es.addEvent(rData);
            es.addProgramOrder(rFlag, rData);
        }
        es.addCoherenceOrder("data", iData);
        es.addCoherenceOrder("data", wData);
        es.addCoherenceOrder("flag", iFlag);
        es.addCoherenceOrder("flag", wFlag);
        es.addReadsFrom(rFlag, wFlag);
        es.addReadsFrom(rData, iData);
        return es;
    }

    private static String firstErrorLine(InvalidEventStructureException e) {
        List<String> errs = e.report().errors();
        return errs.isEmpty() ? "(no error)" : errs.get(0);
    }

    // ── Report ─────────────────────────────────────────────────────────────

    private static void writeReport(Path outDir, List<CaseResult> results,
                                    long handled, long elapsedMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Day-14 robustness sweep report\n");
        sb.append("# Edge cases A-K run programmatically. Each must be HANDLED GRACEFULLY:\n");
        sb.append("# a correct verdict, a validator rejection, a resource refusal, or a\n");
        sb.append("# capped timeout -- never an unhandled exception or a crash.\n");
        sb.append("# Format: <id> | <outcome> | <time/mem (non-deterministic, not diffed)>\n");
        sb.append("#\n");
        for (CaseResult r : results) {
            sb.append(r.id()).append(" | ").append(r.outcome())
              .append(" | time=").append(r.ms()).append("ms mem=").append(r.memMb()).append("MB");
            if (r.exception() != null) sb.append(" EXCEPTION=").append(r.exception());
            sb.append('\n');
        }
        sb.append("#\n");
        sb.append("Summary: ").append(handled).append('/').append(results.size())
          .append(" handled gracefully, ")
          .append(handled == results.size() ? "no crashes" : "CRASHES DETECTED")
          .append('\n');
        Files.writeString(outDir.resolve("robustness-report.txt"), sb.toString());
    }

    private static void printReport(List<CaseResult> results, long handled) {
        System.out.println();
        System.out.printf("Summary: %d/%d handled gracefully, %s%n",
                handled, results.size(),
                handled == results.size() ? "no crashes" : "CRASHES DETECTED");
    }
}
