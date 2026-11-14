package wev.smt.cli;

import com.weakest.model.EventStructure;

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
import wev.smt.DependencyInfo;
import wev.smt.EventStructureEncoder;
import wev.smt.LitmusCorpus;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the RC11 thin-air baseline model — {@link AxiomaticConsistency#consistencyRC11},
 * whose distinguishing axiom {@code acyclic(po ∪ rf)} sits on the shared per-location
 * coherence + RMW-atomicity base — over the load-buffering family and reports a
 * per-test {@code ALLOWED} / {@code FORBIDDEN} verdict, also written to {@code rc11-results.csv}.
 *
 * <p>Tests: the three paper §4 worked examples under {@code eval/examples/paper}
 * ({@code LB-fake-xor}, {@code LB-fake-xor-cycle}, {@code LB-real}), parsed by
 * {@link LitmusParser}, plus the hand-built corpus cases {@code LBdep-fake} and
 * {@code LBdep-real} from {@link LitmusCorpus#classics()}. For each, the wired candidate
 * execution is {@code ALLOWED} iff {@code po ∪ rf} is acyclic (SAT) and {@code FORBIDDEN}
 * otherwise (UNSAT).
 *
 * <p>This model deliberately ignores dependencies, so — unlike WEAKEST's {@code jfCoherence}
 * — it does <em>not</em> distinguish fake from real dependency cycles: it forbids every LB
 * cycle whose candidate is actually wired as the cycle. It touches none of the five validated
 * {@link wev.smt.MemoryModel} encodings.
 *
 * <p>Usage: {@code Rc11ThinAirRun [out.csv]} (default {@code rc11-results.csv} in the CWD).
 */
public final class Rc11ThinAirRun {

    private Rc11ThinAirRun() { }

    private record Case(String test, String source, EventStructure es, DependencyInfo deps) { }

    public static void main(String[] args) throws Exception {
        Path outCsv = Path.of(args.length > 0 ? args[0] : "rc11-results.csv");
        Path paperDir = Path.of("eval", "examples", "paper");

        List<Case> cases = new ArrayList<>();

        // Parsed paper §4 worked examples.
        for (String fn : List.of("LB-fake-xor.litmus", "LB-fake-xor-cycle.litmus", "LB-real.litmus")) {
            Path p = paperDir.resolve(fn);
            LitmusCase lc = LitmusParser.parse(Files.readString(p), fn);
            cases.add(new Case(lc.name(), "file:" + fn, lc.es(), lc.deps()));
        }

        // Hand-built corpus cases.
        for (LitmusCorpus.LitmusCase lc : LitmusCorpus.classics()) {
            if (lc.name().equals("LBdep-fake") || lc.name().equals("LBdep-real")) {
                cases.add(new Case(lc.name(), "corpus", lc.es(), lc.deps()));
            }
        }

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);

        List<String[]> rows = new ArrayList<>();

        System.out.println("RC11 thin-air model: acyclic(po ∪ rf) on the wired candidate");
        System.out.printf(Locale.ROOT, "%-18s %-28s %s%n", "test", "source", "verdict");
        System.out.println("-".repeat(60));

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            for (Case c : cases) {
                EventStructureEncoder enc = new EventStructureEncoder(ctx, c.es(), c.deps());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                boolean unsat;
                try (ProverEnvironment pr = ctx.newProverEnvironment()) {
                    pr.addConstraint(bmgr.and(wf, ax.consistencyRC11()));
                    unsat = pr.isUnsat();
                }
                String verdict = unsat ? "FORBIDDEN" : "ALLOWED";
                System.out.printf(Locale.ROOT, "%-18s %-28s %s%n", c.test(), c.source(), verdict);
                rows.add(new String[] { c.test(), c.source(), verdict });
            }
        }

        try (var w = Files.newBufferedWriter(outCsv)) {
            w.write("test,source,verdict\n");
            for (String[] r : rows) {
                w.write(r[0] + ',' + r[1] + ',' + r[2] + '\n');
            }
        }
        System.out.println("\nCSV written to " + outCsv.toAbsolutePath());
    }
}
