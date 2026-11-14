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
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Producer for {@code eval/rc11-direction-check.csv} — the §6.4 claim that RC11 and
 * WEAKEST never diverge in the {@code rc11_allows_weakest_forbids} direction.
 *
 * <p>Runs every {@code .litmus} in {@code eval/examples/paper} and {@code eval/examples/jmm}
 * (7 + 20 = 27 tests) under both RC11 and WEAKEST on the loader-wired candidate, and
 * classifies each into one of three directions:
 *
 * <ul>
 *   <li>{@code agree} — both models return the same verdict;</li>
 *   <li>{@code rc11_forbids_weakest_allows} — RC11 over-restricts a grounded/fake LB
 *       that WEAKEST recovers (the expected direction of divergence);</li>
 *   <li>{@code rc11_allows_weakest_forbids} — the *unexpected* direction. RC11's
 *       {@code acyclic(po ∪ rf)} is strictly stronger than WEAKEST's
 *       {@code acyclic(sdep ∪ jf)} on this fragment, since {@code sdep ⊆ po} and
 *       {@code jf ⊆ rf}, so this should never occur. A non-zero count here is a
 *       soundness signal, not a curiosity.</li>
 * </ul>
 *
 * <p>Row order is by filename within each directory (paper first, then jmm), which is
 * what makes the output byte-comparable against the committed CSV. Note that this puts
 * {@code LB-fake-xor-cycle} before {@code LB-fake-xor}: the comparison is on the full
 * filename, and {@code '-'} (0x2D) sorts before {@code '.'} (0x2E).
 *
 * <p>RC11 here is the deliberately simplified {@code acyclic(po ∪ rf)} of
 * {@link AxiomaticConsistency#consistencyRC11()} — no release/acquire, as our litmus
 * format carries none. See {@code eval/rc11-comparison.csv} (written by
 * {@code wev.smt.ablation.Rc11ComparisonRun}) for the narrower hand-picked §6.4 panel
 * with per-test wiring diagnostics; this class is the corpus-wide direction sweep.
 *
 * <p>Usage: {@code Rc11DirectionCheck [out.csv] [dir ...]}, defaulting to
 * {@code eval/rc11-direction-check.csv} over the two example directories above.
 * Requires {@code -Djava.library.path=target/native} so JavaSMT can load Z3.
 */
public final class Rc11DirectionCheck {

    private Rc11DirectionCheck() { }

    private enum Verdict { ALLOWED, FORBIDDEN, ERROR }

    private record Row(String test, Verdict rc11, Verdict weakest, String direction) { }

    public static void main(String[] args) throws Exception {
        Path outCsv = Path.of(args.length > 0 ? args[0] : "eval/rc11-direction-check.csv");
        List<Path> dirs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            dirs.add(Path.of(args[i]));
        }
        if (dirs.isEmpty()) {
            dirs.add(Path.of("eval", "examples", "paper"));
            dirs.add(Path.of("eval", "examples", "jmm"));
        }

        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            files.addAll(litmusFilesIn(dir));
        }
        System.out.printf(Locale.ROOT, "RC11-vs-WEAKEST direction check: %d tests from %s%n",
                files.size(), dirs);

        List<Row> rows = new ArrayList<>();
        for (Path f : files) {
            rows.add(check(f));
        }

        writeCsv(outCsv, rows);
        printSummary(rows, outCsv);

        // Exit non-zero if the unexpected direction ever fires — this is the claim under
        // test, so a regression should break a CI/reproduction run rather than be buried
        // in a CSV nobody reads.
        long unexpected = rows.stream()
                .filter(r -> r.direction().equals("rc11_allows_weakest_forbids")).count();
        long errors = rows.stream().filter(r -> r.direction().equals("error")).count();
        if (unexpected > 0 || errors > 0) {
            System.err.printf(Locale.ROOT,
                    "FAIL: %d unexpected-direction, %d error row(s)%n", unexpected, errors);
            System.exit(1);
        }
    }

    private static List<Path> litmusFilesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            System.err.println("[warn] not a directory, skipping: " + dir);
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".litmus"))
                    // Sort on the full filename (extension included) so the row order is
                    // reproducible and matches the committed CSV.
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /** Both verdicts for one file, sharing a single solver context and encoding. */
    private static Row check(Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.litmus$", "");
        Configuration cfg;
        try {
            cfg = Configuration.defaultConfiguration();
            LogManager log = BasicLogManager.create(cfg);
            LitmusCase lc = LitmusParser.parse(Files.readString(file), name);
            try (SolverContext ctx = SolverContextFactory.createSolverContext(
                    cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
                BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
                EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                Verdict rc11 = solve(ctx, bmgr, wf, ax.consistencyRC11());
                Verdict weakest = solve(ctx, bmgr, wf, ax.consistencyWEAKEST());
                Row row = new Row(name, rc11, weakest, direction(rc11, weakest));
                System.out.printf(Locale.ROOT, "  %-26s RC11=%-9s WEAKEST=%-9s %s%n",
                        row.test(), row.rc11(), row.weakest(), row.direction());
                return row;
            }
        } catch (Exception e) {
            System.out.printf(Locale.ROOT, "  %-26s ERROR (%s)%n",
                    name, e.getClass().getSimpleName());
            return new Row(name, Verdict.ERROR, Verdict.ERROR, "error");
        }
    }

    private static Verdict solve(SolverContext ctx, BooleanFormulaManager bmgr,
                                 BooleanFormula wf, BooleanFormula cons) {
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(wf, cons));
            return p.isUnsat() ? Verdict.FORBIDDEN : Verdict.ALLOWED;
        } catch (Exception e) {
            return Verdict.ERROR;
        }
    }

    private static String direction(Verdict rc11, Verdict weakest) {
        if (rc11 == Verdict.ERROR || weakest == Verdict.ERROR) return "error";
        if (rc11 == weakest) return "agree";
        return (rc11 == Verdict.FORBIDDEN)
                ? "rc11_forbids_weakest_allows"
                : "rc11_allows_weakest_forbids";
    }

    private static void writeCsv(Path path, List<Row> rows) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder("test,rc11_verdict,weakest_verdict,direction\n");
        for (Row r : rows) {
            sb.append(r.test()).append(',')
              .append(r.rc11()).append(',')
              .append(r.weakest()).append(',')
              .append(r.direction()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static void printSummary(List<Row> rows, Path outCsv) {
        long agree = rows.stream().filter(r -> r.direction().equals("agree")).count();
        long fwa = rows.stream()
                .filter(r -> r.direction().equals("rc11_forbids_weakest_allows")).count();
        long awf = rows.stream()
                .filter(r -> r.direction().equals("rc11_allows_weakest_forbids")).count();
        long err = rows.stream().filter(r -> r.direction().equals("error")).count();
        System.out.println("\n--- direction summary ---");
        System.out.printf(Locale.ROOT, "  agree                        : %d%n", agree);
        System.out.printf(Locale.ROOT, "  rc11_forbids_weakest_allows  : %d%n", fwa);
        System.out.printf(Locale.ROOT, "  rc11_allows_weakest_forbids  : %d  (expected 0)%n", awf);
        if (err > 0) System.out.printf(Locale.ROOT, "  error                        : %d%n", err);
        System.out.printf(Locale.ROOT, "%nCSV: %s%n", outCsv.toAbsolutePath());
    }
}
