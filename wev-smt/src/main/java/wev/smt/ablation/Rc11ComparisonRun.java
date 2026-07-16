package wev.smt.ablation;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
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
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * §6.4 RC11 thin-air baseline comparison. Runs the two no-thin-air conditions — RC11's
 * blanket {@code acyclic(po ∪ rf)} and WEAKEST's dependency-sensitive
 * {@code acyclic(sdep ∪ jf)} — over a fixed panel of load-buffering litmus tests, and
 * emits {@code eval/rc11-comparison.csv} (columns: test, model, verdict, witness_size,
 * notes) plus a console agreement/divergence summary.
 *
 * <p>Each cell is a full-execution consistency check on the frozen
 * {@link EventStructureEncoder} + {@link AxiomaticConsistency}, exactly as
 * {@link AblationRun} does for WEAKEST: well-formedness pins every read to its
 * exists-clause target value, so {@code UNSAT(wf ∧ consistency(model))} means the target
 * outcome is FORBIDDEN and SAT means it is ALLOWED. The only variable across the two
 * cells of a test is which model's consistency axiom is conjoined.
 *
 * <p>{@code witness_size} is the number of events in the candidate execution being
 * checked (the full pinned litmus execution) when a verdict of ALLOWED admits it, and
 * {@code -} when FORBIDDEN (no consistent execution realises the target outcome).
 *
 * <p>Args (all optional, key=value): {@code examples=eval/examples/paper} (dir for the
 * curated LB files), {@code corpusRoot=eval/corpus} (dir for the Dat3M/PPC cases),
 * {@code out=eval} (output dir for the CSV), {@code timeoutSec=60}.
 */
public final class Rc11ComparisonRun {

    private Rc11ComparisonRun() { }

    private enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT, ERROR }

    private static final MemoryModel[] MODELS = { MemoryModel.RC11, MemoryModel.WEAKEST };

    /** A test in the panel: display name and where to read its .litmus from. */
    private record Test(String name, String kind, String relPath) { }

    private record Row(String test, MemoryModel model, Verdict verdict,
                       String witnessSize, String notes) { }

    private record Opts(Path examples, Path corpusRoot, Path out, int timeoutSec) { }

    public static void main(String[] args) throws Exception {
        Opts opt = parseOpts(args);
        Files.createDirectories(opt.out());

        // The §6.4 panel. `kind` records the *semantic* class of the load-buffering cycle
        // once written values are folded (real dep vs fake/identity dep) — NOT the corpus
        // family name, which can mislead: LB+datas / DETOUR0236 spell a syntactic data
        // dependency (`xor r,r,r; +1`) that folds to the constant 1, i.e. a FAKE dep, so
        // they are grounded LB (WEAKEST recovers them), not genuine thin-air. The one
        // genuine thin-air case (real `r+1` dep) is LB-real. Two of the originally
        // requested files parse degenerately (see notes) and are kept for the record with
        // LB-fake-xor-cycle added as the faithful fake-XOR wiring.
        List<Test> panel = List.of(
                new Test("LB-fake-xor", "fake-dep(degenerate-parse)",
                        opt.examples().resolve("LB-fake-xor.litmus").toString()),
                new Test("LB-fake-xor-cycle", "fake-dep",
                        opt.examples().resolve("LB-fake-xor-cycle.litmus").toString()),
                new Test("LBfd", "fake-dep",
                        opt.examples().resolve("LBfd.litmus").toString()),
                new Test("LB-real", "real-dep(thin-air)",
                        opt.examples().resolve("LB-real.litmus").toString()),
                new Test("LB+datas", "fake-dep(degenerate-parse)",
                        opt.corpusRoot().resolve("Dat3M/litmus/AARCH64/SYS/LB+datas.litmus").toString()),
                new Test("DETOUR0236", "fake-dep",
                        opt.corpusRoot().resolve("Dat3M/litmus/PPC/DETOUR0236.litmus").toString()));

        System.out.printf(Locale.ROOT,
                "§6.4 RC11-vs-WEAKEST comparison: %d tests × %d models, timeoutSec=%d%n",
                panel.size(), MODELS.length, opt.timeoutSec());

        List<Row> rows = new ArrayList<>();
        for (Test t : panel) {
            EventStructure es;
            DependencyInfo deps;
            try {
                var lc = LitmusParser.parse(Files.readString(Path.of(t.relPath())), t.name());
                es = lc.es();
                deps = lc.deps();
            } catch (Exception e) {
                for (MemoryModel m : MODELS) {
                    rows.add(new Row(t.name(), m, Verdict.ERROR, "-", clean(e.toString())));
                }
                continue;
            }
            int nEvents = es.getEvents().size();
            int semantic = deps.semanticEdges().size();
            // Is the load-buffering cycle actually wired? A faithful LB has every read
            // observing the non-initial value 1 (carried by a program write of 1). If the
            // parser could not fold the store to 1 (AArch64 arithmetic) or the store is a
            // literal 0 (`r^r`), reads fall back to the initial write and no cycle exists —
            // then every model trivially ALLOWS an all-zeros execution, telling us nothing.
            int reads = 0, readsWired = 0, writesOfOne = 0;
            for (Event e : es.getEvents()) {
                if (e instanceof ReadEvent r) {
                    reads++;
                    if (r.getValue() != 0) readsWired++;
                } else if (e instanceof WriteEvent w && w.getValue() != 0) {
                    writesOfOne++;
                }
            }
            boolean wired = reads > 0 && readsWired == reads && writesOfOne > 0;
            for (MemoryModel m : MODELS) {
                Verdict v = solve(es, deps, m, opt.timeoutSec());
                String size = (v == Verdict.ALLOWED) ? Integer.toString(nEvents) : "-";
                String note = t.kind() + "; events=" + nEvents + "; sdepEdges=" + semantic
                        + "; cycleWired=" + wired
                        + "; readsAtValue1=" + readsWired + "/" + reads
                        + (wired ? "" : "; DEGENERATE-PARSE(no LB cycle wired)");
                rows.add(new Row(t.name(), m, v, size, note));
                System.out.printf(Locale.ROOT, "  %-18s %-8s %-10s size=%-3s wired=%-5s (%s)%n",
                        t.name(), m, v, size, wired, t.kind());
            }
        }

        writeCsv(opt.out().resolve("rc11-comparison.csv"), rows);
        printSummary(panel, rows);
        System.out.printf(Locale.ROOT, "%nCSV: %s%n",
                opt.out().resolve("rc11-comparison.csv").toAbsolutePath());
    }

    /** Full-execution verdict for one (es, deps) under model {@code m}. */
    private static Verdict solve(EventStructure es, DependencyInfo deps,
                                 MemoryModel m, int timeoutSec) {
        ShutdownManager sm = ShutdownManager.create();
        try {
            Configuration config = Configuration.defaultConfiguration();
            LogManager log = BasicLogManager.create(config);
            try (SolverContext ctx = SolverContextFactory.createSolverContext(
                    config, log, sm.getNotifier(), Solvers.Z3)) {
                BooleanFormulaManager bmgr =
                        ctx.getFormulaManager().getBooleanFormulaManager();
                EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                BooleanFormula cons = switch (m) {
                    case SC -> ax.consistencySC();
                    case TSO -> ax.consistencyTSO();
                    case PSO -> ax.consistencyPSO();
                    case RA -> ax.consistencyRA();
                    case WEAKEST -> ax.consistencyWEAKEST();
                    case RC11 -> ax.consistencyRC11();
                };
                boolean unsat;
                try (ProverEnvironment p = ctx.newProverEnvironment()) {
                    p.addConstraint(bmgr.and(wf, cons));
                    unsat = p.isUnsat();
                }
                return unsat ? Verdict.FORBIDDEN : Verdict.ALLOWED;
            }
        } catch (Throwable ex) {
            boolean interrupted = ex instanceof InterruptedException
                    || (ex.getCause() instanceof InterruptedException);
            return interrupted ? Verdict.TIMEOUT : Verdict.ERROR;
        }
    }

    private static void writeCsv(Path path, List<Row> rows) throws Exception {
        StringBuilder sb = new StringBuilder("test,model,verdict,witness_size,notes\n");
        for (Row r : rows) {
            sb.append(r.test()).append(',')
              .append(r.model()).append(',')
              .append(r.verdict()).append(',')
              .append(r.witnessSize()).append(',')
              .append('"').append(r.notes()).append('"').append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static void printSummary(List<Test> panel, List<Row> rows) {
        System.out.println("\n--- RC11 vs WEAKEST ---");
        List<String> agree = new ArrayList<>();
        List<String> differ = new ArrayList<>();
        List<String> unusable = new ArrayList<>();
        for (Test t : panel) {
            Verdict rc11 = verdictOf(rows, t.name(), MemoryModel.RC11);
            Verdict weakest = verdictOf(rows, t.name(), MemoryModel.WEAKEST);
            String line = String.format(Locale.ROOT, "%-18s RC11=%-9s WEAKEST=%-9s [%s]",
                    t.name(), rc11, weakest, t.kind());
            if (t.kind().contains("degenerate")) unusable.add(line);
            else if (rc11 == weakest) agree.add(line);
            else differ.add(line);
        }
        System.out.println("AGREE — both FORBID genuine thin-air (" + agree.size() + "):");
        for (String s : agree) System.out.println("  " + s);
        System.out.println("DIFFER — RC11 over-restricts grounded/fake LB (" + differ.size() + "):");
        for (String s : differ) System.out.println("  " + s);
        System.out.println("UNUSABLE — degenerate parse, no LB cycle wired (" + unusable.size() + "):");
        for (String s : unusable) System.out.println("  " + s);
    }

    private static Verdict verdictOf(List<Row> rows, String test, MemoryModel m) {
        for (Row r : rows) {
            if (r.test().equals(test) && r.model() == m) return r.verdict();
        }
        return Verdict.ERROR;
    }

    private static Opts parseOpts(String[] args) {
        Path examples = Path.of("eval", "examples", "paper");
        Path corpusRoot = Path.of("eval", "corpus");
        Path out = Path.of("eval");
        int timeoutSec = 60;
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "examples" -> examples = Path.of(v);
                case "corpusroot" -> corpusRoot = Path.of(v);
                case "out" -> out = Path.of(v);
                case "timeoutsec" -> timeoutSec = Integer.parseInt(v);
                default -> System.err.println("[opt] ignoring unknown option: " + a);
            }
        }
        return new Opts(examples, corpusRoot, out, timeoutSec);
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replace(',', ';').replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }
}
