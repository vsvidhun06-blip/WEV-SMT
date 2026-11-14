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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares our PSO (which retains RC11 release/acquire cross-location W→R ordering via
 * {@code irreflexiveHbEco}) against canonical SPARC PSO
 * ({@link AxiomaticConsistency#consistencyPSOCanonical}, the same model without that
 * conjunct) over the 40-test {@link LitmusCorpus#classics()} atlas.
 *
 * <p>For each test the full-execution consistency verdict of the wired candidate is
 * recorded under both models (ALLOWED = consistent/SAT, FORBIDDEN = UNSAT). Output is
 * {@code eval/pso-canonical-comparison.csv} with columns
 * {@code test,pso_verdict,pso_canonical_verdict,differs}. Canonical PSO can only ever be
 * weaker (more ALLOWED), so a difference always means PSO=FORBIDDEN, canonical=ALLOWED.
 */
public final class PsoCanonicalComparison {

    private PsoCanonicalComparison() { }

    public static void main(String[] args) throws Exception {
        Path outCsv = (args.length > 0) ? Path.of(args[0])
                : Path.of("eval", "pso-canonical-comparison.csv");
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<String> rows = new ArrayList<>();
        rows.add("test,pso_verdict,pso_canonical_verdict,differs");
        int differ = 0;

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            List<LitmusCase> cases = LitmusCorpus.classics();
            System.out.printf("PSO vs PSO_CANONICAL over %d classics tests%n", cases.size());

            for (LitmusCase lc : cases) {
                EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();

                String pso = verdict(ctx, bmgr, wf, ax.consistencyPSO());
                String canon = verdict(ctx, bmgr, wf, ax.consistencyPSOCanonical());
                boolean diff = !pso.equals(canon);
                if (diff) differ++;
                rows.add(lc.name() + ',' + pso + ',' + canon + ',' + diff);
                System.out.printf("  %-14s PSO=%-9s PSO_CANONICAL=%-9s%s%n",
                        lc.name(), pso, canon, diff ? "   <-- DIFFERS" : "");
            }
        }

        Files.writeString(outCsv, String.join("\n", rows) + "\n");
        System.out.printf("%n%d of %d tests differ. CSV: %s%n",
                differ, rows.size() - 1, outCsv.toAbsolutePath());
    }

    /** ALLOWED if the wired candidate is consistent (SAT) under {@code cons}, else FORBIDDEN. */
    private static String verdict(SolverContext ctx, BooleanFormulaManager bmgr,
            BooleanFormula wf, BooleanFormula cons) throws Exception {
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(wf, cons));
            return p.isUnsat() ? "FORBIDDEN" : "ALLOWED";
        }
    }
}
