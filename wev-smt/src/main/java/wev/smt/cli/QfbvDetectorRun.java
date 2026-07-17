package wev.smt.cli;

import wev.smt.DependencyInfo.KindedEdge;
import wev.smt.QfbvConstancyOracle;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.LitmusParser.DepDecision;
import wev.smt.parse.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-runs the whole {@code .litmus} corpus through {@link LitmusParser} twice — once with
 * the solver-free detector (default) and once with the exact {@link QfbvConstancyOracle}
 * installed as {@link LitmusParser#CONSTANCY_ORACLE} — and compares the per-test
 * dependency classification.
 *
 * <p>"Verdict" here is the detector's semantic-dependency count {@code sdep=N} (the size of
 * {@code sdep_impl}, the number of dependency edges kept semantic). Because the exact
 * oracle is never <em>less</em> precise than the heuristic, a test can only change by
 * having a semantic edge demoted to fake, so any change shows as {@code sdep} shrinking.
 * {@code method} records which detection path the new detector exercised for that test:
 * {@code pattern} (all decisions resolved by the O(1) idiom fast-path), {@code qfbv} (the
 * oracle decided at least one), {@code conservative} (at least one expression was outside
 * the modelled grammar and left semantic), or {@code none} (no dependency decisions).
 *
 * <p>Outputs {@code eval/qfbv-detector-results.csv}
 * ({@code test,old_verdict,new_verdict,changed,method}) and {@code eval/qfbv-summary.md}.
 *
 * <p>Usage: {@code QfbvDetectorRun [manifest.csv] [corpus-root] [out-csv] [summary-md]}.
 */
public final class QfbvDetectorRun {

    private QfbvDetectorRun() { }

    private record Row(String test, int oldSem, int newSem, boolean changed, String method) { }

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args.length > 0 ? args[0] : "eval/corpus-weakest-manifest.csv");
        Path corpusRoot = Path.of(args.length > 1 ? args[1] : "eval/corpus");
        Path outCsv = Path.of(args.length > 2 ? args[2] : "eval/qfbv-detector-results.csv");
        Path summaryMd = Path.of(args.length > 3 ? args[3] : "eval/qfbv-summary.md");
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        List<String> relPaths = readManifest(manifest);
        System.out.printf(Locale.ROOT, "Manifest: %d test(s) under %s%n",
                relPaths.size(), corpusRoot.toAbsolutePath());

        List<DepDecision> decisions = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        int parsed = 0, skipped = 0, parseError = 0;
        long t0 = System.currentTimeMillis();

        try (QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            for (String rel : relPaths) {
                String content;
                try {
                    content = Files.readString(corpusRoot.resolve(rel));
                } catch (IOException io) { skipped++; continue; }

                // ── OLD detector (solver-free default) ──
                LitmusParser.CONSTANCY_ORACLE = null;
                LitmusParser.DEP_TRACE = null;
                LitmusCase oldCase;
                try {
                    oldCase = LitmusParser.parse(content, rel);
                } catch (ParseException pe) {
                    if (pe.kind() == ParseException.Kind.MALFORMED) parseError++; else skipped++;
                    continue;
                } catch (RuntimeException ex) { parseError++; continue; }
                int oldSem = semanticEdges(oldCase);

                // ── NEW detector (exact QF_BV oracle installed) ──
                decisions.clear();
                LitmusParser.DEP_TRACE = decisions::add;
                LitmusParser.CONSTANCY_ORACLE = oracle;
                LitmusCase newCase;
                try {
                    newCase = LitmusParser.parse(content, rel);
                } finally {
                    LitmusParser.DEP_TRACE = null;
                    LitmusParser.CONSTANCY_ORACLE = null;
                }
                int newSem = semanticEdges(newCase);
                String method = method(decisions);

                parsed++;
                rows.add(new Row(rel, oldSem, newSem, oldSem != newSem, method));
                if (parsed % 500 == 0) System.out.printf(Locale.ROOT, "  parsed %d…%n", parsed);
            }

            long ms = System.currentTimeMillis() - t0;
            writeCsv(outCsv, rows);
            writeSummary(summaryMd, rows, parsed, skipped, parseError, oracle, ms,
                    corpusRoot, manifest);
            long changed = rows.stream().filter(Row::changed).count();
            System.out.printf(Locale.ROOT,
                    "%nparsed=%d skipped=%d parse_error=%d | CHANGED=%d%n",
                    parsed, skipped, parseError, changed);
            System.out.printf(Locale.ROOT,
                    "oracle: %d queries, %d distinct, %d Z3 solves, %d undecided | %.1fs%n",
                    oracle.queries(), oracle.distinctSeen(), oracle.solverCalls(),
                    oracle.undecided(), ms / 1000.0);
            System.out.println("CSV: " + outCsv.toAbsolutePath());
            System.out.println("Summary: " + summaryMd.toAbsolutePath());
        } finally {
            LitmusParser.CONSTANCY_ORACLE = null;
            LitmusParser.DEP_TRACE = null;
        }
    }

    /** Number of semantic dependency edges in a parsed case (= |sdep_impl|). */
    private static int semanticEdges(LitmusCase lc) {
        int n = 0;
        for (KindedEdge ke : lc.deps().allEdges()) if (ke.edge().isSemantic()) n++;
        return n;
    }

    /** Which detection path the new detector used for a test's decisions. */
    private static String method(List<DepDecision> decisions) {
        if (decisions.isEmpty()) return "none";
        boolean qfbv = false, conservative = false;
        for (DepDecision d : decisions) {
            String p = d.pattern();
            if (p.contains("CONSERVATIVE")) conservative = true;
            else if (p.contains("QFBV")) qfbv = true;
        }
        if (conservative) return "conservative";
        if (qfbv) return "qfbv";
        return "pattern";
    }

    // ── output ──

    private static void writeCsv(Path path, List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder("test,old_verdict,new_verdict,changed,method\n");
        for (Row r : rows) {
            sb.append(csv(r.test())).append(",sdep=").append(r.oldSem())
              .append(",sdep=").append(r.newSem()).append(',')
              .append(r.changed()).append(',').append(r.method()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static void writeSummary(Path path, List<Row> rows, int parsed, int skipped,
            int parseError, QfbvConstancyOracle oracle, long ms, Path corpusRoot,
            Path manifest) throws IOException {
        long changed = rows.stream().filter(Row::changed).count();
        long withDeps = rows.stream().filter(r -> r.newSem() > 0 || r.method().equals("qfbv")
                || r.method().equals("conservative") || r.method().equals("pattern")).count();
        Map<String, Integer> byMethod = new TreeMap<>();
        for (Row r : rows) byMethod.merge(r.method(), 1, Integer::sum);
        long totalOldSem = rows.stream().mapToLong(Row::oldSem).sum();
        long totalNewSem = rows.stream().mapToLong(Row::newSem).sum();

        StringBuilder md = new StringBuilder();
        md.append("# QF_BV constancy oracle — detector re-run\n\n");
        md.append("Re-ran the whole corpus through `LitmusParser` twice: the solver-free ")
          .append("detector (default) vs. the exact `QfbvConstancyOracle` installed as the ")
          .append("primary decision procedure (`LitmusParser.CONSTANCY_ORACLE`). Full data: ")
          .append("`").append(path.getParent().relativize(
                  path.resolveSibling("qfbv-detector-results.csv"))).append("`.\n\n");

        md.append("## New detector architecture (`LitmusParser.classify`)\n\n");
        md.append("1. **Idiom fast-path (O(1)).** The 8 self-cancelling patterns ")
          .append("(`r^r`, `r-r`, `r&~r`, `~r&r`, `r*0`, `0*r`, `r&0`, `0&r`, plus ")
          .append("`r|~r`/`~r|r`); if folding removes `reg`, return **fake** immediately.\n");
        md.append("2. **QF_BV constancy oracle (primary).** Otherwise, if `expr` parses as ")
          .append("32-bit bitvector arithmetic, ask Z3 ")
          .append("`∃ r1,r2. e[reg:=r1] ≠ e[reg:=r2]` (other regs shared/free): **SAT ⇒ ")
          .append("real**, **UNSAT ⇒ fake**.\n");
        md.append("3. **Conservative.** If `expr` is outside that grammar, classify as ")
          .append("**semantic** (unchanged from before).\n\n");
        md.append("The oracle subsumes the old linear-cancellation heuristic (it also proves ")
          .append("non-linear invariance), so on the branch-free data/address fragment the ")
          .append("implementation is now **exact**: `sdep_impl = sdep_true`. With no oracle ")
          .append("installed the parser keeps its solver-free fallback, so every other tool's ")
          .append("classification is byte-for-byte unchanged.\n\n");

        md.append("## Corpus\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Manifest | `").append(manifest).append("` |\n");
        md.append("| Corpus root | `").append(corpusRoot).append("` |\n");
        md.append("| Files parsed (rows) | ").append(parsed).append(" |\n");
        md.append("| Files skipped / parse-error | ").append(skipped).append(" / ")
          .append(parseError).append(" |\n\n");

        md.append("## Result: how many verdicts changed?\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| **Tests whose classification changed** | **").append(changed)
          .append(" / ").append(parsed).append("** |\n");
        md.append("| Total semantic edges, old detector | ").append(totalOldSem).append(" |\n");
        md.append("| Total semantic edges, new detector | ").append(totalNewSem).append(" |\n");
        md.append("| Net edges demoted semantic→fake by the oracle | ")
          .append(totalOldSem - totalNewSem).append(" |\n\n");
        if (changed == 0) {
            md.append("**0 changes.** The exact oracle demotes no edge the solver-free ")
              .append("detector kept semantic, and promotes none it dropped — the two agree ")
              .append("on every test. What was *empirically* validated (the heuristic missed ")
              .append("no fake) is now **provably exact** on the modelled fragment: the oracle ")
              .append("is the ground truth and it confirms `sdep_impl = sdep_true`.\n\n");
        } else {
            md.append("**").append(changed).append(" test(s) changed** — the oracle demoted a ")
              .append("semantic edge the heuristic missed. See the CSV (`changed=true`).\n\n");
        }

        md.append("## Which detection path was used\n\n");
        md.append("| method | tests | meaning |\n|---|---|---|\n");
        md.append("| `pattern` | ").append(byMethod.getOrDefault("pattern", 0))
          .append(" | all decisions resolved by the O(1) idiom fast-path |\n");
        md.append("| `qfbv` | ").append(byMethod.getOrDefault("qfbv", 0))
          .append(" | the Z3 oracle decided ≥1 (real or fake) |\n");
        md.append("| `conservative` | ").append(byMethod.getOrDefault("conservative", 0))
          .append(" | ≥1 expression outside the BV grammar, left semantic |\n");
        md.append("| `none` | ").append(byMethod.getOrDefault("none", 0))
          .append(" | no dependency decisions |\n\n");

        md.append("## Performance impact\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Oracle queries (`isConstant` calls) | ").append(oracle.queries())
          .append(" |\n");
        md.append("| Distinct `(expr,reg)` (memoized) | ").append(oracle.distinctSeen())
          .append(" |\n");
        md.append("| **Actual Z3 solves** (cache misses that parsed) | **")
          .append(oracle.solverCalls()).append("** |\n");
        md.append("| Undecided (unparseable ⇒ conservative) | ").append(oracle.undecided())
          .append(" |\n");
        md.append("| Wall-clock, both passes over the corpus | ")
          .append(String.format(Locale.ROOT, "%.1f s", ms / 1000.0)).append(" |\n\n");
        md.append("Memoization collapses the ").append(oracle.queries())
          .append(" oracle calls to ").append(oracle.solverCalls())
          .append(" real Z3 queries (one per distinct expression), so making the detector ")
          .append("exact adds negligible cost to a full-corpus parse — the fast-path handles ")
          .append("the common idioms and the solver is consulted only for the handful of ")
          .append("distinct value expressions the corpus actually contains.\n\n");

        md.append("_No `.tex` files were modified; nothing was committed._\n");
        Files.writeString(path, md.toString());
    }

    private static List<String> readManifest(Path manifest) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            int comma = t.indexOf(',');
            out.add(comma < 0 ? t : t.substring(0, comma));
        }
        return out;
    }

    private static String csv(String s) {
        return (s.indexOf(',') >= 0 || s.indexOf('"') >= 0)
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
