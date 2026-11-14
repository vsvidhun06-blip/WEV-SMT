package wev.smt.cli;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.WriteEvent;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coherence-order enumeration for the SC-per-location tests (CoWW / CoWR / CoRR / CoRW).
 *
 * <p>The loader wires a <em>single</em> candidate execution: {@code rf} value-pinned to
 * the {@code exists} outcome and {@code co} in one fixed order (init first, program writes
 * in textual order). For a coherence test whose {@code exists} names a memory-final value
 * ({@code exists (x=1)}), that single {@code co} order may not even realise the named
 * outcome — so the loader's lone verdict can be misleading (e.g. CoWW-min: the wired order
 * makes {@code x=2} final and reports ALLOWED, though {@code x=1} is unrealisable).
 *
 * <p>This tool instead <b>enumerates every {@code co} ordering</b> (small: 2–3 writes per
 * location) and runs the WEAKEST checker on each. The initial write stays co-minimal (it
 * is the initial state; permuting it would model a program write being overwritten by the
 * init), so only the program writes are permuted, per location, in a Cartesian product.
 *
 * <p>A candidate is counted as witnessing consistency only if it <em>both</em>
 * <ul>
 *   <li>realises the {@code exists} outcome — every memory-final constraint {@code loc=val}
 *       is met by the co-maximal write of {@code loc} having value {@code val} (register
 *       constraints {@code T:r=v} are already pinned through {@code rf}); and</li>
 *   <li>is WEAKEST-consistent (well-formedness ∧ {@code consistencyWEAKEST} is SAT).</li>
 * </ul>
 *
 * <p>Output: {@code eval/co-enumeration-results.csv} with columns
 * {@code test,candidates_tried,any_consistent,weakest_verdict} — {@code weakest_verdict}
 * is ALLOWED iff some enumerated co ordering realises the outcome consistently.
 */
public final class CoEnumeration {

    private CoEnumeration() { }

    private record Result(String test, int candidatesTried, boolean anyConsistent) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CoEnumeration <out.csv> <file.litmus>...");
            System.exit(2);
            return;
        }
        Path outCsv = Path.of(args[0]);
        if (outCsv.getParent() != null) Files.createDirectories(outCsv.getParent());

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        List<String> rows = new ArrayList<>();
        rows.add("test,candidates_tried,any_consistent,weakest_verdict");

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            for (int i = 1; i < args.length; i++) {
                Result r = enumerate(ctx, Path.of(args[i]));
                String verdict = r.anyConsistent() ? "ALLOWED" : "FORBIDDEN";
                rows.add(r.test() + ',' + r.candidatesTried() + ','
                        + r.anyConsistent() + ',' + verdict);
                System.out.printf("%-12s candidates=%d any_consistent=%s -> %s%n",
                        r.test(), r.candidatesTried(), r.anyConsistent(), verdict);
            }
        }
        Files.writeString(outCsv, String.join("\n", rows) + "\n");
        System.out.println("\nCSV: " + outCsv.toAbsolutePath());
    }

    private static Result enumerate(SolverContext ctx, Path file) throws Exception {
        String content = Files.readString(file);
        LitmusCase lc = LitmusParser.parse(content, file.getFileName().toString());
        EventStructure es = lc.es();
        Map<String, Integer> memFinal = memoryFinalConstraints(lc.existsClause());

        // Per location: fixed co-minimal init (loader wires it first) + the permutable
        // program writes (everything after the first entry of the wired order).
        List<String> locs = new ArrayList<>(es.getCoherenceOrder().keySet());
        Map<String, Integer> initOf = new LinkedHashMap<>();
        Map<String, List<List<Integer>>> permsOf = new LinkedHashMap<>();
        int totalCandidates = 1;
        for (String loc : locs) {
            List<Integer> wired = new ArrayList<>(es.getCoherenceOrder().get(loc));
            initOf.put(loc, wired.get(0));
            List<Integer> programWrites = new ArrayList<>(wired.subList(1, wired.size()));
            List<List<Integer>> perms = new ArrayList<>();
            permute(programWrites, 0, perms);
            permsOf.put(loc, perms);
            totalCandidates *= perms.size();
        }

        BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
        boolean anyConsistent = false;
        int tried = 0;

        // Cartesian product over the per-location permutations.
        int[] idx = new int[locs.size()];
        do {
            // Install this co assignment into the (mutable) backing lists.
            boolean realises = true;
            for (int l = 0; l < locs.size(); l++) {
                String loc = locs.get(l);
                List<Integer> order = new ArrayList<>();
                order.add(initOf.get(loc));
                order.addAll(permsOf.get(loc).get(idx[l]));
                List<Integer> backing = es.getCoherenceOrder().get(loc);
                backing.clear();
                backing.addAll(order);
                // Realisation check: memory-final loc=val ⇒ co-max write of loc has value val.
                Integer req = memFinal.get(loc);
                if (req != null) {
                    Event last = es.getEventById(order.get(order.size() - 1));
                    if (!(last instanceof WriteEvent w) || w.getValue() != req) realises = false;
                }
            }
            tried++;

            boolean consistent = isSat(ctx, bmgr, es, lc);
            if (realises && consistent) anyConsistent = true;
            System.out.printf("    [%s] co=%s realises=%s consistent=%s%n",
                    lc.name(), es.getCoherenceOrder(), realises, consistent);
        } while (advance(idx, locs, permsOf));

        return new Result(lc.name(), totalCandidates, anyConsistent);
    }

    private static boolean isSat(SolverContext ctx, BooleanFormulaManager bmgr,
            EventStructure es, LitmusCase lc) throws Exception {
        EventStructureEncoder enc = new EventStructureEncoder(ctx, es, lc.deps());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula wf = enc.encodeWellFormedness();
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(wf, ax.consistencyWEAKEST()));
            return !p.isUnsat();
        }
    }

    /** Advance the mixed-radix index over per-location permutation counts; false at wrap. */
    private static boolean advance(int[] idx, List<String> locs,
            Map<String, List<List<Integer>>> permsOf) {
        for (int l = idx.length - 1; l >= 0; l--) {
            idx[l]++;
            if (idx[l] < permsOf.get(locs.get(l)).size()) return true;
            idx[l] = 0;
        }
        return false;
    }

    /** All permutations of {@code xs} (in place, collected into {@code out}). */
    private static void permute(List<Integer> xs, int k, List<List<Integer>> out) {
        if (k == xs.size()) { out.add(new ArrayList<>(xs)); return; }
        for (int i = k; i < xs.size(); i++) {
            java.util.Collections.swap(xs, k, i);
            permute(xs, k + 1, out);
            java.util.Collections.swap(xs, k, i);
        }
    }

    /**
     * Memory-final constraints {@code loc=val} in the {@code exists} clause. Register
     * constraints {@code T:r=v} (colon before the {@code =}) are excluded — those are
     * pinned through {@code rf} by the loader and are unaffected by co reordering.
     */
    private static Map<String, Integer> memoryFinalConstraints(String exists) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (exists == null) return out;
        String body = exists.replaceFirst("(?i)^\\s*~?exists\\s*", "").trim();
        if (body.startsWith("(") && body.endsWith(")")) body = body.substring(1, body.length() - 1);
        for (String conj : body.split("/\\\\|&&|/\\\\")) {
            String c = conj.trim();
            if (c.isEmpty() || c.contains(":")) continue;           // T:r=v ⇒ register
            Matcher m = Pattern.compile("^([A-Za-z_]\\w*)\\s*=\\s*(-?\\d+)$").matcher(c);
            if (m.matches()) out.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return out;
    }
}
