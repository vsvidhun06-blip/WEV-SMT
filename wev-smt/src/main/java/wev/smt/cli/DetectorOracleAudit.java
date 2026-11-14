package wev.smt.cli;

import wev.smt.QfbvConstancyOracle;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.LitmusParser.DepDecision;


import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>Differential detector audit</b> — the permanent regression gate for the fake/real
 * dependency classifier.
 *
 * <p><b>What this audit checks.</b> That the shipped detector and the exact SMT oracle
 * agree on every audited expression. Any divergence in the unsound direction fails the
 * build.
 *
 * <p>For each audited pair {@code (expr, reg)} it compares
 * <ul>
 *   <li>{@code detector_verdict} — the shipped {@code LitmusParser.Job.classify} path with
 *       no oracle installed ({@code CONSTANCY_ORACLE = null}: idiom folding followed by
 *       linear-cancellation normalisation), and</li>
 *   <li>{@code oracle_verdict} — the exact QF_BV constancy query
 *       {@link QfbvConstancyOracle#isConstant(String, String)}.</li>
 * </ul>
 *
 * <p><b>Two divergence classes, only one of which is a defect.</b> Strict equality of the
 * two verdicts is <em>not</em> the shipped contract and is not expected to hold:
 * <ul>
 *   <li><b>UNSOUND</b> — detector says {@code FAKE}, oracle proves the expression
 *       <em>varies</em>. The detector has dropped a genuinely value-carrying dependency
 *       from {@code sdep}. Removing an edge can break a {@code jf ∪ sdep} cycle and turn a
 *       FORBIDDEN outcome into ALLOWED. <b>This fails the audit.</b></li>
 *   <li><b>CONSERVATIVE</b> — detector says {@code REAL}, oracle proves the expression is
 *       <em>constant</em>. The detector kept an edge it did not have to. This is the
 *       documented behaviour of the solver-free fallback, which bails to {@code REAL} on
 *       anything the linear pass cannot normalise (e.g. {@code r&1&2}). It is sound, so it
 *       does not fail the audit — but the count is <b>pinned</b> against
 *       {@code --max-conservative} so that a silent precision regression is caught too.</li>
 * </ul>
 * Both counts are reported. The strict-equality mismatch total is reported as
 * {@code unsound + conservative} so the raw number asked for is visible.
 *
 * <p><b>Audited population.</b> Two independent sources:
 * <ol>
 *   <li><b>Enumerated</b> — synthetic expressions over one register, a constant pool and
 *       the modelled operators, at 1 and 2 binary operators with optional unary {@code ~},
 *       plus a fixed regression list of the witnesses from the 2026-07-28 folding defect.
 *       These are handed straight to {@code classify}. Because {@code classify} is private,
 *       it is reached by reflection — this audit <em>observes</em> the shipped method and
 *       modifies nothing.</li>
 *   <li><b>Corpus</b> — every {@code (expr, reg)} pair the parser actually hands to
 *       {@code classify} while parsing the supported-syntax fragment, harvested through
 *       {@link LitmusParser#DEP_TRACE}. This is the real reachable population: store value
 *       expressions, register-assignment right-hand sides and branch guards.</li>
 * </ol>
 *
 * <p><b>Coverage note.</b> Every invocation of {@code classify} is audited, but not every
 * dependency <em>edge</em> consumes a {@code classify} verdict. Control edges are recorded
 * with {@code addCtrlDep(w, r, true)} — the verdict computed for the guard expression is
 * discarded — so a control-dependency misclassification is outside what this audit can
 * observe. See {@code findings-audit-hardening.md} §coverage.
 *
 * <p>Exit code {@code 0} when the audit passes, {@code 1} when it fails. Writes a CSV of
 * every divergence.
 *
 * <p>Usage: {@code DetectorOracleAudit [manifest.csv] [corpus-root] [out-csv]
 * [--max-conservative N] [--depth N] [--limit N] [--no-corpus]}.
 */
public final class DetectorOracleAudit {

    private DetectorOracleAudit() { }

    /** One audited pair on which the detector and the oracle disagree. */
    private record Divergence(String source, String expr, String reg,
                              boolean detectorReal, boolean oracleVaries, String origin) {
        /** The detector dropped a dependency the oracle proves is real. */
        boolean unsound() { return !detectorReal && oracleVaries; }

        String kind() { return unsound() ? "UNSOUND" : "CONSERVATIVE"; }

        /** Coarse shape used to group divergences in the report. */
        String shape() {
            String e = expr.replaceAll("\\s+", "");
            StringBuilder ops = new StringBuilder();
            for (char c : e.toCharArray()) {
                if ("+-*&|^~".indexOf(c) >= 0) ops.append(c);
            }
            return ops.length() == 0 ? "(no operator)" : ops.toString();
        }
    }

    // ── The enumeration alphabet ────────────────────────────────────────────────
    //
    // Mirrors the enumeration used in the 2026-07-28 detector investigation so the two
    // populations are comparable. Constants are chosen at mask and carry boundaries.

    private static final String REG = "r0";
    private static final String[] CONSTS = { "0", "1", "2", "3", "7", "8", "255" };
    private static final String[] BIN = { "+", "-", "&", "^", "|", "*" };

    /**
     * Witnesses of the folding defect fixed on 2026-07-28 (precedence-spanning idiom
     * matches). Pinned here so the audit keeps testing them even if the enumeration
     * alphabet is ever narrowed.
     */
    private static final String[][] REGRESSION_WITNESSES = {
            { "~r0 - r0",           "r0" },
            { "r0 ^ r0 + 1",        "r0" },
            { "r0 & ~r0 + 1",       "r0" },
            { "r0 & r0 - r0 + 3",   "r0" },
            { "~7 - r0 ^ r0",       "r0" },
            { "~r0 ^ r0 + r0 ^ r0", "r0" },
            { "t0 ^ t0 & 7 & 7",    "t0" },
            { "~r0 & r0 ^ r0",      "r0" },
    };

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of("eval/corpus-weakest-manifest-v2.csv");
        Path corpusRoot = Path.of("eval/corpus");
        Path outCsv = Path.of("eval/detector-oracle-audit.csv");
        int maxConservative = -1;      // -1 = report only, do not pin
        int depth = 2;
        int limit = 12000;             // 0 = uncapped (nightly sweep)
        boolean doCorpus = true;

        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--max-conservative" -> maxConservative = Integer.parseInt(args[++i]);
                case "--depth" -> depth = Integer.parseInt(args[++i]);
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--no-corpus" -> doCorpus = false;
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() > 0) manifest = Path.of(positional.get(0));
        if (positional.size() > 1) corpusRoot = Path.of(positional.get(1));
        if (positional.size() > 2) outCsv = Path.of(positional.get(2));
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        Method classify = reflectClassify();
        List<Divergence> divergences = new ArrayList<>();
        int enumerated = 0, corpusPairs = 0, outsideGrammar = 0;
        long t0 = System.currentTimeMillis();

        try (QfbvConstancyOracle oracle = new QfbvConstancyOracle()) {

            // ── Population 1: enumerated expressions ────────────────────────────
            LinkedHashSet<String> exprs = enumerate(depth, limit);
            System.out.printf(Locale.ROOT, "[enumerate] depth<=%d over {%s} and %s, limit %s: %d expressions%n",
                    depth, String.join(",", BIN), REG,
                    limit > 0 ? Integer.toString(limit) : "none", exprs.size());
            for (String e : exprs) {
                Boolean constant = oracle.isConstant(e, REG);
                if (constant == null) { outsideGrammar++; continue; }
                enumerated++;
                boolean real = detectorSaysReal(classify, e, REG);
                if (real == constant) {                 // real&&constant, or fake&&varies
                    divergences.add(new Divergence("enumerated", e, REG, real, !constant,
                            "synthetic"));
                }
            }
            for (String[] w : REGRESSION_WITNESSES) {
                Boolean constant = oracle.isConstant(w[0], w[1]);
                if (constant == null) { outsideGrammar++; continue; }
                enumerated++;
                boolean real = detectorSaysReal(classify, w[0], w[1]);
                if (real == constant) {
                    divergences.add(new Divergence("regression-witness", w[0], w[1], real,
                            !constant, "2026-07-28 folding defect"));
                }
            }

            // ── Population 2: every (expr, reg) the corpus actually produces ─────
            if (doCorpus) {
                Map<String, String> seen = new LinkedHashMap<>();   // "expr reg" -> test
                List<DepDecision> decisions = new ArrayList<>();
                LitmusParser.CONSTANCY_ORACLE = null;               // shipped default path
                LitmusParser.DEP_TRACE = decisions::add;
                int files = 0, parseErrors = 0;
                try {
                    for (String rel : readManifest(manifest)) {
                        String content;
                        try {
                            content = Files.readString(corpusRoot.resolve(rel));
                        } catch (IOException io) { continue; }
                        decisions.clear();
                        try {
                            LitmusParser.parse(content, rel);
                        } catch (RuntimeException ex) {
                            parseErrors++;   // ParseException is a RuntimeException subclass;
                            // decisions collected before the failure are still valid
                        }
                        files++;
                        for (DepDecision d : decisions) {
                            seen.putIfAbsent(d.expr() + " " + d.reg(), rel);
                        }
                    }
                } finally {
                    LitmusParser.DEP_TRACE = null;
                }
                System.out.printf(Locale.ROOT,
                        "[corpus]    %d files parsed (%d parse errors), %d distinct (expr,reg) pairs%n",
                        files, parseErrors, seen.size());

                for (Map.Entry<String, String> en : seen.entrySet()) {
                    String[] kv = en.getKey().split(" ", 2);
                    String e = kv[0], reg = kv[1];
                    Boolean constant = oracle.isConstant(e, reg);
                    if (constant == null) { outsideGrammar++; continue; }
                    corpusPairs++;
                    boolean real = detectorSaysReal(classify, e, reg);
                    if (real == constant) {
                        divergences.add(new Divergence("corpus", e, reg, real, !constant,
                                en.getValue()));
                    }
                }
            }
        }

        long unsound = divergences.stream().filter(Divergence::unsound).count();
        long conservative = divergences.size() - unsound;
        double secs = (System.currentTimeMillis() - t0) / 1000.0;

        // ── CSV of every divergence ─────────────────────────────────────────────
        StringBuilder sb = new StringBuilder("kind,source,expr,reg,detector,oracle,shape,origin\n");
        divergences.stream()
                .sorted(Comparator.comparing(Divergence::kind).thenComparing(Divergence::expr))
                .forEach(d -> sb.append(d.kind()).append(',')
                        .append(d.source()).append(',')
                        .append('"').append(d.expr().replace("\"", "\"\"")).append('"').append(',')
                        .append(d.reg()).append(',')
                        .append(d.detectorReal() ? "REAL" : "FAKE").append(',')
                        .append(d.oracleVaries() ? "VARIES" : "CONSTANT").append(',')
                        .append('"').append(d.shape()).append('"').append(',')
                        .append('"').append(d.origin().replace("\"", "\"\"")).append('"')
                        .append('\n'));
        Files.writeString(outCsv, sb.toString());

        // ── Report ──────────────────────────────────────────────────────────────
        System.out.printf(Locale.ROOT, "%n=== differential detector audit ===%n");
        System.out.printf(Locale.ROOT, "  enumerated expressions checked   : %d%n", enumerated);
        System.out.printf(Locale.ROOT, "  corpus (expr,reg) pairs checked  : %d%n", corpusPairs);
        System.out.printf(Locale.ROOT, "  total audited                    : %d%n",
                enumerated + corpusPairs);
        System.out.printf(Locale.ROOT, "  outside the oracle grammar       : %d (not comparable)%n",
                outsideGrammar);
        System.out.printf(Locale.ROOT, "  strict-equality mismatches       : %d%n",
                divergences.size());
        System.out.printf(Locale.ROOT, "    of which CONSERVATIVE (sound)  : %d%n", conservative);
        System.out.printf(Locale.ROOT, "    of which UNSOUND (defect)      : %d%n", unsound);
        System.out.printf(Locale.ROOT, "  wall seconds                     : %.1f%n", secs);
        System.out.printf(Locale.ROOT, "  csv                              : %s%n",
                outCsv.toAbsolutePath());

        if (unsound > 0) {
            System.out.printf(Locale.ROOT, "%n  *** UNSOUND DIVERGENCES, grouped by shape ***%n");
            Map<String, List<Divergence>> byShape = new TreeMap<>();
            divergences.stream().filter(Divergence::unsound)
                    .forEach(d -> byShape.computeIfAbsent(d.shape(), k -> new ArrayList<>()).add(d));
            for (Map.Entry<String, List<Divergence>> g : byShape.entrySet()) {
                List<Divergence> l = g.getValue();
                Divergence minimal = l.stream()
                        .min(Comparator.comparingInt(d -> d.expr().replaceAll("\\s+", "").length()))
                        .orElseThrow();
                System.out.printf(Locale.ROOT,
                        "    shape %-10s  %3d case(s)  minimal counterexample: %s   (wrt %s)%n",
                        g.getKey(), l.size(), minimal.expr(), minimal.reg());
                l.stream().limit(5).forEach(d -> System.out.printf(Locale.ROOT,
                        "        %-30s wrt %-4s detector=FAKE oracle=VARIES   [%s]%n",
                        d.expr(), d.reg(), d.origin()));
            }
        }

        boolean fail = unsound > 0;
        if (maxConservative >= 0 && conservative > maxConservative) {
            System.out.printf(Locale.ROOT,
                    "%n  *** CONSERVATIVE COUNT REGRESSION: %d > pinned maximum %d ***%n"
                    + "      The detector became less precise. This is sound but unintended;%n"
                    + "      re-pin --max-conservative deliberately if the change is wanted.%n",
                    conservative, maxConservative);
            fail = true;
        }

        System.out.printf(Locale.ROOT, "%n  RESULT: %s%n", fail ? "FAIL" : "PASS");
        if (fail) System.exit(1);
    }

    // ── Detector access ─────────────────────────────────────────────────────────

    /**
     * Reflective handle on {@code LitmusParser.Job.classify(String,String)}.
     *
     * <p>{@code classify} is private to a private nested class. The audit needs to compare
     * it pair-by-pair against the oracle over synthetic expressions, including shapes the
     * litmus front ends cannot express (parenthesised terms; {@code |}, which the C dialect
     * reads as the thread-column separator). Reflection observes the shipped method
     * directly and changes nothing about it — no production code is modified, and no
     * accessor is added to the parser for the benefit of a test.
     */
    private static Method reflectClassify() throws Exception {
        Class<?> job = Class.forName("wev.smt.parse.LitmusParser$Job");
        Method m = job.getDeclaredMethod("classify", String.class, String.class);
        m.setAccessible(true);
        return m;
    }

    /** {@code true} when the shipped detector calls {@code (expr, reg)} a REAL dependency. */
    private static boolean detectorSaysReal(Method classify, String expr, String reg)
            throws Exception {
        Object verdict = classify.invoke(null, expr, reg);
        Method real = verdict.getClass().getDeclaredMethod("real");
        real.setAccessible(true);
        return (Boolean) real.invoke(verdict);
    }

    // ── Enumeration ─────────────────────────────────────────────────────────────

    /**
     * All flat expressions with 1..{@code depth} binary operators over the alphabet,
     * in a deterministic order, capped at {@code limit} ({@code 0} = uncapped).
     *
     * <p>The full depth-2 cross-product over this alphabet is ~10^5 expressions, and every
     * one costs a Z3 query, which is far too slow for a build gate. The enumeration is
     * therefore capped. The order is deterministic (shorter expressions first, then
     * insertion order), so a given {@code --limit} always audits exactly the same set and
     * the count is reproducible. Raise or remove the cap for a nightly sweep.
     */
    private static LinkedHashSet<String> enumerate(int depth, int limit) {
        List<String> atoms = new ArrayList<>();
        atoms.add(REG);
        atoms.add("~" + REG);
        for (String c : CONSTS) { atoms.add(c); atoms.add("~" + c); }

        LinkedHashSet<String> cur = new LinkedHashSet<>(atoms);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int d = 1; d <= depth; d++) {
            LinkedHashSet<String> next = new LinkedHashSet<>();
            for (String left : cur) {
                for (String op : BIN) {
                    for (String a : atoms) {
                        String e = left + " " + op + " " + a;
                        next.add(e);
                        // Only expressions mentioning the register carry information;
                        // the rest are trivially constant in it.
                        if (e.matches(".*(?<![A-Za-z0-9_])" + REG + "(?![A-Za-z0-9_]).*")) {
                            out.add(e);
                            if (limit > 0 && out.size() >= limit) return out;
                        }
                    }
                }
            }
            cur = next;
        }
        return out;
    }

    // ── Manifest ────────────────────────────────────────────────────────────────

    /** {@code <relative-path>,<verdict>} per line, no header — same convention as
     *  {@link QfbvDetectorRun#main} so both runners consume the identical manifest. */
    private static List<String> readManifest(Path manifest) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) return out;
        for (String line : Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            int comma = t.indexOf(',');
            out.add(comma < 0 ? t : t.substring(0, comma));
        }
        return out;
    }
}
