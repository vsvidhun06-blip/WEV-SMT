package wev.smt.research;

import com.weakest.model.Event;
import com.weakest.model.FenceEvent;
import com.weakest.model.RMWEvent;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;

import wev.smt.DependencyInfo;
import wev.smt.parse.LitmusCase;
import wev.smt.parse.LitmusParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CONTROL FRONT-END IMPACT AUDIT, stage 1: dump the complete parsed representation of
 * every evaluation input, plus a canonical hash, so that source programs which collapse
 * onto the same internal representation can be found mechanically.
 *
 * <p><b>Read-only.</b> This changes no parser, detector or encoder behaviour; it only
 * observes what {@link LitmusParser} produces.
 *
 * <p>The canonical representation deliberately covers exactly what flows downstream into
 * the encoder: the event list (kind, thread, location, memory order, value, symbolic value
 * expression), program order, reads-from, coherence order, and the dependency sidecar with
 * its {@code isSemantic} flags. Event ids are renumbered per file, because the parser mints
 * them from a global counter and raw ids would differ between two otherwise identical
 * parses.
 *
 * <p>Emits TSV on stdout: one record per file, fields separated by tabs, with the
 * canonical representation itself as the final field (newlines escaped).
 */
public final class ControlParserImpactRun {

    /** Source-level control syntax, counted on comment-stripped program text. */
    private static final Pattern IF_ANY = Pattern.compile("\\bif\\s*\\(");
    private static final Pattern IF_EMPTY = Pattern.compile("\\bif\\s*\\([^)]*\\)\\s*;");
    private static final Pattern IF_WITH_BODY =
            Pattern.compile("\\bif\\s*\\([^)]*\\)\\s*(?!;)\\S");
    private static final Pattern ELSE_ARM = Pattern.compile("\\belse\\b");
    private static final Pattern LOOP = Pattern.compile("\\b(while|for)\\s*\\(");
    /** Assembly-level branches (PPC/ARM/RISCV/x86 dialects). */
    private static final Pattern ASM_BRANCH = Pattern.compile(
            "(?im)^\\s*(beq|bne|blt|bgt|bge|ble|cbz|cbnz|cmp|cmpw|cmpwi|cmpd|tst|test|"
            + "j|jal|jmp|je|jne|jz|jnz)\\b");

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String root : args) {
            if (root.startsWith("@")) {
                // "@listfile:prefix" — one relative path per line, resolved against prefix.
                int c = root.indexOf(':', 2);
                Path list = Path.of(root.substring(1, c < 0 ? root.length() : c));
                Path base = c < 0 ? Path.of("") : Path.of(root.substring(c + 1));
                for (String line : Files.readAllLines(list)) {
                    String rel = line.split(",")[0].strip();
                    if (rel.isEmpty()) continue;
                    Path p = base.resolve(rel);
                    if (Files.exists(p)) files.add(p);
                }
            } else {
                collect(Path.of(root), files);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(String.join("\t",
                "path", "parse_ok", "if_any", "if_empty", "if_body", "else_arms",
                "loops", "asm_branch", "contains_control", "source_program_shape",
                "parsed_control_preserved", "n_events", "n_ctrl_edges", "n_data_edges",
                "n_addr_edges", "hash", "canon")).append('\n');

        for (Path f : files) {
            String src;
            try {
                src = Files.readString(f, StandardCharsets.UTF_8);
            } catch (Exception e) {
                try {
                    src = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                } catch (Exception e2) { continue; }
            }
            String code = stripComments(src);
            int ifAny = count(IF_ANY, code);
            int ifEmpty = count(IF_EMPTY, code);
            int ifBody = count(IF_WITH_BODY, code);
            int elses = count(ELSE_ARM, code);
            int loops = count(LOOP, code);
            int asmBr = count(ASM_BRANCH, code);
            boolean control = ifAny > 0 || loops > 0 || asmBr > 0;

            String shape = shapeOf(ifAny, ifEmpty, ifBody, elses, loops, asmBr);

            LitmusCase lc = null;
            String parseOk = "yes";
            try {
                lc = LitmusParser.parse(src, f.getFileName().toString());
            } catch (Throwable t) {
                parseOk = "no:" + t.getClass().getSimpleName();
            }

            String canon = lc == null ? "" : canonical(lc);
            int nEv = 0, nCtrl = 0, nData = 0, nAddr = 0;
            if (lc != null) {
                nEv = lc.es().getEvents().size();
                for (DependencyInfo.KindedEdge ke : lc.deps().allEdges()) {
                    switch (ke.kind()) {
                        case CTRL -> nCtrl++;
                        case DATA -> nData++;
                        case ADDR -> nAddr++;
                    }
                }
            }

            // Control structure is preserved only if a source branch WITH A BODY (or an
            // else arm) left a trace in the parsed representation. The degenerate
            // "if (c) ;" idiom has no body to lose, so it is reported separately.
            String preserved;
            if (!control) preserved = "n/a";
            else if (lc == null) preserved = "unparsed";
            else if (ifBody > 0 || elses > 0) preserved = nCtrl > 0 ? "partial" : "NO";
            else if (loops > 0) preserved = "NO";           // no loop structure at all
            else preserved = nCtrl > 0 ? "degenerate-idiom" : "NO";

            out.append(String.join("\t",
                    f.toString().replace('\\', '/'), parseOk,
                    Integer.toString(ifAny), Integer.toString(ifEmpty),
                    Integer.toString(ifBody), Integer.toString(elses),
                    Integer.toString(loops), Integer.toString(asmBr),
                    Boolean.toString(control), shape, preserved,
                    Integer.toString(nEv), Integer.toString(nCtrl),
                    Integer.toString(nData), Integer.toString(nAddr),
                    sha1(canon), canon.replace("\n", "\\n").replace("\t", " ")))
               .append('\n');
        }
        System.out.print(out);
    }

    private static String shapeOf(int ifAny, int ifEmpty, int ifBody, int elses,
                                  int loops, int asmBr) {
        List<String> parts = new ArrayList<>();
        if (ifBody > 0 && elses > 0) parts.add("if-else-with-bodies");
        else if (ifBody > 0) parts.add("if-with-body");
        else if (ifEmpty > 0) parts.add("if-empty-arm");
        else if (ifAny > 0) parts.add("if-other");
        if (loops > 0) parts.add("loop");
        if (asmBr > 0) parts.add("asm-branch");
        return parts.isEmpty() ? "none" : String.join("+", parts);
    }

    /**
     * The parsed representation, normalised so that two structurally identical parses of
     * different source texts produce identical strings. Event ids are replaced by their
     * index in the event list.
     */
    private static String canonical(LitmusCase lc) {
        List<Event> evs = lc.es().getEvents();
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < evs.size(); i++) idx.put(evs.get(i).getId(), i);

        StringBuilder b = new StringBuilder();
        b.append("arch=").append(lc.arch()).append('\n');
        for (int i = 0; i < evs.size(); i++) {
            Event e = evs.get(i);
            b.append("E").append(i).append(' ').append(kindOf(e))
             .append(" T").append(e.getThreadId());
            if (e instanceof ReadEvent r) {
                b.append(" loc=").append(r.getVariable()).append(" mo=").append(r.getMemoryOrder())
                 .append(" reg=").append(r.getLocalVar());
            } else if (e instanceof RMWEvent u) {
                b.append(" loc=").append(u.getVariable()).append(" mo=").append(u.getMemoryOrder());
            } else if (e instanceof WriteEvent w) {
                b.append(" loc=").append(w.getVariable()).append(" mo=").append(w.getMemoryOrder())
                 .append(" val=").append(w.getValue())
                 .append(" expr=").append(normExpr(lc.writeValueExprs().get(e.getId()), idx));
            } else if (e instanceof FenceEvent) {
                b.append(" fence");
            }
            b.append('\n');
        }
        // Every relation is emitted SORTED. The underlying maps are keyed by the parser's
        // GLOBAL event ids, which differ between two parses in the same run, so unsorted
        // iteration order is not stable and would produce spurious hash differences
        // between structurally identical parses.
        List<String> rel = new ArrayList<>();
        lc.es().getProgramOrder().forEach((from, tos) -> {
            for (Integer to : tos) rel.add("po " + idx.get(from) + "->" + idx.get(to));
        });
        lc.es().getReadsFrom().forEach((rd, wr) ->
                rel.add("rf " + idx.get(wr) + "->" + idx.get(rd)));
        lc.es().getCoherenceOrder().forEach((var, ws) -> {
            StringBuilder c = new StringBuilder("co ").append(var).append(':');
            for (Integer wid : ws) c.append(' ').append(idx.get(wid));
            rel.add(c.toString());
        });
        rel.sort(null);
        for (String s : rel) b.append(s).append('\n');
        List<String> deps = new ArrayList<>();
        for (DependencyInfo.KindedEdge ke : lc.deps().allEdges()) {
            deps.add("dep " + ke.kind()
                    + " c=" + idx.get(ke.edge().consumer().getId())
                    + " p=" + idx.get(ke.edge().producer().getId())
                    + " sem=" + ke.edge().isSemantic());
        }
        deps.sort(null);
        for (String d : deps) b.append(d).append('\n');
        return b.toString();
    }

    /** Rewrite R&lt;globalId&gt; occurrences to R&lt;index&gt; so hashes are stable. */
    private static String normExpr(String expr, Map<Integer, Integer> idx) {
        if (expr == null) return "?";
        Matcher m = Pattern.compile("R(\\d+)").matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer i = idx.get(Integer.parseInt(m.group(1)));
            m.appendReplacement(sb, "R" + (i == null ? "?" : i));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String kindOf(Event e) {
        if (e instanceof RMWEvent) return "U";
        if (e instanceof ReadEvent) return "R";
        if (e instanceof WriteEvent) return "W";
        if (e instanceof FenceEvent) return "F";
        return "?";
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)\\(\\*.*?\\*\\)", " ");
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String sha1(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.substring(0, 16);
    }

    private static void collect(Path root, List<Path> out) throws Exception {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".litmus")).sorted().forEach(out::add);
        }
    }

    private ControlParserImpactRun() { }
}
