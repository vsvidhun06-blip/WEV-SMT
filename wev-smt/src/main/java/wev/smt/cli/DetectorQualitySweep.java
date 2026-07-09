package wev.smt.cli;

import com.weakest.model.Event;

import wev.smt.DependencyInfo;
import wev.smt.DependencyInfo.KindedEdge;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;
import wev.smt.parse.LitmusParser.DepDecision;
import wev.smt.parse.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detector-quality sweep for the fake/semantic dependency classifier
 * ({@code LitmusParser}'s {@code dependsReally}). Parses every file in the
 * supported-syntax manifest and, for each realised dependency edge, records what the
 * detector decided and whether that decision could matter to a verdict.
 *
 * <p>For every dependency edge examined it captures:
 * <ol>
 *   <li>the edge itself (counted into the total);</li>
 *   <li>the idiom {@code pattern} the classifier matched, via the parser's
 *       {@link LitmusParser#DEP_TRACE} instrumentation;</li>
 *   <li>the fake/semantic classification ({@link DependencyInfo.DepEdge#isSemantic()});</li>
 *   <li>whether the classification could flip a verdict — i.e. the edge lies on a
 *       <em>candidate cycle</em> in {@code (dep ∪ rf)}, the load-buffering shape a
 *       fake-vs-real relabelling can open or close (computed by Kosaraju SCC over the
 *       combined graph, taking <em>all</em> dep edges so relevance is independent of the
 *       very classification under audit);</li>
 *   <li>the source litmus test.</li>
 * </ol>
 *
 * <p>Outputs {@code detector-quality-report.csv} (one row per edge) and
 * {@code pattern-frequency.csv} (pattern × classification counts), and prints the
 * roll-up totals. No solver is invoked — this is a pure parse-and-analyse pass.
 *
 * <p>Usage: {@code DetectorQualitySweep <manifest.csv> <corpus-root> <out-dir>}.
 */
public final class DetectorQualitySweep {

    private DetectorQualitySweep() { }

    /** One realised dependency edge with the detector's verdict about it. */
    private record EdgeRow(String sourceTest, String kind, boolean semantic,
                           String pattern, boolean verdictRelevant,
                           int consumerId, int producerId) { }

    public static void main(String[] args) throws IOException {
        Path manifest = Path.of(args.length > 0 ? args[0] : "eval/corpus-weakest-manifest.csv");
        Path corpusRoot = Path.of(args.length > 1 ? args[1] : "eval/corpus");
        Path outDir = Path.of(args.length > 2 ? args[2] : "eval");
        Files.createDirectories(outDir);

        List<String> relPaths = readManifest(manifest);
        System.out.printf(Locale.ROOT, "Manifest: %d test(s) under %s%n",
                relPaths.size(), corpusRoot.toAbsolutePath());

        List<EdgeRow> rows = new ArrayList<>();
        // Per-file trace collector wired into the parser's instrumentation hook.
        List<DepDecision> decisions = new ArrayList<>();
        LitmusParser.DEP_TRACE = decisions::add;

        int parsed = 0, skipped = 0, parseError = 0, filesWithEdges = 0;
        long totalDecisions = 0;
        try {
            for (String rel : relPaths) {
                Path file = corpusRoot.resolve(rel);
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException io) {
                    skipped++;
                    continue;
                }
                decisions.clear();
                LitmusCase lc;
                try {
                    lc = LitmusParser.parse(content, rel);
                } catch (ParseException pe) {
                    if (pe.kind() == ParseException.Kind.MALFORMED) parseError++; else skipped++;
                    continue;
                } catch (RuntimeException ex) {
                    parseError++;
                    continue;
                }
                parsed++;
                totalDecisions += decisions.size();
                List<EdgeRow> fileRows = edgesForFile(rel, lc, decisions);
                if (!fileRows.isEmpty()) filesWithEdges++;
                rows.addAll(fileRows);
            }
        } finally {
            LitmusParser.DEP_TRACE = null;
        }

        writeReport(outDir.resolve("detector-quality-report.csv"), rows);
        writePatternFrequency(outDir.resolve("pattern-frequency.csv"), rows);
        printSummary(rows, parsed, skipped, parseError, filesWithEdges, totalDecisions, outDir);
    }

    // ── Per-file edge extraction ─────────────────────────────────────────────────

    private static List<EdgeRow> edgesForFile(String rel, LitmusCase lc,
                                              List<DepDecision> decisions) {
        DependencyInfo deps = lc.deps();
        List<KindedEdge> edges = deps.allEdges();
        if (edges.isEmpty()) return List.of();

        // read-id → the patterns of decisions that classified it, split by the decision's
        // real flag, so a fake edge is attributed a cancellation and a semantic edge REAL.
        Map<Integer, LinkedHashSet<String>> fakePat = new HashMap<>();
        Map<Integer, LinkedHashSet<String>> realPat = new HashMap<>();
        for (DepDecision d : decisions) {
            for (Integer id : d.producerReadIds()) {
                (d.real() ? realPat : fakePat)
                        .computeIfAbsent(id, k -> new LinkedHashSet<>()).add(d.pattern());
            }
        }

        Set<Long> cyclic = candidateCycleEdges(lc);

        List<EdgeRow> out = new ArrayList<>(edges.size());
        for (KindedEdge ke : edges) {
            int producer = ke.edge().producer().getId();
            int consumer = ke.edge().consumer().getId();
            boolean sem = ke.edge().isSemantic();
            Set<String> pats = (sem ? realPat : fakePat).getOrDefault(producer, new LinkedHashSet<>());
            String pattern = pats.isEmpty() ? (sem ? "REAL" : "UNKNOWN") : String.join("|", pats);
            boolean relevant = cyclic.contains(edgeKey(producer, consumer));
            out.add(new EdgeRow(rel, ke.kind().name(), sem, pattern, relevant, consumer, producer));
        }
        return out;
    }

    /**
     * The set of dependency edges (keyed producer→consumer) that lie on a cycle in the
     * combined {@code dep ∪ rf} graph — dep contributes producer→consumer (the read
     * happens-before the consumer it feeds), rf contributes write→read. An edge is on a
     * cycle iff its two endpoints share a non-trivial strongly-connected component.
     */
    private static Set<Long> candidateCycleEdges(LitmusCase lc) {
        Map<Integer, List<Integer>> adj = new HashMap<>();
        Set<Integer> nodes = new HashSet<>();
        List<int[]> depEdges = new ArrayList<>();
        for (KindedEdge ke : lc.deps().allEdges()) {
            int p = ke.edge().producer().getId();
            int c = ke.edge().consumer().getId();
            addArc(adj, nodes, p, c);
            depEdges.add(new int[]{p, c});
        }
        for (Map.Entry<Integer, Integer> rf : lc.es().getReadsFrom().entrySet()) {
            addArc(adj, nodes, rf.getValue(), rf.getKey());   // write → read
        }
        Map<Integer, Integer> comp = kosaraju(adj, nodes);
        Map<Integer, Integer> compSize = new HashMap<>();
        for (int c : comp.values()) compSize.merge(c, 1, Integer::sum);

        Set<Long> cyclic = new HashSet<>();
        for (int[] e : depEdges) {
            Integer cp = comp.get(e[0]), cc = comp.get(e[1]);
            if (cp != null && cp.equals(cc) && compSize.getOrDefault(cp, 1) > 1) {
                cyclic.add(edgeKey(e[0], e[1]));
            }
        }
        return cyclic;
    }

    private static void addArc(Map<Integer, List<Integer>> adj, Set<Integer> nodes,
                               int from, int to) {
        adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        nodes.add(from);
        nodes.add(to);
    }

    private static long edgeKey(int from, int to) {
        return ((long) from << 32) | (to & 0xffffffffL);
    }

    /** Kosaraju SCC: node id → component id. Iterative, for arbitrary graph size. */
    private static Map<Integer, Integer> kosaraju(Map<Integer, List<Integer>> adj,
                                                  Set<Integer> nodes) {
        // 1. finish order via iterative post-order DFS
        Deque<int[]> stack = new ArrayDeque<>();   // {node, childIndex}
        Set<Integer> seen = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        for (Integer s : nodes) {
            if (seen.contains(s)) continue;
            stack.push(new int[]{s, 0});
            seen.add(s);
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                List<Integer> succ = adj.getOrDefault(top[0], List.of());
                if (top[1] < succ.size()) {
                    int next = succ.get(top[1]++);
                    if (seen.add(next)) stack.push(new int[]{next, 0});
                } else {
                    order.add(top[0]);
                    stack.pop();
                }
            }
        }
        // 2. transpose
        Map<Integer, List<Integer>> rev = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : adj.entrySet()) {
            for (int v : e.getValue()) rev.computeIfAbsent(v, k -> new ArrayList<>()).add(e.getKey());
        }
        // 3. assign components in reverse finish order
        Map<Integer, Integer> comp = new HashMap<>();
        int cid = 0;
        for (int i = order.size() - 1; i >= 0; i--) {
            int s = order.get(i);
            if (comp.containsKey(s)) continue;
            Deque<Integer> dfs = new ArrayDeque<>();
            dfs.push(s);
            comp.put(s, cid);
            while (!dfs.isEmpty()) {
                int u = dfs.pop();
                for (int v : rev.getOrDefault(u, List.of())) {
                    if (!comp.containsKey(v)) { comp.put(v, cid); dfs.push(v); }
                }
            }
            cid++;
        }
        return comp;
    }

    // ── Manifest ─────────────────────────────────────────────────────────────────

    private static List<String> readManifest(Path manifest) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            int comma = t.indexOf(',');
            out.add(comma < 0 ? t : t.substring(0, comma));
        }
        return out;
    }

    // ── Output ───────────────────────────────────────────────────────────────────

    private static void writeReport(Path path, List<EdgeRow> rows) throws IOException {
        try (var w = Files.newBufferedWriter(path)) {
            w.write("source_test,edge_kind,classification,pattern,verdict_relevant,"
                    + "consumer_id,producer_id\n");
            for (EdgeRow r : rows) {
                w.write(r.sourceTest() + ',' + r.kind() + ','
                        + (r.semantic() ? "SEMANTIC" : "FAKE") + ',' + csv(r.pattern()) + ','
                        + r.verdictRelevant() + ',' + r.consumerId() + ',' + r.producerId() + '\n');
            }
        }
    }

    private static void writePatternFrequency(Path path, List<EdgeRow> rows) throws IOException {
        // pattern → [fakeCount, semanticCount]
        Map<String, long[]> freq = new TreeMap<>();
        for (EdgeRow r : rows) {
            long[] c = freq.computeIfAbsent(r.pattern(), k -> new long[2]);
            c[r.semantic() ? 1 : 0]++;
        }
        try (var w = Files.newBufferedWriter(path)) {
            w.write("pattern,fake_edges,semantic_edges,total\n");
            for (Map.Entry<String, long[]> e : freq.entrySet()) {
                long[] c = e.getValue();
                w.write(csv(e.getKey()) + ',' + c[0] + ',' + c[1] + ',' + (c[0] + c[1]) + '\n');
            }
        }
    }

    private static String csv(String s) {
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    private static void printSummary(List<EdgeRow> rows, int parsed, int skipped,
                                     int parseError, int filesWithEdges, long totalDecisions,
                                     Path outDir) {
        long fake = rows.stream().filter(r -> !r.semantic()).count();
        long semantic = rows.size() - fake;
        long relevant = rows.stream().filter(EdgeRow::verdictRelevant).count();
        long relevantFake = rows.stream().filter(r -> r.verdictRelevant() && !r.semantic()).count();
        long relevantSem = relevant - relevantFake;

        Map<String, long[]> freq = new TreeMap<>();
        for (EdgeRow r : rows) {
            long[] c = freq.computeIfAbsent(r.pattern(), k -> new long[2]);
            c[r.semantic() ? 1 : 0]++;
        }
        Map<String, long[]> byKind = new LinkedHashMap<>();
        for (EdgeRow r : rows) {
            long[] c = byKind.computeIfAbsent(r.kind(), k -> new long[2]);
            c[r.semantic() ? 1 : 0]++;
        }

        System.out.println();
        System.out.println("======== Detector-quality sweep ========");
        System.out.printf(Locale.ROOT,
                "files: parsed=%d  skipped=%d  parse_error=%d  with_edges=%d%n",
                parsed, skipped, parseError, filesWithEdges);
        System.out.printf(Locale.ROOT, "detector classification decisions: %d%n", totalDecisions);
        System.out.println();
        System.out.printf(Locale.ROOT, "TOTAL edges        : %d%n", rows.size());
        System.out.printf(Locale.ROOT, "  FAKE edges       : %d%n", fake);
        System.out.printf(Locale.ROOT, "  SEMANTIC edges   : %d%n", semantic);
        System.out.printf(Locale.ROOT, "verdict-relevant   : %d  (fake=%d semantic=%d)%n",
                relevant, relevantFake, relevantSem);

        System.out.println();
        System.out.println("-- by edge kind (fake / semantic) --");
        for (Map.Entry<String, long[]> e : byKind.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-5s fake=%-7d semantic=%-7d%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        System.out.println();
        System.out.println("-- per-pattern frequency (fake / semantic) --");
        System.out.printf(Locale.ROOT, "  %-22s %10s %10s %10s%n", "pattern", "fake", "semantic", "total");
        freq.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1],
                        a.getValue()[0] + a.getValue()[1]))
                .forEach(e -> System.out.printf(Locale.ROOT, "  %-22s %10d %10d %10d%n",
                        e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] + e.getValue()[1]));

        System.out.println();
        System.out.printf(Locale.ROOT, "CSV: %s%n", outDir.resolve("detector-quality-report.csv"));
        System.out.printf(Locale.ROOT, "CSV: %s%n", outDir.resolve("pattern-frequency.csv"));
    }
}
