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
import wev.smt.EventStructureEncoder;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * C1 — symbolic rf + value-propagation runner. A test's WEAKEST verdict is computed two ways
 * over one shared Z3 context: <b>wired</b> (the loader's fixed rf/co + folded constants) and
 * <b>symbolic</b> ({@link EventStructureEncoder#enableSymbolic}: rf/co decision variables +
 * real value propagation via the retained write-value expressions). ALLOWED = SAT,
 * FORBIDDEN = UNSAT.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code ladder} (default) — runs the fixed validation ladder and prints PASS/FAIL per
 *       step against the expected symbolic verdict.</li>
 *   <li>{@code corpus <manifest> <root> <out.csv>} — runs every manifest test in both modes
 *       and writes {@code test,wired_verdict,symbolic_verdict,change,runtime_ms}.</li>
 * </ul>
 */
public final class SymbolicRfRun {

    private SymbolicRfRun() { }

    /** Symbolic value propagation gets heavy on very large structures; cap to stay bounded. */
    private static final int MAX_EVENTS_SYMBOLIC = 80;

    private enum V { ALLOWED, FORBIDDEN, ERROR, SKIP }

    private record LadderStep(String label, String file, V expected) { }

    public static void main(String[] args) throws Exception {
        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();

            if (args.length >= 2 && args[0].equals("diag")) {
                diag(ctx, bmgr, args[1]);
            } else if (args.length == 0 || args[0].equals("ladder")) {
                runLadder(ctx, bmgr);
            } else if (args[0].equals("corpus")) {
                Path manifest = Path.of(args[1]);
                Path root = Path.of(args[2]);
                Path outCsv = Path.of(args[3]);
                runCorpus(ctx, bmgr, manifest, root, outCsv);
            } else if (args[0].equals("herd7")) {
                Path filelist = Path.of(args[1]);
                Path herdCsv = Path.of(args[2]);
                Path root = Path.of(args[3]);
                Path outCsv = Path.of(args[4]);
                runHerd7(ctx, bmgr, filelist, herdCsv, root, outCsv);
            } else {
                System.err.println("usage: SymbolicRfRun [ladder"
                        + " | corpus <manifest> <root> <out.csv>"
                        + " | herd7 <filelist> <herd7-results.csv> <root> <out.csv>]");
                System.exit(2);
            }
        }
    }

    // ── Validation ladder ──────────────────────────────────────────────────

    private static void runLadder(SolverContext ctx, BooleanFormulaManager bmgr) throws Exception {
        String jmm = "eval/examples/jmm/";
        List<LadderStep> ladder = List.of(
                new LadderStep("(a) LB-real",    "eval/examples/paper/LB-real.litmus",          V.FORBIDDEN),
                new LadderStep("(b) LB-fake",    "eval/examples/paper/LB-fake-xor-cycle.litmus", V.ALLOWED),
                new LadderStep("(c) LBfd",       "eval/examples/leaky-semicolon/LBfd.litmus",    V.ALLOWED),
                new LadderStep("(d) DETOUR0236", "eval/corpus/Dat3M/litmus/PPC/DETOUR0236.litmus", V.ALLOWED),
                new LadderStep("(e) TC06",       jmm + "TC06.litmus",                            V.FORBIDDEN),
                new LadderStep("(f) TC16",       jmm + "TC16.litmus",                            V.ALLOWED),
                new LadderStep("(g) TC18",       jmm + "TC18.litmus",                            V.FORBIDDEN),
                new LadderStep("(h) CoWW",       "eval/examples/diag/CoWW-min.litmus",           V.FORBIDDEN),
                new LadderStep("(h) CoWR",       "eval/examples/weakestmo/CoWR.litmus",          V.FORBIDDEN));

        System.out.println("== C1 symbolic-rf validation ladder (WEAKEST) ==");
        System.out.printf(Locale.ROOT, "%-16s %-10s %-10s %-8s %s%n",
                "step", "wired", "symbolic", "expect", "result");
        int pass = 0, fail = 0;
        for (LadderStep s : ladder) {
            V wired, sym;
            try {
                LitmusCase lc = LitmusParser.parse(Files.readString(Path.of(s.file())), s.label());
                wired = verdict(ctx, bmgr, lc, false);
                sym = verdict(ctx, bmgr, lc, true);
            } catch (Exception e) {
                wired = V.ERROR; sym = V.ERROR;
            }
            boolean ok = sym == s.expected();
            if (ok) pass++; else fail++;
            System.out.printf(Locale.ROOT, "%-16s %-10s %-10s %-8s %s%n",
                    s.label(), wired, sym, s.expected(), ok ? "PASS" : "*** FAIL ***");
        }
        System.out.printf(Locale.ROOT, "%nladder: %d PASS / %d FAIL%n", pass, fail);
        if (fail > 0) System.out.println("LADDER FAILED — see *** FAIL *** rows above.");
    }

    // ── Full-corpus wired-vs-symbolic comparison ───────────────────────────

    private static void runCorpus(SolverContext ctx, BooleanFormulaManager bmgr,
            Path manifest, Path root, Path outCsv) throws Exception {
        List<String> rel = readManifest(manifest);
        System.out.printf(Locale.ROOT, "Corpus: %d tests, wired vs symbolic (WEAKEST)%n", rel.size());
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        List<String> rows = new ArrayList<>();
        rows.add("test,wired_verdict,symbolic_verdict,change,runtime_ms");
        int changed = 0, errors = 0, skipped = 0, done = 0;
        List<Long> times = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (String r : rel) {
            String content;
            try { content = Files.readString(root.resolve(r)); }
            catch (IOException io) { skipped++; continue; }
            V wired, sym; long ms = 0;
            try {
                LitmusCase lc = LitmusParser.parse(content, r);
                wired = verdict(ctx, bmgr, lc, false);
                if (lc.es().getEvents().size() > MAX_EVENTS_SYMBOLIC) {
                    sym = V.SKIP; skipped++;
                } else {
                    long t0 = System.nanoTime();
                    sym = verdict(ctx, bmgr, lc, true);
                    ms = (System.nanoTime() - t0) / 1_000_000;
                    times.add(ms);
                }
            } catch (Exception e) { wired = V.ERROR; sym = V.ERROR; errors++; }
            boolean change = wired != sym && wired != V.ERROR && sym != V.ERROR && sym != V.SKIP;
            if (change) changed++;
            rows.add(csv(r) + "," + wired + "," + sym + "," + change + "," + ms);
            if (++done % 250 == 0) System.out.printf(Locale.ROOT, "  %d/%d…%n", done, rel.size());
        }

        Files.write(outCsv, rows);
        long wall = System.currentTimeMillis() - start;
        times.sort(Long::compare);
        System.out.printf(Locale.ROOT,
                "%ntests=%d changed=%d errors=%d skipped=%d | wall=%.1fs%n",
                rel.size(), changed, errors, skipped, wall / 1000.0);
        if (!times.isEmpty()) {
            System.out.printf(Locale.ROOT, "symbolic solve ms: median=%d p95=%d max=%d%n",
                    times.get(times.size() / 2),
                    times.get((int) (times.size() * 0.95)),
                    times.get(times.size() - 1));
        }
        System.out.println("CSV: " + outCsv.toAbsolutePath());
    }

    // ── herd7 cross-check: SC / TSO, wired vs symbolic ─────────────────────

    /** The strong-fragment models cross-checked against herd7 (`sc.cat`, `x86tso.cat`). */
    private enum Model { SC, TSO }

    /**
     * Re-runs the {@code herd7_baseline.md} comparison with symbolic rf/co/value enumeration
     * turned on. {@code filelist} is {@code relpath,arch}; {@code herdCsv} is
     * {@code relpath,arch,herd_sc,herd_tso}. Emits a per-file join of both tools under both
     * WEV modes plus per-model agreement labels.
     */
    private static void runHerd7(SolverContext ctx, BooleanFormulaManager bmgr,
            Path filelist, Path herdCsv, Path root, Path outCsv) throws Exception {
        List<String[]> files = new ArrayList<>();
        for (String line : Files.readAllLines(filelist)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            int c = t.lastIndexOf(',');
            files.add(c < 0 ? new String[] {t, "?"} : new String[] {t.substring(0, c), t.substring(c + 1)});
        }
        // relpath -> {herd_sc, herd_tso}
        java.util.Map<String, String[]> herd = new java.util.HashMap<>();
        List<String> herdLines = Files.readAllLines(herdCsv);
        for (int i = 1; i < herdLines.size(); i++) {
            String[] f = splitCsv(herdLines.get(i));
            if (f.length < 3) continue;
            herd.put(f[0], new String[] {f[2], f.length > 3 ? f[3] : ""});
        }

        System.out.printf(Locale.ROOT, "herd7 cross-check: %d files, SC+TSO, wired vs symbolic%n",
                files.size());
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        List<String> rows = new ArrayList<>();
        rows.add("file,arch,herd_sc,herd_tso,wev_sc_wired,wev_sc_symbolic,"
                + "wev_tso_wired,wev_tso_symbolic,sc_agree_wired,sc_agree_symbolic,"
                + "tso_agree_wired,tso_agree_symbolic,sc_change,tso_change,solve_ms");

        int done = 0, parseErrors = 0, skipped = 0;
        long start = System.currentTimeMillis();
        for (String[] fa : files) {
            String rel = fa[0], arch = fa[1];
            String[] h = herd.getOrDefault(rel, new String[] {"", ""});
            V scW = V.ERROR, scS = V.ERROR, tsoW = V.ERROR, tsoS = V.ERROR;
            long ms = 0;
            try {
                LitmusCase lc = LitmusParser.parse(Files.readString(root.resolve(rel)), rel);
                scW = verdict(ctx, bmgr, lc, false, Model.SC);
                tsoW = verdict(ctx, bmgr, lc, false, Model.TSO);
                if (lc.es().getEvents().size() > MAX_EVENTS_SYMBOLIC) {
                    scS = V.SKIP; tsoS = V.SKIP; skipped++;
                } else {
                    long t0 = System.nanoTime();
                    scS = verdict(ctx, bmgr, lc, true, Model.SC);
                    tsoS = verdict(ctx, bmgr, lc, true, Model.TSO);
                    ms = (System.nanoTime() - t0) / 1_000_000;
                }
            } catch (Exception e) {
                parseErrors++;
            }
            rows.add(String.join(",",
                    csv(rel), arch, h[0], h[1],
                    scW.toString(), scS.toString(), tsoW.toString(), tsoS.toString(),
                    agree(h[0], scW), agree(h[0], scS),
                    agree(h[1], tsoW), agree(h[1], tsoS),
                    Boolean.toString(scW != scS && scS != V.SKIP),
                    Boolean.toString(tsoW != tsoS && tsoS != V.SKIP),
                    Long.toString(ms)));
            if (++done % 100 == 0) System.out.printf(Locale.ROOT, "  %d/%d…%n", done, files.size());
        }
        Files.write(outCsv, rows);

        System.out.printf(Locale.ROOT, "%nfiles=%d parse_errors=%d symbolic_skipped=%d wall=%.1fs%n",
                files.size(), parseErrors, skipped, (System.currentTimeMillis() - start) / 1000.0);
        // Per-arch agreement tables, wired and symbolic, for both models.
        summarise(rows, "SC  wired",     8);
        summarise(rows, "SC  symbolic",  9);
        summarise(rows, "TSO wired",    10);
        summarise(rows, "TSO symbolic", 11);
        System.out.println("CSV: " + outCsv.toAbsolutePath());
    }

    /**
     * Agreement label for one (herd7 verdict, WEV verdict) pair. {@code NA} whenever either
     * side failed to produce a comparable ALLOWED/FORBIDDEN — herd7 ERROR/TIMEOUT/absent, or
     * a WEV parse error / symbolic size skip. NA rows are excluded from every denominator.
     */
    private static String agree(String herdVerdict, V wev) {
        boolean herdOk = "ALLOWED".equals(herdVerdict) || "FORBIDDEN".equals(herdVerdict);
        if (!herdOk || (wev != V.ALLOWED && wev != V.FORBIDDEN)) return "NA";
        return herdVerdict.equals(wev.toString()) ? "AGREE" : "DISAGREE";
    }

    /** Prints an arch-by-arch agree/disagree table over one agreement column of the CSV rows. */
    private static void summarise(List<String> rows, String label, int col) {
        java.util.Map<String, int[]> byArch = new java.util.TreeMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] f = splitCsv(rows.get(i));
            int[] c = byArch.computeIfAbsent(f[1], k -> new int[3]);
            switch (f[col]) {
                case "AGREE" -> c[0]++;
                case "DISAGREE" -> c[1]++;
                default -> c[2]++;
            }
        }
        int ta = 0, td = 0, tn = 0;
        System.out.printf(Locale.ROOT, "%n== %s ==%n%-8s %8s %9s %5s %8s%n",
                label, "arch", "agree", "disagree", "NA", "agree%");
        for (var e : byArch.entrySet()) {
            int[] c = e.getValue();
            ta += c[0]; td += c[1]; tn += c[2];
            int cmp = c[0] + c[1];
            System.out.printf(Locale.ROOT, "%-8s %8d %9d %5d %7s%n", e.getKey(), c[0], c[1], c[2],
                    cmp == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * c[0] / cmp));
        }
        int cmp = ta + td;
        System.out.printf(Locale.ROOT, "%-8s %8d %9d %5d %7s%n", "TOTAL", ta, td, tn,
                cmp == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * ta / cmp));
    }

    /** Minimal RFC4180 split — the only quoted field we emit is the relpath. */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ── Diagnostic: dump the satisfying execution for one file (symbolic) ──

    private static void diag(SolverContext ctx, BooleanFormulaManager bmgr, String file)
            throws Exception {
        LitmusCase lc = LitmusParser.parse(Files.readString(Path.of(file)), file);
        EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
        enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula wf = enc.encodeWellFormedness();
        System.out.println("events: " + lc.es().getEvents());
        // Decompose: wf alone, wf+coherencePerLocation, wf+full WEAKEST.
        report(ctx, bmgr, "wf", wf);
        report(ctx, bmgr, "wf + coherencePerLocation",
                bmgr.and(wf, ax.coherencePerLocation()));
        report(ctx, bmgr, "wf + consistencyWEAKEST",
                bmgr.and(wf, ax.consistencyWEAKEST()));
    }

    private static void report(SolverContext ctx, BooleanFormulaManager bmgr, String label,
            BooleanFormula f) throws Exception {
        try (ProverEnvironment pr = ctx.newProverEnvironment()) {
            pr.addConstraint(f);
            boolean unsat = pr.isUnsat();
            System.out.println("  " + label + " => " + (unsat ? "UNSAT (forbidden)" : "SAT (allowed)"));
        }
    }

    // ── Core: one WEAKEST verdict, wired or symbolic ───────────────────────

    private static V verdict(SolverContext ctx, BooleanFormulaManager bmgr,
            LitmusCase lc, boolean symbolic) throws Exception {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
        if (symbolic) enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(),
                lc.existsMemFinal());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula wf = enc.encodeWellFormedness();
        try (ProverEnvironment pr = ctx.newProverEnvironment()) {
            pr.addConstraint(bmgr.and(wf, ax.consistencyWEAKEST()));
            return pr.isUnsat() ? V.FORBIDDEN : V.ALLOWED;
        }
    }

    /** As {@link #verdict(SolverContext, BooleanFormulaManager, LitmusCase, boolean)}, for a
     *  chosen strong-fragment model rather than WEAKEST. */
    private static V verdict(SolverContext ctx, BooleanFormulaManager bmgr,
            LitmusCase lc, boolean symbolic, Model model) throws Exception {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
        if (symbolic) enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(),
                lc.existsMemFinal());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula wf = enc.encodeWellFormedness();
        BooleanFormula cons = switch (model) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
        };
        try (ProverEnvironment pr = ctx.newProverEnvironment()) {
            pr.addConstraint(bmgr.and(wf, cons));
            return pr.isUnsat() ? V.FORBIDDEN : V.ALLOWED;
        }
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
