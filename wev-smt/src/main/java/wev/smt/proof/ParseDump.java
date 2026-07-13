package wev.smt.proof;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.WriteEvent;
import com.weakest.model.ReadEvent;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import wev.smt.AxiomaticConsistency;
import wev.smt.DependencyInfo;
import wev.smt.EventStructureEncoder;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Read-only diagnostic for the co4/co10 herd7 disagreement. Parses each file with the
 * frozen {@link LitmusParser}, prints per-event id/thread/kind/loc/value plus the wired
 * program-order and coherence-order, and reports the SC and WEAKEST verdict. No edits to
 * the parser or the semantic core.
 */
public final class ParseDump {
    public static void main(String[] args) throws Exception {
        for (String p : args) {
            String content = Files.readString(Path.of(p));
            LitmusCase lc = LitmusParser.parse(content, Path.of(p).getFileName().toString());
            EventStructure es = lc.es();
            System.out.println("==== " + lc.name() + "  (" + lc.arch() + ") ====");
            System.out.println("exists: " + lc.existsClause());
            System.out.printf("%-4s %-7s %-12s %-6s %s%n", "id", "thread", "kind", "loc", "value");
            for (Event e : es.getEvents()) {
                String val;
                if (e instanceof WriteEvent w) val = "W=" + w.getValue();
                else if (e instanceof ReadEvent r) val = "reads value " + r.getValue();
                else val = "";
                System.out.printf("%-4d %-7d %-12s %-6s %s%n",
                        e.getId(), e.getThreadId(), e.getClass().getSimpleName(),
                        e.getVariable(), val);
            }
            System.out.println("programOrder (id -> [successors]): " + es.getProgramOrder());
            System.out.println("coherenceOrder (loc -> [ids]):     " + es.getCoherenceOrder());
            System.out.print("verdicts: ");
            for (String m : new String[]{"SC", "TSO", "PSO", "RA", "WEAKEST"}) {
                System.out.print(m + "=" + (decideUnsat(es, lc.deps(), m) ? "FORBID " : "allow "));
            }
            System.out.println("\n");
        }
    }

    private static boolean decideUnsat(EventStructure es, DependencyInfo deps, String model)
            throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        SolverContext c = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
        try {
            EventStructureEncoder enc = new EventStructureEncoder(c, es, deps);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            BooleanFormula cons = switch (model) {
                case "SC" -> ax.consistencySC();
                case "TSO" -> ax.consistencyTSO();
                case "PSO" -> ax.consistencyPSO();
                case "RA" -> ax.consistencyRA();
                case "WEAKEST" -> ax.consistencyWEAKEST();
                default -> throw new IllegalStateException(model);
            };
            try (ProverEnvironment pe = c.newProverEnvironment()) {
                pe.addConstraint(enc.getBmgr().and(wf, cons));
                return pe.isUnsat();
            }
        } finally {
            c.close();
        }
    }
}
