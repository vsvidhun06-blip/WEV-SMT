package wev.smt.ablation;

import com.weakest.model.EventStructure;
import org.sosy_lab.common.ShutdownManager;
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
import wev.smt.EventStructureEncoder;
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * §6 sdep <em>discriminating-tests</em> finder (evidence that the dependency
 * detector fires on independently-authored corpus programs, not just curated
 * examples).
 *
 * <p>Distinct from {@link AblationRun}, which reports aggregate cell counts on a
 * fixed seeded slice. This driver instead sweeps the <em>entire</em> validated
 * §6.2 WEAKEST-OK corpus ({@code eval/corpus-weakest-manifest.csv}, 2 998 files),
 * keeps only files that carry a genuine <b>data or address dependency</b> — a
 * store whose value/address is a function of a loaded register, i.e. the parser
 * recorded a non-empty {@code data}/{@code addr} relation (a constant-valued store
 * carries none) — and, for each such file, records the WEAKEST verdict under all
 * three {@link SdepConfig} configurations. It then flags the files where the three
 * verdicts are <em>not</em> all equal: the tests on which the sdep classification
 * mechanism actually changes the outcome.
 *
 * <p>Verdict machinery is identical to {@link AblationRun}: frozen encoder +
 * {@code AxiomaticConsistency}, one fresh {@link SolverContext} per solve under a
 * per-solve shutdown timeout, same bounds ({@code perFileSec=30}, {@code maxEvents=64}).
 *
 * <p>Output: {@code sdep-discriminating-tests.csv} (one row per discriminating file:
 * {@code file,verdict_none,verdict_all-deps-semantic,verdict_current,dataAddrDeps,semanticEdges})
 * and, alongside it, {@code sdep-discriminating-all-depcarrying.csv} recording every
 * dependency-carrying file swept (discriminating or not) for auditability.
 *
 * <p>Args (all optional, key=value): {@code manifest=…}, {@code corpusRoot=…},
 * {@code out=.}, {@code timeoutSec=30}, {@code maxEvents=64}, {@code limit=-1}
 * (cap on #dependency-carrying files solved, for smoke runs; -1 = all).
 */
public final class SdepDiscriminatingRun {

    private SdepDiscriminatingRun() { }

    enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT, ERROR }

    private record Opts(Path manifest, Path corpusRoot, Path out,
                        int timeoutSec, int maxEvents, int limit) { }

    private record ParsedFile(String rel, EventStructure es, DependencyInfo deps,
                              int dataAddrDeps, String skip) { }

    /** All-three-config outcome for one dependency-carrying file. */
    private record Row(String rel, int dataAddrDeps, int semanticEdges,
                       Verdict none, Verdict all, Verdict current) {
        boolean discriminates() {
            return !(none == all && all == current);
        }
    }

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sdep-disc-timeout");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) throws Exception {
        Opts opt = parseOpts(args);
        Files.createDirectories(opt.out());

        System.out.printf(Locale.ROOT,
                "§6 sdep discriminating-tests sweep: manifest=%s timeoutSec=%d maxEvents=%d limit=%d%n",
                opt.manifest(), opt.timeoutSec(), opt.maxEvents(), opt.limit());

        // 1. Read manifest, parse each, keep only data/addr-dependency-carrying files.
        List<String> rels = new ArrayList<>();
        for (String line : Files.readAllLines(opt.manifest())) {
            if (line.isBlank()) continue;
            rels.add(line.split(",", 2)[0]);
        }
        System.out.printf(Locale.ROOT, "manifest: %d files%n", rels.size());

        List<ParsedFile> depCarrying = new ArrayList<>();
        int parsed = 0, skipped = 0, noDep = 0;
        for (String rel : rels) {
            ParsedFile pf = parseCorpusFile(opt.corpusRoot(), rel, opt.maxEvents());
            if (pf.skip() != null) { skipped++; continue; }
            parsed++;
            if (pf.dataAddrDeps() == 0) { noDep++; continue; }
            depCarrying.add(pf);
            if (opt.limit() >= 0 && depCarrying.size() >= opt.limit()) break;
        }
        System.out.printf(Locale.ROOT,
                "parsed=%d skipped=%d no-data/addr-dep=%d  ->  data/addr-dep-carrying=%d%n",
                parsed, skipped, noDep, depCarrying.size());

        // 2. Solve each dep-carrying file under all three configs.
        List<Row> rows = new ArrayList<>();
        int i = 0;
        for (ParsedFile pf : depCarrying) {
            i++;
            Verdict vNone = solve(pf.es(), SdepConfig.NONE.transform(pf.deps()), opt);
            Verdict vAll  = solve(pf.es(), SdepConfig.ALL_DEPS_SEMANTIC.transform(pf.deps()), opt);
            Verdict vCur  = solve(pf.es(), SdepConfig.CURRENT.transform(pf.deps()), opt);
            int sem = pf.deps().semanticEdges().size();
            Row row = new Row(pf.rel(), pf.dataAddrDeps(), sem, vNone, vAll, vCur);
            rows.add(row);
            if (row.discriminates()) {
                System.out.printf(Locale.ROOT, "  [%d/%d] DISCRIMINATES  %s  none=%s all=%s current=%s%n",
                        i, depCarrying.size(), pf.rel(), vNone, vAll, vCur);
            } else if (i % 25 == 0) {
                System.out.printf(Locale.ROOT, "  [%d/%d] ... %s%n", i, depCarrying.size(), pf.rel());
            }
        }

        SCHED.shutdownNow();

        // 3. Write outputs.
        List<Row> disc = rows.stream().filter(Row::discriminates).toList();
        writeCsv(opt.out().resolve("sdep-discriminating-tests.csv"), disc);
        writeCsv(opt.out().resolve("sdep-discriminating-all-depcarrying.csv"), rows);

        System.out.printf(Locale.ROOT,
                "%n=== SUMMARY ===%ndata/addr-dep-carrying files solved: %d%n"
                + "discriminating (verdict differs across none/all-deps-semantic/current): %d%n",
                rows.size(), disc.size());
        for (Row r : disc) {
            System.out.printf(Locale.ROOT, "  %s  none=%s  all-deps-semantic=%s  current=%s%n",
                    r.rel(), r.none(), r.all(), r.current());
        }
        System.out.printf(Locale.ROOT, "%nWrote: %s%n",
                opt.out().resolve("sdep-discriminating-tests.csv").toAbsolutePath());
    }

    /** WEAKEST verdict for one (es, deps) in a fresh, timeout-bounded context. */
    private static Verdict solve(EventStructure es, DependencyInfo deps, Opts opt) {
        ShutdownManager sm = ShutdownManager.create();
        ScheduledFuture<?> to = SCHED.schedule(
                () -> sm.requestShutdown("per-solve timeout " + opt.timeoutSec() + "s"),
                opt.timeoutSec(), TimeUnit.SECONDS);
        try {
            Configuration config = Configuration.defaultConfiguration();
            LogManager log = BasicLogManager.create(config);
            try (SolverContext ctx = SolverContextFactory.createSolverContext(
                    config, log, sm.getNotifier(), Solvers.Z3)) {
                BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
                EventStructureEncoder enc = new EventStructureEncoder(ctx, es, deps);
                AxiomaticConsistency ax = new AxiomaticConsistency(enc);
                BooleanFormula wf = enc.encodeWellFormedness();
                BooleanFormula cons = ax.consistencyWEAKEST();
                boolean unsat;
                try (ProverEnvironment p = ctx.newProverEnvironment()) {
                    p.addConstraint(bmgr.and(wf, cons));
                    unsat = p.isUnsat();
                }
                return unsat ? Verdict.FORBIDDEN : Verdict.ALLOWED;
            }
        } catch (InterruptedException ie) {
            return Verdict.TIMEOUT;
        } catch (Throwable ex) {
            boolean interrupted = ex instanceof InterruptedException
                    || (ex.getCause() instanceof InterruptedException);
            return interrupted ? Verdict.TIMEOUT : Verdict.ERROR;
        } finally {
            to.cancel(false);
        }
    }

    private static ParsedFile parseCorpusFile(Path root, String rel, int maxEvents) {
        Path f = root.resolve(rel);
        try {
            var lc = LitmusParser.parse(Files.readString(f), rel);
            if (lc.existsClause() == null || lc.existsClause().isBlank()) {
                return new ParsedFile(rel, null, null, 0, "no-exists-clause");
            }
            if (lc.es().getEvents().size() > maxEvents) {
                return new ParsedFile(rel, null, null, 0, "too-large");
            }
            int dataAddr = 0;
            for (var e : lc.es().getEvents()) {
                dataAddr += lc.deps().getDataDeps(e).size() + lc.deps().getAddrDeps(e).size();
            }
            return new ParsedFile(rel, lc.es(), lc.deps(), dataAddr, null);
        } catch (Exception e) {
            return new ParsedFile(rel, null, null, 0, "parse-error");
        }
    }

    private static void writeCsv(Path path, List<Row> rows) throws Exception {
        StringBuilder sb = new StringBuilder(
                "file,verdict_none,verdict_all-deps-semantic,verdict_current,dataAddrDeps,semanticEdges\n");
        for (Row r : rows) {
            sb.append('"').append(r.rel()).append('"').append(',')
              .append(r.none()).append(',')
              .append(r.all()).append(',')
              .append(r.current()).append(',')
              .append(r.dataAddrDeps()).append(',')
              .append(r.semanticEdges()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static Opts parseOpts(String[] args) {
        int timeoutSec = 30, maxEvents = 64, limit = -1;
        Path manifest = Path.of("eval", "corpus-weakest-manifest.csv");
        Path corpusRoot = Path.of("eval", "corpus");
        Path out = Path.of(".");
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "timeoutsec" -> timeoutSec = Integer.parseInt(v);
                case "maxevents" -> maxEvents = Integer.parseInt(v);
                case "limit" -> limit = Integer.parseInt(v);
                case "manifest" -> manifest = Path.of(v);
                case "corpusroot" -> corpusRoot = Path.of(v);
                case "out" -> out = Path.of(v);
                default -> System.err.println("[opt] ignoring unknown option: " + a);
            }
        }
        return new Opts(manifest, corpusRoot, out, timeoutSec, maxEvents, limit);
    }
}
