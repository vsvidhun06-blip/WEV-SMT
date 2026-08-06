package wev.smt.research;

import com.weakest.model.Event;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;

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
import wev.smt.QfbvConstancyOracle;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RESEARCH-ONLY prototype path for semantic control classification (GATE 2).
 *
 * <p><b>This does not replace, and does not modify, the shipped detector.</b> It reads
 * the {@link DependencyInfo} the shipped {@link LitmusParser} produces, builds a
 * <em>second</em> sidecar in which control edges are re-classified by the N4 criterion,
 * and feeds each of the two sidecars through the existing
 * {@link AxiomaticConsistency#consistencyWEAKEST()} / {@code jfCoherence} check. Both
 * verdicts are reported side by side. Nothing here is wired into any shipped path.
 *
 * <p>The N4 criterion, as mechanised in {@code isabelle/pwt/PwT_Control.thy}: a control
 * edge {@code w <- r} is SEMANTIC iff the branch's observable write effect is
 * non-constant in {@code r}, decided by the existing QF_BV oracle over the ITE-lifted
 * effect expression
 *
 * <pre>{@code   (g != 0) * e_t  +  (1 - (g != 0)) * e_f   }</pre>
 *
 * <p><b>Front-end fidelity is checked, not assumed.</b> Building that lift needs the
 * guard {@code g} and both arm expressions. {@link #fidelity} reports, per file, whether
 * the parser actually retained a two-armed branch; where it did not, the case is recorded
 * UNSUPPORTED rather than being silently evaluated on a different program.
 */
public final class ControlIntegrationRun {

    /** Source-level branch shapes, counted directly from the .litmus text. */
    private static final Pattern IF_WITH_BODY =
            Pattern.compile("if\\s*\\([^)]*\\)\\s*(?!;)\\S");
    private static final Pattern IF_EMPTY =
            Pattern.compile("if\\s*\\([^)]*\\)\\s*;");
    private static final Pattern ELSE_ARM =
            Pattern.compile("\\belse\\b");

    /** What the parser was able to represent of the source's control structure. */
    record Fidelity(int ifWithBody, int ifEmpty, int elseArms, int ctrlEdges,
                    boolean twoArmedBranchRepresented, String note) { }

    /** One row of the GATE 2 table. */
    record Row(String test, int currentCtrlEdges, int semanticCtrlEdges,
               String currentVerdict, String semanticVerdict,
               String expected, String classification, String reason) { }

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        Path caseDir = Path.of("eval", "research", "control");
        if (Files.isDirectory(caseDir)) {
            try (var s = Files.list(caseDir)) {
                s.filter(p -> p.toString().endsWith(".litmus")).sorted().forEach(files::add);
            }
        }
        // The existing published control examples already in the repo.
        for (String p : new String[] {
                "eval/examples/weakestmo/LB+ctrl.litmus",
                "eval/examples/paper/LB+ctrldata+ctrl-single.litmus",
                "eval/examples/paper/LB+ctrldata+ctrl-double.litmus",
                "eval/examples/leaky-semicolon/LBcd.litmus",
                "eval/examples/leaky-semicolon/LBfc.litmus",
                "eval/examples/jmm/TC03.litmus",
                "eval/examples/jmm/TC04.litmus",
                "eval/examples/jmm/TC07.litmus",
                "eval/examples/jmm/TC08.litmus",
                "eval/examples/jmm/TC17.litmus",
                "eval/examples/jmm/TC19.litmus",
                "eval/examples/jmm/TC20.litmus" }) {
            Path q = Path.of(p);
            if (Files.exists(q)) files.add(q);
        }

        try (QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            System.out.println("=== FRONT-END FIDELITY =========================================");
            System.out.printf("%-34s %10s %9s %6s %6s  %s%n",
                    "test", "if+body", "if-empty", "else", "ctrl", "two-armed branch represented?");
            Map<Path, Fidelity> fid = new LinkedHashMap<>();
            for (Path f : files) {
                LitmusCase lc = LitmusParser.parse(Files.readString(f), f.getFileName().toString());
                Fidelity x = fidelity(f, lc);
                fid.put(f, x);
                System.out.printf("%-34s %10d %9d %6d %6d  %s%n",
                        lc.name(), x.ifWithBody(), x.ifEmpty(), x.elseArms(), x.ctrlEdges(),
                        x.twoArmedBranchRepresented() ? "YES" : "NO  -- " + x.note());
            }

            System.out.println();
            System.out.println("=== EVENT / DEPENDENCY DUMP ====================================");
            for (Path f : files) {
                LitmusCase lc = LitmusParser.parse(Files.readString(f), f.getFileName().toString());
                System.out.println("--- " + lc.name() + "  (" + f + ")");
                for (Event e : lc.es().getEvents()) {
                    if (e.getThreadId() == 0) continue;
                    String kind = e instanceof ReadEvent ? "R" : e instanceof WriteEvent ? "W" : "?";
                    String extra = "";
                    if (e instanceof WriteEvent w) {
                        extra = " val=" + w.getValue()
                                + " expr=" + lc.writeValueExprs().getOrDefault(e.getId(), "?");
                    } else if (e instanceof ReadEvent r) {
                        extra = " reg=" + r.getLocalVar();
                    }
                    System.out.printf("    id=%-3d T%d %s(%s)%s%n",
                            e.getId(), e.getThreadId(), kind, locOf(e), extra);
                }
                for (DependencyInfo.KindedEdge ke : lc.deps().allEdges()) {
                    System.out.printf("    dep %-4s consumer=%d producer=%d isSemantic=%s%n",
                            ke.kind(), ke.edge().consumer().getId(),
                            ke.edge().producer().getId(), ke.edge().isSemantic());
                }
            }

            System.out.println();
            System.out.println("=== CURRENT vs SEMANTIC CONTROL ================================");
            List<Row> rows = new ArrayList<>();
            for (Path f : files) {
                rows.add(evaluate(f, oracle, fid.get(f)));
            }
            System.out.printf("%-30s %5s %5s %-10s %-10s %-10s %-24s%n",
                    "test", "cur", "sem", "cur_verd", "sem_verd", "expected", "classification");
            for (Row r : rows) {
                System.out.printf("%-30s %5d %5d %-10s %-10s %-10s %-24s%n",
                        r.test(), r.currentCtrlEdges(), r.semanticCtrlEdges(),
                        r.currentVerdict(), r.semanticVerdict(), r.expected(), r.classification());
            }

            System.out.println();
            System.out.println("=== REASONS ====================================================");
            for (Row r : rows) System.out.println(r.test() + ": " + r.reason());

            // CSV sidecar for the report.
            StringBuilder csv = new StringBuilder(
                    "test,current_control_edges,semantic_control_edges,current_verdict,"
                    + "semantic_verdict,expected,classification,reason\n");
            for (Row r : rows) {
                csv.append(String.join(",",
                        q(r.test()), Integer.toString(r.currentCtrlEdges()),
                        Integer.toString(r.semanticCtrlEdges()), q(r.currentVerdict()),
                        q(r.semanticVerdict()), q(r.expected()), q(r.classification()),
                        q(r.reason()))).append('\n');
            }
            Files.writeString(Path.of("eval", "research", "control-integration.csv"), csv.toString());
            System.out.println();
            System.out.println("wrote eval/research/control-integration.csv");
        }
    }

    private static String q(String s) { return '"' + s.replace("\"", "\"\"") + '"'; }

    private static String locOf(Event e) {
        if (e instanceof ReadEvent r) return r.getVariable();
        if (e instanceof WriteEvent w) return w.getVariable();
        return "-";
    }

    /**
     * Compare the branch structure present in the SOURCE TEXT with what survived into the
     * parsed {@link DependencyInfo}. A two-armed branch is represented only if the parser
     * recorded control edges for a branch that actually has a body.
     */
    private static Fidelity fidelity(Path f, LitmusCase lc) throws IOException {
        String src = Files.readString(f);
        // Strip (* ... *) comments so prose about branches is not counted as code.
        String code = src.replaceAll("(?s)\\(\\*.*?\\*\\)", "");
        int withBody = count(IF_WITH_BODY, code);
        int empty = count(IF_EMPTY, code);
        int elses = count(ELSE_ARM, code);
        int ctrl = 0;
        for (DependencyInfo.KindedEdge ke : lc.deps().allEdges()) {
            if (ke.kind() == DependencyInfo.DepKind.CTRL) ctrl++;
        }
        String note;
        boolean ok;
        if (withBody == 0 && elses == 0) {
            ok = false;
            note = empty > 0
                    ? "source uses only the degenerate 'if (c) ;' idiom (empty arm, "
                      + "write after the join): no two-way branch exists to lift"
                    : "source contains no branch at all";
        } else if (ctrl == 0) {
            ok = false;
            note = "source has " + withBody + " branch(es) with a body and " + elses
                   + " else-arm(s), but the parser recorded ZERO control edges: the guard "
                   + "was discarded";
        } else {
            ok = true;
            note = "";
        }
        return new Fidelity(withBody, empty, elses, ctrl, ok, note);
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * Re-classify each control edge by N4 and compare verdicts.
     *
     * <p>The effect expression available to the prototype is the consumer write's symbolic
     * value expression over {@code R<readId>} variables ({@code LitmusCase.writeValueExprs}).
     * Where the parser retained a genuine two-armed branch this would be ITE-lifted first;
     * where it did not, the write the parser produced is unconditional and its value
     * expression IS the effect, so the criterion applies directly to it.
     */
    private static Row evaluate(Path f, QfbvConstancyOracle oracle, Fidelity fid)
            throws Exception {
        LitmusCase lc = LitmusParser.parse(Files.readString(f), f.getFileName().toString());

        int currentCtrl = 0;
        int semanticCtrl = 0;
        List<String> notes = new ArrayList<>();

        DependencyInfo reclassified = new DependencyInfo();
        for (DependencyInfo.KindedEdge ke : lc.deps().allEdges()) {
            DependencyInfo.DepEdge e = ke.edge();
            switch (ke.kind()) {
                case DATA -> reclassified.addDataDep(e.consumer(), e.producer(), e.isSemantic());
                case ADDR -> reclassified.addAddrDep(e.consumer(), e.producer(), e.isSemantic());
                case CTRL -> {
                    if (e.isSemantic()) currentCtrl++;
                    boolean sem = n4(lc, e, oracle, notes);
                    if (sem) semanticCtrl++;
                    reclassified.addCtrlDep(e.consumer(), e.producer(), sem);
                }
            }
        }

        String cur = weakest(lc, lc.deps());
        String sem = weakest(lc, reclassified);

        String expected = EXPECTED.getOrDefault(lc.name(), "-");
        String reason;
        String classification;
        if (!fid.twoArmedBranchRepresented() && (fid.ifWithBody() > 0 || fid.elseArms() > 0)) {
            classification = "UNSUPPORTED";
            reason = "front-end fidelity failure: " + fid.note()
                     + "; the program actually analysed is NOT the program written";
        } else if (!fid.twoArmedBranchRepresented() && fid.ifEmpty() > 0) {
            classification = currentCtrl != semanticCtrl ? "EXPECTED PRECISION GAIN" : "UNCHANGED";
            reason = "degenerate 'if (c) ;' idiom: the write executes unconditionally and "
                     + "carries a constant, so N4 fails and the edge is fake"
                     + (notes.isEmpty() ? "" : " [" + String.join("; ", notes) + "]");
        } else if (currentCtrl == semanticCtrl && cur.equals(sem)) {
            classification = "UNCHANGED";
            reason = "no control edge re-classified"
                     + (notes.isEmpty() ? "" : " [" + String.join("; ", notes) + "]");
        } else {
            classification = "EXPECTED PRECISION GAIN";
            reason = "control edges " + currentCtrl + " -> " + semanticCtrl
                     + (notes.isEmpty() ? "" : " [" + String.join("; ", notes) + "]");
        }

        return new Row(lc.name(), currentCtrl, semanticCtrl, cur, sem, expected,
                classification, reason);
    }

    /**
     * The N4 decision for one control edge, via the existing QF_BV constancy oracle.
     * Returns {@code true} (semantic, keep the edge) when the effect genuinely varies with
     * the producing read, and conservatively {@code true} when the oracle is undecided.
     */
    private static boolean n4(LitmusCase lc, DependencyInfo.DepEdge e,
                              QfbvConstancyOracle oracle, List<String> notes) {
        String effect = lc.writeValueExprs().get(e.consumer().getId());
        if (effect == null) {
            notes.add("w" + e.consumer().getId() + ": no value expression, conservative REAL");
            return true;
        }
        String reg = "R" + e.producer().getId();
        Boolean constant = oracle.isConstant(effect, reg);
        if (constant == null) {
            notes.add("w" + e.consumer().getId() + ": oracle undecided on '" + effect
                      + "', conservative REAL");
            return true;
        }
        notes.add("w" + e.consumer().getId() + " effect='" + effect + "' const-in-" + reg
                  + "=" + constant);
        return !constant;
    }

    private static String weakest(LitmusCase lc, DependencyInfo deps) throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        try (SolverContext c = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            EventStructureEncoder enc = new EventStructureEncoder(c, lc.es(), deps);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            BooleanFormula cons = ax.consistencyWEAKEST();
            try (ProverEnvironment p = c.newProverEnvironment()) {
                p.addConstraint(enc.getBmgr().and(wf, cons));
                return p.isUnsat() ? "FORBIDDEN" : "ALLOWED";
            }
        }
    }

    /** Expected verdicts as stated in the GATE 2 brief, for the six constructed cases. */
    private static final Map<String, String> EXPECTED = Map.of(
            "ctrlA-fake-const", "ALLOWED",
            "ctrlB-real", "FORBIDDEN",
            "ctrlC-existence", "FORBIDDEN",
            "ctrlD-equiv-arms", "ALLOWED",
            "ctrlE-guard-only", "ALLOWED",
            "ctrlF-correlated-temp", "ALLOWED");

    private ControlIntegrationRun() { }
}
