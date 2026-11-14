package wev.smt.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit-manifest and symbolic modes of {@link CorpusValidation}: exact membership, hash
 * pinning, one row per (entry × model) with explicit failure results, identical outcome
 * constraints across models, and verdicts on small tests whose answers are known.
 */
class CorpusValidationManifestTest {

    private static final String[] MODELS = {"SC", "TSO", "PSO", "RA", "WEAKEST", "RC11"};

    // Store buffering: SC forbids, TSO allows (W→R relaxation).
    static final String SB = """
            C SB
            { x=0; y=0; }
            P0(int *x, int *y) { WRITE_ONCE(*x, 1); r0 = READ_ONCE(*y); }
            P1(int *x, int *y) { WRITE_ONCE(*y, 1); r1 = READ_ONCE(*x); }
            exists (0:r0=0 /\\ 1:r1=0)
            """;
    // Message passing: SC and TSO forbid.
    static final String MP = """
            C MP
            { x=0; y=0; }
            P0(int *x, int *y) { WRITE_ONCE(*x, 1); WRITE_ONCE(*y, 1); }
            P1(int *x, int *y) { r0 = READ_ONCE(*y); r1 = READ_ONCE(*x); }
            exists (1:r0=1 /\\ 1:r1=0)
            """;
    // Coherence of two same-thread writes: final x=1 is impossible under every model.
    static final String COWW = """
            C CoWW
            { x=0; }
            P0(int *x) { WRITE_ONCE(*x, 1); WRITE_ONCE(*x, 2); }
            exists (x=1)
            """;
    // Load buffering through a fake dependency (r^r^1 = 1): SC forbids, WEAKEST allows.
    static final String LB_FAKE = """
            C LBxor
            { x=0; y=0; }
            P0(int *x, int *y) { r0 = READ_ONCE(*x); WRITE_ONCE(*y, r0 ^ r0 ^ 1); }
            P1(int *x, int *y) { r1 = READ_ONCE(*y); WRITE_ONCE(*x, r1 ^ r1 ^ 1); }
            exists (0:r0=1 /\\ 1:r1=1)
            """;
    // Out-of-thin-air copy cycle: WEAKEST forbids (real dependencies), RA allows.
    static final String OOTA = """
            C OOTA
            { x=0; y=0; }
            P0(int *x, int *y) { r0 = READ_ONCE(*x); WRITE_ONCE(*y, r0); }
            P1(int *x, int *y) { r1 = READ_ONCE(*y); WRITE_ONCE(*x, r1); }
            exists (0:r0=1 /\\ 1:r1=1)
            """;

    private static String put(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return CorpusValidation.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path manifest(Path dir, Map<String, String> entries) throws Exception {
        StringBuilder b = new StringBuilder("path,sha256,note\n");
        entries.forEach((p, h) -> b.append(p).append(',').append(h).append(",x\n"));
        Path m = dir.resolve("manifest.csv");
        Files.writeString(m, b.toString());
        return m;
    }

    /** Runs manifest mode; returns rows keyed "file|model" → {result, verdict, note, ...}. */
    private static Map<String, Map<String, String>> run(Path root, Path out, Path manifest, String... extra)
            throws Exception {
        List<String> args = new ArrayList<>(List.of(root.toString(), out.toString(),
                "manifest=" + manifest, "csv=r.csv", "perFileSec=60"));
        args.addAll(List.of(extra));
        CorpusValidation.main(args.toArray(new String[0]));
        List<String> lines = Files.readAllLines(out.resolve("r.csv"));
        assertEquals("file,sha256,arch,mode,model,result,verdict,solve_ms,note", lines.get(0));
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        String[] h = lines.get(0).split(",");
        for (String l : lines.subList(1, lines.size())) {
            String[] c = l.split(",", -1);
            Map<String, String> r = new HashMap<>();
            for (int i = 0; i < h.length; i++) r.put(h[i], c[i]);
            assertTrue(rows.put(r.get("file") + "|" + r.get("model"), r) == null, "one row per (file, model)");
        }
        return rows;
    }

    private static String result(Map<String, Map<String, String>> rows, String file, String model) {
        return rows.get(file + "|" + model).get("result");
    }

    @Test
    void evaluatesExactlyTheListedFiles(@TempDir Path root, @TempDir Path out) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("a/SB.litmus", put(root, "a/SB.litmus", SB));
        m.put("MP.litmus", put(root, "MP.litmus", MP));
        put(root, "unlisted.litmus", COWW);                 // present but not listed
        put(root, "a/b/unlisted2.litmus", OOTA);
        Map<String, Map<String, String>> rows = run(root, out, manifest(out, m), "mode=symbolic");
        Set<String> files = new HashSet<>();
        rows.values().forEach(r -> files.add(r.get("file")));
        assertEquals(Set.of("a/SB.litmus", "MP.litmus"), files);
        assertEquals(2 * MODELS.length, rows.size());
        assertEquals(m.get("MP.litmus"), rows.get("MP.litmus|SC").get("sha256"));
        assertTrue(Files.readString(out.resolve("r.csv.config.txt")).contains("mode=symbolic"));
    }

    @Test
    void changedMissingDuplicateAndEscapingEntriesRejectTheManifest(@TempDir Path root, @TempDir Path out)
            throws Exception {
        String h = put(root, "SB.litmus", SB);
        Files.writeString(root.resolve("SB.litmus"), SB + "\n");               // content changed
        Path changed = manifest(out, Map.of("SB.litmus", h));
        var ex = assertThrows(CorpusValidation.ManifestException.class,
                () -> CorpusValidation.loadManifest(root, changed));
        assertTrue(ex.getMessage().contains("content changed"), ex.getMessage());

        Path missing = manifest(out, Map.of("nope.litmus", h));
        assertTrue(assertThrows(CorpusValidation.ManifestException.class,
                () -> CorpusValidation.loadManifest(root, missing)).getMessage().contains("missing file"));

        String h2 = put(root, "MP.litmus", MP);
        Path dup = out.resolve("dup.csv");
        Files.writeString(dup, "path,sha256\nMP.litmus," + h2 + "\nMP.litmus," + h2 + "\n");
        assertTrue(assertThrows(CorpusValidation.ManifestException.class,
                () -> CorpusValidation.loadManifest(root, dup)).getMessage().contains("duplicate"));

        Path esc = out.resolve("esc.csv");
        Files.writeString(esc, "path,sha256\n../MP.litmus," + h2 + "\n");
        assertTrue(assertThrows(CorpusValidation.ManifestException.class,
                () -> CorpusValidation.loadManifest(root, esc)).getMessage().contains("escapes"));

        Path noHeader = out.resolve("nohdr.csv");
        Files.writeString(noHeader, "MP.litmus\n");
        assertThrows(CorpusValidation.ManifestException.class, () -> CorpusValidation.loadManifest(root, noHeader));
    }

    @Test
    void knownScTsoOutcomes(@TempDir Path root, @TempDir Path out) throws Exception {
        Map<String, String> m = Map.of("SB.litmus", put(root, "SB.litmus", SB), "MP.litmus", put(root, "MP.litmus", MP));
        var rows = run(root, out, manifest(out, m), "mode=symbolic");
        assertEquals("UNSAT", result(rows, "SB.litmus", "SC"));
        assertEquals("SAT", result(rows, "SB.litmus", "TSO"));
        assertEquals("UNSAT", result(rows, "MP.litmus", "SC"));
        assertEquals("UNSAT", result(rows, "MP.litmus", "TSO"));
        assertEquals("FORBIDDEN", rows.get("SB.litmus|SC").get("verdict"));
    }

    @Test
    void memoryFinalOutcomeIsEncodedOnlyInSymbolicMode(@TempDir Path root, @TempDir Path out1,
                                                         @TempDir Path out2) throws Exception {
        Map<String, String> m = Map.of("CoWW.litmus", put(root, "CoWW.litmus", COWW));
        var sym = run(root, out1, manifest(out1, m), "mode=symbolic");
        for (String model : MODELS) assertEquals("UNSAT", result(sym, "CoWW.litmus", model), model);
        var wired = run(root, out2, manifest(out2, m));                       // default mode = wired
        assertEquals("SAT", result(wired, "CoWW.litmus", "SC"),
                "wired mode ignores the memory-final atom x=1 (documented limitation)");
        assertEquals("wired", wired.get("CoWW.litmus|SC").get("mode"));
    }

    @Test
    void modelSeparatingLoadBufferingPair(@TempDir Path root, @TempDir Path out) throws Exception {
        Map<String, String> m = Map.of("LBxor.litmus", put(root, "LBxor.litmus", LB_FAKE),
                "OOTA.litmus", put(root, "OOTA.litmus", OOTA));
        var rows = run(root, out, manifest(out, m), "mode=symbolic");
        assertEquals("UNSAT", result(rows, "LBxor.litmus", "SC"));
        assertEquals("UNSAT", result(rows, "LBxor.litmus", "RC11"));
        assertEquals("SAT", result(rows, "LBxor.litmus", "WEAKEST"), "fake dependencies: grounded LB allowed");
        assertEquals("UNSAT", result(rows, "OOTA.litmus", "WEAKEST"), "real dependencies: thin air forbidden");
        assertEquals("SAT", result(rows, "OOTA.litmus", "RA"), "RA has no no-thin-air axiom");
        assertEquals("UNSAT", result(rows, "OOTA.litmus", "SC"));
    }

    @Test
    void failuresAreRecordedNeverOmittedOrTurnedIntoVerdicts(@TempDir Path root, @TempDir Path out1,
                                                            @TempDir Path out2) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SB.litmus", put(root, "SB.litmus", SB));
        m.put("bad.litmus", put(root, "bad.litmus", "MIPS nope\n{ }\n P0 ;\n nop ;\nexists (x=1)\n"));
        m.put("noexists.litmus", put(root, "noexists.litmus", SB.replace("exists (0:r0=0 /\\ 1:r1=0)", "")));
        var rows = run(root, out1, manifest(out1, m), "mode=symbolic", "maxEvents=64");
        for (String model : MODELS) {
            assertEquals("UNSUPPORTED", result(rows, "bad.litmus", model));
            assertEquals("UNSUPPORTED", result(rows, "noexists.litmus", model));
            assertEquals("", rows.get("bad.litmus|" + model).get("verdict"));
        }
        // Over the event bound: UNSUPPORTED, not FORBIDDEN.
        var small = run(root, out2, manifest(out2, Map.of("SB.litmus", m.get("SB.litmus"))), "mode=symbolic", "maxEvents=1");
        for (String model : MODELS) assertEquals("UNSUPPORTED", result(small, "SB.litmus", model));
    }

    @Test
    void exhaustedBudgetYieldsTimeoutRowsForEveryEntryAndModel(@TempDir Path root, @TempDir Path out) throws Exception {
        Map<String, String> m = Map.of("SB.litmus", put(root, "SB.litmus", SB), "MP.litmus", put(root, "MP.litmus", MP));
        var rows = run(root, out, manifest(out, m), "mode=symbolic", "budgetMin=0");
        assertEquals(2 * MODELS.length, rows.size());
        rows.values().forEach(r -> {
            assertEquals("TIMEOUT", r.get("result"));
            assertEquals("", r.get("verdict"));
        });
    }

    @Test
    void weakestParityWithSymbolicRfRun(@TempDir Path root, @TempDir Path out) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (var e : Map.of("SB.litmus", SB, "MP.litmus", MP, "CoWW.litmus", COWW,
                "LBxor.litmus", LB_FAKE, "OOTA.litmus", OOTA).entrySet()) {
            m.put(e.getKey(), put(root, e.getKey(), e.getValue()));
        }
        var rows = run(root, out, manifest(out, m), "mode=symbolic");
        Path list = out.resolve("list.txt");
        Files.writeString(list, String.join("\n", m.keySet()) + "\n");
        Path srr = out.resolve("srr.csv");
        SymbolicRfRun.main(new String[]{"corpus", list.toString(), root.toString(), srr.toString()});
        int compared = 0;
        for (String l : Files.readAllLines(srr).subList(1, m.size() + 1)) {
            String[] c = l.split(",");
            String file = c[0].replace("\"", "");
            assertEquals(c[2], rows.get(file + "|WEAKEST").get("verdict"), "symbolic WEAKEST parity: " + file);
            compared++;
        }
        assertEquals(m.size(), compared);
    }

    // A store whose value expression the parser accepts but the QF_BV grammar does not ('/').
    static final String DIV = """
            C DIV
            { x=0; y=0; }
            P0(int *x, int *y) { r0 = READ_ONCE(*x); WRITE_ONCE(*y, r0 / 2); }
            exists (0:r0=1)
            """;

    @Test
    void incompleteSymbolicEncodingIsUnsupportedNotAVerdict(@TempDir Path root, @TempDir Path out1,
                                                            @TempDir Path out2) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DIV.litmus", put(root, "DIV.litmus", DIV));
        m.put("SB.litmus", put(root, "SB.litmus", SB));
        var rows = run(root, out1, manifest(out1, m), "mode=symbolic");
        for (String model : MODELS) {
            assertEquals("UNSUPPORTED", result(rows, "DIV.litmus", model));
            assertEquals("", rows.get("DIV.litmus|" + model).get("verdict"));
            assertTrue(rows.get("DIV.litmus|" + model).get("note").startsWith("incomplete-symbolic-encoding"));
        }
        assertEquals("UNSAT", result(rows, "SB.litmus", "SC"), "complete encodings are still solved");
        List<String> gaps = Files.readAllLines(out1.resolve("r.csv.encoding-gaps.csv"));
        assertEquals("file,sha256,gap", gaps.get(0));
        assertEquals(1, gaps.size() - 1);
        assertTrue(gaps.get(1).startsWith("DIV.litmus,") && gaps.get(1).contains("outside the grammar"), gaps.get(1));
        assertTrue(Files.readString(out1.resolve("r.csv.config.txt")).contains("encoding_gap_files=1"));
        // Wired mode is unaffected by the symbolic-encoding check.
        var wired = run(root, out2, manifest(out2, m));
        assertTrue(List.of("SAT", "UNSAT").contains(result(wired, "DIV.litmus", "SC")));
    }

    @Test
    void encodingGapCheckFindsSkippedOutcomePinsAndPassesCompleteCases() throws Exception {
        var cfg = org.sosy_lab.common.configuration.Configuration.defaultConfiguration();
        try (var ctx = org.sosy_lab.java_smt.SolverContextFactory.createSolverContext(cfg,
                org.sosy_lab.common.log.BasicLogManager.create(cfg),
                org.sosy_lab.common.ShutdownNotifier.createDummy(),
                org.sosy_lab.java_smt.SolverContextFactory.Solvers.Z3)) {
            com.weakest.model.Event.resetCounter();
            var lc = wev.smt.parse.LitmusParser.parse(SB, "SB.litmus");
            assertEquals(List.of(), CorpusValidation.symbolicEncodingGaps(lc, ctx));
            int writeId = lc.es().getEvents().stream().filter(e -> e instanceof com.weakest.model.WriteEvent
                    && e.getThreadId() == 1).findFirst().orElseThrow().getId();
            var broken = new wev.smt.parse.LitmusCase(lc.sourceName(), lc.arch(), lc.name(), lc.es(), lc.deps(),
                    lc.existsClause(), lc.expectations(), lc.herdObservation(), lc.writeValueExprs(),
                    Map.of(writeId, 1), Map.of("nowhere", 1));
            List<String> gaps = CorpusValidation.symbolicEncodingGaps(broken, ctx);
            assertTrue(gaps.stream().anyMatch(g -> g.contains("not a read")), gaps.toString());
            assertTrue(gaps.stream().anyMatch(g -> g.contains("'nowhere' which has no write")), gaps.toString());
        }
    }

    @Test
    void defaultDirectoryModeIsUnchanged(@TempDir Path root, @TempDir Path out) throws Exception {
        put(root, "CoWW.litmus", COWW);
        CorpusValidation.main(new String[]{root.toString(), out.toString(), "csv=d.csv"});
        List<String> lines = Files.readAllLines(out.resolve("d.csv"));
        assertEquals("file,arch,model,expected,actual,witness_size,solve_ms,status,note", lines.get(0));
        assertFalse(Files.exists(out.resolve("d.csv.config.txt")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("CoWW.litmus,C,SC,,ALLOWED,")),
                "default wired mode keeps its historical verdict for the memory-only outcome");
    }
}
