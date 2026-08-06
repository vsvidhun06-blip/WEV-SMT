package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.WriteEvent;
import org.junit.jupiter.api.Test;

import wev.smt.DependencyInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the control-guard parsing defect found by the front-end impact
 * audit (see {@code research/control-parser-impact.md}).
 *
 * <p>Before the fix, {@link LitmusParser.Lexer#lexC} matched {@code C_WRITE_ONCE} with
 * {@code .find()} — a substring search — before it ever reached the {@code if|while|for}
 * test, so a guarded store such as
 *
 * <pre>{@code   if (r0==1) WRITE_ONCE(*x,1)   }</pre>
 *
 * lexed as a bare store and the guard was <em>silently discarded</em>: no BRANCH
 * instruction, therefore no control edge, therefore a parsed representation identical to
 * the unguarded {@code WRITE_ONCE(*x,1)}.
 *
 * <p>These tests pin the four shapes A/B/C/D from the audit brief and assert they stay
 * mutually distinguishable. They deliberately assert on the <em>parsed representation</em>
 * (event counts and control edges), not on solver verdicts, so they fail loudly if the
 * front end ever collapses them again.
 */
class ControlGuardParsingTest {

    private static final String HDR = "C t\n{ }\n";
    private static final String P1 = "P1(int *x, int *y) { r1 = READ_ONCE(*x); "
            + "WRITE_ONCE(*y, 1); }\n";
    private static final String EXISTS = "exists (0:r0=1 /\\ 1:r1=1)\n";

    private static LitmusCase parse(String p0Body) {
        return LitmusParser.parse(
                HDR + "P0(int *x, int *y) { " + p0Body + " }\n" + P1 + EXISTS, "t.litmus");
    }

    private static int ctrlEdges(LitmusCase lc) {
        int n = 0;
        for (DependencyInfo.KindedEdge ke : lc.deps().allEdges()) {
            if (ke.kind() == DependencyInfo.DepKind.CTRL) n++;
        }
        return n;
    }

    /** Writes to {@code var} on the program threads (thread 0 holds the initialisers). */
    private static int writesTo(LitmusCase lc, String var) {
        int n = 0;
        for (Event e : lc.es().getEvents()) {
            if (e instanceof WriteEvent w && w.getThreadId() != 0
                    && w.getVariable().equals(var)) n++;
        }
        return n;
    }

    // ── A: plain unguarded store — no control at all ─────────────────────────

    @Test
    void caseA_plainStoreHasNoControlEdge() {
        LitmusCase a = parse("r0 = READ_ONCE(*y); WRITE_ONCE(*x,1);");
        assertEquals(0, ctrlEdges(a), "an unguarded store carries no control dependency");
        assertEquals(1, writesTo(a, "x"), "one write to x");
    }

    // ── B: guarded store — THE REGRESSION ────────────────────────────────────

    /**
     * The defect itself: this must not parse the same as case A. Before the fix both
     * produced zero control edges and byte-identical event structures.
     */
    @Test
    void caseB_guardedStoreRecordsControlEdge() {
        LitmusCase b = parse("r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1);");
        assertEquals(1, ctrlEdges(b),
                "a guarded store must record a control edge — the guard must not be dropped");
        assertEquals(1, writesTo(b, "x"), "one write to x");
    }

    @Test
    void caseA_andCaseB_areDistinguishable() {
        LitmusCase a = parse("r0 = READ_ONCE(*y); WRITE_ONCE(*x,1);");
        LitmusCase b = parse("r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1);");
        assertNotEquals(ctrlEdges(a), ctrlEdges(b),
                "unguarded and guarded stores must not parse identically");
    }

    // ── C / D: both arms present ─────────────────────────────────────────────

    @Test
    void caseC_bothArmsSameValueRecordsControlOnEachArm() {
        LitmusCase c = parse(
                "r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1); else WRITE_ONCE(*x,1);");
        assertEquals(2, ctrlEdges(c), "both arms are control-dependent on the guard");
        assertEquals(2, writesTo(c, "x"),
                "KNOWN LIMITATION: arms are straight-lined, so both writes are emitted");
    }

    @Test
    void caseD_bothArmsDifferentValues() {
        LitmusCase d = parse(
                "r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1); else WRITE_ONCE(*x,0);");
        assertEquals(2, ctrlEdges(d), "both arms are control-dependent on the guard");
        assertEquals(2, writesTo(d, "x"), "both arm writes are emitted");
    }

    @Test
    void caseC_andCaseD_areDistinguishable() {
        LitmusCase c = parse(
                "r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1); else WRITE_ONCE(*x,1);");
        LitmusCase d = parse(
                "r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1); else WRITE_ONCE(*x,0);");
        assertNotEquals(writeValues(c), writeValues(d),
                "same-value and differing-value arms must not parse identically");
    }

    private static String writeValues(LitmusCase lc) {
        StringBuilder b = new StringBuilder();
        for (Event e : lc.es().getEvents()) {
            if (e instanceof WriteEvent w && w.getThreadId() != 0
                    && w.getVariable().equals("x")) b.append(w.getValue()).append(',');
        }
        return b.toString();
    }

    /** All four shapes stay mutually distinct in the parsed representation. */
    @Test
    void allFourShapesRemainDistinguishable() {
        String a = shape("r0 = READ_ONCE(*y); WRITE_ONCE(*x,1);");
        String b = shape("r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1);");
        String c = shape("r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1); "
                + "else WRITE_ONCE(*x,1);");
        String d = shape("r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1); "
                + "else WRITE_ONCE(*x,0);");
        assertEquals(4, java.util.Set.of(a, b, c, d).size(),
                "A/B/C/D must map to four distinct parsed representations, got "
                        + java.util.List.of(a, b, c, d));
    }

    private static String shape(String body) {
        LitmusCase lc = parse(body);
        return "ctrl=" + ctrlEdges(lc) + " wx=" + writesTo(lc, "x")
                + " vals=" + writeValues(lc);
    }

    // ── Guard forms that must keep working ───────────────────────────────────

    /** The pre-existing empty-arm idiom is unchanged by the fix. */
    @Test
    void emptyArmIdiomStillRecordsControlEdge() {
        LitmusCase e = parse("r0 = READ_ONCE(*y); if (r0==1) ; WRITE_ONCE(*x,1);");
        assertEquals(1, ctrlEdges(e), "the 'if (c) ;' idiom must keep its control edge");
        assertEquals(1, writesTo(e, "x"));
    }

    /** Nested parentheses in the guard must not confuse the balanced-paren scan. */
    @Test
    void nestedParenGuardIsSplitCorrectly() {
        LitmusCase n = parse("r0 = READ_ONCE(*y); if ((r0 & 1) == 1) WRITE_ONCE(*x,1);");
        assertEquals(1, ctrlEdges(n), "balanced-paren guard must still be recognised");
        assertEquals(1, writesTo(n, "x"), "the guarded store must survive the split");
    }

    /**
     * The control edge must point at the read the guard mentions, not merely exist.
     */
    @Test
    void controlEdgeProducerIsTheGuardedRead() {
        LitmusCase b = parse("r0 = READ_ONCE(*y); if (r0==1) WRITE_ONCE(*x,1);");
        boolean found = false;
        for (DependencyInfo.KindedEdge ke : b.deps().allEdges()) {
            if (ke.kind() != DependencyInfo.DepKind.CTRL) continue;
            Event p = ke.edge().producer();
            Event c = ke.edge().consumer();
            if (p instanceof com.weakest.model.ReadEvent r
                    && r.getVariable().equals("y")
                    && c instanceof WriteEvent w && w.getVariable().equals("x")) {
                found = true;
            }
        }
        assertTrue(found, "ctrl edge must run from the read of y to the guarded write of x");
    }
}
