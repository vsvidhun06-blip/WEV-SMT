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
import wev.smt.LitmusCorpus;
import wev.smt.LitmusCorpus.LitmusCase;
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;
import wev.smt.MinimalWitness;
import wev.smt.MinimalWitnessExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reconstructs the weak-memory-model "separation atlas" from {@link LitmusCorpus}.
 *
 * <p>Two analyses, both driven by a single shared {@link SolverContext}:
 * <ol>
 *   <li><b>Validation.</b> For every (litmus, model) cell, decide whether the wired
 *       forbidden-under-SC outcome is consistent (ALLOWED) or not (FORBIDDEN) under
 *       that model — the same full-execution consistency check as
 *       {@code AxiomaticConsistencyTest} — and compare it against the textbook
 *       outcome recorded in the corpus. Disagreements are reported, never fatal:
 *       they pinpoint either an encoding limitation or a genuine model subtlety.</li>
 *   <li><b>Separation.</b> For every ordered pair (M, M') with M &ne; M', search for
 *       the minimum-cardinality execution that M allows but M' forbids
 *       ({@link MinimalWitnessExtractor#findMinimalSeparating}). The set of tests
 *       that separate each pair, with their minimum witness sizes, is the atlas.</li>
 * </ol>
 *
 * <p>Outputs {@code consistency-validation.csv}, {@code atlas-separations.csv} and
 * {@code mismatches.txt} in the working directory (or the directory named by the
 * first CLI argument), and prints a validation summary plus the atlas matrix.
 *
 * <p>The whole run is bounded by {@link #BUDGET_MS}. Validation always completes;
 * separation work is ordered most-informative-first and abandoned (recorded as
 * {@code skipped}) once the budget is spent, so the tool never exceeds it.
 *
 * <p>Note on sizes: {@link MinimalWitnessExtractor#findMinimalConsistent} returns the
 * smallest <em>consistent</em> sub-execution, which under the gated encoding is the
 * trivial single event — uninformative for outcome validation. Validation therefore
 * uses the full-execution consistency verdict; the minimum-cardinality machinery is
 * exercised where it is meaningful: the separation atlas.
 */
public final class AtlasReconstruct {

    private static final long BUDGET_MS = 5 * 60 * 1000L;
    /** Wall-clock point after which no new separation searches are started. */
    private static final long SEPARATION_DEADLINE_MS = 260 * 1000L;
    /** Per-pair refinement budget; a single no-separation pair cannot exceed this. */
    private static final long PER_CALL_BUDGET_MS = 3000L;

    private static final MemoryModel[] MODELS = MemoryModel.values();

    private static final String RED = "[31m";
    private static final String GREEN = "[32m";
    private static final String DIM = "[2m";
    private static final String RESET = "[0m";

    private AtlasReconstruct() { }

    private record ValidationRow(String litmus, MemoryModel model, Outcome expected,
                                 Outcome actual, boolean compared, boolean match,
                                 int size, long ms) { }

    private record SeparationRow(String litmus, MemoryModel allow, MemoryModel forbid,
                                 String status, Integer size, long ms) { }

    /** Per-case SMT scaffolding, built once over the shared context. */
    private record Encoded(LitmusCase lc, EventStructureEncoder enc,
                           AxiomaticConsistency ax, MinimalWitnessExtractor mw,
                           BooleanFormula wellFormed) { }

    public static void main(String[] args) throws Exception {
        Path outDir = (args.length > 0) ? Path.of(args[0]) : Path.of(".");
        Files.createDirectories(outDir);

        long start = System.currentTimeMillis();

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {

            List<LitmusCase> cases = LitmusCorpus.classics();
            System.out.printf("Atlas reconstruction over %d litmus tests x %d models%n",
                    cases.size(), MODELS.length);

            List<Encoded> encoded = new ArrayList<>();
            for (LitmusCase lc : cases) {
                EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                MinimalWitnessExtractor mw = new MinimalWitnessExtractor(ctx, enc, ax);
                encoded.add(new Encoded(lc, enc, ax, mw, enc.encodeWellFormedness()));
            }

            List<ValidationRow> validation = validate(ctx, encoded);
            List<SeparationRow> separations = separate(encoded, start);

            writeValidationCsv(outDir, validation);
            writeAtlasCsv(outDir, separations);
            writeMismatches(outDir, validation);

            printValidationSummary(validation);
            printAtlasMatrix(separations);

            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("%nTotal wall-clock: %.1f s (budget %.0f s). CSVs in %s%n",
                    elapsed / 1000.0, BUDGET_MS / 1000.0, outDir.toAbsolutePath());
        }
    }

    // ── Validation: full-execution consistency vs. textbook ────────────────

    private static List<ValidationRow> validate(SolverContext ctx, List<Encoded> encoded)
            throws Exception {
        BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
        List<ValidationRow> rows = new ArrayList<>();
        for (Encoded e : encoded) {
            int size = e.lc().es().getEvents().size();
            for (MemoryModel m : MODELS) {
                BooleanFormula cons = consistencyOf(e.ax(), m);
                long t0 = System.currentTimeMillis();
                boolean unsat;
                try (ProverEnvironment p = ctx.newProverEnvironment()) {
                    p.addConstraint(bmgr.and(e.wellFormed(), cons));
                    unsat = p.isUnsat();
                }
                long ms = System.currentTimeMillis() - t0;
                Outcome actual = unsat ? Outcome.FORBIDDEN : Outcome.ALLOWED;
                Outcome expected = e.lc().expected().getOrDefault(m, Outcome.UNKNOWN);
                boolean compared = expected != Outcome.UNKNOWN;
                boolean match = compared && expected == actual;
                rows.add(new ValidationRow(e.lc().name(), m, expected, actual,
                        compared, match, size, ms));
            }
        }
        return rows;
    }

    // ── Separation: minimum differential witnesses ─────────────────────────

    private static List<SeparationRow> separate(List<Encoded> encoded, long start) {
        // Order the (case, allow, forbid) jobs most-informative-first so that, if the
        // budget runs out, the cells most likely to yield a witness are computed.
        // Plausibility never decides the *result* — only the order of computation.
        record Job(Encoded e, MemoryModel allow, MemoryModel forbid, boolean plausible) { }
        List<Job> jobs = new ArrayList<>();
        for (Encoded e : encoded) {
            for (MemoryModel allow : MODELS) {
                for (MemoryModel forbid : MODELS) {
                    if (allow == forbid) continue;
                    Outcome ea = e.lc().expected().getOrDefault(allow, Outcome.UNKNOWN);
                    Outcome ef = e.lc().expected().getOrDefault(forbid, Outcome.UNKNOWN);
                    boolean plausible =
                            ea != Outcome.FORBIDDEN && ef != Outcome.ALLOWED;
                    jobs.add(new Job(e, allow, forbid, plausible));
                }
            }
        }
        jobs.sort((a, b) -> Boolean.compare(b.plausible(), a.plausible()));

        List<SeparationRow> rows = new ArrayList<>();
        boolean budgetSpent = false;
        for (Job j : jobs) {
            long elapsed = System.currentTimeMillis() - start;
            if (budgetSpent || elapsed > SEPARATION_DEADLINE_MS) {
                budgetSpent = true;
                rows.add(new SeparationRow(j.e().lc().name(), j.allow(), j.forbid(),
                        "skipped", null, 0));
                continue;
            }
            long t0 = System.currentTimeMillis();
            Optional<MinimalWitness> w =
                    j.e().mw().findMinimalSeparating(j.allow(), j.forbid(), PER_CALL_BUDGET_MS);
            long ms = System.currentTimeMillis() - t0;
            if (w.isPresent()) {
                rows.add(new SeparationRow(j.e().lc().name(), j.allow(), j.forbid(),
                        "separated", w.get().cardinality(), ms));
            } else {
                // Distinguish an exhausted search ("none") from one cut off by the
                // per-call budget ("capped"): the latter ran (almost) the full budget.
                String status = ms >= (PER_CALL_BUDGET_MS * 9) / 10 ? "capped" : "none";
                rows.add(new SeparationRow(j.e().lc().name(), j.allow(), j.forbid(),
                        status, null, ms));
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

    // ── CSV / log output ───────────────────────────────────────────────────

    private static void writeValidationCsv(Path dir, List<ValidationRow> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder("litmus,model,expected,actual,match,size,ms\n");
        for (ValidationRow r : rows) {
            sb.append(r.litmus()).append(',')
              .append(r.model()).append(',')
              .append(r.expected()).append(',')
              .append(r.actual()).append(',')
              .append(r.compared() ? r.match() : "n/a").append(',')
              .append(r.size()).append(',')
              .append(r.ms()).append('\n');
        }
        Files.writeString(dir.resolve("consistency-validation.csv"), sb.toString());
    }

    private static void writeAtlasCsv(Path dir, List<SeparationRow> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder("litmus,allowedBy,forbiddenBy,status,witnessSize,ms\n");
        for (SeparationRow r : rows) {
            sb.append(r.litmus()).append(',')
              .append(r.allow()).append(',')
              .append(r.forbid()).append(',')
              .append(r.status()).append(',')
              .append(r.size() == null ? "" : r.size()).append(',')
              .append(r.ms()).append('\n');
        }
        Files.writeString(dir.resolve("atlas-separations.csv"), sb.toString());
    }

    private static void writeMismatches(Path dir, List<ValidationRow> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Validation mismatches (encoding verdict != textbook outcome).\n");
        sb.append("# These are research findings, not test failures: each is either an\n");
        sb.append("# incompleteness in this prototype's axioms or a genuine model subtlety.\n\n");
        int n = 0;
        for (ValidationRow r : rows) {
            if (r.compared() && !r.match()) {
                n++;
                sb.append(String.format("%-12s %-8s expected=%-9s actual=%-9s%n",
                        r.litmus(), r.model(), r.expected(), r.actual()));
            }
        }
        sb.append(String.format("%n%d mismatch(es).%n", n));
        Files.writeString(dir.resolve("mismatches.txt"), sb.toString());
    }

    // ── Console summaries ──────────────────────────────────────────────────

    private static void printValidationSummary(List<ValidationRow> rows) {
        int compared = 0, matched = 0;
        for (ValidationRow r : rows) {
            if (r.compared()) {
                compared++;
                if (r.match()) matched++;
            }
        }
        int mismatched = compared - matched;
        System.out.println();
        System.out.println("== Validation: encoding verdict vs. textbook ==");
        System.out.printf("Compared cells: %d   %smatched: %d%s   %smismatched: %d%s%n",
                compared, GREEN, matched, RESET,
                mismatched == 0 ? GREEN : RED, mismatched, RESET);

        if (mismatched > 0) {
            System.out.println("Mismatches (paper material, logged to mismatches.txt):");
            for (ValidationRow r : rows) {
                if (r.compared() && !r.match()) {
                    System.out.printf("  %s%-12s %-8s expected=%-9s actual=%-9s%s%n",
                            RED, r.litmus(), r.model(), r.expected(), r.actual(), RESET);
                }
            }
        }
    }

    private static void printAtlasMatrix(List<SeparationRow> rows) {
        System.out.println();
        System.out.println("== Separation atlas: M allows / M' forbids (minimum witness size) ==");
        for (MemoryModel allow : MODELS) {
            for (MemoryModel forbid : MODELS) {
                if (allow == forbid) continue;
                List<SeparationRow> hits = new ArrayList<>();
                int none = 0, capped = 0, skipped = 0;
                for (SeparationRow r : rows) {
                    if (r.allow() == allow && r.forbid() == forbid) {
                        switch (r.status()) {
                            case "separated" -> hits.add(r);
                            case "none" -> none++;
                            case "capped" -> capped++;
                            case "skipped" -> skipped++;
                            default -> { }
                        }
                    }
                }
                StringBuilder tail = new StringBuilder();
                List<String> notes = new ArrayList<>();
                if (none > 0) notes.add(none + " none");
                if (capped > 0) notes.add(capped + " capped");
                if (skipped > 0) notes.add(skipped + " skipped");
                if (!notes.isEmpty()) {
                    tail.append(DIM).append("  [")
                        .append(String.join(", ", notes)).append("]").append(RESET);
                }

                StringBuilder line = new StringBuilder();
                line.append(String.format("%-8s \\ %-8s : ", allow, forbid));
                if (hits.isEmpty()) {
                    line.append(DIM).append("(no separating test)").append(RESET).append(tail);
                } else {
                    hits.sort((a, b) -> Integer.compare(a.size(), b.size()));
                    List<String> parts = new ArrayList<>();
                    for (SeparationRow h : hits) {
                        parts.add(h.litmus() + "(" + h.size() + ")");
                    }
                    line.append(String.join(", ", parts)).append(tail);
                }
                System.out.println(line);
            }
        }
    }
}
