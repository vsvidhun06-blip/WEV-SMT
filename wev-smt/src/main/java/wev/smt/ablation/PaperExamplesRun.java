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
import wev.smt.DependencyInfo.DepKind;
import wev.smt.DependencyInfo.KindedEdge;
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
 * Source-paper named-example driver (evaluation-only). Sweeps a directory of
 * curated {@code .litmus} files — the Chakraborty &amp; Vafeiadis (POPL 2019,
 * "Weakestmo") and Jeffrey et&nbsp;al. (POPL 2022, "The Leaky Semicolon") named
 * LB/OOTA/coherence/control examples — and, for each file, records the WEAKEST
 * verdict under all three {@link SdepConfig} configurations together with a
 * per-kind (DATA/ADDR/CTRL) breakdown of how many syntactic dependency edges the
 * shipped detector recorded and how many of those it kept semantic.
 *
 * <p>Verdict machinery is bit-for-bit identical to {@link SdepDiscriminatingRun}
 * and {@link AblationRun}: the frozen encoder + {@code AxiomaticConsistency}, one
 * fresh {@link SolverContext} per solve under a per-solve shutdown timeout, same
 * bounds. This class touches no commit-41db4b2 file; the only knob is the
 * {@link DependencyInfo} re-flagging done by {@link SdepConfig#transform}.
 *
 * <p>Output CSV columns:
 * {@code file,dataDeps,addrDeps,ctrlDeps,semData,semAddr,semCtrl,
 * verdict_none,verdict_all-deps-semantic,verdict_current,note}.
 *
 * <p>Args (all optional, key=value): {@code dir=eval/examples/weakestmo},
 * {@code out=eval/paper-examples.csv}, {@code timeoutSec=30}, {@code maxEvents=64}.
 */
public final class PaperExamplesRun {

    private PaperExamplesRun() { }

    enum Verdict { ALLOWED, FORBIDDEN, TIMEOUT, ERROR }

    private record Opts(Path dir, Path out, int timeoutSec, int maxEvents) { }

    private record Row(String file,
                       int dataDeps, int addrDeps, int ctrlDeps,
                       int semData, int semAddr, int semCtrl,
                       Verdict none, Verdict all, Verdict current, String note) { }

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "paper-examples-timeout");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) throws Exception {
        Opts opt = parseOpts(args);

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(opt.dir())) {
            s.filter(p -> p.toString().endsWith(".litmus")).sorted().forEach(files::add);
        }
        System.out.printf(Locale.ROOT, "paper-examples: dir=%s files=%d out=%s%n",
                opt.dir(), files.size(), opt.out());

        StringBuilder sb = new StringBuilder(
                "file,dataDeps,addrDeps,ctrlDeps,semData,semAddr,semCtrl,"
                + "verdict_none,verdict_all-deps-semantic,verdict_current,note\n");

        List<Row> rows = new ArrayList<>();
        for (Path f : files) {
            Row row = solveFile(f, opt);
            rows.add(row);
            sb.append('"').append(row.file()).append('"').append(',')
              .append(row.dataDeps()).append(',')
              .append(row.addrDeps()).append(',')
              .append(row.ctrlDeps()).append(',')
              .append(row.semData()).append(',')
              .append(row.semAddr()).append(',')
              .append(row.semCtrl()).append(',')
              .append(row.none()).append(',')
              .append(row.all()).append(',')
              .append(row.current()).append(',')
              .append(row.note()).append('\n');
            System.out.printf(Locale.ROOT,
                    "  %-26s data=%d addr=%d ctrl=%d (sem %d/%d/%d)  none=%-9s all=%-9s current=%-9s %s%n",
                    row.file(), row.dataDeps(), row.addrDeps(), row.ctrlDeps(),
                    row.semData(), row.semAddr(), row.semCtrl(),
                    row.none(), row.all(), row.current(), row.note());
        }

        SCHED.shutdownNow();
        Files.createDirectories(opt.out().toAbsolutePath().getParent());
        Files.writeString(opt.out(), sb.toString());
        System.out.printf(Locale.ROOT, "%nWrote: %s%n", opt.out().toAbsolutePath());
    }

    private static Row solveFile(Path f, Opts opt) {
        String name = f.getFileName().toString();
        var lc = parseSafe(f);
        if (lc == null) {
            return new Row(name, 0, 0, 0, 0, 0, 0,
                    Verdict.ERROR, Verdict.ERROR, Verdict.ERROR, "parse-error");
        }
        EventStructure es = lc.es();
        DependencyInfo deps = lc.deps();
        if (es.getEvents().size() > opt.maxEvents()) {
            return new Row(name, 0, 0, 0, 0, 0, 0,
                    Verdict.ERROR, Verdict.ERROR, Verdict.ERROR,
                    "too-large:" + es.getEvents().size());
        }

        int data = 0, addr = 0, ctrl = 0, semData = 0, semAddr = 0, semCtrl = 0;
        for (KindedEdge ke : deps.allEdges()) {
            boolean sem = ke.edge().isSemantic();
            if (ke.kind() == DepKind.DATA) { data++; if (sem) semData++; }
            else if (ke.kind() == DepKind.ADDR) { addr++; if (sem) semAddr++; }
            else if (ke.kind() == DepKind.CTRL) { ctrl++; if (sem) semCtrl++; }
        }

        Verdict vNone = solve(es, SdepConfig.NONE.transform(deps), opt);
        Verdict vAll = solve(es, SdepConfig.ALL_DEPS_SEMANTIC.transform(deps), opt);
        Verdict vCur = solve(es, SdepConfig.CURRENT.transform(deps), opt);
        return new Row(name, data, addr, ctrl, semData, semAddr, semCtrl,
                vNone, vAll, vCur, "");
    }

    private static wev.smt.parse.LitmusCase parseSafe(Path f) {
        try {
            return LitmusParser.parse(Files.readString(f), f.getFileName().toString());
        } catch (Exception e) {
            return null;
        }
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

    private static Opts parseOpts(String[] args) {
        int timeoutSec = 30, maxEvents = 64;
        Path dir = Path.of("eval", "examples", "weakestmo");
        Path out = Path.of("eval", "paper-examples.csv");
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT);
            String v = kv[1].trim();
            switch (k) {
                case "timeoutsec" -> timeoutSec = Integer.parseInt(v);
                case "maxevents" -> maxEvents = Integer.parseInt(v);
                case "dir" -> dir = Path.of(v);
                case "out" -> out = Path.of(v);
                default -> System.err.println("[opt] ignoring unknown option: " + a);
            }
        }
        return new Opts(dir, out, timeoutSec, maxEvents);
    }
}
