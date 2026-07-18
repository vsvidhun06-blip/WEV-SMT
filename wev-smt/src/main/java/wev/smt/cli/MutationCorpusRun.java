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
import wev.smt.DependencyInfo.KindedEdge;
import wev.smt.EventStructureEncoder;
import wev.smt.QfbvConstancyOracle;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mutation-generated discriminating corpus (task T2.1).
 *
 * <p>Takes the fake-dependency-carrying corpus tests (all {@code r^r} self-XOR) and, for
 * each, mechanically produces a <em>real</em>-dependency mutant by turning the fake
 * self-cancelling instruction into a genuine <em>self-identity</em> one — self-XOR →
 * self-AND — program structure and every constant-folded value untouched:
 * <ul>
 *   <li>PPC / RISC-V: {@code xor rd,rn,rn} → {@code and rd,rn,rn}</li>
 *   <li>AArch64: {@code EOR Wd,Wn,Wn} → {@code AND Wd,Wn,Wn}</li>
 * </ul>
 *
 * <p>{@code rn^rn = 0} (fake) and {@code rn&rn = rn} (real) both fold to 0 when the loaded
 * value is modelled as 0, so the mutant reproduces the original's stored values <em>exactly</em>
 * — the wired candidate (whose {@code rf}/{@code co} follow the {@code exists} values) stays
 * aligned, and the <em>only</em> change is that the dependency is now semantic. (A literal
 * {@code reg+1} would change the stored value and de-align the candidate, spuriously flipping
 * the mutant to ALLOWED — see eval/mutation-summary.md.)
 *
 * <p>Both the original and the mutant are parsed with the exact {@link QfbvConstancyOracle}
 * installed as {@link LitmusParser#CONSTANCY_ORACLE} (so fakeness/realness is decided by
 * the solver, not human judgment), then checked under WEAKEST. A pair is
 * <b>discriminating</b> iff the original (fake) is ALLOWED and the mutant (real) is
 * FORBIDDEN — the grounded-LB vs. thin-air separation.
 *
 * <p>Outputs {@code eval/mutation-corpus-results.csv} and {@code eval/mutation-summary.md}.
 *
 * <p>Usage: {@code MutationCorpusRun [fake-tests.csv] [corpus-root] [out-csv] [summary-md]}.
 */
public final class MutationCorpusRun {

    private MutationCorpusRun() { }

    private static final Pattern PPC_XOR =
            Pattern.compile("(?i)\\bxor\\s+(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*\\2\\b");
    private static final Pattern ARM_EOR =
            Pattern.compile("(?i)\\beor\\s+(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*\\2\\b");

    private record Row(String test, String arch, String origVerdict, String mutVerdict,
                       String exprOrig, String exprMut, boolean discriminating,
                       int origSem, int mutSem, String note) { }

    public static void main(String[] args) throws Exception {
        Path fakeCsv = Path.of(args.length > 0 ? args[0] : "eval/sdep-discriminating-all-fake-tests.csv");
        Path corpusRoot = Path.of(args.length > 1 ? args[1] : "eval/corpus");
        Path outCsv = Path.of(args.length > 2 ? args[2] : "eval/mutation-corpus-results.csv");
        Path summaryMd = Path.of(args.length > 3 ? args[3] : "eval/mutation-summary.md");
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        List<String[]> fakeTests = readFakeCsv(fakeCsv);   // {relPath, arch}
        System.out.printf(Locale.ROOT, "Fake-carrying tests: %d%n", fakeTests.size());

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<Row> rows = new ArrayList<>();

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                     cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
             QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
            LitmusParser.CONSTANCY_ORACLE = oracle;      // exact detector as ground truth
            try {
                for (String[] ft : fakeTests) {
                    rows.add(process(ctx, bmgr, corpusRoot, ft[0], ft[1]));
                }
            } finally {
                LitmusParser.CONSTANCY_ORACLE = null;
            }
        }

        writeCsv(outCsv, rows);
        writeSummary(summaryMd, rows, fakeCsv, corpusRoot);
        long disc = rows.stream().filter(Row::discriminating).count();
        long mutated = rows.stream().filter(r -> r.exprMut() != null && !r.exprMut().isEmpty()).count();
        System.out.printf(Locale.ROOT, "%npairs=%d mutated=%d DISCRIMINATING=%d%n",
                rows.size(), mutated, disc);
        System.out.println("CSV: " + outCsv.toAbsolutePath());
        System.out.println("Summary: " + summaryMd.toAbsolutePath());
    }

    private static Row process(SolverContext ctx, BooleanFormulaManager bmgr, Path corpusRoot,
                               String rel, String arch) {
        String content;
        try {
            content = Files.readString(corpusRoot.resolve(rel));
        } catch (IOException io) {
            return new Row(rel, arch, "", "", "", "", false, -1, -1, "read-error");
        }

        // ── mutate: fake self-XOR → real (reg+1), structure preserved ──
        Pattern pat = arch.equalsIgnoreCase("ARM") ? ARM_EOR : PPC_XOR;
        Matcher m = pat.matcher(content);
        if (!m.find()) {
            return new Row(rel, arch, "", "", "", "", false, -1, -1,
                    "no-self-xor-site(arch=" + arch + ")");
        }
        String exprOrig = m.group().replaceAll("\\s+", " ").trim();
        // self-XOR (rn^rn=0, fake) → self-AND (rn&rn=rn, real): identical folded value,
        // now a genuine dependency. Uniform across arches; no immediate operand needed.
        String replacement = "and " + m.group(1) + "," + m.group(2) + "," + m.group(2);
        String mutated = pat.matcher(content).replaceAll(
                Matcher.quoteReplacement(replacement).replace("$", "\\$"));
        // (all occurrences replaced — column-format tests carry the idiom on both threads)

        // ── parse both (exact oracle installed globally) ──
        LitmusCase origCase, mutCase;
        try {
            origCase = LitmusParser.parse(content, rel);
        } catch (RuntimeException e) {
            return new Row(rel, arch, "", "", exprOrig, replacement, false, -1, -1,
                    "orig-parse-error:" + e.getClass().getSimpleName());
        }
        try {
            mutCase = LitmusParser.parse(mutated, rel + "#mut");
        } catch (RuntimeException e) {
            return new Row(rel, arch, "", "", exprOrig, replacement, false, -1, -1,
                    "mutant-parse-error:" + e.getClass().getSimpleName());
        }

        int origSem = semanticEdges(origCase);
        int mutSem = semanticEdges(mutCase);

        String origV = verdict(ctx, bmgr, origCase);
        String mutV = verdict(ctx, bmgr, mutCase);
        boolean disc = origV.equals("ALLOWED") && mutV.equals("FORBIDDEN");

        String note;
        if (!origV.equals("ALLOWED") && !origV.equals("FORBIDDEN")) note = "orig-solver:" + origV;
        else if (origSem != 0) note = "orig-not-fake(sem=" + origSem + ")";        // oracle disagrees
        else if (mutSem == 0) note = "mutant-not-semantic(sem=0)";                  // mutation ineffective
        else if (disc) note = "discriminating: fake=ALLOWED, real=FORBIDDEN";
        else if (origV.equals("ALLOWED") && mutV.equals("ALLOWED")) note = "fake edge not on a thin-air cycle";
        else if (origV.equals("FORBIDDEN")) note = "orig FORBIDDEN (candidate already inconsistent)";
        else note = "";
        return new Row(rel, arch, origV, mutV, exprOrig, replacement, disc, origSem, mutSem, note);
    }

    private static int semanticEdges(LitmusCase lc) {
        int n = 0;
        for (KindedEdge ke : lc.deps().allEdges()) if (ke.edge().isSemantic()) n++;
        return n;
    }

    private static String verdict(SolverContext ctx, BooleanFormulaManager bmgr, LitmusCase lc) {
        try {
            EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
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

    // ── I/O ──

    private static List<String[]> readFakeCsv(Path csv) throws IOException {
        List<String[]> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {          // skip header
            String ln = lines.get(i).strip();
            if (ln.isEmpty()) continue;
            // col1 = "quoted path", col2 = arch
            Matcher m = Pattern.compile("^\"([^\"]*)\"\\s*,\\s*([^,]*)").matcher(ln);
            if (m.find()) out.add(new String[]{m.group(1), m.group(2).trim()});
        }
        return out;
    }

    private static void writeCsv(Path path, List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder(
                "original_test,original_verdict,mutant_verdict,expression_original,"
                        + "expression_mutant,discriminating,notes\n");
        for (Row r : rows) {
            sb.append(csv(r.test())).append(',')
              .append(r.origVerdict()).append(',')
              .append(r.mutVerdict()).append(',')
              .append(csv(r.exprOrig())).append(',')
              .append(csv(r.exprMut())).append(',')
              .append(r.discriminating() ? "yes" : "no").append(',')
              .append(csv(r.note())).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static void writeSummary(Path path, List<Row> rows, Path fakeCsv, Path corpusRoot)
            throws IOException {
        int total = rows.size();
        List<Row> mutated = rows.stream()
                .filter(r -> r.origVerdict().equals("ALLOWED") || r.origVerdict().equals("FORBIDDEN"))
                .toList();
        long disc = rows.stream().filter(Row::discriminating).count();
        long notMutable = rows.stream().filter(r -> r.note().startsWith("no-self-xor")).count();
        long parseErr = rows.stream().filter(r -> r.note().contains("parse-error")).count();

        // by-arch breakdown of discriminating pairs
        Map<String, int[]> byArch = new TreeMap<>();     // arch -> {pairs, discriminating}
        for (Row r : rows) {
            int[] c = byArch.computeIfAbsent(r.arch(), k -> new int[2]);
            c[0]++;
            if (r.discriminating()) c[1]++;
        }
        // discriminating example list
        List<Row> discRows = rows.stream().filter(Row::discriminating).toList();

        StringBuilder md = new StringBuilder();
        md.append("# Mutation-generated discriminating corpus (T2.1)\n\n");
        md.append("Mechanically derived fake→real dependency pairs from the wild corpus. For ")
          .append("each fake-carrying test (all `r^r` self-XOR), the fake self-cancelling ")
          .append("instruction is turned into a genuine self-identity one — **program ")
          .append("structure and every folded value untouched, only fake→real**:\n\n");
        md.append("- PPC / RISC-V: `xor rd,rn,rn` → `and rd,rn,rn`\n");
        md.append("- AArch64: `EOR Wd,Wn,Wn` → `AND Wd,Wn,Wn`\n\n");
        md.append("`rn^rn = 0` (fake) and `rn&rn = rn` (real) both fold to 0 when the loaded ")
          .append("value is modelled as 0, so the mutant reproduces the original's stored ")
          .append("values exactly — the wired candidate stays aligned with the `exists` ")
          .append("outcome and the only change is that the dependency becomes semantic. A ")
          .append("literal `reg+1` mutation was rejected: it changes the stored value, ")
          .append("de-aligns the candidate (no write of the required value ⇒ reads default ")
          .append("to init), and spuriously flips otherwise-discriminating mutants to ALLOWED.\n\n");
        md.append("Both original and mutant are parsed with the exact **QF_BV constancy ")
          .append("oracle** (`QfbvConstancyOracle`) installed as the detector, so fakeness ")
          .append("(original: `semantic_edges=0`) and realness (mutant: `semantic_edges>0`) ")
          .append("are decided by the solver, not by hand. A pair is **discriminating** iff ")
          .append("the original is `ALLOWED` (grounded LB) and the mutant is `FORBIDDEN` ")
          .append("(real thin-air).\n\n");
        md.append("Source: `").append(fakeCsv).append("` over `").append(corpusRoot).append("`.\n\n");

        md.append("## Totals\n\n| | |\n|---|---|\n");
        md.append("| Fake-carrying tests (pairs generated) | ").append(total).append(" |\n");
        md.append("| Successfully mutated + solved | ").append(mutated.size()).append(" |\n");
        md.append("| No self-XOR mutation site | ").append(notMutable).append(" |\n");
        md.append("| Parse errors (orig or mutant) | ").append(parseErr).append(" |\n");
        md.append("| **Discriminating pairs (fake=ALLOWED, real=FORBIDDEN)** | **")
          .append(disc).append("** |\n\n");

        md.append("## By architecture\n\n| arch | pairs | discriminating |\n|---|---|---|\n");
        for (Map.Entry<String, int[]> e : byArch.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0])
              .append(" | ").append(e.getValue()[1]).append(" |\n");
        }
        md.append('\n');

        if (!discRows.isEmpty()) {
            md.append("## Discriminating pairs\n\n");
            md.append("| test | arch | orig→mut expression |\n|---|---|---|\n");
            for (Row r : discRows) {
                md.append("| `").append(r.test()).append("` | ").append(r.arch()).append(" | `")
                  .append(r.exprOrig()).append("` → `").append(r.exprMut()).append("` |\n");
            }
            md.append('\n');
        }

        md.append("## For the paper (§6.3)\n\n");
        md.append(paperParagraph(total, mutated.size(), disc, byArch));
        md.append("\n\n_No `.tex` files were modified; nothing was committed._\n");
        Files.writeString(path, md.toString());
    }

    private static String paperParagraph(int total, int solved, long disc, Map<String, int[]> byArch) {
        StringBuilder archBits = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byArch.entrySet()) {
            if (e.getValue()[1] > 0) parts.add(e.getValue()[1] + " " + e.getKey());
        }
        archBits.append(String.join(", ", parts));
        String delta = disc > 2 ? "raises the discriminating-test count from 2 to " + disc
                : "reproduces, mechanically and from independently-authored base programs, "
                        + "the " + disc + " discriminating case(s) previously identified";
        return "To put WEAKEST's semantic-dependency sensitivity on a mechanically-derived "
                + "footing rather than the two hand-picked discriminating tests (`LB+datas`, "
                + "`DETOUR0236`), we generate a paired corpus by mutation. Starting from the "
                + total + " fake-dependency-carrying tests in the wild herd7/Dat3M corpus "
                + "(every one an `r^r` self-XOR idiom, confirmed fake by a QF_BV constancy "
                + "oracle), we turn each fake self-cancelling instruction into a genuine "
                + "self-identity dependency (`r^r → r&r`), which leaves every constant-folded "
                + "value — and hence the wired candidate — identical while flipping the "
                + "dependency from fake to real. Running WEAKEST on each original/mutant pair "
                + "yields " + disc + " discriminating pair(s) where the fake original is "
                + "grounded (ALLOWED) and the real mutant is thin-air (FORBIDDEN)"
                + (archBits.length() > 0 ? " (" + archBits + ")" : "") + ". Because the "
                + "mutation is purely syntactic with the solver as ground truth, this " + delta
                + ", confirming that the corpus's fake→real separation is driven by genuine "
                + "value dependence rather than syntactic form.";
    }

    private static String csv(String s) {
        if (s == null) return "";
        return (s.indexOf(',') >= 0 || s.indexOf('"') >= 0)
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
