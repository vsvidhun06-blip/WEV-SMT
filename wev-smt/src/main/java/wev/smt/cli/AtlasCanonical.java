package wev.smt.cli;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the 40-test {@link LitmusCorpus#classics()} atlas under six models —
 * SC, TSO, PSO' (our PSO, with the RC11 release/acquire term), PSO_CANONICAL
 * (standard SPARC PSO, {@link AxiomaticConsistency#consistencyPSOCanonical}), RA,
 * WEAKEST — recording the full-execution consistency verdict of the wired candidate.
 *
 * <p>Emits {@code eval/atlas-canonical.csv}
 * ({@code test,sc,tso,pso_prime,pso_canonical,ra,weakest}) and, on stdout, a
 * per-test dump of the wired execution (events / po / co) so the hierarchy-violation
 * analysis can name the responsible execution.
 */
public final class AtlasCanonical {

    private AtlasCanonical() { }

    private static final String[] COLS =
            {"sc", "tso", "pso_prime", "pso_canonical", "ra", "weakest"};

    public static void main(String[] args) throws Exception {
        Path outCsv = Path.of(args.length > 0 ? args[0] : "eval/atlas-canonical.csv");
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<String> rows = new ArrayList<>();
        rows.add("test," + String.join(",", COLS));

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            List<LitmusCase> cases = LitmusCorpus.classics();
            System.out.println("Atlas over " + cases.size() + " tests x 6 models");

            for (LitmusCase lc : cases) {
                EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();

                Map<String, String> v = new LinkedHashMap<>();
                v.put("sc", verdict(ctx, bmgr, wf, ax.consistencySC()));
                v.put("tso", verdict(ctx, bmgr, wf, ax.consistencyTSO()));
                v.put("pso_prime", verdict(ctx, bmgr, wf, ax.consistencyPSO()));
                v.put("pso_canonical", verdict(ctx, bmgr, wf, ax.consistencyPSOCanonical()));
                v.put("ra", verdict(ctx, bmgr, wf, ax.consistencyRA()));
                v.put("weakest", verdict(ctx, bmgr, wf, ax.consistencyWEAKEST()));

                StringBuilder sb = new StringBuilder(lc.name());
                for (String c : COLS) sb.append(',').append(v.get(c));
                rows.add(sb.toString());

                System.out.printf("%-14s SC=%-9s TSO=%-9s PSO'=%-9s PSOc=%-9s RA=%-9s WEAKEST=%-9s%n",
                        lc.name(), v.get("sc"), v.get("tso"), v.get("pso_prime"),
                        v.get("pso_canonical"), v.get("ra"), v.get("weakest"));
                dumpExecution(lc.es());
            }
        }
        Files.writeString(outCsv, String.join("\n", rows) + "\n");
        System.out.println("\nCSV: " + outCsv.toAbsolutePath());
    }

    private static String verdict(SolverContext ctx, BooleanFormulaManager bmgr,
            BooleanFormula wf, BooleanFormula cons) throws Exception {
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(wf, cons));
            return p.isUnsat() ? "FORBIDDEN" : "ALLOWED";
        }
    }

    /** Compact one-line-per-relation dump of the wired execution. */
    private static void dumpExecution(EventStructure es) {
        StringBuilder ev = new StringBuilder("    events: ");
        for (Event e : es.getEvents()) {
            String kind = e instanceof WriteEvent w ? "W(" + e.getVariable() + ")=" + w.getValue()
                    : e instanceof ReadEvent r ? "R(" + e.getVariable() + ")" : e.getClass().getSimpleName();
            ev.append("e").append(e.getId()).append(":T").append(e.getThreadId())
              .append(':').append(kind).append("  ");
        }
        System.out.println(ev);
        System.out.println("    po: " + es.getProgramOrder() + "   co: " + es.getCoherenceOrder()
                + "   rf: " + es.getReadsFrom());
    }
}
