package wev.smt.cli;

import org.sosy_lab.common.ShutdownNotifier;
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
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;
import wev.smt.MinimalWitness;
import wev.smt.MinimalWitnessExtractor;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Validates the SMT decision procedure against a directory of herd7 {@code .litmus}
 * tests parsed by {@link LitmusParser}.
 *
 * <p>For every parsed test and every model in {@link MemoryModel}, this records:
 * <ul>
 *   <li><b>{@code actual}</b> — the full-execution consistency verdict of the
 *       {@code exists}-wired candidate: {@code ALLOWED} if that execution is consistent
 *       under the model, {@code FORBIDDEN} otherwise. This is the validated question
 *       (the same one {@code AtlasReconstruct} answers) and the one comparable to
 *       herd7's per-model {@code Allowed}/{@code Forbidden}.</li>
 *   <li><b>{@code witness_size}</b> — the cardinality of the minimum consistent
 *       sub-execution from {@link MinimalWitnessExtractor#findMinimalConsistent}
 *       ({@code -1} if none, {@code -2} if skipped past the budget). Under the gated
 *       encoding this is typically the trivial singleton, so it is a secondary datum,
 *       not the verdict — see the note in {@code AtlasReconstruct}.</li>
 * </ul>
 *
 * <p>Outputs {@code corpus-validation.csv} (one row per test × model) and prints a
 * per-architecture summary: comparable cells, % matched, mean solve time and the
 * slowest / mismatching outliers. The whole run is bounded by {@link #BUDGET_MS}; the
 * cheap full-execution verdict is always recorded, while the optional minimum-witness
 * optimisation is skipped once the deadline passes.
 *
 * <p>Usage: {@code CorpusValidation <litmus-dir> [out-dir]}. A single unparseable file
 * never aborts the run — {@link LitmusParser#parseDirectory} logs and skips it.
 */
public final class CorpusValidation {

    private static final long BUDGET_MS = 10 * 60 * 1000L;
    /** After this point, skip the (optional) minimum-witness optimisation. */
    private static final long MINWIT_DEADLINE_MS = 5 * 60 * 1000L;
    private static final MemoryModel[] MODELS = MemoryModel.values();

    private CorpusValidation() { }

    private record Row(String file, String arch, MemoryModel model, Outcome expected,
                       Outcome actual, boolean compared, boolean match,
                       int witnessSize, long solveMs) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CorpusValidation <litmus-dir> [out-dir]");
            System.exit(2);
            return;
        }
        Path litmusDir = Path.of(args[0]);
        Path outDir = (args.length > 1) ? Path.of(args[1]) : Path.of(".");
        Files.createDirectories(outDir);

        if (!Files.isDirectory(litmusDir)) {
            System.err.println("not a directory: " + litmusDir.toAbsolutePath());
            System.exit(2);
            return;
        }

        long start = System.currentTimeMillis();
        List<LitmusCase> cases = LitmusParser.parseDirectory(litmusDir);
        System.out.printf("Parsed %d .litmus file(s) from %s%n",
                cases.size(), litmusDir.toAbsolutePath());
        if (cases.isEmpty()) {
            System.out.println("Nothing to validate.");
            return;
        }

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<Row> rows = new ArrayList<>();
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            for (LitmusCase lc : cases) {
                rows.addAll(validateCase(ctx, bmgr, lc, start));
            }
        }

        writeCsv(outDir, rows);
        printSummary(rows);
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("%nTotal wall-clock: %.1f s. CSV in %s%n",
                elapsed / 1000.0, outDir.toAbsolutePath());
    }

    private static List<Row> validateCase(SolverContext ctx, BooleanFormulaManager bmgr,
                                          LitmusCase lc, long start) throws Exception {
        List<Row> rows = new ArrayList<>();
        EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        MinimalWitnessExtractor mw = new MinimalWitnessExtractor(ctx, enc, ax);
        BooleanFormula wf = enc.encodeWellFormedness();
        String arch = lc.arch().name();

        for (MemoryModel m : MODELS) {
            BooleanFormula cons = consistencyOf(ax, m);
            long t0 = System.currentTimeMillis();
            boolean unsat;
            try (ProverEnvironment p = ctx.newProverEnvironment()) {
                p.addConstraint(bmgr.and(wf, cons));
                unsat = p.isUnsat();
            }
            Outcome actual = unsat ? Outcome.FORBIDDEN : Outcome.ALLOWED;

            // Secondary: minimum consistent sub-execution cardinality (budget-permitting).
            int witness = -2;
            if (System.currentTimeMillis() - start < MINWIT_DEADLINE_MS) {
                Optional<MinimalWitness> w = mw.findMinimalConsistent(m);
                witness = w.map(MinimalWitness::cardinality).orElse(-1);
            }
            long ms = System.currentTimeMillis() - t0;

            Outcome expected = lc.expectations().getOrDefault(m, Outcome.UNKNOWN);
            boolean compared = expected != Outcome.UNKNOWN;
            boolean match = compared && expected == actual;
            rows.add(new Row(lc.sourceName(), arch, m, expected, actual,
                    compared, match, witness, ms));

            if (System.currentTimeMillis() - start > BUDGET_MS) {
                System.err.println("[budget] stopping after " + lc.sourceName());
                return rows;
            }
        }
        return rows;
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

    // ── Output ─────────────────────────────────────────────────────────────────

    private static void writeCsv(Path dir, List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder(
                "file,arch,model,expected,actual,witness_size,solve_ms,match\n");
        for (Row r : rows) {
            sb.append(r.file()).append(',')
              .append(r.arch()).append(',')
              .append(r.model()).append(',')
              .append(r.compared() ? r.expected() : "").append(',')
              .append(r.actual()).append(',')
              .append(r.witnessSize()).append(',')
              .append(r.solveMs()).append(',')
              .append(r.compared() ? r.match() : "n/a").append('\n');
        }
        Files.writeString(dir.resolve("corpus-validation.csv"), sb.toString());
    }

    private static void printSummary(List<Row> rows) {
        // Group by architecture.
        Map<String, List<Row>> byArch = new LinkedHashMap<>();
        for (Row r : rows) byArch.computeIfAbsent(r.arch(), k -> new ArrayList<>()).add(r);

        System.out.println();
        System.out.println("== Per-architecture validation summary ==");
        System.out.printf("%-8s %8s %8s %8s %10s%n",
                "arch", "cells", "compared", "matched", "mean_ms");
        for (Map.Entry<String, List<Row>> e : byArch.entrySet()) {
            List<Row> rs = e.getValue();
            int compared = 0, matched = 0;
            long totalMs = 0;
            for (Row r : rs) {
                totalMs += r.solveMs();
                if (r.compared()) {
                    compared++;
                    if (r.match()) matched++;
                }
            }
            double pct = compared == 0 ? 0.0 : 100.0 * matched / compared;
            System.out.printf(Locale.ROOT, "%-8s %8d %8d %7.0f%% %10.1f%n",
                    e.getKey(), rs.size(), compared, pct, (double) totalMs / rs.size());
        }

        // Mismatches and slow outliers across the whole corpus.
        List<Row> mismatches = new ArrayList<>();
        Row slowest = null;
        for (Row r : rows) {
            if (r.compared() && !r.match()) mismatches.add(r);
            if (slowest == null || r.solveMs() > slowest.solveMs()) slowest = r;
        }
        System.out.printf("%nMismatches (parser/encoder verdict != herd7 ground truth): %d%n",
                mismatches.size());
        int shown = 0;
        for (Row r : mismatches) {
            if (shown++ >= 25) {
                System.out.printf("  … and %d more%n", mismatches.size() - 25);
                break;
            }
            System.out.printf("  %-24s %-8s expected=%-9s actual=%-9s%n",
                    r.file(), r.model(), r.expected(), r.actual());
        }
        if (slowest != null) {
            System.out.printf("Slowest cell: %s / %s = %d ms%n",
                    slowest.file(), slowest.model(), slowest.solveMs());
        }
    }
}
