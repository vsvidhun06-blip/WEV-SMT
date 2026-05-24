package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;

import wev.smt.DependencyInfo;
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the standard herd7 {@code .litmus} test format into a WEV {@link LitmusCase}
 * — an {@link EventStructure} with the {@code exists}-named candidate execution wired
 * in, a syntactic {@link DependencyInfo} sidecar, and the herd7 ground-truth metadata.
 *
 * <h2>Supported format</h2>
 * <pre>{@code
 *   <ARCH> <name>                 e.g.  X86 SB     or     C MP
 *   { x=0; y=0; 0:r2=x; }         initial state (memory + thread-local registers)
 *    P0          | P1          ;  proc header (column layout)
 *    MOV [x],$1  | MOV [y],$1  ;  one instruction per thread per row
 *    MOV EAX,[y] | MOV EAX,[x] ;
 *   exists (0:EAX=0 /\ 1:EAX=0)   target outcome (also wires rf)
 * }</pre>
 * The C dialect may instead use {@code Pn(args){ stmt; stmt; }} function bodies.
 *
 * <h2>Instruction model</h2>
 * Architectural instructions map onto the WEV event vocabulary, which has only
 * {@code READ}/{@code WRITE} accesses with a {@link MemoryOrder}. Loads become
 * {@link ReadEvent}s, stores {@link WriteEvent}s; release/acquire variants set the
 * order; fences are recognised but — the model has no fence event — are <em>not</em>
 * encoded (a documented limitation, see {@code docs/litmus-parser-coverage.md}); a
 * read-modify-write is decomposed into a po-linked read→write pair with a data
 * dependency (atomicity not separately enforced). Anything outside this subset makes
 * the file {@linkplain ParseException.Kind#UNSUPPORTED_INSTRUCTION skipped}, never fatal.
 *
 * <h2>Dependencies</h2>
 * Data/addr/ctrl dependencies are detected <em>syntactically</em> from instruction text
 * and recorded {@code isSemantic = true} by default. An identity idiom that cancels the
 * read it mentions ({@code r ^ r}, {@code r - r}, {@code r & ~r}, …) carries no real
 * value flow and is recorded {@code isSemantic = false}, exactly the convention the
 * Stage-2 jf-coherence axiom relies on (Chakraborty &amp; Vafeiadis, POPL 2019, §3).
 *
 * <h2>Candidate execution</h2>
 * The {@code exists} clause names the outcome under test; each read's required value
 * fixes its {@code rf} edge (to the write of that value at its location, the initial
 * write preferred for value {@code 0}), and per-location coherence lists the initial
 * write first then the program writes in textual order. This is the same wired-outcome
 * methodology as {@code LitmusCorpus}, so the candidate execution's consistency under a
 * model is the validated question and the clause itself need not be re-queried.
 *
 * <p>The parser is deterministic: the same input yields the same event composition,
 * program order, rf/co cardinalities and dependency edges (event ids may differ, as
 * they come from the shared global counter and are never reset here).
 */
public final class LitmusParser {

    private LitmusParser() { }

    /** The architecture dialects this parser maps to WEV events. */
    public enum Arch {
        X86, PPC, ARM, RISCV, C;

        /** Resolve a header architecture token, or {@code null} if unrecognised. */
        static Arch resolve(String token) {
            return switch (token.toUpperCase(Locale.ROOT)) {
                case "X86", "I386", "AMD64", "X86_64", "X86-64" -> X86;
                case "PPC", "POWER", "POWERPC" -> PPC;
                case "ARM", "AARCH64", "ARM64", "ARMV7", "ARMV8" -> ARM;
                case "RISCV", "RISCV64", "RV64", "RV32" -> RISCV;
                case "C", "C11", "CPP" -> C;
                default -> null;
            };
        }

        boolean isAsm() { return this != C; }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Parse one {@code .litmus} file body. Throws {@link ParseException} on failure. */
    public static LitmusCase parse(String content, String sourceName) {
        return new Job(content, sourceName).run();
    }

    /**
     * Parse every {@code *.litmus} file in {@code dir} (non-recursive, name-sorted for
     * determinism). Files that fail to parse are logged to {@code stderr} and skipped —
     * a single bad file never aborts the run. Returns the successfully parsed cases.
     */
    public static List<LitmusCase> parseDirectory(Path dir) throws IOException {
        Map<String, Path> sorted = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.litmus")) {
            for (Path p : stream) sorted.put(p.getFileName().toString(), p);
        }
        List<LitmusCase> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : sorted.entrySet()) {
            String name = e.getKey();
            String content;
            try {
                content = Files.readString(e.getValue());
            } catch (IOException io) {
                System.err.println("[skip] " + name + ": cannot read (" + io.getMessage() + ")");
                continue;
            }
            try {
                out.add(parse(content, name));
            } catch (ParseException pe) {
                String verb = pe.isSkippable() ? "skip" : "parse-error";
                System.err.println("[" + verb + "] " + pe.getMessage());
            } catch (RuntimeException unexpected) {
                // Defensive: never let one pathological file kill the corpus run.
                System.err.println("[skip] " + name + ": unexpected " + unexpected);
            }
        }
        return out;
    }

    // ── Normalised instruction ────────────────────────────────────────────────

    private enum IKind { LOAD, STORE, IMM, ASSIGN, FENCE, RMW, BRANCH, NOP }

    /**
     * One architecture-neutral instruction. {@code loc}/{@code base} naming a memory
     * access (the base is a register for asm, resolved against the init address
     * bindings; for C {@code loc} is the variable directly). {@code expr} is the value
     * (STORE/ASSIGN) or condition (BRANCH) text; {@code reg} the destination register.
     */
    private record Insn(IKind kind, String reg, String loc, boolean locIsRegister,
                        String indexReg, String expr, MemoryOrder mo, String raw) { }

    // ── Register value provenance (the per-thread dataflow lattice) ───────────

    /**
     * What a register currently holds: an integer {@code constant} when statically
     * known, and the set of {@link ReadEvent}s whose value flows into it, each flagged
     * by whether that flow is <em>semantic</em> (survives the identity-cancellation
     * heuristic). Both can be populated at once (e.g. a constant that nonetheless
     * mentions a cancelled read).
     */
    private record RegSrc(Integer constant, Map<ReadEvent, Boolean> reads) {
        static RegSrc constant(int v) { return new RegSrc(v, Map.of()); }
        static RegSrc ofRead(ReadEvent r) {
            Map<ReadEvent, Boolean> m = new LinkedHashMap<>();
            m.put(r, Boolean.TRUE);
            return new RegSrc(null, m);
        }
    }

    // ── The parse job (one per file; holds all mutable parse state) ───────────

    private static final class Job {
        final String src;
        final String[] lines;

        Arch arch;
        String name = "";
        String existsClause = "";
        String herdObservation = "";
        final Map<MemoryModel, Outcome> expectations = new EnumMap<>(MemoryModel.class);

        // Initial state.
        final Map<String, Integer> initValues = new LinkedHashMap<>();           // var -> value
        final Map<Integer, Map<String, Integer>> regConstInit = new LinkedHashMap<>(); // P -> reg -> int
        final Map<Integer, Map<String, String>> regAddrInit = new LinkedHashMap<>();   // P -> reg -> var

        // Built structure.
        final EventStructure es = new EventStructure();
        final DependencyInfo deps = new DependencyInfo();
        final LinkedHashSet<String> locations = new LinkedHashSet<>();
        final Map<String, WriteEvent> initOf = new LinkedHashMap<>();
        final Map<String, List<WriteEvent>> programWrites = new LinkedHashMap<>();
        final Map<String, ReadEvent> readByThreadReg = new LinkedHashMap<>();    // "P:reg" -> last read

        Job(String content, String sourceName) {
            this.src = sourceName;
            this.lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        }

        LitmusCase run() {
            extractMetadataComments();
            int cursor = parseHeader();
            cursor = parseInitBlock(cursor);
            List<List<RawInstr>> threads = parseBody(cursor);
            buildEvents(threads);
            wireReadsFrom();
            wireCoherence();
            return new LitmusCase(src, arch, name, es, deps, existsClause,
                    expectations, herdObservation);
        }

        // ── Comments → expectations ───────────────────────────────────────────

        private static final Pattern PER_MODEL = Pattern.compile(
                "(?i)\\b(SC|TSO|PSO|RA|WEAKEST)\\b\\s*[:=]\\s*(allowed|forbidden|yes|no)\\b");
        private static final Pattern OBSERVATION = Pattern.compile(
                "(?i)\\bObservation\\b.*\\b(Always|Sometimes|Never)\\b.*");

        /**
         * Scan comments ({@code # …}, {@code (* … *)}, {@code // …}) for herd7 ground
         * truth: explicit {@code <MODEL>=Allowed|Forbidden} tokens populate the
         * per-model expectation map; a herd {@code Observation … Never|Sometimes|Always}
         * line is kept verbatim as a coarse model-agnostic fallback.
         */
        private void extractMetadataComments() {
            for (String line : lines) {
                String c = commentText(line);
                if (c == null) continue;
                Matcher pm = PER_MODEL.matcher(c);
                while (pm.find()) {
                    MemoryModel m = MemoryModel.valueOf(pm.group(1).toUpperCase(Locale.ROOT));
                    String v = pm.group(2).toLowerCase(Locale.ROOT);
                    Outcome o = (v.equals("allowed") || v.equals("yes"))
                            ? Outcome.ALLOWED : Outcome.FORBIDDEN;
                    expectations.put(m, o);
                }
                if (herdObservation.isEmpty()) {
                    Matcher ob = OBSERVATION.matcher(c);
                    if (ob.find()) herdObservation = ob.group().trim();
                }
            }
        }

        /** The comment payload of a line, or {@code null} if the line is not a comment. */
        private static String commentText(String line) {
            String t = line.strip();
            if (t.startsWith("#")) return t.substring(1).strip();
            if (t.startsWith("//")) return t.substring(2).strip();
            if (t.startsWith("(*")) {
                String body = t.substring(2);
                if (body.endsWith("*)")) body = body.substring(0, body.length() - 2);
                return body.strip();
            }
            return null;
        }

        private static boolean isIgnorable(String line) {
            String t = line.strip();
            return t.isEmpty() || commentText(line) != null;
        }

        // ── Header ────────────────────────────────────────────────────────────

        private int parseHeader() {
            for (int i = 0; i < lines.length; i++) {
                if (isIgnorable(lines[i])) continue;
                String[] tok = lines[i].strip().split("\\s+", 2);
                // Tolerate a leading "TST"/"Litmus"/"Test" keyword before the arch.
                String archTok = tok[0];
                String rest = tok.length > 1 ? tok[1] : "";
                if (archTok.equalsIgnoreCase("TST") || archTok.equalsIgnoreCase("Litmus")
                        || archTok.equalsIgnoreCase("Test")) {
                    String[] t2 = rest.split("\\s+", 2);
                    archTok = t2[0];
                    rest = t2.length > 1 ? t2[1] : "";
                }
                Arch a = Arch.resolve(archTok);
                if (a == null) {
                    throw new ParseException(src, i + 1, ParseException.Kind.UNSUPPORTED_ARCH,
                            "unknown architecture '" + archTok + "'");
                }
                arch = a;
                name = rest.isBlank() ? "(unnamed)" : rest.strip();
                return i + 1;
            }
            throw new ParseException(src, -1, ParseException.Kind.EMPTY,
                    "no header line (file is empty or all comments)");
        }

        // ── Initial-state block ─────────────────────────────────────────────────

        private static final Pattern INIT_REG = Pattern.compile(
                "(\\d+)\\s*:\\s*([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*|-?\\d+|0x[0-9A-Fa-f]+)");
        private static final Pattern INIT_MEM = Pattern.compile(
                "([A-Za-z_]\\w*)\\s*=\\s*(-?\\d+|0x[0-9A-Fa-f]+)");

        /**
         * Optional metadata between the header and the init block, as emitted by herd7's
         * diy/diycross generators: a quoted one-line description and {@code KEY=value}
         * annotation lines ({@code Cycle=}, {@code Com=}, {@code Orig=}, {@code Prefetch=},
         * {@code Generator=}, {@code Relax=}, {@code Safe=}, …). These are not comments,
         * so {@link #isIgnorable} does not catch them; they are skipped only here, before
         * the {@code {} block, and never confused with it (a line opening with {@code &#123;}
         * is never treated as metadata) nor with the {@code x=0;} assignments inside it.
         */
        private static boolean isPreInitMetadata(String line) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("{")) return false;
            if (t.startsWith("\"")) return true;                 // quoted description
            return t.matches("[A-Za-z_][\\w.]*\\s*=.*");          // KEY=value annotation
        }

        /** Parse {@code { … }}; returns the index of the line after the closing brace. */
        private int parseInitBlock(int from) {
            int i = from;
            while (i < lines.length && (isIgnorable(lines[i]) || isPreInitMetadata(lines[i]))) i++;
            if (i >= lines.length) {
                throw new ParseException(src, -1, ParseException.Kind.MALFORMED,
                        "missing initial-state block");
            }
            int openLine = i;
            int open = lines[i].indexOf('{');
            if (open < 0) {
                throw new ParseException(src, i + 1, ParseException.Kind.MALFORMED,
                        "expected '{' to begin the initial-state block");
            }
            StringBuilder body = new StringBuilder();
            int close = -1;
            int scan = i;
            int startCol = open + 1;
            while (scan < lines.length) {
                String line = lines[scan];
                int c = (scan == i) ? line.indexOf('}', startCol) : line.indexOf('}');
                int s = (scan == i) ? startCol : 0;
                if (c >= 0) {
                    body.append(line, s, c).append(' ');
                    close = scan;
                    break;
                }
                body.append(line.substring(s)).append(' ');
                scan++;
            }
            if (close < 0) {
                throw new ParseException(src, openLine + 1, ParseException.Kind.MALFORMED,
                        "unterminated initial-state block (no closing '}')");
            }
            parseInitAssignments(body.toString());
            return close + 1;
        }

        private void parseInitAssignments(String body) {
            for (String stmt : body.split(";")) {
                String s = stmt.strip();
                if (s.isEmpty()) continue;
                Matcher reg = INIT_REG.matcher(s);
                if (reg.matches()) {
                    int p = Integer.parseInt(reg.group(1));
                    String r = reg.group(2);
                    String val = reg.group(3);
                    if (val.matches("-?\\d+|0x[0-9A-Fa-f]+")) {
                        regConstInit.computeIfAbsent(p, k -> new LinkedHashMap<>())
                                .put(r, parseIntLit(val));
                    } else {
                        regAddrInit.computeIfAbsent(p, k -> new LinkedHashMap<>()).put(r, val);
                    }
                    continue;
                }
                Matcher mem = INIT_MEM.matcher(s);
                if (mem.matches()) {
                    initValues.put(mem.group(1), parseIntLit(mem.group(2)));
                }
                // Anything else in the init block (type decls like `int *x`) is ignored.
            }
        }

        // ── Body: split into per-thread instruction lists ─────────────────────────

        /** A raw instruction cell with the source line it began on. */
        private record RawInstr(String text, int line) { }

        private static final Pattern FUNC_HEADER =
                Pattern.compile("\\bP\\d+\\s*\\([^)]*\\)\\s*\\{");

        private List<List<RawInstr>> parseBody(int from) {
            int i = from;
            while (i < lines.length && isIgnorable(lines[i])) i++;
            // Find where the body ends: the exists/forall/observation clause.
            int end = lines.length;
            for (int k = i; k < lines.length; k++) {
                String t = lines[k].strip();
                if (t.startsWith("exists") || t.startsWith("~exists")
                        || t.startsWith("forall") || t.startsWith("locations")
                        || t.startsWith("filter") || t.startsWith("final")) {
                    end = k;
                    break;
                }
            }
            captureExists(end);

            String bodyText = joinLines(i, end);
            List<List<RawInstr>> threads = FUNC_HEADER.matcher(bodyText).find()
                    ? parseFunctionBody(i, end)
                    : parseColumnBody(i, end);
            if (threads.isEmpty()) {
                throw new ParseException(src, i + 1, ParseException.Kind.MALFORMED,
                        "no thread instructions found");
            }
            return threads;
        }

        private void captureExists(int existsLine) {
            if (existsLine >= lines.length) return;
            StringBuilder sb = new StringBuilder();
            for (int k = existsLine; k < lines.length; k++) {
                if (commentText(lines[k]) != null) continue;
                sb.append(lines[k].strip()).append(' ');
            }
            existsClause = sb.toString().strip();
        }

        // Column layout: `P0 | P1 ;` proc header, then `instr | instr ;` rows.
        private List<List<RawInstr>> parseColumnBody(int from, int to) {
            List<RawInstr> rows = collectRows(from, to);
            if (rows.isEmpty()) return List.of();
            // First row is the proc header: cells like P0, P1, ...
            RawInstr header = rows.get(0);
            String[] hcells = header.text().split("\\|", -1);
            int nThreads = hcells.length;
            boolean looksLikeHeader = true;
            for (String hc : hcells) {
                if (!hc.strip().matches("(?i)P\\d+")) { looksLikeHeader = false; break; }
            }
            if (!looksLikeHeader) {
                throw new ParseException(src, header.line(), ParseException.Kind.MALFORMED,
                        "expected a 'P0 | P1 | …' proc header, got: " + header.text());
            }
            List<List<RawInstr>> threads = new ArrayList<>();
            for (int t = 0; t < nThreads; t++) threads.add(new ArrayList<>());
            for (int r = 1; r < rows.size(); r++) {
                RawInstr row = rows.get(r);
                String[] cells = row.text().split("\\|", -1);
                for (int t = 0; t < nThreads; t++) {
                    String cell = (t < cells.length) ? cells[t].strip() : "";
                    if (!cell.isEmpty()) threads.get(t).add(new RawInstr(cell, row.line()));
                }
            }
            return threads;
        }

        /** Accumulate physical lines into {@code ;}-terminated rows, tracking start line. */
        private List<RawInstr> collectRows(int from, int to) {
            List<RawInstr> rows = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            int startLine = -1;
            for (int k = from; k < to; k++) {
                if (isIgnorable(lines[k])) continue;
                String text = stripInlineComment(lines[k]);
                int idx = 0;
                while (idx < text.length()) {
                    int semi = text.indexOf(';', idx);
                    if (startLine < 0) startLine = k + 1;
                    if (semi < 0) {
                        cur.append(text.substring(idx)).append(' ');
                        idx = text.length();
                    } else {
                        cur.append(text, idx, semi);
                        String rowText = cur.toString().strip();
                        if (!rowText.isEmpty()) rows.add(new RawInstr(rowText, startLine));
                        cur.setLength(0);
                        startLine = -1;
                        idx = semi + 1;
                    }
                }
            }
            String tail = cur.toString().strip();
            if (!tail.isEmpty()) rows.add(new RawInstr(tail, startLine < 0 ? from + 1 : startLine));
            return rows;
        }

        // Function layout (C): Pn(args){ stmt; stmt; }
        private List<List<RawInstr>> parseFunctionBody(int from, int to) {
            String body = joinLines(from, to);
            List<List<RawInstr>> threads = new ArrayList<>();
            Matcher m = Pattern.compile("P(\\d+)\\s*\\([^)]*\\)\\s*\\{").matcher(body);
            int searchFrom = 0;
            while (m.find(searchFrom)) {
                int braceOpen = m.end() - 1;
                int depth = 0, close = -1;
                for (int p = braceOpen; p < body.length(); p++) {
                    char ch = body.charAt(p);
                    if (ch == '{') depth++;
                    else if (ch == '}') { depth--; if (depth == 0) { close = p; break; } }
                }
                if (close < 0) {
                    throw new ParseException(src, from + 1, ParseException.Kind.MALFORMED,
                            "unterminated function body for P" + m.group(1));
                }
                String inner = body.substring(braceOpen + 1, close);
                List<RawInstr> stmts = new ArrayList<>();
                for (String st : inner.split(";")) {
                    String s = st.strip();
                    if (!s.isEmpty()) stmts.add(new RawInstr(s, from + 1));
                }
                threads.add(stmts);
                searchFrom = close + 1;
            }
            return threads;
        }

        private String joinLines(int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int k = from; k < to; k++) {
                if (isIgnorable(lines[k])) continue;
                sb.append(stripInlineComment(lines[k])).append('\n');
            }
            return sb.toString();
        }

        private static String stripInlineComment(String line) {
            int h = line.indexOf('#');
            int s = line.indexOf("//");
            int cut = line.length();
            if (h >= 0) cut = Math.min(cut, h);
            if (s >= 0) cut = Math.min(cut, s);
            return line.substring(0, cut);
        }

        // ── Event construction (per-thread dataflow) ──────────────────────────────

        private void buildEvents(List<List<RawInstr>> threads) {
            for (int p = 0; p < threads.size(); p++) {
                buildThread(p, threads.get(p));
            }
        }

        private void buildThread(int pIndex, List<RawInstr> rawInstrs) {
            int threadId = pIndex + 1;                       // thread 0 reserved for inits
            Map<String, RegSrc> regEnv = new LinkedHashMap<>();
            Map<String, String> addrEnv = new LinkedHashMap<>();
            // Seed from thread-local init bindings.
            regConstInit.getOrDefault(pIndex, Map.of())
                    .forEach((r, v) -> regEnv.put(r, RegSrc.constant(v)));
            addrEnv.putAll(regAddrInit.getOrDefault(pIndex, Map.of()));

            List<ReadEvent> ctrlReads = new ArrayList<>();
            Event prevMem = null;

            for (RawInstr ri : rawInstrs) {
                Insn in = lex(ri);
                switch (in.kind()) {
                    case NOP, FENCE -> { /* no event in the WEV model */ }
                    case IMM -> regEnv.put(in.reg(), RegSrc.constant(parseIntLit(in.expr())));
                    case ASSIGN -> regEnv.put(in.reg(), deriveReg(in.expr(), regEnv));
                    case BRANCH -> ctrlReads.addAll(
                            readsOf(referencedReads(in.expr(), regEnv)));
                    case LOAD -> {
                        String loc = resolveLoc(in, addrEnv, ri);
                        ReadEvent rd = new ReadEvent(threadId, loc, in.mo(), in.reg());
                        es.addEvent(rd);
                        prevMem = link(prevMem, rd);
                        seenLocation(loc);
                        regEnv.put(in.reg(), RegSrc.ofRead(rd));
                        readByThreadReg.put(pIndex + ":" + in.reg(), rd);
                        addAddrDeps(rd, in, regEnv);
                    }
                    case STORE -> {
                        String loc = resolveLoc(in, addrEnv, ri);
                        int value = evalConst(in.expr(), regEnv);
                        WriteEvent wr = new WriteEvent(threadId, loc, in.mo(), value,
                                in.expr() == null ? Integer.toString(value) : in.expr().strip());
                        es.addEvent(wr);
                        prevMem = link(prevMem, wr);
                        seenLocation(loc);
                        programWrites.computeIfAbsent(loc, k -> new ArrayList<>()).add(wr);
                        addDataDeps(wr, in.expr(), regEnv);
                        addAddrDeps(wr, in, regEnv);
                        for (ReadEvent cr : ctrlReads) deps.addCtrlDep(wr, cr, true);
                    }
                    case RMW -> {
                        String loc = resolveLoc(in, addrEnv, ri);
                        ReadEvent rd = new ReadEvent(threadId, loc, in.mo(), in.reg());
                        es.addEvent(rd);
                        prevMem = link(prevMem, rd);
                        int value = in.expr() == null ? 1 : evalConst(in.expr(), regEnv);
                        WriteEvent wr = new WriteEvent(threadId, loc, in.mo(), value, "rmw");
                        es.addEvent(wr);
                        prevMem = link(prevMem, wr);
                        es.addProgramOrder(rd, wr);
                        seenLocation(loc);
                        programWrites.computeIfAbsent(loc, k -> new ArrayList<>()).add(wr);
                        deps.addDataDep(wr, rd, true);   // value written depends on value read
                        if (in.reg() != null) {
                            regEnv.put(in.reg(), RegSrc.ofRead(rd));
                            readByThreadReg.put(pIndex + ":" + in.reg(), rd);
                        }
                    }
                }
            }
        }

        /** Add a program-order edge from the previous memory access; returns the new one. */
        private Event link(Event prev, Event next) {
            if (prev != null) es.addProgramOrder(prev, next);
            return next;
        }

        private void seenLocation(String loc) {
            if (locations.add(loc)) {
                WriteEvent init = new WriteEvent(0, loc, MemoryOrder.RELAXED,
                        initValues.getOrDefault(loc, 0),
                        Integer.toString(initValues.getOrDefault(loc, 0)));
                es.addEvent(init);
                initOf.put(loc, init);
            }
        }

        private String resolveLoc(Insn in, Map<String, String> addrEnv, RawInstr ri) {
            if (!in.locIsRegister()) return in.loc();
            String bound = addrEnv.get(in.loc());
            if (bound == null) {
                throw new ParseException(src, ri.line(),
                        ParseException.Kind.UNSUPPORTED_INSTRUCTION,
                        "base register '" + in.loc() + "' is not bound to a variable in "
                                + "the initial state: " + ri.text());
            }
            return bound;
        }

        // ── Dependency detection ──────────────────────────────────────────────────

        private void addDataDeps(WriteEvent consumer, String expr, Map<String, RegSrc> env) {
            if (expr == null) return;
            for (Map.Entry<ReadEvent, Boolean> e : referencedReads(expr, env).entrySet()) {
                deps.addDataDep(consumer, e.getKey(), e.getValue());
            }
        }

        private void addAddrDeps(Event consumer, Insn in, Map<String, RegSrc> env) {
            if (in.indexReg() == null) return;
            RegSrc s = env.get(in.indexReg());
            if (s == null) return;
            for (Map.Entry<ReadEvent, Boolean> e : s.reads().entrySet()) {
                deps.addAddrDep(consumer, e.getKey(), e.getValue());
            }
        }

        /**
         * The reads whose value flows into {@code expr}, mapped to whether that flow is
         * semantic. Composes through register assignments: a read reaches the expression
         * as semantic only if it is a semantic input to its register <em>and</em> that
         * register is used non-trivially in the expression (identity idioms cancel it).
         */
        private Map<ReadEvent, Boolean> referencedReads(String expr, Map<String, RegSrc> env) {
            Map<ReadEvent, Boolean> acc = new LinkedHashMap<>();
            for (String reg : identifiers(expr)) {
                RegSrc s = env.get(reg);
                if (s == null) continue;
                boolean useReal = dependsReally(expr, reg);
                for (Map.Entry<ReadEvent, Boolean> e : s.reads().entrySet()) {
                    boolean sem = useReal && e.getValue();
                    acc.merge(e.getKey(), sem, Boolean::logicalOr);
                }
            }
            return acc;
        }

        private RegSrc deriveReg(String expr, Map<String, RegSrc> env) {
            Map<ReadEvent, Boolean> reads = referencedReads(expr, env);
            Integer constant = tryEval(expr, env);
            return new RegSrc(constant, reads);
        }

        private static List<ReadEvent> readsOf(Map<ReadEvent, Boolean> m) {
            return new ArrayList<>(m.keySet());
        }

        // ── rf / co wiring ─────────────────────────────────────────────────────────

        private static final Pattern EXISTS_REG = Pattern.compile(
                "(\\d+)\\s*:\\s*([A-Za-z_]\\w*)\\s*=\\s*(-?\\d+|0x[0-9A-Fa-f]+)");

        private void wireReadsFrom() {
            Map<String, Integer> required = new LinkedHashMap<>();
            Matcher m = EXISTS_REG.matcher(existsClause);
            while (m.find()) {
                required.put(m.group(1) + ":" + m.group(2), parseIntLit(m.group(3)));
            }
            for (Event e : es.getEvents()) {
                if (!(e instanceof ReadEvent rd)) continue;
                Integer want = requiredValueFor(rd, required);
                WriteEvent target = chooseWrite(rd.getVariable(), want == null ? 0 : want);
                es.addReadsFrom(rd, target);
            }
        }

        /** The exists-required value for a read, found by its (thread, register) key. */
        private Integer requiredValueFor(ReadEvent rd, Map<String, Integer> required) {
            for (Map.Entry<String, ReadEvent> e : readByThreadReg.entrySet()) {
                if (e.getValue() == rd) return required.get(e.getKey());
            }
            return null;
        }

        /**
         * The write a read of value {@code want} at {@code loc} reads-from: a write of
         * that value, preferring the initial write (so value-0 reads see the init), else
         * the lowest-id matching program write; falling back to the init if none match.
         */
        private WriteEvent chooseWrite(String loc, int want) {
            WriteEvent init = initOf.get(loc);
            if (init != null && init.getValue() == want) return init;
            WriteEvent best = null;
            for (WriteEvent w : programWrites.getOrDefault(loc, List.of())) {
                if (w.getValue() == want && (best == null || w.getId() < best.getId())) best = w;
            }
            if (best != null) return best;
            if (init != null) {
                System.err.println("[" + src + "] no write of value " + want + " to '" + loc
                        + "'; defaulting read to the initial write");
                return init;
            }
            // Should not happen: every referenced location gets an init.
            throw new ParseException(src, -1, ParseException.Kind.MALFORMED,
                    "read of location '" + loc + "' that has no write (not even initial)");
        }

        private void wireCoherence() {
            for (String loc : locations) {
                WriteEvent init = initOf.get(loc);
                if (init != null) es.addCoherenceOrder(loc, init);
                for (WriteEvent w : programWrites.getOrDefault(loc, List.of())) {
                    es.addCoherenceOrder(loc, w);
                }
            }
        }

        // ── Per-architecture lexer ────────────────────────────────────────────────

        private Insn lex(RawInstr ri) {
            String text = ri.text().strip();
            if (text.isEmpty() || text.equalsIgnoreCase("nop")) {
                return new Insn(IKind.NOP, null, null, false, null, null, null, text);
            }
            Insn in = switch (arch) {
                case C -> lexC(text, ri);
                case X86 -> lexX86(text, ri);
                case PPC -> lexPPC(text, ri);
                case ARM -> lexARM(text, ri);
                case RISCV -> lexRISCV(text, ri);
            };
            if (in == null) {
                throw new ParseException(src, ri.line(),
                        ParseException.Kind.UNSUPPORTED_INSTRUCTION,
                        "unsupported " + arch + " instruction: " + text);
            }
            return in;
        }

        // C dialect ----------------------------------------------------------------

        private static final Pattern C_WRITE_ONCE = Pattern.compile(
                "(?i)WRITE_ONCE\\s*\\(\\s*\\*?\\s*(\\w+)\\s*,\\s*([^)]+)\\)");
        private static final Pattern C_STORE_REL = Pattern.compile(
                "(?i)smp_store_release\\s*\\(\\s*&?\\s*(\\w+)\\s*,\\s*([^)]+)\\)");
        private static final Pattern C_ATOMIC_STORE = Pattern.compile(
                "(?i)atomic_store(?:_explicit)?\\s*\\(\\s*&?\\s*(\\w+)\\s*,\\s*([^,)]+)\\s*(?:,\\s*(\\w+)\\s*)?\\)");
        private static final Pattern C_DEREF_STORE = Pattern.compile(
                "\\*\\s*(\\w+)\\s*=\\s*(.+)");
        private static final Pattern C_READ_ONCE = Pattern.compile(
                "(?i)(\\w+)\\s*=\\s*READ_ONCE\\s*\\(\\s*\\*?\\s*(\\w+)\\s*\\)");
        private static final Pattern C_LOAD_ACQ = Pattern.compile(
                "(?i)(\\w+)\\s*=\\s*smp_load_acquire\\s*\\(\\s*&?\\s*(\\w+)\\s*\\)");
        private static final Pattern C_ATOMIC_LOAD = Pattern.compile(
                "(?i)(\\w+)\\s*=\\s*atomic_load(?:_explicit)?\\s*\\(\\s*&?\\s*(\\w+)\\s*(?:,\\s*(\\w+)\\s*)?\\)");
        private static final Pattern C_DEREF_LOAD = Pattern.compile(
                "(\\w+)\\s*=\\s*\\*\\s*(\\w+)");
        private static final Pattern C_FENCE = Pattern.compile(
                "(?i)(?:atomic_thread_fence|atomic_fence)\\s*\\(\\s*(\\w+)\\s*\\)");
        private static final Pattern C_RMW = Pattern.compile(
                "(?i)(\\w+)\\s*=\\s*atomic_(?:exchange|fetch_add|fetch_sub|fetch_or|fetch_and|"
                        + "compare_exchange_strong|compare_exchange_weak)(?:_explicit)?"
                        + "\\s*\\(\\s*&?\\s*(\\w+)\\s*,.*\\)");
        private static final Pattern C_ASSIGN = Pattern.compile(
                "([A-Za-z_]\\w*)\\s*=\\s*(.+)");

        private Insn lexC(String t, RawInstr ri) {
            Matcher m;
            if ((m = C_WRITE_ONCE.matcher(t)).find()) {
                return store(m.group(1), m.group(2), MemoryOrder.RELAXED, t);
            }
            if ((m = C_STORE_REL.matcher(t)).find()) {
                return store(m.group(1), m.group(2), MemoryOrder.RELEASE, t);
            }
            if ((m = C_ATOMIC_STORE.matcher(t)).find()) {
                MemoryOrder mo = m.group(3) == null ? MemoryOrder.SC : moFromToken(m.group(3));
                return store(m.group(1), m.group(2), mo, t);
            }
            if ((m = C_RMW.matcher(t)).find()) {
                return new Insn(IKind.RMW, m.group(1), m.group(2), false, null, null,
                        MemoryOrder.SC, t);
            }
            if ((m = C_READ_ONCE.matcher(t)).find()) {
                return load(m.group(1), m.group(2), MemoryOrder.RELAXED, t);
            }
            if ((m = C_LOAD_ACQ.matcher(t)).find()) {
                return load(m.group(1), m.group(2), MemoryOrder.ACQUIRE, t);
            }
            if ((m = C_ATOMIC_LOAD.matcher(t)).find()) {
                MemoryOrder mo = m.group(3) == null ? MemoryOrder.SC : moFromToken(m.group(3));
                return load(m.group(1), m.group(2), mo, t);
            }
            if ((m = C_DEREF_LOAD.matcher(t)).matches()) {
                return load(m.group(1), m.group(2), MemoryOrder.RELAXED, t);
            }
            if ((m = C_FENCE.matcher(t)).find()) {
                return new Insn(IKind.FENCE, null, null, false, null, null,
                        moFromToken(m.group(1)), t);
            }
            if (t.matches("(?i)smp_mb\\s*\\(\\s*\\).*") || t.matches("(?i)smp_[rw]mb\\s*\\(\\s*\\).*")) {
                return new Insn(IKind.FENCE, null, null, false, null, null, MemoryOrder.SC, t);
            }
            if ((m = C_DEREF_STORE.matcher(t)).matches()) {
                return store(m.group(1), m.group(2), MemoryOrder.RELAXED, t);
            }
            if ((m = C_ASSIGN.matcher(t)).matches()) {
                return new Insn(IKind.ASSIGN, m.group(1), null, false, null, m.group(2),
                        null, t);
            }
            if (t.matches("(?i)(if|while|for)\\b.*")) {
                // A control construct: feed any mentioned registers to ctrl dependencies.
                return new Insn(IKind.BRANCH, null, null, false, null, t, null, t);
            }
            return null;
        }

        // x86 (Intel-style operands, as herd7 emits) ---------------------------------

        private Insn lexX86(String t, RawInstr ri) {
            String s = t.replaceFirst("(?i)^lock\\s+", "");
            String[] head = s.split("\\s+", 2);
            String op = head[0].toUpperCase(Locale.ROOT);
            String rest = head.length > 1 ? head[1] : "";
            List<String> ops = splitOperands(rest);
            switch (op) {
                case "MFENCE", "LFENCE", "SFENCE" ->
                        { return new Insn(IKind.FENCE, null, null, false, null, null,
                                MemoryOrder.SC, t); }
                case "MOV" -> {
                    if (ops.size() != 2) return null;
                    String dst = ops.get(0), src = ops.get(1);
                    if (isMem(dst)) return store(memBase(dst), immOrReg(src),
                            MemoryOrder.RELAXED, t, memIndex(dst));
                    if (isMem(src)) return load(dst, memBase(src), MemoryOrder.RELAXED, t,
                            memIndex(src));
                    if (isImm(src)) return new Insn(IKind.IMM, dst, null, false, null,
                            stripImm(src), null, t);
                    return new Insn(IKind.ASSIGN, dst, null, false, null, src, null, t);
                }
                case "XCHG" -> {
                    if (ops.size() != 2) return null;
                    String a = ops.get(0), b = ops.get(1);
                    if (isMem(a)) return rmw(b, memBase(a), t);
                    if (isMem(b)) return rmw(a, memBase(b), t);
                    return null;
                }
                case "XADD", "CMPXCHG" -> {
                    if (ops.size() != 2) return null;
                    if (isMem(ops.get(0))) return rmw(ops.get(1), memBase(ops.get(0)), t);
                    if (isMem(ops.get(1))) return rmw(ops.get(0), memBase(ops.get(1)), t);
                    return null;
                }
                case "ADD", "SUB", "XOR", "AND", "OR" -> {
                    if (ops.size() != 2 || isMem(ops.get(0))) return null;
                    String dst = ops.get(0);
                    String expr = dst + opSym(op) + immOrReg(ops.get(1));
                    return new Insn(IKind.ASSIGN, dst, null, false, null, expr, null, t);
                }
                case "INC", "DEC" -> {
                    if (ops.isEmpty() || isMem(ops.get(0))) return null;
                    String dst = ops.get(0);
                    return new Insn(IKind.ASSIGN, dst, null, false, null,
                            dst + (op.equals("INC") ? "+1" : "-1"), null, t);
                }
                case "CMP", "TEST" ->
                        { return new Insn(IKind.BRANCH, null, null, false, null, rest, null, t); }
                default -> {
                    if (op.startsWith("J")) {            // JMP/JE/JNE/… — ctrl handled by CMP
                        return new Insn(IKind.NOP, null, null, false, null, null, null, t);
                    }
                    return null;
                }
            }
        }

        // PPC / ARM / RISCV share a register-addressed load/store/fence shape ----------

        private Insn lexPPC(String t, RawInstr ri) {
            return lexRegisterAsm(t, PPC_LEX);
        }

        private Insn lexARM(String t, RawInstr ri) {
            return lexRegisterAsm(t, ARM_LEX);
        }

        private Insn lexRISCV(String t, RawInstr ri) {
            return lexRegisterAsm(t, RISCV_LEX);
        }

        private Insn lexRegisterAsm(String t, AsmLexicon lex) {
            String[] head = t.split("[\\s,]+", 2);
            String op = head[0].toLowerCase(Locale.ROOT);
            String rest = head.length > 1 ? head[1] : "";
            List<String> ops = splitOperands(rest);
            if (lex.fences.contains(op)) {
                return new Insn(IKind.FENCE, null, null, false, null, null, MemoryOrder.SC, t);
            }
            MemoryOrder mo = lex.orderOf(op);
            if (lex.isLoadImm(op) && ops.size() >= 2) {                // li/mov reg, #imm
                String dst = ops.get(0);
                String v = stripImm(ops.get(1));
                if (isIntLit(v)) return new Insn(IKind.IMM, dst, null, false, null, v, null, t);
                return new Insn(IKind.ASSIGN, dst, null, false, null, ops.get(1), null, t);
            }
            if (lex.isStore(op) && ops.size() >= 2) {                  // st rS, mem(rB)
                MemRef ref = lex.memRef(ops);
                if (ref == null) return null;
                return new Insn(IKind.STORE, null, ref.base, true, ref.index, ops.get(0), mo, t);
            }
            if (lex.isLoad(op) && ops.size() >= 2) {                   // ld rD, mem(rB)
                MemRef ref = lex.memRef(ops);
                if (ref == null) return null;
                return new Insn(IKind.LOAD, ops.get(0), ref.base, true, ref.index, null, mo, t);
            }
            if (lex.isRmw(op) && ops.size() >= 2) {
                MemRef ref = lex.memRef(ops);
                if (ref == null) return null;
                return new Insn(IKind.RMW, ops.get(0), ref.base, true, null, null, mo, t);
            }
            if (lex.isArith(op) && ops.size() >= 2) {                  // add/xor/… reg, a, b
                String dst = ops.get(0);
                String expr = ops.size() >= 3
                        ? ops.get(1) + opSym(op) + ops.get(2)
                        : dst + opSym(op) + ops.get(1);
                return new Insn(IKind.ASSIGN, dst, null, false, null, expr, null, t);
            }
            if (lex.isBranch(op)) {
                return new Insn(IKind.BRANCH, null, null, false, null, rest, null, t);
            }
            if (lex.isNop(op)) {
                return new Insn(IKind.NOP, null, null, false, null, null, null, t);
            }
            return null;
        }

        // ── Operand helpers ─────────────────────────────────────────────────────────

        private Insn store(String loc, String expr, MemoryOrder mo, String raw) {
            return store(loc, expr, mo, raw, null);
        }

        private Insn store(String loc, String expr, MemoryOrder mo, String raw, String index) {
            return new Insn(IKind.STORE, null, loc, false, index, expr, mo, raw);
        }

        private Insn load(String reg, String loc, MemoryOrder mo, String raw) {
            return load(reg, loc, mo, raw, null);
        }

        private Insn load(String reg, String loc, MemoryOrder mo, String raw, String index) {
            return new Insn(IKind.LOAD, reg, loc, false, index, null, mo, raw);
        }

        private Insn rmw(String reg, String loc, String raw) {
            return new Insn(IKind.RMW, reg, loc, false, null, null, MemoryOrder.SC, raw);
        }

        private static List<String> splitOperands(String rest) {
            List<String> out = new ArrayList<>();
            if (rest == null || rest.isBlank()) return out;
            for (String o : rest.split(",")) {
                String s = o.strip();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }

        private static boolean isMem(String op) { return op.contains("["); }

        private static String memBase(String op) {
            int l = op.indexOf('['), r = op.indexOf(']');
            String inner = (l >= 0 && r > l) ? op.substring(l + 1, r) : op;
            // base is the first identifier; an index register (if any) follows a + or , .
            Matcher m = Pattern.compile("[A-Za-z_]\\w*").matcher(inner);
            return m.find() ? m.group() : inner.strip();
        }

        private static String memIndex(String op) {
            int l = op.indexOf('['), r = op.indexOf(']');
            String inner = (l >= 0 && r > l) ? op.substring(l + 1, r) : op;
            Matcher m = Pattern.compile("[A-Za-z_]\\w*").matcher(inner);
            if (m.find() && m.find()) return m.group();   // a second identifier ⇒ index reg
            return null;
        }

        private static boolean isImm(String op) {
            return op.startsWith("$") || op.startsWith("#") || isIntLit(op);
        }

        private static String stripImm(String op) {
            String s = op.strip();
            if (s.startsWith("$") || s.startsWith("#")) s = s.substring(1);
            return s.strip();
        }

        private static String immOrReg(String op) {
            return isImm(op) ? stripImm(op) : op.strip();
        }

        private static String opSym(String op) {
            return switch (op.toUpperCase(Locale.ROOT)) {
                case "ADD", "ADDI", "ADDU" -> "+";
                case "SUB", "SUBF", "SUBU" -> "-";
                case "XOR", "EOR" -> "^";
                case "AND" -> "&";
                case "OR", "ORR" -> "|";
                default -> "+";
            };
        }

        // ── Memory-order tokens ─────────────────────────────────────────────────────

        private static MemoryOrder moFromToken(String tok) {
            String s = tok.toLowerCase(Locale.ROOT);
            if (s.contains("relaxed")) return MemoryOrder.RELAXED;
            if (s.contains("acquire") || s.contains("consume")) return MemoryOrder.ACQUIRE;
            if (s.contains("release")) return MemoryOrder.RELEASE;
            // seq_cst, acq_rel, and anything unrecognised → strongest we model.
            return MemoryOrder.SC;
        }

        // ── Identifier / dependency heuristics ───────────────────────────────────────

        private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

        private static List<String> identifiers(String expr) {
            List<String> out = new ArrayList<>();
            if (expr == null) return out;
            Matcher m = IDENT.matcher(expr);
            while (m.find()) if (!out.contains(m.group())) out.add(m.group());
            return out;
        }

        /**
         * Whether {@code expr} genuinely depends on register {@code reg} — i.e. its value
         * varies with {@code reg}. Self-cancelling identity idioms ({@code r^r}, {@code
         * r-r}, {@code r&~r}, {@code r|~r}, {@code r*0}, {@code r&0}) are folded to
         * constants first; if {@code reg} no longer appears, the dependency is fake.
         */
        private static boolean dependsReally(String expr, String reg) {
            String e = expr.replaceAll("\\s+", "");
            String R = Pattern.quote(reg);
            String[] toZero = {
                    R + "\\^" + R, R + "-" + R, R + "&~" + R, "~" + R + "&" + R,
                    R + "\\*0", "0\\*" + R, R + "&0", "0&" + R
            };
            String[] toOnes = { R + "\\|~" + R, "~" + R + "\\|" + R };
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String z : toZero) {
                    String n = e.replaceAll(bounded(z), "0");
                    if (!n.equals(e)) { e = n; changed = true; }
                }
                for (String a : toOnes) {
                    String n = e.replaceAll(bounded(a), "1");
                    if (!n.equals(e)) { e = n; changed = true; }
                }
            }
            return Pattern.compile("(?<![A-Za-z0-9_])" + R + "(?![A-Za-z0-9_])").matcher(e).find();
        }

        private static String bounded(String p) {
            return "(?<![A-Za-z0-9_])(?:" + p + ")(?![A-Za-z0-9_])";
        }

        // ── Constant evaluation (for write values; substitutes loads as 0) ────────────

        private int evalConst(String expr, Map<String, RegSrc> env) {
            Integer v = tryEval(expr, env);
            return v == null ? 1 : v;     // data-dependent writes: a deterministic placeholder
        }

        private Integer tryEval(String expr, Map<String, RegSrc> env) {
            if (expr == null) return null;
            try {
                return new ExprEval(expr.replaceAll("\\s+", ""), env).parse();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private static int parseIntLit(String s) {
            String t = s.strip();
            if (t.startsWith("$") || t.startsWith("#")) t = t.substring(1);
            if (t.startsWith("0x") || t.startsWith("0X")) return Integer.parseInt(t.substring(2), 16);
            return Integer.parseInt(t);
        }

        private static boolean isIntLit(String s) {
            String t = stripImm(s);
            return t.matches("-?\\d+|0x[0-9A-Fa-f]+");
        }

        /** A tiny integer expression evaluator over {@code + - * ^ & | ~ ( )}. */
        private final class ExprEval {
            final String s;
            final Map<String, RegSrc> env;
            int pos;

            ExprEval(String s, Map<String, RegSrc> env) { this.s = s; this.env = env; }

            int parse() {
                int v = parseOr();
                if (pos != s.length()) throw new IllegalStateException("trailing input");
                return v;
            }

            int parseOr() {
                int v = parseXor();
                while (peek('|')) { pos++; v |= parseXor(); }
                return v;
            }

            int parseXor() {
                int v = parseAnd();
                while (peek('^')) { pos++; v ^= parseAnd(); }
                return v;
            }

            int parseAnd() {
                int v = parseAdd();
                while (peek('&')) { pos++; v &= parseAdd(); }
                return v;
            }

            int parseAdd() {
                int v = parseMul();
                while (peek('+') || peek('-')) {
                    char c = s.charAt(pos++);
                    int rhs = parseMul();
                    v = (c == '+') ? v + rhs : v - rhs;
                }
                return v;
            }

            int parseMul() {
                int v = parseUnary();
                while (peek('*')) { pos++; v *= parseUnary(); }
                return v;
            }

            int parseUnary() {
                if (peek('~')) { pos++; return ~parseUnary(); }
                if (peek('-')) { pos++; return -parseUnary(); }
                return parsePrimary();
            }

            int parsePrimary() {
                if (peek('(')) {
                    pos++;
                    int v = parseOr();
                    if (!peek(')')) throw new IllegalStateException("expected )");
                    pos++;
                    return v;
                }
                int start = pos;
                if (peek('0') && pos + 1 < s.length() && (s.charAt(pos + 1) == 'x' || s.charAt(pos + 1) == 'X')) {
                    pos += 2;
                    int h = pos;
                    while (pos < s.length() && isHex(s.charAt(pos))) pos++;
                    return Integer.parseInt(s.substring(h, pos), 16);
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                if (pos > start) return Integer.parseInt(s.substring(start, pos));
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
                if (pos > start) {
                    String id = s.substring(start, pos);
                    RegSrc rs = env.get(id);
                    return (rs != null && rs.constant() != null) ? rs.constant() : 0;
                }
                throw new IllegalStateException("unexpected char at " + pos);
            }

            boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }
            boolean isHex(char c) {
                return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            }
        }
    }

    // ── Per-assembly-dialect lexicons ─────────────────────────────────────────────

    private record MemRef(String base, String index) { }

    /** The mnemonic vocabulary of one assembly dialect (PPC / ARM / RISCV). */
    private record AsmLexicon(
            java.util.Set<String> loadImm, java.util.Set<String> loads,
            java.util.Set<String> stores, java.util.Set<String> fences,
            java.util.Set<String> rmws, java.util.Set<String> arith,
            java.util.Set<String> branches, java.util.Set<String> nops,
            java.util.Set<String> acquireOps, java.util.Set<String> releaseOps) {

        boolean isLoadImm(String op) { return loadImm.contains(op); }
        boolean isLoad(String op) { return loads.contains(op); }
        boolean isStore(String op) { return stores.contains(op); }
        boolean isRmw(String op) { return rmws.contains(op); }
        boolean isArith(String op) { return arith.contains(op); }
        boolean isBranch(String op) { return branches.contains(op); }
        boolean isNop(String op) { return nops.contains(op); }

        MemoryOrder orderOf(String op) {
            if (acquireOps.contains(op)) return MemoryOrder.ACQUIRE;
            if (releaseOps.contains(op)) return MemoryOrder.RELEASE;
            return MemoryOrder.RELAXED;
        }

        /**
         * The memory reference of a load/store/rmw: the base register and an optional
         * index register. Handles {@code disp(rB)} (PPC/RISCV) and {@code [Xn]} /
         * {@code [Xn, Xm]} (ARM), with the destination/source register at operand 0.
         */
        MemRef memRef(List<String> ops) {
            for (int i = 1; i < ops.size(); i++) {
                String o = ops.get(i);
                Matcher disp = Pattern.compile("\\w*\\(\\s*([A-Za-z_]\\w*)\\s*\\)").matcher(o);
                if (disp.find()) return new MemRef(disp.group(1), null);
                String inner = o;
                int lb = o.indexOf('['), rb = o.indexOf(']');
                if (lb >= 0 && rb > lb) inner = o.substring(lb + 1, rb);
                Matcher id = Pattern.compile("[A-Za-z_]\\w*").matcher(inner);
                if (id.find()) {
                    String base = id.group();
                    String index = id.find() ? id.group() : null;
                    return new MemRef(base, index);
                }
            }
            return null;
        }
    }

    private static AsmLexicon lexicon(String[][] groups) {
        return new AsmLexicon(
                set(groups[0]), set(groups[1]), set(groups[2]), set(groups[3]),
                set(groups[4]), set(groups[5]), set(groups[6]), set(groups[7]),
                set(groups[8]), set(groups[9]));
    }

    private static java.util.Set<String> set(String[] a) {
        return new java.util.LinkedHashSet<>(List.of(a));
    }

    // groups order: loadImm, loads, stores, fences, rmws, arith, branches, nops, acquire, release
    private static final AsmLexicon PPC_LEX = lexicon(new String[][]{
            {"li", "lis"},
            {"lwz", "ld", "lbz", "lhz", "lwa"},
            {"stw", "std", "stb", "sth"},
            {"sync", "lwsync", "hwsync", "isync", "eieio"},
            {"lwarx", "stwcx.", "ldarx", "stdcx."},
            {"add", "addi", "subf", "xor", "and", "or", "mr", "neg"},
            {"cmpw", "cmpwi", "cmpd", "beq", "bne", "blt", "bgt", "b"},
            {"nop"},
            {}, {}
    });

    private static final AsmLexicon ARM_LEX = lexicon(new String[][]{
            {"mov", "movz", "movw"},
            {"ldr", "ldar", "ldapr", "ldrb", "ldrh", "ldaprb"},
            {"str", "stlr", "strb", "strh"},
            {"dmb", "dsb", "isb"},
            {"swp", "ldadd", "cas", "casa", "casal", "ldaddal"},
            {"add", "sub", "eor", "and", "orr", "mvn"},
            {"cmp", "cbz", "cbnz", "b", "beq", "bne", "tst"},
            {"nop"},
            {"ldar", "ldapr", "ldaprb", "casa", "casal", "ldaddal"},
            {"stlr"}
    });

    private static final AsmLexicon RISCV_LEX = lexicon(new String[][]{
            {"li", "lui"},
            {"lw", "ld", "lb", "lh", "lr.w", "lr.d"},
            {"sw", "sd", "sb", "sh", "sc.w", "sc.d"},
            {"fence", "fence.i"},
            {"amoswap.w", "amoadd.w", "amoswap.d", "amoadd.d"},
            {"add", "addi", "sub", "xor", "and", "or", "mv", "neg"},
            {"beq", "bne", "blt", "bge", "j", "jal"},
            {"nop"},
            {}, {}
    });
}
