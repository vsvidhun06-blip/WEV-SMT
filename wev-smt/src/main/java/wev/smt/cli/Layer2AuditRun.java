package wev.smt.cli;

import wev.smt.QfbvConstancyOracle;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.LitmusParser.DepDecision;
import wev.smt.parse.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Workstream C3 — Layer-2 detector audit.
 *
 * <p>The QF_BV constancy oracle ({@link QfbvConstancyOracle}) is the dependency detector's
 * <b>Layer 1</b>: it decides constancy of a value/address expression by a Z3 query. When an
 * expression does not parse as the modelled 32-bit bitvector-arithmetic grammar the oracle
 * returns {@code null} and the detector falls back to <b>Layer 2</b> — a conservative
 * always-semantic classification (pattern {@code "CONSERVATIVE"}). The paper claims all
 * supported-syntax tests are decided by Layer 1; this tool enumerates every Layer-2 routing
 * so the claim can be verified (and the offending sites fixed or excluded).
 *
 * <p>Re-parses the whole manifest with the oracle installed and {@link LitmusParser#DEP_TRACE}
 * capturing every classification. Each decision whose pattern contains {@code CONSERVATIVE}
 * is a Layer-2 site; it is written to {@code eval/layer2-audit.csv} with columns
 * {@code test,thread,instr,expression,reason}, de-duplicated per
 * {@code (test,thread,instr,expression)} (one instruction with n registers fires n identical
 * conservative decisions). {@code reason} is the oracle's diagnosis
 * ({@link QfbvConstancyOracle#undecidedReasons()}): {@code "parse: …"} = outside the BV
 * grammar, {@code "solver: …"} = a Z3/interrupt failure.
 *
 * <p>Usage: {@code Layer2AuditRun [manifest.csv] [corpus-root] [out-csv]}. Prints the set of
 * distinct offending expressions (the "N Layer-2 sites") with their reasons and site counts.
 */
public final class Layer2AuditRun {

    private Layer2AuditRun() { }

    private record Site(String test, int thread, int instr, String expr) { }

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args.length > 0 ? args[0] : "eval/corpus-weakest-manifest.csv");
        Path corpusRoot = Path.of(args.length > 1 ? args[1] : "eval/corpus");
        Path outCsv = Path.of(args.length > 2 ? args[2] : "eval/layer2-audit.csv");
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        List<String> relPaths = readManifest(manifest);
        System.out.printf(Locale.ROOT, "Manifest: %d test(s) under %s%n",
                relPaths.size(), corpusRoot.toAbsolutePath());

        // De-duplicated Layer-2 sites, in first-seen order, each pinned to its raw expression.
        Map<Site, String> sites = new LinkedHashMap<>();
        List<DepDecision> decisions = new ArrayList<>();
        int parsed = 0, skipped = 0, parseError = 0, conservativeTests = 0;
        long t0 = System.currentTimeMillis();

        try (QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {
            for (String rel : relPaths) {
                String content;
                try {
                    content = Files.readString(corpusRoot.resolve(rel));
                } catch (IOException io) { skipped++; continue; }

                decisions.clear();
                LitmusParser.DEP_TRACE = decisions::add;
                LitmusParser.CONSTANCY_ORACLE = oracle;
                try {
                    LitmusCase ignored = LitmusParser.parse(content, rel);
                } catch (ParseException pe) {
                    if (pe.kind() == ParseException.Kind.MALFORMED) parseError++; else skipped++;
                    continue;
                } catch (RuntimeException ex) {
                    parseError++; continue;
                } finally {
                    LitmusParser.DEP_TRACE = null;
                    LitmusParser.CONSTANCY_ORACLE = null;
                }
                parsed++;

                boolean anyConservative = false;
                for (DepDecision d : decisions) {
                    if (!d.pattern().contains("CONSERVATIVE")) continue;
                    anyConservative = true;
                    sites.putIfAbsent(
                            new Site(rel, d.threadId(), d.instrIndex(), d.expr().strip()),
                            d.expr().strip());
                }
                if (anyConservative) conservativeTests++;
                if (parsed % 500 == 0) System.out.printf(Locale.ROOT, "  parsed %d…%n", parsed);
            }

            Map<String, String> reasons = oracle.undecidedReasons();  // stripped-expr -> reason
            writeCsv(outCsv, sites, reasons);
            long ms = System.currentTimeMillis() - t0;

            // Distinct offending expressions (the "N Layer-2 sites") with reason + site count.
            Map<String, long[]> byExpr = new TreeMap<>();       // stripped-expr -> {siteCount}
            Map<String, Set<String>> testsByExpr = new TreeMap<>();
            for (Site s : sites.keySet()) {
                String key = s.expr().replaceAll("\\s+", "");
                byExpr.computeIfAbsent(key, k -> new long[1])[0]++;
                testsByExpr.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(s.test());
            }

            System.out.printf(Locale.ROOT,
                    "%nparsed=%d skipped=%d parse_error=%d | conservative_tests=%d | "
                    + "layer2_sites=%d | distinct_expressions=%d%n",
                    parsed, skipped, parseError, conservativeTests, sites.size(), byExpr.size());
            System.out.printf(Locale.ROOT, "oracle: %d queries, %d distinct, %d Z3 solves, "
                    + "%d undecided | %.1fs%n", oracle.queries(), oracle.distinctSeen(),
                    oracle.solverCalls(), oracle.undecided(), ms / 1000.0);
            System.out.println("\n== Distinct Layer-2 expressions ==");
            for (var e : byExpr.entrySet()) {
                System.out.printf(Locale.ROOT, "  %-28s  sites=%-4d tests=%-4d  reason=%s%n",
                        e.getKey(), e.getValue()[0], testsByExpr.get(e.getKey()).size(),
                        reasons.getOrDefault(e.getKey(), "(unknown)"));
            }
            System.out.println("\nCSV: " + outCsv.toAbsolutePath());
        } finally {
            LitmusParser.CONSTANCY_ORACLE = null;
            LitmusParser.DEP_TRACE = null;
        }
    }

    private static void writeCsv(Path path, Map<Site, String> sites,
            Map<String, String> reasons) throws IOException {
        StringBuilder sb = new StringBuilder("test,thread,instr,expression,reason\n");
        for (Site s : sites.keySet()) {
            String reason = reasons.getOrDefault(s.expr().replaceAll("\\s+", ""), "(unknown)");
            sb.append(csv(s.test())).append(',')
              .append(s.thread()).append(',')
              .append(s.instr()).append(',')
              .append(csv(s.expr())).append(',')
              .append(csv(reason)).append('\n');
        }
        Files.writeString(path, sb.toString());
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
        return (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0)
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
