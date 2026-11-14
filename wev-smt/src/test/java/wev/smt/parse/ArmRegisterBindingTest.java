package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
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
import wev.smt.DependencyInfo;
import wev.smt.EventStructureEncoder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for AArch64 register identity and outcome binding.
 *
 * <p>Before the fix every dataflow map and the exists-clause matcher keyed registers by
 * their spelling, so {@code W0} and {@code X0} were different registers. herd writes
 * values with W names and outcomes with X names, so {@code LDR W0 … exists (0:X0=1)} left
 * the read unconstrained (wired from the initial write), and {@code 0:X3=1 … STR W3}
 * stored 0. Outcome registers were also bound to their <em>last load</em> regardless of
 * later overwrites, and every other outcome term was silently dropped.
 *
 * <p>Asserted here: generated outcome constraints ({@code existsReadValues}, rf wiring,
 * memory-final constraints), composed values, dependency edges, explicit rejections, and
 * WEAKEST / SC verdicts on the wired candidate of small tests whose answer is known
 * independently (the AArch64 load-buffering matched pair).
 */
class ArmRegisterBindingTest {

    private static LitmusCase parse(String src) {
        Event.resetCounter();
        return LitmusParser.parse(src, "t.litmus");
    }

    private static ReadEvent read(LitmusCase lc, int thread, String var) {
        for (Event e : lc.es().getEvents()) {
            if (e instanceof ReadEvent r && r.getThreadId() == thread && r.getVariable().equals(var)) return r;
        }
        throw new AssertionError("no read of " + var + " on T" + thread);
    }

    private static WriteEvent write(LitmusCase lc, int thread, String var) {
        for (Event e : lc.es().getEvents()) {
            if (e instanceof WriteEvent w && w.getThreadId() == thread && w.getVariable().equals(var)) return w;
        }
        throw new AssertionError("no write of " + var + " on T" + thread);
    }

    private static int wiredFromThread(LitmusCase lc, ReadEvent r) {
        return lc.es().getEventById(lc.es().getReadsFrom().get(r.getId())).getThreadId();
    }

    private static String composed(LitmusCase lc, WriteEvent w) {
        return lc.writeValueExprs().get(w.getId()).replace(" ", "");
    }

    private static ParseException rejected(String src) {
        ParseException ex = assertThrows(ParseException.class, () -> parse(src));
        assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex.kind());
        return ex;
    }

    /** Wired-candidate verdict: true = FORBIDDEN (UNSAT). */
    private static boolean forbidden(LitmusCase lc, DependencyInfo deps,
                                     Function<AxiomaticConsistency, BooleanFormula> model) throws Exception {
        var cfg = Configuration.defaultConfiguration();
        try (SolverContext c = SolverContextFactory.createSolverContext(
                cfg, BasicLogManager.create(cfg), ShutdownNotifier.createDummy(), Solvers.Z3)) {
            EventStructureEncoder enc = new EventStructureEncoder(c, lc.es(), deps);
            AxiomaticConsistency ax = new AxiomaticConsistency(enc);
            try (ProverEnvironment p = c.newProverEnvironment()) {
                p.addConstraint(enc.getBmgr().and(enc.encodeWellFormedness(), model.apply(ax)));
                return p.isUnsat();
            }
        }
    }

    private static DependencyInfo allSemantic(DependencyInfo d) {
        DependencyInfo o = new DependencyInfo();
        for (DependencyInfo.KindedEdge k : d.allEdges()) {
            switch (k.kind()) {
                case DATA -> o.addDataDep(k.edge().consumer(), k.edge().producer(), true);
                case ADDR -> o.addAddrDep(k.edge().consumer(), k.edge().producer(), true);
                case CTRL -> o.addCtrlDep(k.edge().consumer(), k.edge().producer(), true);
            }
        }
        return o;
    }

    // ── W load, X outcome; equivalent spellings ──────────────────────────────

    private static final String ONE_LOAD = """
            AArch64 W-load
            { 0:X1=x; }
             P0 ;
             LDR W0,[X1] ;
            exists (0:%s=1)
            """;

    @Test
    void wLoadIsBoundByAnXNamedOutcome() {
        LitmusCase lc = parse(ONE_LOAD.formatted("X0"));
        ReadEvent r = read(lc, 1, "x");
        assertEquals(Map.of(r.getId(), 1), lc.existsReadValues(),
                "0:X0=1 must pin the W0 load (before the fix: {} — the read was unconstrained)");
    }

    @Test
    void wAndXOutcomeSpellingsBindIdentically() {
        for (String spelling : List.of("X0", "W0", "x0", "w0")) {
            LitmusCase lc = parse(ONE_LOAD.formatted(spelling));
            assertEquals(Map.of(read(lc, 1, "x").getId(), 1), lc.existsReadValues(), spelling);
        }
    }

    // ── initial-state aliases ─────────────────────────────────────────────────

    @Test
    void xInitialisedRegisterIsReadThroughItsWName() {
        LitmusCase lc = parse("""
                AArch64 init-alias
                { 0:X3=1; 0:X4=y; }
                 P0 ;
                 STR W3,[X4] ;
                exists (y=1)
                """);
        WriteEvent w = write(lc, 1, "y");
        assertEquals(1, w.getValue(), "0:X3=1 then STR W3 stores 1 (before the fix: 0)");
        assertEquals("(1)", composed(lc, w));
    }

    @Test
    void contradictoryAliasInitialisersAreRejected() {
        // W3=-1 zero-extends to 0x00000000FFFFFFFF; X3=-1 is all ones: different registers values.
        rejected("""
                AArch64 contra
                { 0:X3=-1; 0:W3=-1; 0:X4=y; }
                 P0 ;
                 STR W3,[X4] ;
                exists (y=1)
                """);
        // Agreeing aliases are accepted.
        LitmusCase ok = parse("""
                AArch64 agree
                { 0:X3=1; 0:W3=1; 0:X4=y; }
                 P0 ;
                 STR W3,[X4] ;
                exists (y=1)
                """);
        assertEquals(1, write(ok, 1, "y").getValue());
    }

    @Test
    void typedInitialisersAreParsedNotIgnored() {
        // herdtools7 aarch64-VMSA/LDR.litmus shape: before the fix "int64_t x=1" was dropped (x read as 0).
        LitmusCase lc = parse("""
                AArch64 typed
                { 0:X1=x; int64_t 0:X2=0; int64_t x=1; }
                 P0 ;
                 LDR X2,[X1] ;
                exists (0:X2=1)
                """);
        ReadEvent r = read(lc, 1, "x");
        assertEquals(Map.of(r.getId(), 1), lc.existsReadValues());
        assertEquals(0, wiredFromThread(lc, r), "x=1 is the initial value: read wired from the init write");
    }

    // ── chains, overwrites ────────────────────────────────────────────────────

    @Test
    void copyAndArithmeticChainsAcrossAliases() {
        LitmusCase lc = parse("""
                AArch64 chain
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 MOV X2,X0 ;
                 ADD W3,W2,#1 ;
                 STR W3,[X4] ;
                exists (0:X2=1)
                """);
        ReadEvent r = read(lc, 1, "x");
        WriteEvent w = write(lc, 1, "y");
        assertEquals("(((R" + r.getId() + "))+1)", composed(lc, w), "W0 → X2 → W3 is one value chain");
        assertEquals(1, lc.deps().semanticEdges().size(), "one real data edge R→W through the aliases");
        assertEquals(Map.of(r.getId(), 1), lc.existsReadValues(), "X2 holds the loaded value: bound");
    }

    @Test
    void outcomeBindsTheFinalValueNotTheLastLoad() {
        // Reload: the second read is the final value.
        LitmusCase reload = parse("""
                AArch64 reload
                { 0:X1=x; 0:X2=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 LDR W0,[X2] ;
                exists (0:X0=1)
                """);
        assertEquals(Map.of(read(reload, 1, "y").getId(), 1), reload.existsReadValues());
        // Overwrite by a constant after the load: 0:X0=1 is statically false — rejected, not
        // silently bound to the stale load.
        ParseException ex = rejected("""
                AArch64 overwrite
                { 0:X1=x; }
                 P0 ;
                 LDR W0,[X1] ;
                 MOV W0,#5 ;
                exists (0:X0=1)
                """);
        assertTrue(ex.getMessage().contains("statically unsatisfiable"), ex.getMessage());
        // …and a satisfied constant outcome needs no constraint.
        LitmusCase sat = parse("""
                AArch64 overwrite-sat
                { 0:X1=x; }
                 P0 ;
                 LDR W0,[X1] ;
                 MOV W0,#5 ;
                exists (0:X0=5)
                """);
        assertEquals(Map.of(), sat.existsReadValues());
    }

    // ── width-sensitive cases are rejected ───────────────────────────────────

    @Test
    void sixtyFourBitStoreOfAPossiblyWideValueIsRejected() {
        ParseException ex = rejected("""
                AArch64 wide-store
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR X0,[X1] ;
                 ADD X2,X0,#1 ;
                 STR X2,[X4] ;
                exists (y=1)
                """);
        assertTrue(ex.getMessage().contains("64-bit store"), ex.getMessage());
        // The same value stored through its W view is exact (low 32 bits) and accepted.
        LitmusCase ok = parse("""
                AArch64 narrow-store
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR X0,[X1] ;
                 ADD X2,X0,#1 ;
                 STR W2,[X4] ;
                exists (y=1)
                """);
        assertEquals(1, ok.deps().semanticEdges().size());
    }

    @Test
    void xNamedOutcomeOnAWideValueIsRejected() {
        // True in 64-bit (X0 is all ones), but not representable: rejected as width-sensitive,
        // not mis-reported as unsatisfiable.
        ParseException wide = rejected("""
                AArch64 wide-outcome
                { }
                 P0 ;
                 MOV X0,#-1 ;
                exists (0:X0=-1)
                """);
        assertTrue(wide.getMessage().contains("upper 32 bits"), wide.getMessage());
        rejected("""
                AArch64 wide-init-outcome
                { 0:X3=-1; }
                 P0 ;
                 NOP ;
                exists (0:X3=-1)
                """);
        // The W view of the same register is a 32-bit value and is checkable.
        parse("""
                AArch64 w-view
                { 0:X3=-1; }
                 P0 ;
                 NOP ;
                exists (0:W3=-1)
                """);
        // An X register holding a zero-extended 32-bit value cannot equal a negative constant.
        ParseException ex = rejected(ONE_LOAD.formatted("X0").replace("=1)", "=-1)"));
        assertTrue(ex.getMessage().contains("statically unsatisfiable"), ex.getMessage());
    }

    @Test
    void sixtyFourBitLiteralsAreRejectedExplicitlyNotCrashed() {
        // A register holding 0x0000000100000001: its W view (1) is exact, its X view is not.
        parse("""
                AArch64 wide-reg-w
                { 0:X1=0x0000000100000001; }
                 P0 ;
                 NOP ;
                exists (0:W1=1)
                """);
        ParseException x = rejected("""
                AArch64 wide-reg-x
                { 0:X1=0x0000000100000001; }
                 P0 ;
                 NOP ;
                exists (0:X1=4294967297)
                """);
        assertTrue(x.getMessage().contains("upper 32 bits"), x.getMessage());
        // A location whose initial value is not a 32-bit value: rejected on use (before the
        // fix: NumberFormatException).
        rejected("""
                AArch64 wide-loc
                { uint64_t x=0x2222222222222222; 0:X1=x; }
                 P0 ;
                 LDR X0,[X1] ;
                exists (0:X0=0)
                """);
    }

    // ── zero registers, SP ────────────────────────────────────────────────────

    @Test
    void zeroRegistersReadZeroAndDiscardWrites() {
        LitmusCase lc = parse("""
                AArch64 zr
                { 0:X1=x; 0:X4=y; 0:X5=z; }
                 P0 ;
                 LDR XZR,[X1] ;
                 STR XZR,[X4] ;
                 LDR W0,[X1] ;
                 ORR W2,WZR,W0 ;
                 STR W2,[X5] ;
                exists (y=0)
                """);
        WriteEvent wy = write(lc, 1, "y");
        assertEquals(0, wy.getValue(), "LDR XZR discards the load: STR XZR still stores 0");
        assertEquals("0", composed(lc, wy));
        ReadEvent second = (ReadEvent) lc.es().getEvents().stream()
                .filter(e -> e instanceof ReadEvent && e.getThreadId() == 1).reduce((a, b) -> b).orElseThrow();
        assertEquals("(0|(R" + second.getId() + "))", composed(lc, write(lc, 1, "z")));
        assertEquals(1, lc.deps().semanticEdges().size(), "ORR with WZR keeps the real edge from W0 only");
    }

    @Test
    void stackPointerIsRejected() {
        rejected("""
                AArch64 sp
                { 0:X1=x; }
                 P0 ;
                 MOV X0,SP ;
                exists (0:X0=0)
                """);
    }

    // ── missing / unsupported outcome registers and terms ────────────────────

    @Test
    void unboundOrUnsupportedOutcomesAreRejectedNotDropped() {
        String base = """
                AArch64 bad-outcome
                { 0:X1=x; }
                 P0 ;
                 LDR W0,[X1] ;
                 ADD W2,W0,#1 ;
                exists (%s)
                """;
        for (String atom : List.of(
                "0:X9=0",                       // never initialised or written
                "2:X0=1",                       // no such thread
                "0:X1=1",                       // holds an address
                "0:X2=2",                       // computed function of a load
                "0:X0=1 \\/ 0:X0=2",            // disjunction
                "0:X0=1 /\\ ~fault(P0,x)",      // fault term / negation
                "0:X0=1 /\\ 0:X0=2")) {         // contradictory constraints on one load
            ParseException ex = rejected(base.formatted(atom));
            assertTrue(ex.getMessage().contains("outcome"), atom + ": " + ex.getMessage());
        }
    }

    @Test
    void locationsListIsIgnoredAndFilterIsRejected() {
        // herdtools7 aarch64-VMSA/LDRptex shape: "locations [...]" precedes the exists clause.
        LitmusCase lc = parse("""
                AArch64 locs
                { 0:X1=x; }
                 P0 ;
                 LDR W0,[X1] ;
                locations [0:X0;]
                exists (0:X0=1)
                """);
        assertEquals(Map.of(read(lc, 1, "x").getId(), 1), lc.existsReadValues());
        rejected("""
                AArch64 filt
                { 0:X1=x; }
                 P0 ;
                 LDR W0,[X1] ;
                filter (0:X0=0)
                exists (0:X0=1)
                """);
    }

    @Test
    void bracketedMemoryOutcomeIsBound() {
        LitmusCase lc = parse("""
                AArch64 mem
                { 0:X1=x; }
                 P0 ;
                 MOV W0,#2 ;
                 STR W0,[X1] ;
                exists ([x]=2)
                """);
        assertEquals(Map.of("x", 2), lc.existsMemFinal(), "[x]=2 was silently dropped before the fix");
    }

    @Test
    void unmodelledInitialValuesAreRejectedOnUse() {
        // pte_x=(oa:…) and 0:X5=(…) cannot be represented; using them must not read 0.
        rejected("""
                AArch64 pte
                { 0:X1=pte_x; pte_x=(oa:phy_x,af:0); }
                 P0 ;
                 LDR X0,[X1] ;
                exists (0:X0=0)
                """);
        rejected("""
                AArch64 pte-reg
                { 0:X1=x; 0:X5=(oa:phy_z,db:0); }
                 P0 ;
                 STR X5,[X1] ;
                exists (x=0)
                """);
    }

    // ── the reported AArch64 LB+datas family: matched pair, with verdicts ─────

    /** Dat3M/litmus/AARCH64/TUTO/LB+datas.litmus (verbatim body). */
    private static final String LB_DATAS = """
            AArch64 LB+datas
            {
            0:X1=x; 0:X3=y;
            1:X1=y; 1:X3=x;
            }
             P0           | P1           ;
             LDR W0,[X1]  | LDR W0,[X1]  ;
             EOR W2,W0,W0 | EOR W2,W0,W0 ;
             ADD W2,W2,#1 | ADD W2,W2,#1 ;
             STR W2,[X3]  | STR W2,[X3]  ;
            exists
            (0:X0=1 /\\ 1:X0=1)
            """;

    @Test
    void aarch64LbDatasBindsTheLoadBufferingOutcome() throws Exception {
        LitmusCase lc = parse(LB_DATAS);
        ReadEvent r0 = read(lc, 1, "x");
        ReadEvent r1 = read(lc, 2, "y");
        assertEquals(Map.of(r0.getId(), 1, r1.getId(), 1), lc.existsReadValues());
        assertEquals(2, wiredFromThread(lc, r0), "P0 reads x=1 from P1's store (the LB cycle)");
        assertEquals(1, wiredFromThread(lc, r1));
        assertEquals(0, lc.deps().semanticEdges().size(), "both (r^r)+1 data edges are fake");
        assertTrue(forbidden(lc, lc.deps(), AxiomaticConsistency::consistencySC), "SC forbids LB");
        assertEquals(false, forbidden(lc, lc.deps(), AxiomaticConsistency::consistencyWEAKEST),
                "WEAKEST allows grounded LB (fake data dependencies)");
        assertTrue(forbidden(lc, allSemantic(lc.deps()), AxiomaticConsistency::consistencyWEAKEST),
                "treating the fake edges as semantic forbids it: the test discriminates");
    }

    @Test
    void aarch64LbWithRealDataIsForbiddenUnderWeakest() throws Exception {
        LitmusCase lc = parse(LB_DATAS.replace("EOR W2,W0,W0 | EOR W2,W0,W0 ;\n", "")
                .replace("ADD W2,W2,#1 | ADD W2,W2,#1", "ADD W2,W0,#1 | ADD W2,W0,#1"));
        assertEquals(2, lc.existsReadValues().size());
        assertEquals(2, lc.deps().semanticEdges().size(), "R+1 data edges are real");
        assertTrue(forbidden(lc, lc.deps(), AxiomaticConsistency::consistencyWEAKEST),
                "real data dependencies close the thin-air cycle");
    }

    // ── non-AArch64 behaviour unchanged ───────────────────────────────────────

    @Test
    void ppcOutcomeBindingIsUnchanged() {
        LitmusCase lc = parse("""
                PPC LB+datas
                {
                0:r2=x; 0:r4=y;
                1:r2=y; 1:r4=x;
                }
                 P0           | P1           ;
                 lwz r1,0(r2) | lwz r1,0(r2) ;
                 xor r3,r1,r1 | xor r3,r1,r1 ;
                 addi r3,r3,1 | addi r3,r3,1 ;
                 stw r3,0(r4) | stw r3,0(r4) ;
                ~exists
                (0:r1=1 /\\ 1:r1=1)
                """);
        assertEquals(Map.of(read(lc, 1, "x").getId(), 1, read(lc, 2, "y").getId(), 1), lc.existsReadValues());
        assertEquals("r1", read(lc, 1, "x").getLocalVar(), "PPC register names are not rewritten");
    }

    @Test
    void cOutcomeBindingIsUnchanged() {
        LitmusCase lc = parse("""
                C t
                { }
                P0(int *x) { r0 = READ_ONCE(*x); }
                exists (0:r0=1)
                """);
        assertEquals(Map.of(read(lc, 1, "x").getId(), 1), lc.existsReadValues());
    }
}
