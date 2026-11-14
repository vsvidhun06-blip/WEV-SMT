package wev.smt.cli;

import com.weakest.model.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import wev.smt.AxiomaticConsistency;
import wev.smt.EventStructureEncoder;
import wev.smt.MemoryModel;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Symbolic six-model verdicts, computed exactly as {@link CorpusValidation} does in
 * manifest mode, for the inputs the well-formedness correction (F-01) concerns.
 *
 * <p>Inputs are read from the package, never copied: the system property
 * {@code wev.packageRoot} must name the artifact root (the directory holding
 * {@code corpus/} and {@code inputs/}). A missing property or file FAILS the test; it is
 * never skipped. Corpus files are checked against their frozen SHA-256 first.
 */
class F01CorpusRegressionTest {

    private SolverContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        Event.resetCounter();
        Configuration cfg = Configuration.defaultConfiguration();
        ctx = SolverContextFactory.createSolverContext(
                cfg, BasicLogManager.create(cfg), ShutdownNotifier.createDummy(), Solvers.Z3);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    private static Path root() {
        String p = System.getProperty("wev.packageRoot");
        assertTrue(p != null && !p.isBlank(), "set -Dwev.packageRoot=<artifact root>");
        return Path.of(p);
    }

    private static String read(Path rel, String sha256) throws Exception {
        Path f = root().resolve(rel);
        assertTrue(Files.isRegularFile(f), "missing input " + f);
        byte[] bytes = Files.readAllBytes(f);
        if (sha256 != null) {
            String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(sha256, got, "frozen hash of " + rel);
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static BooleanFormula consistency(AxiomaticConsistency ax, MemoryModel m) {
        return switch (m) {
            case SC -> ax.consistencySC();
            case TSO -> ax.consistencyTSO();
            case PSO -> ax.consistencyPSO();
            case RA -> ax.consistencyRA();
            case WEAKEST -> ax.consistencyWEAKEST();
            case RC11 -> ax.consistencyRC11();
        };
    }

    /** "SC=F TSO=F ..." for the symbolic fixed-outcome query of one litmus file. */
    private String symbolicVerdicts(String content, String name) throws Exception {
        LitmusCase lc = LitmusParser.parse(content, name);
        List<String> gaps = CorpusValidation.symbolicEncodingGaps(lc, ctx);
        assertTrue(gaps.isEmpty(), "symbolic encoding gaps for " + name + ": " + gaps);
        EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
        enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
        AxiomaticConsistency ax = new AxiomaticConsistency(enc);
        BooleanFormula wf = enc.encodeWellFormedness();
        StringBuilder sb = new StringBuilder();
        for (MemoryModel m : MemoryModel.values()) {
            try (ProverEnvironment p = ctx.newProverEnvironment()) {
                p.addConstraint(enc.getBmgr().and(wf, consistency(ax, m)));
                sb.append(m).append('=').append(p.isUnsat() ? "F" : "A").append(' ');
            }
        }
        return sb.toString().trim();
    }

    private void corpusFile(String rel, String sha256) throws Exception {
        String content = read(Path.of("corpus", "litmus").resolve(rel), sha256);
        // Submitted build: all six F. Corrected (agrees with the reference enumerator):
        assertEquals("SC=F TSO=F PSO=A RA=A WEAKEST=A RC11=A", symbolicVerdicts(content, rel));
    }

    // ── The three eligible files whose verdicts the correction changes ───────────

    @Test
    void ppc2plus2WRfiDatas() throws Exception {
        corpusFile("Dat3M/litmus/PPC/2+2W+rfi-datas.litmus",
                "dc65dd980b07bffaaa0193eb5ff83ecc27e04c533ee92bfda7bf153dcbbcee1e");
    }

    @Test
    void x86M55ii() throws Exception {
        corpusFile("Dat3M/litmus/X86/m55ii.litmus",
                "ed5f07274f701e511194121e7e4dd49f97a66026d756e18a4429c937b4706eac");
    }

    @Test
    void aarch64Mixed2plus2WPosw0q0() throws Exception {
        corpusFile("Dat3M/litmus/AARCH64/mixed/2+2W+posw0q0-poq0q0-posq0w0s009.litmus",
                "a98456fe8e0ec8ec202b3e5830aca767bf2a2c272cbf29e1a86ac98762795988");
    }

    // ── Controls the correction must not change ──────────────────────────────────

    @Test
    void lbRealAsPackagedIsValueUnreachableUnderEveryModel() throws Exception {
        // The packaged LB-real writes r+1, so r0=r1=1 needs a write of 1 that no thread
        // makes: FORBIDDEN by value consistency alone, in both builds (finding F-05).
        String c = read(Path.of("inputs", "examples", "paper", "LB-real.litmus"), null);
        assertEquals("SC=F TSO=F PSO=F RA=F WEAKEST=F RC11=F", symbolicVerdicts(c, "LB-real"));
    }

    @Test
    void lbCopyDiagnosticSeparatesRaFromWeakest() throws Exception {
        // Labelled diagnostic input (the figure's copy program), added 2026-10-18.
        String c = read(Path.of("inputs", "diagnostic-added-2026-10", "LB-copy.litmus"), null);
        assertEquals("SC=F TSO=F PSO=F RA=A WEAKEST=F RC11=F", symbolicVerdicts(c, "LB-copy"));
    }
}
