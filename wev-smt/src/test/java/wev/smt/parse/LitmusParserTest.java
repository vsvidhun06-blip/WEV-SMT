package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.EventType;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;
import org.junit.jupiter.api.Test;

import wev.smt.DependencyInfo;
import wev.smt.LitmusCorpus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link LitmusParser}. The structural assertions confirm that
 * parsing the canonical litmus tests — in both the x86 and C dialects — reproduces the
 * hand-crafted {@link LitmusCorpus#classics()} event structures shape-for-shape (event
 * composition, program-order / reads-from / coherence cardinalities, location count).
 * The dependency assertions confirm the {@code isSemantic} heuristic, and the malformed
 * case confirms a precise, line-tagged {@link ParseException} rather than a stack trace.
 *
 * <p>These are pure parser/data tests: no SMT solver is involved, so the suite is fast
 * and deterministic. They run alongside the existing 16 tests.
 */
class LitmusParserTest {

    // ── X86 dialect: structural match against the hand-crafted classics ──────────

    @Test
    void sbX86MatchesHandcrafted() {
        assertParsedMatches("""
                X86 SB
                { }
                 P0          | P1          ;
                 MOV [x],$1  | MOV [y],$1  ;
                 MOV EAX,[y] | MOV EAX,[x] ;
                exists (0:EAX=0 /\\ 1:EAX=0)
                """, "SB", "SB/x86");
    }

    @Test
    void mpX86MatchesHandcrafted() {
        assertParsedMatches("""
                X86 MP
                { }
                 P0          | P1          ;
                 MOV [d],$1  | MOV EAX,[f] ;
                 MOV [f],$1  | MOV EBX,[d] ;
                exists (1:EAX=1 /\\ 1:EBX=0)
                """, "MP", "MP/x86");
    }

    @Test
    void lbX86MatchesHandcrafted() {
        assertParsedMatches("""
                X86 LB
                { }
                 P0          | P1          ;
                 MOV EAX,[x] | MOV EBX,[y] ;
                 MOV [y],$1  | MOV [x],$1  ;
                exists (0:EAX=1 /\\ 1:EBX=1)
                """, "LB", "LB/x86");
    }

    @Test
    void iriwX86MatchesHandcrafted() {
        assertParsedMatches("""
                X86 IRIW
                { }
                 P0         | P1         | P2          | P3          ;
                 MOV [x],$1 | MOV [y],$1 | MOV EAX,[x] | MOV ECX,[y] ;
                            |            | MOV EBX,[y] | MOV EDX,[x] ;
                exists (2:EAX=1 /\\ 2:EBX=0 /\\ 3:ECX=1 /\\ 3:EDX=0)
                """, "IRIW", "IRIW/x86");
    }

    // ── C dialect: structural match against the hand-crafted classics ────────────

    @Test
    void sbCMatchesHandcrafted() {
        assertParsedMatches("""
                C SB
                { }
                 P0                 | P1                 ;
                 WRITE_ONCE(*x,1)   | WRITE_ONCE(*y,1)   ;
                 r1 = READ_ONCE(*y) | r2 = READ_ONCE(*x) ;
                exists (0:r1=0 /\\ 1:r2=0)
                """, "SB", "SB/C");
    }

    @Test
    void mpCMatchesHandcrafted() {
        assertParsedMatches("""
                C MP
                { }
                 P0                 | P1                 ;
                 WRITE_ONCE(*d,1)   | r1 = READ_ONCE(*f) ;
                 WRITE_ONCE(*f,1)   | r2 = READ_ONCE(*d) ;
                exists (1:r1=1 /\\ 1:r2=0)
                """, "MP", "MP/C");
    }

    @Test
    void lbCMatchesHandcrafted() {
        assertParsedMatches("""
                C LB
                { }
                 P0                 | P1                 ;
                 r1 = READ_ONCE(*x) | r2 = READ_ONCE(*y) ;
                 WRITE_ONCE(*y,1)   | WRITE_ONCE(*x,1)   ;
                exists (0:r1=1 /\\ 1:r2=1)
                """, "LB", "LB/C");
    }

    @Test
    void iriwCMatchesHandcrafted() {
        assertParsedMatches("""
                C IRIW
                { }
                 P0               | P1               | P2                 | P3                 ;
                 WRITE_ONCE(*x,1) | WRITE_ONCE(*y,1) | r1 = READ_ONCE(*x) | r3 = READ_ONCE(*y) ;
                                  |                  | r2 = READ_ONCE(*y) | r4 = READ_ONCE(*x) ;
                exists (2:r1=1 /\\ 2:r2=0 /\\ 3:r3=1 /\\ 3:r4=0)
                """, "IRIW", "IRIW/C");
    }

    // ── Dependency detection ─────────────────────────────────────────────────────

    @Test
    void identityXorIsFakeDependency() {
        LitmusCase lc = LitmusParser.parse("""
                C FAKEDEP
                { }
                 P0 ;
                 r1 = READ_ONCE(*x) ;
                 WRITE_ONCE(*y, r1 ^ r1) ;
                exists (0:r1=0)
                """, "fakedep.litmus");

        WriteEvent writeY = writeTo(lc.es(), "y");
        ReadEvent readX = readOf(lc.es(), "x");
        DependencyInfo deps = lc.deps();

        // The edge is present (the store syntactically mentions the read) ...
        assertTrue(deps.getDataDeps(writeY).contains(readX),
                "an identity-xor store still records a syntactic data dependency");
        // ... but it carries no real value flow, so it is not semantic.
        assertTrue(deps.semanticEdges().isEmpty(),
                "r1 ^ r1 cancels the read, so the dependency must be isSemantic=false");
    }

    @Test
    void realArithmeticIsSemanticDependency() {
        LitmusCase lc = LitmusParser.parse("""
                C REALDEP
                { }
                 P0 ;
                 r1 = READ_ONCE(*x) ;
                 WRITE_ONCE(*y, r1 + 1) ;
                exists (0:r1=0)
                """, "realdep.litmus");

        WriteEvent writeY = writeTo(lc.es(), "y");
        ReadEvent readX = readOf(lc.es(), "x");
        DependencyInfo deps = lc.deps();

        assertTrue(deps.getDataDeps(writeY).contains(readX),
                "the store on r1 + 1 records a data dependency");
        assertEquals(1, deps.semanticEdges().size(),
                "r1 + 1 genuinely varies with the read, so exactly one semantic edge");
        DependencyInfo.DepEdge edge = deps.semanticEdges().get(0);
        assertSame(writeY, edge.consumer(), "consumer is the store");
        assertSame(readX, edge.producer(), "producer is the load");
        assertTrue(edge.isSemantic(), "edge is semantic");
    }

    // ── Address dependencies: AArch64 indexed addressing ─────────────────────────

    /**
     * {@code [Xbase, Windex, SXTW]} — the AArch64 indexed (extended-register) address
     * form. Regression for the {@code splitOperands} bug: the operand list was split on
     * <em>every</em> comma, severing {@code [X5} from {@code W4,SXTW]} so the index
     * register {@code W4} was dropped before {@link DependencyInfo} ever saw it and the
     * load recorded <em>no</em> address dependency. The bracketed expression must be one
     * atomic operand so the index register survives.
     */
    @Test
    void indexedAddressExtractsAddrDependency() {
        LitmusCase lc = LitmusParser.parse("""
                AArch64 ADDR-SXTW
                { 0:X0=x; 0:X1=y; }
                 P0                  ;
                 LDR W4,[X0]         ;
                 LDR W3,[X1,W4,SXTW] ;
                exists (0:W4=0)
                """, "addr-sxtw.litmus");

        ReadEvent loadX = readOf(lc.es(), "x");   // LDR W4,[X0] — produces W4
        ReadEvent loadY = readOf(lc.es(), "y");   // LDR W3,[X1,W4,SXTW] — indexed by W4
        DependencyInfo deps = lc.deps();

        assertTrue(deps.getAddrDeps(loadY).contains(loadX),
                "the index register W4 must produce an ADDR dependency from the indexed "
                        + "load back to the load that defined W4");
        assertEquals(1, deps.allEdges().stream()
                        .filter(e -> e.kind() == DependencyInfo.DepKind.ADDR).count(),
                "exactly one address dependency edge");
    }

    /**
     * The plain two-register form {@code [Xbase, Windex]} (no extend) must likewise keep
     * the index register through operand splitting.
     */
    @Test
    void plainTwoRegisterAddressExtractsAddrDependency() {
        LitmusCase lc = LitmusParser.parse("""
                AArch64 ADDR-REG
                { 0:X0=x; 0:X1=y; }
                 P0             ;
                 LDR W4,[X0]    ;
                 LDR W3,[X1,W4] ;
                exists (0:W4=0)
                """, "addr-reg.litmus");

        ReadEvent loadX = readOf(lc.es(), "x");
        ReadEvent loadY = readOf(lc.es(), "y");
        assertTrue(lc.deps().getAddrDeps(loadY).contains(loadX),
                "index register W4 in [X1,W4] must produce an ADDR dependency");
    }

    /**
     * {@code [Xbase, #imm]} is a constant displacement, <em>not</em> an index register:
     * it must not manufacture a spurious address dependency. Only a second register
     * identifier inside the brackets is an index; an immediate is address-invariant.
     */
    @Test
    void immediateOffsetIsNotAnAddressDependency() {
        LitmusCase lc = LitmusParser.parse("""
                AArch64 ADDR-IMM
                { 0:X0=x; 0:X1=y; }
                 P0             ;
                 LDR W4,[X0]    ;
                 LDR W3,[X1,#8] ;
                exists (0:W4=0)
                """, "addr-imm.litmus");

        ReadEvent loadY = readOf(lc.es(), "y");
        assertTrue(lc.deps().getAddrDeps(loadY).isEmpty(),
                "a #imm displacement is not an index register — no address dependency");
        assertEquals(0, lc.deps().allEdges().stream()
                        .filter(e -> e.kind() == DependencyInfo.DepKind.ADDR).count(),
                "no address dependency edges at all for an immediate-offset load");
    }

    /**
     * A representative, checked-in {@code +addr} litmus file (the herd7 idiom: an
     * {@code EOR}-self address register feeding an indexed load {@code [Xbase,Windex,SXTW]}).
     * The address dependency must be recorded, and — because {@code W2 = W0 ^ W0} cancels —
     * classified fake ({@code isSemantic = false}), confirming the parse fix surfaces the
     * edge without disturbing the fake/semantic classifier.
     */
    @Test
    void representativeAddrFileRecordsFakeAddrDependency() throws Exception {
        Path file = Path.of("eval", "examples", "paper", "MP+dmb.sy+addr.litmus");
        LitmusCase lc = LitmusParser.parse(Files.readString(file), file.getFileName().toString());

        List<DependencyInfo.KindedEdge> addr = lc.deps().allEdges().stream()
                .filter(e -> e.kind() == DependencyInfo.DepKind.ADDR).toList();
        assertEquals(1, addr.size(), "the indexed load records exactly one ADDR dependency");
        assertFalse(addr.get(0).edge().isSemantic(),
                "the r^r index register carries no real value flow — the ADDR edge is fake");
    }

    // ── Robustness ───────────────────────────────────────────────────────────────

    @Test
    void malformedFileThrowsParseExceptionWithLine() {
        // An initial-state block that is never closed: a structural (malformed) error.
        String broken = "X86 BROKEN\n{ x=0\nP0 ;\n";
        ParseException ex = assertThrows(ParseException.class,
                () -> LitmusParser.parse(broken, "broken.litmus"));
        assertEquals(ParseException.Kind.MALFORMED, ex.kind(), "classified as malformed");
        assertEquals(2, ex.line(), "points at the line with the unterminated '{'");
        assertTrue(ex.getMessage().contains("broken.litmus:2"),
                "message carries source:line, not a bare stack trace: " + ex.getMessage());
    }

    @Test
    void unknownArchitectureIsSkippable() {
        ParseException ex = assertThrows(ParseException.class,
                () -> LitmusParser.parse("MIPS SB\n{ }\n P0 ;\n NOP ;\n", "mips.litmus"));
        assertEquals(ParseException.Kind.UNSUPPORTED_ARCH, ex.kind());
        assertTrue(ex.isSkippable(),
                "an unknown architecture is a skip-with-warning, not a hard error");
    }

    @Test
    void parseDirectorySkipsBadFilesAndKeepsGood() throws Exception {
        Path dir = Files.createTempDirectory("wev-litmus-robustness");
        try {
            Files.writeString(dir.resolve("good.litmus"), """
                    C SB
                    { }
                     P0                 | P1                 ;
                     WRITE_ONCE(*x,1)   | WRITE_ONCE(*y,1)   ;
                     r1 = READ_ONCE(*y) | r2 = READ_ONCE(*x) ;
                    exists (0:r1=0 /\\ 1:r2=0)
                    """);
            Files.writeString(dir.resolve("unknown-arch.litmus"), "MIPS SB\n{ }\n P0 ;\n NOP ;\n");
            Files.writeString(dir.resolve("malformed.litmus"), "X86 BROKEN\n{ x=0\nP0 ;\n");
            Files.writeString(dir.resolve("empty.litmus"), "\n(* only a comment *)\n");

            // One bad file of each kind must NOT abort the run; the good one survives.
            List<LitmusCase> ok = LitmusParser.parseDirectory(dir);
            assertEquals(1, ok.size(), "exactly the one well-formed file is returned");
            assertEquals("good.litmus", ok.get(0).sourceName());
        } finally {
            for (Path p : Files.list(dir).toList()) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static void assertParsedMatches(String litmus, String corpusName, String what) {
        LitmusCase parsed = LitmusParser.parse(litmus, what);
        assertStructurallyEqual(corpus(corpusName), parsed.es(), what);
    }

    private static EventStructure corpus(String name) {
        List<LitmusCorpus.LitmusCase> cases = LitmusCorpus.classics();
        return cases.stream()
                .filter(lc -> lc.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("corpus case not found: " + name))
                .es();
    }

    private static WriteEvent writeTo(EventStructure es, String var) {
        return es.getEvents().stream()
                .filter(e -> e instanceof WriteEvent && e.getThreadId() != 0)
                .map(e -> (WriteEvent) e)
                .filter(w -> w.getVariable().equals(var))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no program write to " + var));
    }

    private static ReadEvent readOf(EventStructure es, String var) {
        return es.getEvents().stream()
                .filter(e -> e instanceof ReadEvent)
                .map(e -> (ReadEvent) e)
                .filter(r -> r.getVariable().equals(var))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no read of " + var));
    }

    /**
     * Structural (id-independent) equality: same event-type composition and the same
     * program-order / reads-from / coherence-order cardinalities. Mirrors the helper in
     * {@code ParametricProgramsTest} — event ids differ between builders, so we compare
     * shape, not identity.
     */
    private static void assertStructurallyEqual(EventStructure expected,
                                                EventStructure actual, String what) {
        assertEquals(expected.getEvents().size(), actual.getEvents().size(),
                what + ": event count");
        assertEquals(typeCount(expected, EventType.READ), typeCount(actual, EventType.READ),
                what + ": read count");
        assertEquals(typeCount(expected, EventType.WRITE), typeCount(actual, EventType.WRITE),
                what + ": write count");
        assertEquals(edgeCount(expected), edgeCount(actual),
                what + ": program-order edge count");
        assertEquals(expected.getReadsFrom().size(), actual.getReadsFrom().size(),
                what + ": reads-from edge count");
        assertEquals(coEntries(expected), coEntries(actual),
                what + ": coherence-order entry count");
        assertEquals(expected.getCoherenceOrder().size(), actual.getCoherenceOrder().size(),
                what + ": location count");
    }

    private static long typeCount(EventStructure es, EventType t) {
        return es.getEvents().stream().filter(e -> e.getType() == t).count();
    }

    private static int edgeCount(EventStructure es) {
        return es.getProgramOrder().values().stream().mapToInt(List::size).sum();
    }

    private static int coEntries(EventStructure es) {
        return es.getCoherenceOrder().values().stream().mapToInt(List::size).sum();
    }
}
