package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.junit.jupiter.api.Test;

import wev.smt.DependencyInfo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the AArch64 {@code #}-immediate defect found by the
 * formal–implementation coherence check (2026-10-09).
 *
 * <p>Before the fix, {@code stripInlineComment} treated every {@code #} as the start of a
 * comment. AArch64 writes immediates as {@code #1}, so a row such as
 * {@code MOV W0,#2 | STG X0,[X1] ;} lost the rest of the physical line — the other threads'
 * cells and the {@code ;} terminator — and merged with the next row. Stores and the data
 * edges into them silently disappeared (e.g. {@code LDR; ADD W2,W0,#1; STR W2} parsed as a
 * lone read). These tests assert the parsed representation — events, operands, values,
 * composed value expressions and dependency edges — not merely that parsing succeeds.
 *
 * <p>The six reported missing-real-dependency cases are reproduced inline from the
 * (git-ignored) corpus files they come from, so the tests run without the corpus.
 */
class ArmImmediateParsingTest {

    private static LitmusCase parse(String src) {
        Event.resetCounter();
        return LitmusParser.parse(src, "t.litmus");
    }

    /** Program events of {@code thread}, in program order, as e.g. {@code "R:x"}, {@code "W:y=1"}. */
    private static List<String> events(LitmusCase lc, int thread) {
        List<String> out = new ArrayList<>();
        for (Event e : lc.es().getEvents()) {
            if (e.getThreadId() != thread) continue;
            if (e instanceof ReadEvent) out.add("R:" + e.getVariable());
            else if (e instanceof WriteEvent w) out.add("W:" + w.getVariable() + "=" + w.getValue());
            else out.add("F");
        }
        return out;
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

    /** DATA edges as {@code "R<id>->W<id>:sem|fake"}. */
    private static List<String> dataEdges(LitmusCase lc) {
        List<String> out = new ArrayList<>();
        for (DependencyInfo.KindedEdge k : lc.deps().allEdges()) {
            if (k.kind() != DependencyInfo.DepKind.DATA) continue;
            out.add("R" + k.edge().producer().getId() + "->W" + k.edge().consumer().getId()
                    + (k.edge().isSemantic() ? ":sem" : ":fake"));
        }
        return out;
    }

    private static String edge(ReadEvent r, WriteEvent w, boolean sem) {
        return "R" + r.getId() + "->W" + w.getId() + (sem ? ":sem" : ":fake");
    }

    private static String composed(LitmusCase lc, WriteEvent w) {
        return lc.writeValueExprs().get(w.getId()).replace(" ", "");
    }

    // ── the K13 reproduction: a dropped store ────────────────────────────────

    @Test
    void addImmediateNoLongerDropsTheFollowingStore() {
        LitmusCase lc = parse("""
                AArch64 K13
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 ADD W2,W0,#1 ;
                 STR W2,[X4] ;
                exists (0:X0=1)
                """);
        assertEquals(List.of("R:x", "W:y=1"), events(lc, 1),
                "the store after ADD #1 must survive; before the fix only the read did");
        ReadEvent r = read(lc, 1, "x");
        WriteEvent w = write(lc, 1, "y");
        assertEquals("((R" + r.getId() + ")+1)", composed(lc, w), "store value is R+1");
        assertEquals(List.of(edge(r, w, true)), dataEdges(lc), "R+1 is a real data dependency");
    }

    // ── ADD / SUB / hex / negative immediates ───────────────────────────────

    @Test
    void subImmediateLowersToSubtraction() {
        LitmusCase lc = parse("""
                AArch64 SUBI
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 SUB W2,W0,#3 ;
                 STR W2,[X4] ;
                exists (0:X0=1)
                """);
        ReadEvent r = read(lc, 1, "x");
        WriteEvent w = write(lc, 1, "y");
        assertEquals("((R" + r.getId() + ")-3)", composed(lc, w));
        assertEquals(List.of(edge(r, w, true)), dataEdges(lc));
    }

    @Test
    void hexAndNegativeImmediatesAreDecimalLiterals() {
        LitmusCase lc = parse("""
                AArch64 HEXI
                { 0:X1=x; 0:X4=y; 0:X5=z; }
                 P0 ;
                 LDR W0,[X1] ;
                 ADD W2,W0,#0x10 ;
                 STR W2,[X4] ;
                 ADD W3,W0,#-1 ;
                 MOV W6,#0x10 ;
                 STR W6,[X5] ;
                 STR W3,[X5] ;
                exists (0:X0=1)
                """);
        ReadEvent r = read(lc, 1, "x");
        WriteEvent wy = write(lc, 1, "y");
        assertEquals("((R" + r.getId() + ")+16)", composed(lc, wy), "#0x10 is the literal 16");
        // Folded wired-mode placeholder values (reads fold to 0): 0+16, the constant 16, 0+(-1).
        assertEquals(List.of("R:x", "W:y=16", "W:z=16", "W:z=-1"), events(lc, 1),
                "MOV W6,#0x10 stores the constant 16");
        WriteEvent wz2 = (WriteEvent) lc.es().getEvents().stream()
                .filter(e -> e instanceof WriteEvent && e.getThreadId() == 1 && e.getVariable().equals("z"))
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("((R" + r.getId() + ")+(-1))", composed(lc, wz2), "#-1 is the literal -1");
    }

    /** The AArch64 LB+datas idiom: EOR-self then ADD #1 is constant 1 — a FAKE edge. */
    @Test
    void eorSelfThenAddImmediateIsAFakeDependency() {
        LitmusCase lc = parse("""
                AArch64 LB+datas-shape
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 EOR W2,W0,W0 ;
                 ADD W2,W2,#1 ;
                 STR W2,[X4] ;
                exists (0:X0=1)
                """);
        ReadEvent r = read(lc, 1, "x");
        WriteEvent w = write(lc, 1, "y");
        assertEquals(List.of("R:x", "W:y=1"), events(lc, 1), "store of the constant 1");
        assertEquals("(((R" + r.getId() + ")^(R" + r.getId() + "))+1)", composed(lc, w));
        assertEquals(List.of(edge(r, w, false)), dataEdges(lc), "(r^r)+1 is constant: fake");
    }

    // ── multiple thread columns in one row ───────────────────────────────────

    @Test
    void immediateInOneColumnKeepsTheOtherColumnsAndTheRowTerminator() {
        LitmusCase lc = parse("""
                AArch64 2+2W-shape
                { 0:X1=x; 0:X3=y; 1:X1=y; 1:X3=x; }
                 P0          | P1          ;
                 MOV W0,#2   | MOV W2,#1   ;
                 STR W0,[X1] | STR W2,[X3] ;
                 DMB ST      | DMB ST      ;
                 MOV W2,#1   | MOV W0,#2   ;
                 STR W2,[X3] | STR W0,[X1] ;
                exists (x=2 /\\ y=2)
                """);
        assertEquals(List.of("W:x=2", "F", "W:y=1"), events(lc, 1));
        assertEquals(List.of("W:x=1", "F", "W:y=2"), events(lc, 2));
    }

    // ── genuine comments are still comments ──────────────────────────────────

    @Test
    void genuineCommentsAreStillStrippedOnArm() {
        LitmusCase lc = parse("""
                AArch64 COMMENTS
                # a whole-line hash comment
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                # another whole-line comment between rows
                 LDR W0,[X1] ;   // trailing slash comment
                 ADD W2,W0,#1 ; # trailing hash comment, not an immediate
                 STR W2,[X4] ;
                exists (0:X0=1)
                """);
        assertEquals(List.of("R:x", "W:y=1"), events(lc, 1));
        ReadEvent r = read(lc, 1, "x");
        WriteEvent w = write(lc, 1, "y");
        assertEquals("((R" + r.getId() + ")+1)", composed(lc, w));
    }

    /** herdtools7 doc/SB-labelled.litmus: on x86 a {@code #} after the row terminator is a comment. */
    @Test
    void x86HashAfterRowTerminatorRemainsAComment() {
        LitmusCase lc = parse("""
                X86 SB-labelled
                { x=0; y=0; }
                 P0          | P1          ;
                 MOV [y],$1  | MOV [x],$1  ; #(a)Wy1  | (c)Wx1
                 MOV EAX,[x] | MOV EAX,[y] ; #(b)Rx0  | (d)Ry0
                exists (0:EAX=0 /\\ 1:EAX=0)
                """);
        assertEquals(List.of("W:y=1", "R:x"), events(lc, 1));
        assertEquals(List.of("W:x=1", "R:y"), events(lc, 2));
    }

    @Test
    void ppcLoweringIsUnchanged() {
        LitmusCase lc = parse("""
                PPC LB+datas-P0
                { 0:r2=x; 0:r4=y; }
                 P0           ;
                 lwz r1,0(r2) ;
                 xor r3,r1,r1 ;
                 addi r3,r3,1 ;
                 stw r3,0(r4) ;
                exists (0:r1=1)
                """);
        ReadEvent r = read(lc, 1, "x");
        WriteEvent w = write(lc, 1, "y");
        assertEquals("(((R" + r.getId() + ")^(R" + r.getId() + "))+1)", composed(lc, w));
        assertEquals(List.of(edge(r, w, false)), dataEdges(lc));
    }

    // ── immediate forms the model cannot represent are rejected, not misread ──

    @Test
    void shiftedOperandIsRejectedRatherThanDropped() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                AArch64 SHIFT
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 EOR W2,W0,W0,LSL #2 ;
                 STR W2,[X4] ;
                exists (0:X0=1)
                """));
        assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex.kind(),
                "W0 ^ (W0<<2) is not W0^W0; lowering it as such would invent a fake edge");

        // herd also writes the shift amount without '#': ADD W2,W0,#1,LSL 12 is W0+4096.
        ParseException ex2 = assertThrows(ParseException.class, () -> parse("""
                AArch64 SHIFT12
                { 0:X1=x; 0:X4=y; }
                 P0 ;
                 LDR W0,[X1] ;
                 ADD W2,W0,#1, LSL 12 ;
                 STR W2,[X4] ;
                exists (0:X0=1)
                """));
        assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex2.kind());
    }

    @Test
    void nonZeroImmediateOffsetIsRejected() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                AArch64 OFFS
                { 0:X1=x; }
                 P0 ;
                 LDR W0,[X1,#4] ;
                exists (0:X0=1)
                """));
        assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex.kind(), "[X1,#4] addresses x+4, not x");
    }

    // ── the six reported missing-real-dependency cases ────────────────────────

    private static final String LB_RS_BODY = """
                AArch64 LB+rs
                Prefetch=0:x=F,0:y=W,1:y=F,1:x=W
                Com=Rf Rf
                {
                0:X1=x; 0:X3=y;
                1:X1=y; 1:X3=x;
                }
                 P0          | P1          ;
                 LDR W0,[X1] | LDR W0,[X1] ;
                 ADD W2,W0,#0| ADD W2,W0,#1;
                 MOV W0,#1   | STR W2,[X3] ;
                 STR W0,[X3] |             ;
                """;

    /**
     * Dat3M/litmus/AARCH64/HAND/LB+rs.litmus (verbatim). Since the register/outcome-binding
     * repair the full test is rejected explicitly: its outcome names {@code 0:X2} and
     * {@code 1:X2}, which hold values computed from loads ({@code R+0}, {@code R+1}); only a
     * loaded value or a constant can be bound. Before that repair those atoms were silently
     * dropped. The extraction itself (this test's original purpose) is asserted on the same
     * body with the bindable atoms only.
     */
    @Test
    void reported_LBrsFullOutcomeIsRejected() {
        ParseException ex = assertThrows(ParseException.class, () -> parse(
                LB_RS_BODY + "exists\n(0:X0=1 /\\ 0:X2=2 /\\ 1:X2=2 /\\ y=1 /\\ x=2)\n"));
        assertTrue(ex.getMessage().contains("computed value"), ex.getMessage());
    }

    @Test
    void reported_LBrs() {
        LitmusCase lc = parse(LB_RS_BODY + "exists\n(0:X0=1 /\\ y=1 /\\ x=2)\n");
        assertEquals(List.of("R:x", "W:y=1"), events(lc, 1));
        assertEquals(List.of("R:y", "W:x=1"), events(lc, 2));
        ReadEvent r1 = read(lc, 2, "y");
        WriteEvent w1 = write(lc, 2, "x");
        assertEquals("((R" + r1.getId() + ")+1)", composed(lc, w1));
        assertEquals(List.of(edge(r1, w1, true)), dataEdges(lc),
                "only P1 carries data (R+1, real); P0 stores the constant 1");
        assertEquals("1", composed(lc, write(lc, 1, "y")).replace("(", "").replace(")", ""));
    }

    /** herdtools7/catalogue/aarch64/tests/LB+rel+data-post.litmus (verbatim body). */
    @Test
    void reported_LBrelDataPost() {
        LitmusCase lc = parse("""
                AArch64 LB+rel+data-post
                {
                0:X0=x; 0:X3=y;
                1:X0=y; 1:X3=x;
                }
                 P0           | P1             ;
                 LDR W1,[X0]  | LDR W1,[X0]    ;
                 MOV W2,#1    | ORR W2,W1,#1   ;
                 STLR W2,[X3] | STR W2,[X3],#4 ;
                exists (0:X1=1 /\\ 1:X1=1)
                """);
        assertEquals(List.of("R:x", "W:y=1"), events(lc, 1));
        assertEquals(MemoryOrder.RELEASE, write(lc, 1, "y").getMemoryOrder());
        assertEquals(List.of("R:y", "W:x=1"), events(lc, 2),
                "post-indexed STR accesses x itself (the writeback happens afterwards)");
        ReadEvent r1 = read(lc, 2, "y");
        WriteEvent w1 = write(lc, 2, "x");
        assertEquals("((R" + r1.getId() + ")|1)", composed(lc, w1));
        assertEquals(List.of(edge(r1, w1, true)), dataEdges(lc));
    }

    /** herdtools7/catalogue/aarch64-pick/tests/T14-controldep.litmus: labels are unsupported — now explicitly. */
    @Test
    void reported_T14controldepIsExplicitlyUnsupported() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                AArch64 T14-controldep
                {
                0:X1=x; 0:X3=y;
                1:X1=y; 1:X11=x; 1:X5=za; 1:X8=zb;
                }
                P0           | P1               ;
                MOV W0,#1    | LDR W0,[X1]      ;
                STR W0,[X1]  | CMP W0,#1        ;
                MOV W2,#1    | B.NE L0          ;
                STLR W2,[X3] | L0:              ;
                             | STR W2,[X5]      ;
                             | LDAR W6,[X5]     ;
                             | LDR W7,[X8]      ;
                             | STR W7,[X8]      ;
                             | LDAR W9,[X8]     ;
                             | LDR W10,[X11]    ;
                exists (1:X0=1 /\\ 1:X10=0)
                """));
        assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex.kind());
        assertTrue(ex.getMessage().contains("L0:") || ex.getMessage().contains("B.NE"),
                "fails on the branch/label, not on an immediate: " + ex.getMessage());
    }

    /** herdtools7/litmus/tests/AArch64.neon/V09.litmus: post-index writeback then reuse of the base. */
    @Test
    void reported_V09IsExplicitlyUnsupported() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                AArch64 V09
                (* Tests load immediate, post-indexed *)
                {
                int16_t x=1;
                0:X0=x; 0:X1=y;
                }

                 P0                 ;
                 LDR B0, [X0], #1   ;
                 STR B0, [X0]       ;
                forall (x=257)
                """));
        assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex.kind());
        // Since the register/outcome-binding repair the sub-word initialiser (int16_t x) is
        // rejected first; the post-index reuse would be rejected next (see the untyped V11).
        assertTrue(ex.getMessage().contains("'x' whose initial value is not a modelled integer"),
                ex.getMessage());
    }

    /** Both V11 variants (herd/tests/instructions and litmus/tests): the second store targets x+1. */
    @Test
    void reported_V11IsExplicitlyUnsupported() {
        for (String init : List.of("uint8_t a=1; uint8_t x[2];", "a=1;")) {
            ParseException ex = assertThrows(ParseException.class, () -> parse("""
                    AArch64 V11
                    (* Tests store immediate, post-indexed *)
                    {
                    %s
                    0:X0=a;
                    0:X1=x;
                    }

                     P0                 ;
                     LDR B0, [X0]       ;
                     STR B0, [X1], #1   ;
                     STR B0, [X1]       ;
                     forall x=257
                    """.formatted(init)));
            assertEquals(ParseException.Kind.UNSUPPORTED_INSTRUCTION, ex.kind());
            // The typed variant now fails first on its sub-word initialisers (uint8_t);
            // the untyped one still reaches the post-index base reuse.
            assertTrue(ex.getMessage().contains(init.startsWith("uint8_t")
                    ? "'a' whose initial value is not a modelled integer" : "post-index"), ex.getMessage());
        }
    }

    @Test
    void zeroOffsetAndRegisterIndexStillParse() {
        LitmusCase lc = parse("""
                AArch64 IDX
                { 0:X1=x; 0:X5=y; }
                 P0 ;
                 LDR W0,[X1,#0] ;
                 EOR W4,W0,W0 ;
                 LDR W6,[X5,W4,SXTW] ;
                exists (0:X0=1)
                """);
        assertEquals(List.of("R:x", "R:y"), events(lc, 1));
        assertFalse(lc.deps().getAddrDeps(read(lc, 1, "y")).isEmpty(), "index register address dependency kept");
    }
}
