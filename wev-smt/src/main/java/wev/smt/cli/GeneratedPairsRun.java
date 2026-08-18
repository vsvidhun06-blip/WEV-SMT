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

/**
 * Independently generated matched-pair benchmark for the semantic-vs-syntactic
 * dependency distinction.
 *
 * <p><b>Why this is not circular.</b> The programs are emitted as {@code .litmus}
 * source text and pushed through the ordinary front end
 * ({@link LitmusParser#parse}); nothing sets {@code isSemantic} by construction.
 * Whether a given store expression is constant in its read register is decided by
 * {@link QfbvConstancyOracle}, installed as {@link LitmusParser#CONSTANCY_ORACLE},
 * i.e. by Z3 rather than by the shipped classifier's idiom folder. The
 * <em>required</em> verdict is then derived from the model's own theorems, not from
 * any implementation:
 * <ul>
 *   <li>a load-buffering ring whose data dependencies are all fake carries no
 *       {@code sdep} edge, so {@code sdep u jf = jf} is bipartite, hence acyclic
 *       (grounded-LB admission) &rArr; required ALLOWED;</li>
 *   <li>if even one dependency on the ring is fake the {@code sdep u jf} cycle is
 *       broken at that edge &rArr; required ALLOWED;</li>
 *   <li>only when every dependency on the ring is semantic does the cycle close
 *       &rArr; required FORBIDDEN (thin-air).</li>
 * </ul>
 *
 * <p>The syntactic baseline is the all-dependencies-semantic configuration: every
 * recorded {@code dep} edge is treated as carrying value-flow. On a ring it always
 * closes the cycle, so it predicts FORBIDDEN unconditionally. A generated case is
 * <b>discriminating</b> exactly when the required verdict is ALLOWED, since there
 * the two criteria must disagree.
 *
 * <p>Emits {@code eval/generated-pairs.csv}.
 */
public final class GeneratedPairsRun {

    private GeneratedPairsRun() { }

    // The loader constant-folds a store value with the loaded register modelled as 0,
    // so an expression must evaluate to 1 there for the ring to be wired with every
    // read observing 1. Bitwise-or is excluded throughout: '|' is the litmus column
    // separator and breaks the parse.

    /** Constant in {@code r}, value 1. */
    private static final String[] FAKE = {
        "r ^ r ^ 1",
        "r - r + 1",
        "r * 0 + 1",
        "r & 0 ^ 1",
        "r * 0 ^ 1",
        "r - r ^ 1",
        "r * 0 - 0 + 1",
        "r ^ r - 0 + 1",
    };

    /** Varies with {@code r}; evaluates to 1 at {@code r = 0}. */
    private static final String[] REAL = {
        "r + 1",
        "r ^ 1",
        "1 - r",
        "r * 2 + 1",
        "r * 3 + 1",
        "r & 2 ^ 1",
        "r + 1 ^ 0",
        "r << 1 ^ 1",
    };

    private record Row(String name, int threads, int nFake, String fakeExpr, String realExpr,
                       int oracleSemanticEdges, int oracleTotalEdges,
                       String required, String weakest, String syntactic,
                       boolean valid, boolean discriminating, String status,
                       int directOracleNonConstant, boolean parserAgreesWithOracle,
                       String folding, int foldingSemanticEdges) { }

    public static void main(String[] args) throws Exception {
        Path outCsv = Path.of(args.length > 0 ? args[0] : "eval/generated-pairs.csv");
        Path dumpDir = args.length > 1 ? Path.of(args[1]) : null;
        int nMin = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int nMax = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());
        if (dumpDir != null) Files.createDirectories(dumpDir);

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<Row> rows = new ArrayList<>();

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                     cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
             QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            LitmusParser.CONSTANCY_ORACLE = oracle;   // Z3 decides constancy, not classify()
            try {
                for (int n = nMin; n <= nMax; n++) {
                    for (String fe : FAKE) {
                        for (String re : REAL) {
                            for (int k = 0; k <= n; k++) {   // k = number of fake sites
                                rows.add(one(ctx, bmgr, oracle, dumpDir, n, k, fe, re));
                            }
                        }
                    }
                }
            } finally {
                LitmusParser.CONSTANCY_ORACLE = null;
            }
        }

        writeCsv(outCsv, rows);
        summarize(rows);
    }

    // ── generation ──────────────────────────────────────────────────────────

    /** An {@code n}-thread LB ring; the first {@code k} threads carry the fake expression. */
    static String litmus(String name, int n, int k, String fakeExpr, String realExpr) {
        StringBuilder b = new StringBuilder();
        b.append("C ").append(name).append('\n');
        b.append("{ }\n");
        // header row
        for (int i = 0; i < n; i++) b.append(i == 0 ? " P" : " | P").append(i);
        b.append(" ;\n");
        // read row
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? " " : " | ")
             .append("r").append(i).append(" = READ_ONCE(*x").append(i).append(")");
        }
        b.append(" ;\n");
        // write row: thread i writes the next location in the ring
        for (int i = 0; i < n; i++) {
            String e = (i < k ? fakeExpr : realExpr).replace("r", "r" + i);
            b.append(i == 0 ? " " : " | ")
             .append("WRITE_ONCE(*x").append((i + 1) % n).append(", ").append(e).append(")");
        }
        b.append(" ;\n");
        b.append("exists (");
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(" /\\ ");
            b.append(i).append(":r").append(i).append("=1");
        }
        b.append(")\n");
        return b.toString();
    }

    private static Row one(SolverContext ctx, BooleanFormulaManager bmgr,
                           QfbvConstancyOracle oracle, Path dumpDir,
                           int n, int k, String fakeExpr, String realExpr) {
        String name = String.format(Locale.ROOT, "GEN-n%d-k%d-%s-%s",
                n, k, slug(fakeExpr), slug(realExpr));
        String src = litmus(name, n, k, fakeExpr, realExpr);
        if (dumpDir != null) {
            try { Files.writeString(dumpDir.resolve(name + ".litmus"), src); }
            catch (Exception ignored) { }
        }

        LitmusCase lc;
        try {
            lc = LitmusParser.parse(src, name + ".litmus");
        } catch (Exception e) {
            return new Row(name, n, k, fakeExpr, realExpr, -1, -1, "?", "?", "?",
                    false, false, "parse-error:" + e.getClass().getSimpleName(), -1, false, "?", -1);
        }

        int total = 0, semantic = 0;
        for (KindedEdge ke : lc.deps().allEdges()) {
            if (ke.kind() != DepKind.DATA) continue;
            total++;
            if (ke.edge().isSemantic()) semantic++;
        }

        // Structural validity: the ring must have produced exactly n data-dependency
        // edges. Anything else means the front end did not represent the program we
        // generated, and the case carries no evidence either way.
        if (total != n) {
            return new Row(name, n, k, fakeExpr, realExpr, semantic, total, "?", "?", "?",
                    false, false, "front-end-shape:expected " + n + " data deps, got " + total, -1, false,
                    "?", -1);
        }

        // Ground truth, queried straight from the solver on the generated expression
        // strings. This bypasses the front end entirely -- in particular it bypasses
        // the classifier's cancellation fold, which can decide a site before the
        // oracle is reached -- so the labels below owe nothing to the code under test.
        int directNonConst = 0;
        boolean oracleDecidedAll = true;
        for (int i = 0; i < n; i++) {
            String e = (i < k ? fakeExpr : realExpr).replace("r", "r" + i);
            Boolean c = oracle.isConstant(e, "r" + i);
            if (c == null) { oracleDecidedAll = false; break; }
            if (!c) directNonConst++;
        }
        if (!oracleDecidedAll) {
            return new Row(name, n, k, fakeExpr, realExpr, semantic, total, "?", "?", "?",
                    false, false, "oracle-undecided", -1, false, "?", -1);
        }

        // Required verdict from the model's theorems, using the DIRECT oracle labels.
        String required = (directNonConst == n) ? "FORBIDDEN" : "ALLOWED";
        boolean agrees = (directNonConst == semantic);

        // Strengthened baseline: re-parse with the oracle switched OFF, so the shipped
        // classifier must fall back to idiom folding plus linear-cancellation
        // normalisation. This is a genuine constant-folding analysis with no solver.
        String folding;
        int foldSem = -1;
        wev.smt.parse.LitmusParser.ConstancyOracle saved = LitmusParser.CONSTANCY_ORACLE;
        LitmusParser.CONSTANCY_ORACLE = null;
        try {
            LitmusCase lcF = LitmusParser.parse(src, name + ".litmus");
            foldSem = 0;
            for (KindedEdge ke : lcF.deps().allEdges()) {
                if (ke.kind() == DepKind.DATA && ke.edge().isSemantic()) foldSem++;
            }
            folding = verdict(ctx, bmgr, lcF.es(), lcF.deps());
        } catch (Exception e) {
            folding = "ERROR:" + e.getClass().getSimpleName();
        } finally {
            LitmusParser.CONSTANCY_ORACLE = saved;
        }

        String weakest = verdict(ctx, bmgr, lc.es(), lc.deps());
        String syntactic = verdict(ctx, bmgr, lc.es(), allSemantic(lc.deps()));

        if (weakest.startsWith("ERROR") || syntactic.startsWith("ERROR")) {
            return new Row(name, n, k, fakeExpr, realExpr, semantic, total, required,
                    weakest, syntactic, false, false, "solver-error", directNonConst, agrees,
                    folding, foldSem);
        }

        // Validity gate, independent of the criterion under test. If the ring is wired
        // as a genuine load-buffering cycle then treating every dependency as
        // value-carrying must close it, so the syntactic baseline must return
        // FORBIDDEN. When it does not, the loader failed to align the candidate with
        // the exists clause (typically because a store value did not fold to 1) and
        // the case carries no evidence either way.
        if (!"FORBIDDEN".equals(syntactic)) {
            return new Row(name, n, k, fakeExpr, realExpr, semantic, total, required,
                    weakest, syntactic, false, false, "unwired:baseline-not-forbidden",
                    directNonConst, agrees, folding, foldSem);
        }

        boolean disc = !required.equals(syntactic);
        return new Row(name, n, k, fakeExpr, realExpr, semantic, total,
                required, weakest, syntactic, true, disc, "ok", directNonConst, agrees,
                folding, foldSem);
    }

    /** The syntactic baseline: every recorded dependency edge counts as value-carrying. */
    private static DependencyInfo allSemantic(DependencyInfo d) {
        DependencyInfo out = new DependencyInfo();
        for (KindedEdge ke : d.allEdges()) {
            switch (ke.kind()) {
                case DATA -> out.addDataDep(ke.edge().consumer(), ke.edge().producer(), true);
                case ADDR -> out.addAddrDep(ke.edge().consumer(), ke.edge().producer(), true);
                case CTRL -> out.addCtrlDep(ke.edge().consumer(), ke.edge().producer(), true);
            }
        }
        return out;
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

    private static String slug(String e) {
        return e.replace(" ", "").replace("^", "x").replace("*", "m").replace("|", "o")
                .replace("&", "a").replace("+", "p").replace("-", "s");
    }

    // ── reporting ───────────────────────────────────────────────────────────

    private static void writeCsv(Path p, List<Row> rows) throws Exception {
        StringBuilder b = new StringBuilder(
                "name,threads,n_fake_sites,fake_expr,real_expr,oracle_semantic_edges,"
                + "oracle_total_edges,direct_oracle_nonconstant,parser_agrees_oracle,"
                + "required,weakest,syntactic_baseline,folding_baseline,folding_semantic_edges,valid,discriminating,status\n");
        for (Row r : rows) {
            b.append(r.name()).append(',').append(r.threads()).append(',').append(r.nFake())
             .append(',').append('"').append(r.fakeExpr()).append('"')
             .append(',').append('"').append(r.realExpr()).append('"')
             .append(',').append(r.oracleSemanticEdges()).append(',').append(r.oracleTotalEdges())
             .append(',').append(r.directOracleNonConstant())
             .append(',').append(r.parserAgreesWithOracle())
             .append(',').append(r.required()).append(',').append(r.weakest())
             .append(',').append(r.syntactic())
             .append(',').append(r.folding())
             .append(',').append(r.foldingSemanticEdges())
             .append(',').append(r.valid())
             .append(',').append(r.discriminating()).append(',').append('"').append(r.status())
             .append('"').append('\n');
        }
        Files.writeString(p, b.toString());
        System.out.println("wrote " + p);
    }

    private static void summarize(List<Row> rows) {
        int total = rows.size();
        List<Row> valid = rows.stream().filter(Row::valid).toList();
        List<Row> disc = valid.stream().filter(Row::discriminating).toList();

        long wOk = valid.stream().filter(r -> r.weakest().equals(r.required())).count();
        long sOk = valid.stream().filter(r -> r.syntactic().equals(r.required())).count();
        long wOkD = disc.stream().filter(r -> r.weakest().equals(r.required())).count();
        long sOkD = disc.stream().filter(r -> r.syntactic().equals(r.required())).count();

        Map<String, Integer> status = new LinkedHashMap<>();
        for (Row r : rows) status.merge(r.status(), 1, Integer::sum);

        // confusion for WEAKEST over valid cases
        long tp = valid.stream().filter(r -> r.required().equals("FORBIDDEN") && r.weakest().equals("FORBIDDEN")).count();
        long tn = valid.stream().filter(r -> r.required().equals("ALLOWED") && r.weakest().equals("ALLOWED")).count();
        long fp = valid.stream().filter(r -> r.required().equals("ALLOWED") && r.weakest().equals("FORBIDDEN")).count();
        long fn = valid.stream().filter(r -> r.required().equals("FORBIDDEN") && r.weakest().equals("ALLOWED")).count();

        System.out.println("\n================ GENERATED MATCHED-PAIR BENCHMARK ================");
        System.out.printf(Locale.ROOT, "generated cases        : %d%n", total);
        System.out.printf(Locale.ROOT, "valid cases            : %d%n", valid.size());
        System.out.printf(Locale.ROOT, "discriminating cases   : %d%n", disc.size());
        System.out.println("status breakdown       : " + status);
        System.out.printf(Locale.ROOT, "%nWEAKEST   correct (valid)         : %d/%d%n", wOk, valid.size());
        System.out.printf(Locale.ROOT, "syntactic correct (valid)         : %d/%d%n", sOk, valid.size());
        System.out.printf(Locale.ROOT, "WEAKEST   correct (discriminating): %d/%d%n", wOkD, disc.size());
        System.out.printf(Locale.ROOT, "syntactic correct (discriminating): %d/%d%n", sOkD, disc.size());
        System.out.printf(Locale.ROOT, "%nWEAKEST confusion (valid): TP=%d TN=%d FP=%d FN=%d%n", tp, tn, fp, fn);
        long fOk = valid.stream().filter(r -> r.folding().equals(r.required())).count();
        long fOkD = disc.stream().filter(r -> r.folding().equals(r.required())).count();
        System.out.printf(Locale.ROOT, "folding   correct (valid)         : %d/%d%n", fOk, valid.size());
        System.out.printf(Locale.ROOT, "folding   correct (discriminating): %d/%d%n", fOkD, disc.size());
        long agree = valid.stream().filter(Row::parserAgreesWithOracle).count();
        System.out.printf(Locale.ROOT, "parser flags == direct oracle      : %d/%d%n", agree, valid.size());
        System.out.println("=================================================================");
    }
}
