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
import wev.smt.DependencyInfo;
import wev.smt.DependencyInfo.DepKind;
import wev.smt.DependencyInfo.KindedEdge;
import wev.smt.EventStructureEncoder;
import wev.smt.QfbvConstancyOracle;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Benchmark over systematically enumerated expressions, many of which fall outside
 * the folding baseline's hard-coded idiom set.
 *
 * <p>Each candidate expression is placed at <em>every</em> site of an {@code n}-thread
 * load-buffering ring. Ground truth is the direct QF_BV oracle verdict on the
 * expression: if it is constant in the read register the ring carries no semantic
 * dependency and the required verdict is ALLOWED (grounded-LB admission); if it is
 * non-constant the cycle closes and the required verdict is FORBIDDEN (thin-air).
 * Neither WEAKEST nor the folding baseline contributes to the label.
 *
 * <p>Three criteria are compared: the QF_BV-backed detector (WEAKEST), the shipped
 * classifier with the oracle disabled (idiom folding plus linear-cancellation
 * normalisation, no solver), and the all-dependencies-semantic syntactic baseline.
 *
 * <p>Candidates are supplied by an external enumeration and are NOT filtered by what
 * any classifier does with them.
 */
public final class HardExprBenchRun {

    private HardExprBenchRun() { }

    private record Row(String expr, int threads, String shape, boolean oracleConstant,
                       int oracleSemEdges, int foldSemEdges, int totalEdges,
                       String required, String weakest, String folding, String syntactic,
                       boolean valid, String status) { }

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "eval/generated-hard-candidates.json");
        Path out = Path.of(args.length > 1 ? args[1] : "eval/generated-hard-pairs.csv");
        int nMin = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int nMax = args.length > 3 ? Integer.parseInt(args[3]) : 3;

        List<String> cands = readJsonStrings(in);
        System.out.printf(Locale.ROOT, "candidates: %d%n", cands.size());

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<Row> rows = new ArrayList<>();

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                     cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
             QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            int i = 0;
            for (String e : cands) {
                if (++i % 200 == 0) System.out.printf(Locale.ROOT, "  %d/%d%n", i, cands.size());
                for (int n = nMin; n <= nMax; n++) {
                    rows.add(one(ctx, bmgr, oracle, e, n));
                }
            }
        }
        writeCsv(out, rows);
        summarize(rows);
    }

    /** Uniform ring: every thread stores the same expression shape over its own register. */
    static String litmus(String name, int n, String expr) {
        StringBuilder b = new StringBuilder();
        b.append("C ").append(name).append('\n').append("{ }\n");
        for (int i = 0; i < n; i++) b.append(i == 0 ? " P" : " | P").append(i);
        b.append(" ;\n");
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? " " : " | ")
             .append("r").append(i).append(" = READ_ONCE(*x").append(i).append(")");
        }
        b.append(" ;\n");
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? " " : " | ")
             .append("WRITE_ONCE(*x").append((i + 1) % n).append(", ")
             .append(expr.replace("r", "r" + i)).append(")");
        }
        b.append(" ;\n").append("exists (");
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(" /\\ ");
            b.append(i).append(":r").append(i).append("=1");
        }
        b.append(")\n");
        return b.toString();
    }

    /** Operator skeleton, e.g. "r & 0 * 1 ^ 1" -> "&*^". */
    static String shapeOf(String e) {
        StringBuilder b = new StringBuilder();
        for (char c : e.toCharArray()) if ("+-*&^~".indexOf(c) >= 0) b.append(c);
        return b.toString();
    }

    private static Row one(SolverContext ctx, BooleanFormulaManager bmgr,
                           QfbvConstancyOracle oracle, String expr, int n) {
        String name = "HARD-n" + n + "-" + Integer.toHexString(expr.hashCode());
        String src = litmus(name, n, expr);
        String shape = shapeOf(expr);

        // GROUND TRUTH: direct oracle query on thread 0's instance, no front end involved.
        Boolean c0 = oracle.isConstant(expr.replace("r", "r0"), "r0");
        if (c0 == null) {
            return new Row(expr, n, shape, false, -1, -1, -1, "?", "?", "?", "?",
                    false, "oracle-undecided");
        }
        boolean oracleConst = c0;
        String required = oracleConst ? "ALLOWED" : "FORBIDDEN";

        LitmusCase lcO, lcF;
        try {
            LitmusParser.CONSTANCY_ORACLE = oracle;
            try { lcO = LitmusParser.parse(src, name + ".litmus"); }
            finally { LitmusParser.CONSTANCY_ORACLE = null; }
            lcF = LitmusParser.parse(src, name + ".litmus");     // oracle OFF: folding path
        } catch (Exception e) {
            return new Row(expr, n, shape, oracleConst, -1, -1, -1, required, "?", "?", "?",
                    false, "parse-error:" + e.getClass().getSimpleName());
        }

        int total = 0, semO = 0, semF = 0;
        for (KindedEdge k : lcO.deps().allEdges()) {
            if (k.kind() != DepKind.DATA) continue;
            total++;
            if (k.edge().isSemantic()) semO++;
        }
        for (KindedEdge k : lcF.deps().allEdges()) {
            if (k.kind() == DepKind.DATA && k.edge().isSemantic()) semF++;
        }
        if (total != n) {
            return new Row(expr, n, shape, oracleConst, semO, semF, total, required,
                    "?", "?", "?", false, "front-end-shape:" + total + "!=" + n);
        }

        String weakest = verdict(ctx, bmgr, lcO.es(), lcO.deps());
        String folding = verdict(ctx, bmgr, lcF.es(), lcF.deps());
        String syntactic = verdict(ctx, bmgr, lcO.es(), allSemantic(lcO.deps()));

        if (weakest.startsWith("ERROR") || folding.startsWith("ERROR")
                || syntactic.startsWith("ERROR")) {
            return new Row(expr, n, shape, oracleConst, semO, semF, total, required,
                    weakest, folding, syntactic, false, "solver-error");
        }
        // Wiring gate, independent of every criterion under test.
        if (!"FORBIDDEN".equals(syntactic)) {
            return new Row(expr, n, shape, oracleConst, semO, semF, total, required,
                    weakest, folding, syntactic, false, "unwired");
        }
        return new Row(expr, n, shape, oracleConst, semO, semF, total, required,
                weakest, folding, syntactic, true, "ok");
    }

    private static DependencyInfo allSemantic(DependencyInfo d) {
        DependencyInfo o = new DependencyInfo();
        for (KindedEdge k : d.allEdges()) {
            switch (k.kind()) {
                case DATA -> o.addDataDep(k.edge().consumer(), k.edge().producer(), true);
                case ADDR -> o.addAddrDep(k.edge().consumer(), k.edge().producer(), true);
                case CTRL -> o.addCtrlDep(k.edge().consumer(), k.edge().producer(), true);
            }
        }
        return o;
    }

    private static String verdict(SolverContext ctx, BooleanFormulaManager bmgr,
                                  com.weakest.model.EventStructure es, DependencyInfo deps) {
        try {
            EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            BooleanFormula wf = enc.encodeWellFormedness();
            try (ProverEnvironment p = ctx.newProverEnvironment()) {
                p.addConstraint(bmgr.and(wf, ax.consistencyWEAKEST()));
                return p.isUnsat() ? "FORBIDDEN" : "ALLOWED";
            }
        } catch (Exception e) {
            return "ERROR:" + e.getClass().getSimpleName();
        }
    }

    private static List<String> readJsonStrings(Path p) throws Exception {
        String s = Files.readString(p);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(s);
        while (m.find()) out.add(m.group(1).replace("\\\"", "\""));
        return out;
    }

    private static void writeCsv(Path p, List<Row> rows) throws Exception {
        StringBuilder b = new StringBuilder(
                "expr,threads,shape,oracle_constant,oracle_sem_edges,fold_sem_edges,"
                + "total_edges,required,weakest,folding,syntactic,valid,status\n");
        for (Row r : rows) {
            b.append('"').append(r.expr()).append('"').append(',').append(r.threads())
             .append(',').append('"').append(r.shape()).append('"')
             .append(',').append(r.oracleConstant())
             .append(',').append(r.oracleSemEdges()).append(',').append(r.foldSemEdges())
             .append(',').append(r.totalEdges()).append(',').append(r.required())
             .append(',').append(r.weakest()).append(',').append(r.folding())
             .append(',').append(r.syntactic()).append(',').append(r.valid())
             .append(',').append('"').append(r.status()).append('"').append('\n');
        }
        Files.writeString(p, b.toString());
        System.out.println("wrote " + p);
    }

    private static void summarize(List<Row> rows) {
        List<Row> v = rows.stream().filter(Row::valid).toList();
        Map<String, Integer> st = new LinkedHashMap<>();
        for (Row r : rows) st.merge(r.status(), 1, Integer::sum);
        long oc = v.stream().filter(Row::oracleConstant).count();
        long wOk = v.stream().filter(r -> r.weakest().equals(r.required())).count();
        long fOk = v.stream().filter(r -> r.folding().equals(r.required())).count();
        long sOk = v.stream().filter(r -> r.syntactic().equals(r.required())).count();
        long fFP = v.stream().filter(r -> r.required().equals("ALLOWED")
                && r.folding().equals("FORBIDDEN")).count();
        long fFN = v.stream().filter(r -> r.required().equals("FORBIDDEN")
                && r.folding().equals("ALLOWED")).count();
        long wFP = v.stream().filter(r -> r.required().equals("ALLOWED")
                && r.weakest().equals("FORBIDDEN")).count();
        long wFN = v.stream().filter(r -> r.required().equals("FORBIDDEN")
                && r.weakest().equals("ALLOWED")).count();
        System.out.println("\n============== HARD-EXPRESSION BENCHMARK ==============");
        System.out.printf(Locale.ROOT, "generated              : %d%n", rows.size());
        System.out.printf(Locale.ROOT, "valid                  : %d%n", v.size());
        System.out.println("status                 : " + st);
        System.out.printf(Locale.ROOT, "oracle CONSTANT        : %d%n", oc);
        System.out.printf(Locale.ROOT, "oracle NON-CONSTANT    : %d%n", v.size() - oc);
        System.out.printf(Locale.ROOT, "WEAKEST  correct       : %d/%d  (FP=%d FN=%d)%n",
                wOk, v.size(), wFP, wFN);
        System.out.printf(Locale.ROOT, "folding  correct       : %d/%d  (FP=%d FN=%d)%n",
                fOk, v.size(), fFP, fFN);
        System.out.printf(Locale.ROOT, "syntactic correct      : %d/%d%n", sOk, v.size());
        System.out.println("=======================================================");
    }
}
