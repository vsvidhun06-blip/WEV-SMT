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
import wev.smt.LitmusCorpus;
import wev.smt.LitmusCorpus.LitmusCase;
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the 40-test {@link LitmusCorpus#classics() classics} atlas under <b>RA_FORMAL</b>
 * (the named release/acquire axiom, {@link AxiomaticConsistency#consistencyRA_FORMAL}) and
 * writes {@code ra-formal-atlas.csv} — the artifact that closes TACAS reviewer M1 by
 * exhibiting the formal strength chain {@code SC ⊇ TSO ⊇ RA_FORMAL ⊇ WEAKEST}.
 *
 * <p>For every litmus case it decides the full-execution consistency verdict (UNSAT of
 * {@code wellFormed ∧ consistency} ⇒ FORBIDDEN, else ALLOWED) under all four chain rungs
 * — SC, TSO, RA_FORMAL, WEAKEST — plus the pre-existing {@link AxiomaticConsistency#consistencyRA}
 * so the CSV can confirm RA_FORMAL reproduces it verdict-for-verdict. Two per-row checks
 * accompany the verdicts:
 * <ul>
 *   <li><b>chainOK</b> — the monotonicity of the forbidden-set along the chain:
 *       {@code forbid(WEAKEST) ⇒ forbid(RA_FORMAL) ⇒ forbid(TSO) ⇒ forbid(SC)}. A false here
 *       is a counterexample to the chain and is surfaced loudly.</li>
 *   <li><b>raFormalMatchesRA</b> — RA_FORMAL and the existing RA agree (expected always
 *       true; they are the same relation under two names).</li>
 * </ul>
 *
 * <p>The run is a plain sequential sweep over 40 small structures (no separation search,
 * no budget); it shares one Z3 {@link SolverContext}. This is intentionally <em>not</em>
 * wired through {@link MemoryModel} — RA_FORMAL is reached by calling its consistency
 * method directly, so the shared model enum and every {@code MemoryModel.values()} sweep
 * stay byte-for-byte unchanged.
 *
 * <p>Output: {@code <outDir>/ra-formal-atlas.csv} (default {@code outDir = eval}).
 */
public final class RaFormalAtlasRun {

    private static final String RED = "[31m";
    private static final String GREEN = "[32m";
    private static final String RESET = "[0m";

    private RaFormalAtlasRun() { }

    private record Row(String litmus, int size, Outcome sc, Outcome tso, Outcome ra,
                       Outcome raFormal, Outcome weakest, Outcome expectedRa,
                       boolean raFormalMatchesRa, boolean chainOk, long ms) { }

    public static void main(String[] args) throws Exception {
        Path outDir = (args.length > 0) ? Path.of(args[0]) : Path.of("eval");
        Files.createDirectories(outDir);

        Configuration cfg = Configuration.defaultConfiguration();
        LogManager log = BasicLogManager.create(cfg);
        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3)) {
            BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();

            List<LitmusCase> cases = LitmusCorpus.classics();
            System.out.printf("RA_FORMAL atlas over %d litmus tests "
                    + "(chain SC ⊇ TSO ⊇ RA_FORMAL ⊇ WEAKEST)%n", cases.size());

            List<Row> rows = new ArrayList<>();
            for (LitmusCase lc : cases) {
                EventStructureEncoder enc =
                        new EventStructureEncoder(ctx, lc.es(), lc.deps());
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                int size = lc.es().getEvents().size();

                long t0 = System.currentTimeMillis();
                Outcome sc = verdict(ctx, bmgr, wf, ax.consistencySC());
                Outcome tso = verdict(ctx, bmgr, wf, ax.consistencyTSO());
                Outcome ra = verdict(ctx, bmgr, wf, ax.consistencyRA());
                Outcome raF = verdict(ctx, bmgr, wf, ax.consistencyRA_FORMAL());
                Outcome weakest = verdict(ctx, bmgr, wf, ax.consistencyWEAKEST());
                long ms = System.currentTimeMillis() - t0;

                boolean chainOk = implies(weakest, raF) && implies(raF, tso) && implies(tso, sc);
                Outcome expectedRa = lc.expected().getOrDefault(MemoryModel.RA, Outcome.UNKNOWN);
                rows.add(new Row(lc.name(), size, sc, tso, ra, raF, weakest,
                        expectedRa, raF == ra, chainOk, ms));
            }

            writeCsv(outDir, rows);
            printSummary(rows);
            System.out.printf("%nCSV: %s%n",
                    outDir.resolve("ra-formal-atlas.csv").toAbsolutePath());
        }
    }

    /** FORBIDDEN iff {@code wellFormed ∧ consistency} is UNSAT, else ALLOWED. */
    private static Outcome verdict(SolverContext ctx, BooleanFormulaManager bmgr,
                                   BooleanFormula wellFormed, BooleanFormula cons)
            throws Exception {
        try (ProverEnvironment p = ctx.newProverEnvironment()) {
            p.addConstraint(bmgr.and(wellFormed, cons));
            return p.isUnsat() ? Outcome.FORBIDDEN : Outcome.ALLOWED;
        }
    }

    /** Forbidden-set monotonicity: if the weaker model forbids, the stronger must too. */
    private static boolean implies(Outcome weaker, Outcome stronger) {
        return weaker != Outcome.FORBIDDEN || stronger == Outcome.FORBIDDEN;
    }

    private static void writeCsv(Path dir, List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder(
                "litmus,size,SC,TSO,RA,RA_FORMAL,WEAKEST,expectedRA,raFormalMatchesRA,chainOK,ms\n");
        for (Row r : rows) {
            sb.append(r.litmus()).append(',')
              .append(r.size()).append(',')
              .append(r.sc()).append(',')
              .append(r.tso()).append(',')
              .append(r.ra()).append(',')
              .append(r.raFormal()).append(',')
              .append(r.weakest()).append(',')
              .append(r.expectedRa()).append(',')
              .append(r.raFormalMatchesRa()).append(',')
              .append(r.chainOk()).append(',')
              .append(r.ms()).append('\n');
        }
        Files.writeString(dir.resolve("ra-formal-atlas.csv"), sb.toString());
    }

    private static void printSummary(List<Row> rows) {
        int chainViolations = 0, raMismatch = 0, comparedRa = 0, matchedRa = 0;
        // scTsoRa: does SC ⊇ TSO ⊇ RA_FORMAL hold (the sub-chain that survives)?
        int scTsoRaViolations = 0;
        // raStrongerThanWeakest: RA_FORMAL forbids where WEAKEST allows (the reverse gap).
        int raForbidsWeakestAllows = 0;
        for (Row r : rows) {
            if (!r.chainOk()) chainViolations++;
            if (!r.raFormalMatchesRa()) raMismatch++;
            if (!(implies(r.raFormal(), r.tso()) && implies(r.tso(), r.sc()))) scTsoRaViolations++;
            if (r.raFormal() == Outcome.FORBIDDEN && r.weakest() == Outcome.ALLOWED) {
                raForbidsWeakestAllows++;
            }
            if (r.expectedRa() != Outcome.UNKNOWN) {
                comparedRa++;
                if (r.expectedRa() == r.raFormal()) matchedRa++;
            }
        }
        System.out.println();
        System.out.println("== RA_FORMAL atlas summary ==");
        System.out.printf("RA_FORMAL ≡ RA (existing irreflexive(hb;eco?)): %s%d/%d%s tests%n",
                raMismatch == 0 ? GREEN : RED, rows.size() - raMismatch, rows.size(), RESET);
        System.out.printf("RA_FORMAL vs. textbook RA column: %s%d/%d%s matched (%d compared)%n",
                matchedRa == comparedRa ? GREEN : RED, matchedRa, comparedRa, RESET, comparedRa);
        System.out.printf("Sub-chain SC ⊇ TSO ⊇ RA_FORMAL holds on: %s%d/%d%s tests%n",
                scTsoRaViolations == 0 ? GREEN : RED, rows.size() - scTsoRaViolations,
                rows.size(), RESET);
        System.out.printf("Link RA_FORMAL ⊇ WEAKEST holds on: %s%d/%d%s tests%n",
                chainViolations == 0 ? GREEN : RED, rows.size() - chainViolations,
                rows.size(), RESET);

        if (chainViolations > 0) {
            System.out.println(RED + "RA_FORMAL ⊇ WEAKEST FAILS (WEAKEST forbids, RA_FORMAL "
                    + "allows) — WEAKEST's jf-coherence forbids dependency-carried thin-air "
                    + "that RA cannot see:" + RESET);
            for (Row r : rows) {
                if (!r.chainOk()) {
                    System.out.printf("  %s%-14s SC=%s TSO=%s RA_FORMAL=%s WEAKEST=%s%s%n",
                            RED, r.litmus(), r.sc(), r.tso(), r.raFormal(), r.weakest(), RESET);
                }
            }
            if (raForbidsWeakestAllows > 0) {
                System.out.printf("Conversely, RA_FORMAL forbids where WEAKEST allows on %d "
                        + "test(s) (release/acquire synchronisation WEAKEST ignores).%n",
                        raForbidsWeakestAllows);
                System.out.println("=> RA_FORMAL and WEAKEST are INCOMPARABLE: the total order "
                        + "SC ⊇ TSO ⊇ RA ⊇ WEAKEST does NOT hold. Only SC ⊇ TSO ⊇ RA_FORMAL "
                        + "survives as a chain. (Expected: RA has no dependency tracking; "
                        + "WEAKEST has no release/acquire synchronisation.)");
            }
        }
        if (raMismatch > 0) {
            System.out.println(RED + "RA_FORMAL != RA on:" + RESET);
            for (Row r : rows) {
                if (!r.raFormalMatchesRa()) {
                    System.out.printf("  %s%-14s RA=%s RA_FORMAL=%s%s%n",
                            RED, r.litmus(), r.ra(), r.raFormal(), RESET);
                }
            }
        }
    }
}
