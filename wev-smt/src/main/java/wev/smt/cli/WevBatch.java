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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Batch WEAKEST runner for the Dat3M comparison harness. For each {@code .litmus} file
 * argument, parses and checks the wired candidate's WEAKEST consistency, printing one
 * pipe-separated line {@code name|verdict|solve_ms|status} to stdout. The solver time is
 * the model-check itself (encode well-formedness + {@code consistencyWEAKEST} + isUnsat),
 * excluding JVM startup and parsing, so it is comparable across files in one process.
 */
public final class WevBatch {

    private WevBatch() { }

    public static void main(String[] args) throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            for (String arg : args) {
                Path p = Path.of(arg);
                String name = p.getFileName().toString().replaceFirst("\\.litmus$", "");
                try {
                    String content = Files.readString(p);
                    LitmusCase lc = LitmusParser.parse(content, name);
                    long t0 = System.nanoTime();
                    EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
                    AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                    BooleanFormula wf = enc.encodeWellFormedness();
                    boolean unsat;
                    try (ProverEnvironment pr = ctx.newProverEnvironment()) {
                        pr.addConstraint(bmgr.and(wf, ax.consistencyWEAKEST()));
                        unsat = pr.isUnsat();
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    String verdict = unsat ? "FORBIDDEN" : "ALLOWED";
                    System.out.println(name + "|" + verdict + "|" + ms + "|OK");
                } catch (Exception e) {
                    System.out.println(name + "|ERROR|0|" + e.getClass().getSimpleName()
                            + ":" + String.valueOf(e.getMessage()).replace('|', ' ').replace('\n', ' '));
                }
            }
        }
    }
}
