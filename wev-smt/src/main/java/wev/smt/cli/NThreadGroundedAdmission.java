package wev.smt.cli;

import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;

import wev.smt.AxiomaticConsistency;
import wev.smt.EventStructureEncoder;
import wev.smt.bench.ParametricPrograms;
import wev.smt.bench.ParametricPrograms.Program;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the paper's <em>n-thread grounded LB admission</em> remark: the fake-dependency
 * load-buffering chain {@code LBFakeChain(n)} is ALLOWED by WEAKEST for every {@code n},
 * because its (identity, value-irrelevant) data dependencies are excluded from {@code sdep}
 * and therefore close no out-of-thin-air cycle.
 *
 * <p>Uses exactly the headline consistency check of {@link ScalabilitySweep}: build
 * well-formedness ∧ {@code consistencyWEAKEST}, then {@code isUnsat}. UNSAT means the
 * wired execution has no consistent model (FORBIDDEN); SAT means it does (ALLOWED). Each
 * {@code n} owns a fresh {@link SolverContext}. Writes {@code eval/nthread-grounded-admission.csv}
 * with columns {@code n,events,verdict,fullexec_ms}.
 */
public final class NThreadGroundedAdmission {

    private NThreadGroundedAdmission() { }

    private record Row(int n, int events, String verdict, long fullExecMs) { }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "eval");
        int lo = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int hi = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        Files.createDirectories(outDir);

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);

        com.weakest.model.Event.resetCounter();
        System.out.printf("n-thread grounded LB admission: LBFakeChain(n) under WEAKEST, n=%d..%d%n%n",
                lo, hi);

        List<Row> rows = new ArrayList<>();
        boolean allAllowed = true;
        for (int n = lo; n <= hi; n++) {
            Program prog = ParametricPrograms.buildLBFakeChain(n);

            ShutdownManager sm = ShutdownManager.create();
            SolverContext ctx = SolverContextFactory.createSolverContext(
                    cfg, log, sm.getNotifier(), Solvers.Z3);
            try {
                long t0 = System.nanoTime();
                EventStructureEncoder enc = new EventStructureEncoder(ctx, prog.es(), prog.deps());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                BooleanFormula cons = ax.consistencyWEAKEST();
                boolean unsat;
                try (ProverEnvironment p = ctx.newProverEnvironment()) {
                    p.addConstraint(enc.getBmgr().and(wf, cons));
                    unsat = p.isUnsat();
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;

                String verdict = unsat ? "FORBIDDEN" : "ALLOWED";
                if (unsat) allAllowed = false;
                rows.add(new Row(n, prog.eventCount(), verdict, ms));
                System.out.printf("  LBFakeChain n=%-2d events=%-3d %-9s full=%5dms%s%n",
                        n, prog.eventCount(), verdict, ms,
                        unsat ? "   !! EXPECTED ALLOWED" : "");
            } finally {
                ctx.close();
            }
        }

        StringBuilder sb = new StringBuilder("n,events,verdict,fullexec_ms\n");
        for (Row r : rows) {
            sb.append(r.n()).append(',').append(r.events()).append(',')
              .append(r.verdict()).append(',').append(r.fullExecMs()).append('\n');
        }
        Path out = outDir.resolve("nthread-grounded-admission.csv");
        Files.writeString(out, sb.toString());

        System.out.printf("%nAll ALLOWED under WEAKEST: %s%n", allAllowed ? "YES" : "NO");
        System.out.printf("Wrote %s%n", out.toAbsolutePath());
        if (!allAllowed) {
            System.err.println("STOP TRIGGER: LBFakeChain must be ALLOWED for all n under WEAKEST.");
            System.exit(1);
        }
    }
}
