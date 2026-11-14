package wev.smt.parse;

import com.weakest.model.Event;
import com.weakest.model.EventStructure;
import com.weakest.model.FenceEvent;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.RMWEvent;
import com.weakest.model.WriteEvent;

import wev.smt.DependencyInfo;
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;
import wev.smt.validate.InputValidator;
import wev.smt.validate.InvalidEventStructureException;
import wev.smt.validate.ValidationReport;

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
import java.util.Set;
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
 * order. As of Day 12, fences become {@link FenceEvent}s (kind mapped from the mnemonic
 * / memory order) and a read-modify-write becomes a single atomic {@link RMWEvent}
 * (read+write at one event id; x86 LOCK / seq_cst forms order like a full fence); the
 * consistency layer encodes both. Anything outside this subset makes
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

    // ── Detector-quality instrumentation (additive; inert unless a sink is set) ──

    /**
     * One classification decision the fake/semantic detector ({@link Job#dependsReally})
     * makes: for a single register {@code reg} used in {@code expr}, whether that use is a
     * <em>real</em> (value-carrying) dependency, and which idiom {@code pattern} the
     * verdict rests on ({@code "REAL"} when no cancellation fires). {@code producerReadIds}
     * are the ids of the {@link ReadEvent}s whose value reaches {@code expr} through
     * {@code reg} — the producers of any dependency edge this decision governs.
     *
     * <p>Emitted only when {@link #DEP_TRACE} is installed; the field is inert (and the
     * detector byte-for-byte unchanged) otherwise. Not thread-safe by design — a sweep
     * sets the sink, parses one file, drains it, single-threaded.
     */
    public record DepDecision(String expr, String reg, boolean real, String pattern,
                              List<Integer> producerReadIds, int threadId, int instrIndex) { }

    /** Optional sink for {@link DepDecision}s; {@code null} (the default) disables tracing. */
    public static volatile java.util.function.Consumer<DepDecision> DEP_TRACE = null;

    /**
     * Pluggable exact constancy oracle for the dependency detector. Given a value
     * expression {@code expr} and a register {@code reg} that the O(1) idiom fast-path did
     * <em>not</em> already fold to a constant, it returns {@code TRUE} if {@code expr} is
     * provably <em>constant</em> in {@code reg} (a fake dependency), {@code FALSE} if it
     * provably <em>varies</em> with {@code reg} (a real dependency), or {@code null} if the
     * expression is outside the oracle's modelled grammar — in which case the detector
     * falls back to a conservative <em>semantic</em> classification.
     *
     * <p>{@code null} by default (inert): with no oracle installed the detector uses its
     * solver-free linear-cancellation fallback, so the parser carries no solver dependency
     * for its many callers and the default classification is byte-for-byte unchanged.
     * Installing an oracle (e.g. {@code wev.smt.QfbvConstancyOracle}) makes it the
     * <em>primary</em> decision procedure after the idiom fast-path, so
     * {@code sdep_impl = sdep_true} exactly on the modelled (branch-free bitvector-arithmetic)
     * fragment. Not thread-safe by design — set it, run single-threaded, then clear it.
     */
    @FunctionalInterface
    public interface ConstancyOracle {
        /** {@code TRUE}=constant/fake, {@code FALSE}=varies/real, {@code null}=undecided. */
        Boolean isConstant(String expr, String reg);
    }

    /** Optional exact constancy oracle; {@code null} (default) selects the solver-free path. */
    public static volatile ConstancyOracle CONSTANCY_ORACLE = null;

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

        // AArch64 register identity (see armCanonical): Wn and Xn name one register, keyed
        // Xn everywhere. Per thread: the 64-bit initial value of each constant-initialised
        // register (contradiction check; upper-zero width state), and the names whose initial
        // value is outside the modelled forms (page-table / tag values, arrays) — any use of
        // those is rejected rather than silently read as 0.
        final Map<Integer, Map<String, Long>> armInit64 = new LinkedHashMap<>();
        final Map<Integer, Set<String>> armUnsupportedInitRegs = new LinkedHashMap<>();
        final Set<String> armUnsupportedInitLocs = new LinkedHashSet<>();
        // Width state of the thread being lowered: whether each register's 64-bit value is
        // the zero-extension of the 32-bit value the model tracks. Plus each thread's final
        // register state, which outcome binding resolves exists-clause registers against.
        Map<String, Boolean> armUpperZero = new LinkedHashMap<>();
        int armWidthThread = -1;
        final Map<Integer, Map<String, String>> armFinalSymbolic = new LinkedHashMap<>();
        final Map<Integer, Map<String, Boolean>> armFinalUpperZero = new LinkedHashMap<>();
        final Map<Integer, Set<String>> armFinalAddr = new LinkedHashMap<>();

        // Built structure.
        final EventStructure es = new EventStructure();
        final DependencyInfo deps = new DependencyInfo();
        final LinkedHashSet<String> locations = new LinkedHashSet<>();
        final Map<String, WriteEvent> initOf = new LinkedHashMap<>();
        final Map<String, List<WriteEvent>> programWrites = new LinkedHashMap<>();
        final Map<String, ReadEvent> readByThreadReg = new LinkedHashMap<>();    // "P:reg" -> last read

        // Current site being lowered (for DepDecision tracing / the Layer-2 audit): the
        // WEV thread id and the 0-based instruction index within the thread. Inert unless
        // DEP_TRACE is installed; updated by buildThread as it walks each thread's rows.
        int curThreadId = 0;
        int curInstrIndex = -1;

        // C1 symbolic value model (additive; never changes the folded constant values). Per
        // WriteEvent id, the symbolic value expression as a string over R<readId> variables,
        // integer literals and the QF_BV operators (e.g. "R5+1", "R3^R3^1", "2"); per
        // exists-named ReadEvent id, the value the exists clause pins it to. Consumed only by
        // SymbolicRfEncoder; ignored in wired mode. See parse/SYMBOLIC_RF_DESIGN.md.
        final Map<Integer, String> writeValueExprs = new LinkedHashMap<>();
        final Map<Integer, Integer> existsReadValues = new LinkedHashMap<>();
        // C1: memory-final constraints from the exists clause (loc=val, no thread:reg), for
        // symbolic coherence tests (CoWW/CoWR family) whose outcome pins the co-maximal value.
        final Map<String, Integer> existsMemFinal = new LinkedHashMap<>();

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
            // Day 14: validate the assembled structure before producing the case, so a
            // parser bug that emits e.g. a read with no write surfaces as a clear
            // InvalidEventStructureException rather than a misleading SMT-UNSAT downstream.
            ValidationReport report = InputValidator.validate(es, deps);
            if (!report.valid()) {
                throw new InvalidEventStructureException(
                        "[" + src + "] parsed structure is invalid", report);
            }
            return new LitmusCase(src, arch, name, es, deps, existsClause,
                    expectations, herdObservation, writeValueExprs, existsReadValues,
                    existsMemFinal);
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
                if (arch == Arch.ARM) {
                    armInitStatement(s);
                    continue;
                }
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

        private static final Pattern ARM_SCALAR_TYPE = Pattern.compile(
                "(?:unsigned\\s+int|int32_t|uint32_t|int64_t|uint64_t|unsigned|long|int)\\s+");
        private static final Pattern ARM_INIT_NAMED_REG = Pattern.compile("(\\d+)\\s*:\\s*([A-Za-z_]\\w*)\\s*=");
        private static final Pattern ARM_INIT_NAMED_LOC = Pattern.compile(
                "(?:[A-Za-z_]\\w*\\s+)*\\*?\\s*([A-Za-z_]\\w*)");

        /**
         * One AArch64 initial-state statement. Register names are canonical (Wn/Xn → Xn);
         * scalar type prefixes ({@code int64_t 0:X2=0}, {@code int64_t x=1}) are accepted;
         * a register initialised under both names must agree on its 64-bit value (a W
         * initialiser zero-extends: {@code W3=-1} is {@code 0x00000000FFFFFFFF}, {@code X3=-1}
         * is all ones). Statements whose value the model cannot represent (page-table and
         * tag values, arrays) mark their name as unsupported instead of being ignored.
         */
        private void armInitStatement(String s) {
            String t = s;
            boolean typed = false;
            Matcher ty = ARM_SCALAR_TYPE.matcher(t);
            if (ty.lookingAt()) {
                t = t.substring(ty.end()).strip();
                typed = true;
            }
            Matcher reg = INIT_REG.matcher(t);
            if (reg.matches()) {
                int p = Integer.parseInt(reg.group(1));
                String r = armCanonical(reg.group(2));
                if (r == null) r = reg.group(2);              // not a W/X GPR (e.g. AArch32 R0)
                String val = reg.group(3);
                Map<String, Integer> consts = regConstInit.computeIfAbsent(p, k -> new LinkedHashMap<>());
                Map<String, String> addrs = regAddrInit.computeIfAbsent(p, k -> new LinkedHashMap<>());
                if (val.matches("-?\\d+|0x[0-9A-Fa-f]+")) {
                    Long lv = armLiteral(val);
                    if (lv == null) {                            // wider than 64 bits
                        armUnsupportedInitRegs.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(r);
                        return;
                    }
                    int v = (int) (long) lv;                     // the low 32 bits the model tracks
                    long v64 = reg.group(2).matches("[Ww]\\d+") ? (lv & 0xffffffffL) : lv;
                    Long prev = armInit64.computeIfAbsent(p, k -> new LinkedHashMap<>()).putIfAbsent(r, v64);
                    if ((prev != null && prev != v64) || addrs.containsKey(r)) {
                        throw armUnsupported(-1, "contradictory initial values for register " + r
                                + " of P" + p + " (W/X aliases name one register)", s);
                    }
                    consts.put(r, v);
                } else {
                    String prevAddr = addrs.putIfAbsent(r, val);
                    if ((prevAddr != null && !prevAddr.equals(val)) || consts.containsKey(r)) {
                        throw armUnsupported(-1, "contradictory initial values for register " + r
                                + " of P" + p + " (W/X aliases name one register)", s);
                    }
                }
                return;
            }
            Matcher mem = INIT_MEM.matcher(t);
            if (mem.matches()) {
                // A location holds a 32-bit value in the model; a value that is not one
                // (e.g. int64_t x=0x0000000100000001) cannot be represented.
                Long lv = armLiteral(mem.group(2));
                if (lv == null || lv < Integer.MIN_VALUE || lv > 0xffffffffL) {
                    armUnsupportedInitLocs.add(mem.group(1));
                } else {
                    initValues.put(mem.group(1), (int) (long) lv);
                }
                return;
            }
            if (typed && t.matches("[A-Za-z_]\\w*")) return;            // declaration: value 0
            Matcher ur = ARM_INIT_NAMED_REG.matcher(t);
            if (ur.lookingAt()) {
                String r = armCanonical(ur.group(2));
                armUnsupportedInitRegs.computeIfAbsent(Integer.parseInt(ur.group(1)), k -> new LinkedHashSet<>())
                        .add(r == null ? ur.group(2) : r);
                return;
            }
            Matcher ul = ARM_INIT_NAMED_LOC.matcher(t);
            if (ul.lookingAt()) armUnsupportedInitLocs.add(ul.group(1));
        }

        /** A decimal or hex initial-state literal as a 64-bit value (hex is unsigned), or null if wider. */
        private static Long armLiteral(String s) {
            String t = s.strip();
            boolean neg = t.startsWith("-");
            if (neg) t = t.substring(1);
            try {
                long v = (t.startsWith("0x") || t.startsWith("0X"))
                        ? Long.parseUnsignedLong(t.substring(2), 16) : Long.parseLong(t);
                return neg ? -v : v;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private ParseException armUnsupported(int line, String why, String text) {
            return new ParseException(src, line, ParseException.Kind.UNSUPPORTED_INSTRUCTION,
                    "AArch64 " + why + ": " + text);
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
                    if (s.isEmpty()) continue;
                    for (String piece : splitGuardedStatement(s)) {
                        stmts.add(new RawInstr(piece, from + 1));
                    }
                }
                threads.add(stmts);
                searchFrom = close + 1;
            }
            return threads;
        }

        private static final Pattern ELSE_PREFIX = Pattern.compile("(?i)^else\\b\\s*");
        private static final Pattern IF_PREFIX = Pattern.compile("(?i)^if\\s*\\(");

        /**
         * Split a guarded statement {@code "if (c) STMT"} into the guard {@code "if (c)"}
         * and the guarded statement {@code "STMT"}, and strip a leading {@code else}.
         *
         * <p>Without this, {@link Lexer#lexC} matches {@code C_WRITE_ONCE} with
         * {@code .find()} (a substring search) before it ever reaches the
         * {@code if|while|for} test, so {@code "if (r0==1) WRITE_ONCE(*x,1)"} lexes as a
         * bare store and <em>the guard is silently discarded</em> — no {@code BRANCH}
         * instruction, hence no control edge. Splitting here restores parity with the
         * {@code "if (c) ; STMT"} idiom, which only worked because the empty arm left
         * {@code "if (c)"} as a statement of its own.
         *
         * <p>This is deliberately a lexical repair at the statement boundary: it changes
         * no lexer rule, no dependency policy, and no {@code isSemantic} flag. Control
         * edges remain unconditionally semantic, exactly as before.
         *
         * <p><b>What this does NOT fix.</b> There is still no branch structure: the arms
         * of an {@code if/else} become straight-line statements, so a two-armed branch
         * yields TWO write events where the program performs one. Representing that needs
         * real branch structure in the event model and is out of scope here.
         */
        private static List<String> splitGuardedStatement(String s) {
            List<String> out = new ArrayList<>();
            String rest = s;
            Matcher e = ELSE_PREFIX.matcher(rest);
            if (e.find()) rest = rest.substring(e.end()).strip();
            int g = guardEnd(rest);
            if (g > 0) {
                out.add(rest.substring(0, g).strip());
                String body = rest.substring(g).strip();
                if (!body.isEmpty()) out.add(body);
            } else if (!rest.isEmpty()) {
                out.add(rest);
            }
            return out;
        }

        /**
         * Index just past the closing parenthesis of a leading {@code if (...)}, or -1 if
         * {@code s} does not start with one. Parenthesis matching is balanced, so guards
         * such as {@code if ((r0 & 1) == 1)} are handled.
         */
        private static int guardEnd(String s) {
            Matcher m = IF_PREFIX.matcher(s);
            if (!m.find()) return -1;
            int depth = 0;
            for (int i = m.end() - 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) return i + 1;
            }
            return -1;
        }

        private String joinLines(int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int k = from; k < to; k++) {
                if (isIgnorable(lines[k])) continue;
                sb.append(stripInlineComment(lines[k])).append('\n');
            }
            return sb.toString();
        }

        /**
         * Cut a trailing {@code #…} or {@code //…} comment from a body line.
         *
         * <p>On AArch64 {@code #} is also the immediate prefix ({@code MOV W0,#1},
         * {@code ADD W2,W0,#1}). Treating that {@code #} as a comment cut the rest of the
         * physical line — including the other threads' cells and the {@code ;} row
         * terminator — so rows merged and instructions (stores among them) silently
         * disappeared. A {@code #} immediately followed by an immediate literal (optional
         * sign, then a digit) is therefore kept on ARM; every other {@code #} still starts
         * a comment, and other dialects are unchanged.
         */
        private String stripInlineComment(String line) {
            int h = commentHash(line);
            int s = line.indexOf("//");
            int cut = line.length();
            if (h >= 0) cut = Math.min(cut, h);
            if (s >= 0) cut = Math.min(cut, s);
            return line.substring(0, cut);
        }

        /** Index of the first {@code #} that begins a comment, or -1 if there is none. */
        private int commentHash(String line) {
            for (int i = line.indexOf('#'); i >= 0; i = line.indexOf('#', i + 1)) {
                if (arch == Arch.ARM && isImmediatePrefix(line, i)) continue;
                return i;
            }
            return -1;
        }

        /** Whether the {@code #} at {@code i} prefixes an immediate literal ({@code #1}, {@code #-4}, {@code #0x10}). */
        private static boolean isImmediatePrefix(String line, int i) {
            int j = i + 1;
            if (j < line.length() && (line.charAt(j) == '-' || line.charAt(j) == '+')) j++;
            return j < line.length() && Character.isDigit(line.charAt(j));
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
            // C1: per-register symbolic value string (over R<readId> vars + literals), the
            // dataflow the constant folding erases. Kept in lockstep with regEnv.
            Map<String, String> regSymbolic = new LinkedHashMap<>();
            // Seed from thread-local init bindings.
            regConstInit.getOrDefault(pIndex, Map.of())
                    .forEach((r, v) -> { regEnv.put(r, RegSrc.constant(v));
                                          regSymbolic.put(r, Integer.toString(v)); });
            addrEnv.putAll(regAddrInit.getOrDefault(pIndex, Map.of()));

            List<ReadEvent> ctrlReads = new ArrayList<>();
            Event prevMem = null;

            for (int instrIdx = 0; instrIdx < rawInstrs.size(); instrIdx++) {
                RawInstr ri = rawInstrs.get(instrIdx);
                curThreadId = threadId;
                curInstrIndex = instrIdx;
                Insn in = lex(ri);
                switch (in.kind()) {
                    case NOP -> { /* no event in the WEV model */ }
                    case FENCE -> {
                        // Day 12: fences are now encoded (FenceEvent), in program order.
                        FenceEvent f = new FenceEvent(threadId, fenceKind(in));
                        es.addEvent(f);
                        prevMem = link(prevMem, f);
                    }
                    case IMM -> {
                        regEnv.put(in.reg(), RegSrc.constant(parseIntLit(in.expr())));
                        regSymbolic.put(in.reg(), Integer.toString(parseIntLit(in.expr())));
                    }
                    case ASSIGN -> {
                        regEnv.put(in.reg(), deriveReg(in.expr(), regEnv));
                        regSymbolic.put(in.reg(), substituteSymbolic(in.expr(), regSymbolic));
                    }
                    case BRANCH -> ctrlReads.addAll(
                            readsOf(referencedReads(in.expr(), regEnv)));
                    case LOAD -> {
                        String loc = resolveLoc(in, addrEnv, ri);
                        ReadEvent rd = new ReadEvent(threadId, loc, in.mo(), in.reg());
                        es.addEvent(rd);
                        prevMem = link(prevMem, rd);
                        seenLocation(loc);
                        regEnv.put(in.reg(), RegSrc.ofRead(rd));
                        regSymbolic.put(in.reg(), "R" + rd.getId());   // C1: register holds this read
                        readByThreadReg.put(pIndex + ":" + in.reg(), rd);
                        addAddrDeps(rd, in, regEnv);
                        writeBack(in, addrEnv);
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
                        // C1: retain the symbolic value function (over R<readId>), not just
                        // the folded constant, so SymbolicRfEncoder can propagate real values.
                        writeValueExprs.put(wr.getId(), in.expr() == null
                                ? Integer.toString(value)
                                : substituteSymbolic(in.expr(), regSymbolic));
                        addDataDeps(wr, in.expr(), regEnv);
                        addAddrDeps(wr, in, regEnv);
                        for (ReadEvent cr : ctrlReads) deps.addCtrlDep(wr, cr, true);
                        writeBack(in, addrEnv);
                    }
                    case RMW -> {
                        // Day 12: a single atomic RMWEvent (read+write at one event id),
                        // rather than a decomposed read→write pair. Atomicity is enforced
                        // by AxiomaticConsistency.rmwAtomicity, so no synthetic data dep is
                        // needed. The candidate read consumes the location's initial value;
                        // the write produces a distinct placeholder (oldVal+1). x86 LOCK-ed
                        // and seq_cst RMWs act as a full fence in program order.
                        String loc = resolveLoc(in, addrEnv, ri);
                        int oldV = initValues.getOrDefault(loc, 0);
                        int newV = in.expr() == null ? oldV + 1 : evalConst(in.expr(), regEnv);
                        boolean fullFence = arch == Arch.X86 || in.mo() == MemoryOrder.SC;
                        RMWEvent u = new RMWEvent(threadId, loc, in.mo(), oldV, newV, fullFence);
                        es.addEvent(u);
                        prevMem = link(prevMem, u);
                        seenLocation(loc);
                        programWrites.computeIfAbsent(loc, k -> new ArrayList<>()).add(u);
                        // C1: RMW value functions are not modelled — the written value is a
                        // constant placeholder, so its symbolic value is that literal too.
                        writeValueExprs.put(u.getId(), Integer.toString(newV));
                        addAddrDeps(u, in, regEnv);
                        if (in.reg() != null) {
                            // No separate read event to track; the loaded value flows on as
                            // a constant (RMW-carried dependencies are not modelled).
                            regEnv.put(in.reg(), RegSrc.constant(oldV));
                            regSymbolic.put(in.reg(), Integer.toString(oldV));
                        }
                    }
                }
            }
            if (arch == Arch.ARM) {
                // The thread's final register state, for exists-clause binding (wireReadsFrom).
                if (armWidthThread != pIndex) armEnterThread(pIndex);
                armFinalSymbolic.put(pIndex, new LinkedHashMap<>(regSymbolic));
                armFinalUpperZero.put(pIndex, new LinkedHashMap<>(armUpperZero));
                armFinalAddr.put(pIndex, new LinkedHashSet<>(addrEnv.keySet()));
            }
        }

        /** Add a program-order edge from the previous memory access; returns the new one. */
        private Event link(Event prev, Event next) {
            if (prev != null) es.addProgramOrder(prev, next);
            return next;
        }

        private void seenLocation(String loc) {
            if (arch == Arch.ARM && armUnsupportedInitLocs.contains(loc)) {
                throw armUnsupported(-1, "access to location '" + loc
                        + "' whose initial value is not a modelled integer", loc);
            }
            if (locations.add(loc)) {
                int initVal = initValues.getOrDefault(loc, 0);
                WriteEvent init = new WriteEvent(0, loc, MemoryOrder.RELAXED,
                        initVal, Integer.toString(initVal));
                es.addEvent(init);
                initOf.put(loc, init);
                writeValueExprs.put(init.getId(), Integer.toString(initVal));  // C1
            }
        }

        /**
         * C1: rewrite a value expression into the symbolic string the {@link SymbolicRfEncoder}
         * consumes — every register identifier replaced by its {@code regSymbolic} definition
         * (parenthesised), so the result contains only {@code R<readId>} variables, integer
         * literals and QF_BV operators. A register with no definition (never loaded/assigned)
         * folds to {@code 0}, matching {@code ExprEval}'s treatment of an unknown identifier,
         * so the symbolic value of a constant store is identical to its folded constant.
         */
        private static String substituteSymbolic(String expr, Map<String, String> regSymbolic) {
            if (expr == null) return "0";
            StringBuilder out = new StringBuilder();
            Matcher m = IDENT.matcher(expr);
            int last = 0;
            while (m.find()) {
                out.append(expr, last, m.start());
                String id = m.group();
                String sym = regSymbolic.get(id);
                out.append(sym != null ? "(" + sym + ")" : "0");
                last = m.end();
            }
            out.append(expr.substring(last));
            return out.toString();
        }

        /**
         * AArch64 post-index writeback ({@code STR W0,[X1],#4}): the access itself used the
         * base, but afterwards the base holds {@code x+imm}, which is not a modelled
         * location. Unbind it so a later access through it fails explicitly in
         * {@link #resolveLoc} instead of silently aliasing {@code x}.
         */
        private void writeBack(Insn in, Map<String, String> addrEnv) {
            if (arch == Arch.ARM && in.locIsRegister() && armPostIndexed(in.raw())) {
                addrEnv.put(in.loc(), null);
            }
        }

        private String resolveLoc(Insn in, Map<String, String> addrEnv, RawInstr ri) {
            if (!in.locIsRegister()) return in.loc();
            String bound = addrEnv.get(in.loc());
            if (bound == null && addrEnv.containsKey(in.loc())) {
                throw new ParseException(src, ri.line(),
                        ParseException.Kind.UNSUPPORTED_INSTRUCTION,
                        "base register '" + in.loc() + "' was post-index written back; its "
                                + "new address is not a modelled location: " + ri.text());
            }
            if (bound == null) {
                throw new ParseException(src, ri.line(),
                        ParseException.Kind.UNSUPPORTED_INSTRUCTION,
                        "base register '" + in.loc() + "' is not bound to a variable in "
                                + "the initial state: " + ri.text());
            }
            return bound;
        }

        /**
         * Map a parsed fence to its {@link FenceEvent.FenceKind}. C follows the parsed
         * memory order (seq_cst / smp_mb ⇒ FULL, acquire ⇒ ACQ, release ⇒ REL); assembly
         * maps the mnemonic and barrier domain: full barriers ⇒ FULL, load/acquire/
         * instruction-sync barriers ⇒ ACQ, store/release barriers ⇒ REL.
         */
        private FenceEvent.FenceKind fenceKind(Insn in) {
            if (arch == Arch.C) {
                return switch (in.mo()) {
                    case ACQUIRE -> FenceEvent.FenceKind.ACQ;
                    case RELEASE -> FenceEvent.FenceKind.REL;
                    default -> FenceEvent.FenceKind.FULL;     // SC / seq_cst / smp_mb
                };
            }
            String raw = in.raw().strip().toLowerCase(Locale.ROOT);
            String op = raw.split("[\\s,]+", 2)[0];
            String operand = raw.contains(" ")
                    ? raw.substring(raw.indexOf(' ') + 1).replaceAll("\\s", "") : "";
            // store-ordering / release barriers
            if (op.equals("sfence") || op.equals("lwsync") || op.equals("eieio")) {
                return FenceEvent.FenceKind.REL;
            }
            // load-ordering / acquire / instruction-sync barriers
            if (op.equals("lfence") || op.equals("isync") || op.equals("isb")) {
                return FenceEvent.FenceKind.ACQ;
            }
            // ARM dmb/dsb domains: …ld ⇒ acquire, …st ⇒ release, sy/ish ⇒ full.
            if (op.equals("dmb") || op.equals("dsb")) {
                if (operand.endsWith("ld")) return FenceEvent.FenceKind.ACQ;
                if (operand.endsWith("st")) return FenceEvent.FenceKind.REL;
                return FenceEvent.FenceKind.FULL;
            }
            // RISC-V FENCE pred,succ sets: r,r ⇒ acquire-ish; w,w ⇒ release-ish.
            if (op.equals("fence")) {
                if (operand.equals("r,r")) return FenceEvent.FenceKind.ACQ;
                if (operand.equals("w,w")) return FenceEvent.FenceKind.REL;
                return FenceEvent.FenceKind.FULL;             // rw,rw / fence.i
            }
            return FenceEvent.FenceKind.FULL;                 // mfence, sync, hwsync, dmb ish
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
            var trace = DEP_TRACE;                       // read the volatile once
            for (String reg : identifiers(expr)) {
                RegSrc s = env.get(reg);
                if (s == null) continue;
                Verdict v = classify(expr, reg);
                boolean useReal = v.real();
                for (Map.Entry<ReadEvent, Boolean> e : s.reads().entrySet()) {
                    boolean sem = useReal && e.getValue();
                    acc.merge(e.getKey(), sem, Boolean::logicalOr);
                }
                if (trace != null) {
                    List<Integer> ids = new ArrayList<>();
                    for (ReadEvent r : s.reads().keySet()) ids.add(r.getId());
                    trace.accept(new DepDecision(expr, reg, v.real(), v.pattern(), ids,
                            curThreadId, curInstrIndex));
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
        // A bare location constraint loc=val (the '(?<![:\w])' excludes the reg of N:reg=val).
        private static final Pattern EXISTS_MEM = Pattern.compile(
                "(?<![:\\w])([A-Za-z_]\\w*)\\s*=\\s*(-?\\d+|0x[0-9A-Fa-f]+)");

        private void wireReadsFrom() {
            if (arch == Arch.ARM) {
                Map<Integer, Integer> bound = armBindOutcome();
                for (Event e : es.getEvents()) {
                    if (!(e instanceof ReadEvent rd)) continue;
                    Integer want = bound.get(rd.getId());
                    if (want != null) existsReadValues.put(rd.getId(), want);
                    es.addReadsFrom(rd, chooseWrite(rd.getVariable(), want == null ? 0 : want));
                }
                return;
            }
            Map<String, Integer> required = new LinkedHashMap<>();
            Matcher m = EXISTS_REG.matcher(existsClause);
            while (m.find()) {
                required.put(m.group(1) + ":" + m.group(2), parseIntLit(m.group(3)));
            }
            // C1: memory-final constraints (bare loc=val for a real location).
            Matcher mm = EXISTS_MEM.matcher(existsClause);
            while (mm.find()) {
                if (locations.contains(mm.group(1))) {
                    existsMemFinal.put(mm.group(1), parseIntLit(mm.group(2)));
                }
            }
            for (Event e : es.getEvents()) {
                if (!(e instanceof ReadEvent rd)) continue;
                Integer want = requiredValueFor(rd, required);
                // C1: record which reads the exists clause actually pins (want != null). Reads
                // it does not name stay free under symbolic enumeration (unlike wired mode,
                // which pins them to the initial write).
                if (want != null) existsReadValues.put(rd.getId(), want);
                WriteEvent target = chooseWrite(rd.getVariable(), want == null ? 0 : want);
                es.addReadsFrom(rd, target);
            }
        }

        private static final Pattern ARM_OUTCOME_QUANT = Pattern.compile("(~\\s*exists|exists|forall)\\b");
        private static final Pattern ARM_OUTCOME_LOCATIONS = Pattern.compile("locations\\s*\\[[^\\]]*\\]");
        private static final Pattern ARM_OUTCOME_REG = Pattern.compile(
                "(\\d+)\\s*:\\s*([A-Za-z_]\\w*)\\s*=\\s*(-?(?:0x[0-9A-Fa-f]+|\\d+))");
        private static final Pattern ARM_OUTCOME_MEM = Pattern.compile(
                "(\\[\\s*)?([A-Za-z_]\\w*)\\s*(\\])?\\s*=\\s*(-?(?:0x[0-9A-Fa-f]+|\\d+))");

        /**
         * AArch64 outcome binding. The exists clause must be a conjunction of register atoms
         * {@code N:Rn=v} and memory atoms {@code x=v} / {@code [x]=v}; anything else
         * (disjunction, negated atoms, fault / tag / page-table terms) is rejected rather than
         * silently dropped. A register atom is resolved against the register's <em>final</em>
         * value in thread N (not its last load), under the W/X identity of armCanonical:
         * <ul>
         *   <li>final value is exactly one read: that read is pinned to v — an X-named atom
         *       only when the register's upper 32 bits are known zero, so X = v ⇔ the 32-bit
         *       value = v for 0 ≤ v &lt; 2^32;</li>
         *   <li>final value is a constant (initial value or computed from constants): checked
         *       here; a satisfied atom needs no constraint, a violated one is rejected;</li>
         *   <li>anything else (a computed function of reads, an address, an undefined name,
         *       a width-sensitive X value) is rejected.</li>
         * </ul>
         * Returns read id → required 32-bit value. The quantifier is not interpreted here.
         */
        private Map<Integer, Integer> armBindOutcome() {
            Map<Integer, Integer> bound = new LinkedHashMap<>();
            // "locations [...]" only lists what herd prints; it constrains nothing. A "filter"
            // restricts the executions considered, which is not modelled.
            String c = ARM_OUTCOME_LOCATIONS.matcher(existsClause).replaceAll(" ").strip();
            if (c.matches("(?s)filter\\b.*") || c.contains(" filter ")) {
                throw armUnsupported(-1, "outcome: filter clause is not supported", existsClause);
            }
            if (c.isEmpty()) return bound;
            Matcher q = ARM_OUTCOME_QUANT.matcher(c);
            String body = q.lookingAt() ? c.substring(q.end()) : c;
            if (body.contains("\\/")) throw armUnsupported(-1, "outcome: disjunction is not supported", c);
            for (String raw : body.replace("(", " ").replace(")", " ").split("/\\\\")) {
                String a = raw.strip();
                if (a.isEmpty()) continue;
                if (a.startsWith("~")) throw armUnsupported(-1, "outcome: negated atom is not supported", a);
                Matcher r = ARM_OUTCOME_REG.matcher(a);
                if (r.matches()) {
                    armBindRegisterAtom(Integer.parseInt(r.group(1)), r.group(2), parseLongLit(r.group(3)), a, bound);
                    continue;
                }
                Matcher m = ARM_OUTCOME_MEM.matcher(a);
                if (m.matches() && (m.group(1) == null) == (m.group(3) == null)) {
                    String loc = m.group(2);
                    long v = parseLongLit(m.group(4));
                    if (armUnsupportedInitLocs.contains(loc)) {
                        throw armUnsupported(-1, "outcome: location '" + loc + "' has an unmodelled initial value", a);
                    }
                    if (v < Integer.MIN_VALUE || v > 0xffffffffL) {
                        throw armUnsupported(-1, "outcome: memory value outside 32 bits", a);
                    }
                    if (locations.contains(loc)) {
                        existsMemFinal.put(loc, (int) v);
                    } else if (initValues.getOrDefault(loc, 0) != (int) v) {
                        throw armUnsupported(-1, "outcome: statically unsatisfiable (location '" + loc
                                + "' is never accessed and holds its initial value)", a);
                    }
                    continue;
                }
                throw armUnsupported(-1, "outcome: unsupported term", a);
            }
            return bound;
        }

        private void armBindRegisterAtom(int p, String name, long v, String atom, Map<Integer, Integer> bound) {
            String canon = armCanonical(name);
            boolean xView = name.matches("[Xx]\\d+|[Xx][Zz][Rr]");
            if (canon == null) canon = name;                       // AArch32 Rn etc.: 32-bit names
            if (canon.equals("XZR")) {
                if (v != 0) throw armUnsupported(-1, "outcome: statically unsatisfiable (zero register)", atom);
                return;
            }
            Map<String, String> fin = armFinalSymbolic.get(p);
            if (fin == null) throw armUnsupported(-1, "outcome: names thread P" + p + ", which does not exist", atom);
            String sym = fin.get(canon);
            if (sym == null) {
                if (armUnsupportedInitRegs.getOrDefault(p, Set.of()).contains(canon)) {
                    throw armUnsupported(-1, "outcome: register's initial value is not modelled", atom);
                }
                if (armFinalAddr.getOrDefault(p, Set.of()).contains(canon)) {
                    throw armUnsupported(-1, "outcome: register holds an address", atom);
                }
                throw armUnsupported(-1, "outcome: register is never initialised or written", atom);
            }
            if (xView && !armFinalUpperZero.get(p).getOrDefault(canon, true)) {
                throw armUnsupported(-1, "outcome: X register whose upper 32 bits are not known to be "
                        + "zero (64-bit value not representable)", atom);
            }
            // Upper bits are zero from here on for X names, so X = v ⇔ 0 ≤ v < 2^32 ∧ low32 = v.
            if (xView ? (v < 0 || v > 0xffffffffL) : (v < Integer.MIN_VALUE || v > 0xffffffffL)) {
                throw armUnsupported(-1, xView
                        ? "outcome: statically unsatisfiable (an X register holding a zero-extended "
                          + "32-bit value cannot equal " + v + ")"
                        : "outcome: value outside 32 bits", atom);
            }
            int want = (int) v;
            String core = stripOuterParens(sym.replaceAll("\\s+", ""));
            if (core.matches("R\\d+")) {
                int rid = Integer.parseInt(core.substring(1));
                Integer prev = bound.putIfAbsent(rid, want);
                if (prev != null && prev != want) {
                    throw armUnsupported(-1, "outcome: contradictory constraints on one loaded value", atom);
                }
                return;
            }
            if (!IDENT.matcher(core).find()) {
                Integer k = tryEval(core, Map.of());
                if (k == null) throw armUnsupported(-1, "outcome: constant register value not evaluable", atom);
                if (k != want) {
                    throw armUnsupported(-1, "outcome: statically unsatisfiable (register holds the constant "
                            + k + ")", atom);
                }
                return;                                            // statically satisfied
            }
            throw armUnsupported(-1, "outcome: register holds a computed value (" + sym
                    + "); only a loaded value or a constant can be bound", atom);
        }

        private static String stripOuterParens(String s) {
            while (s.startsWith("(") && s.endsWith(")")) {
                int depth = 0;
                boolean wraps = true;
                for (int i = 0; i < s.length() - 1; i++) {
                    if (s.charAt(i) == '(') depth++;
                    else if (s.charAt(i) == ')' && --depth == 0) { wraps = false; break; }
                }
                if (!wraps) break;
                s = s.substring(1, s.length() - 1);
            }
            return s;
        }

        /** An outcome constant as a 64-bit value; a wider literal is rejected explicitly. */
        private long parseLongLit(String s) {
            Long v = armLiteral(s);
            if (v == null) throw armUnsupported(-1, "outcome: constant wider than 64 bits", s);
            return v;
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
        // Memory-order-annotated WRITE_ONCE / READ_ONCE macros (release/acquire fragment,
        // TACAS M1): WRITE_ONCE_REL(*x,e) / WRITE_ONCE_RELACQ(*x,e) / WRITE_ONCE_ACQ_REL(*x,e)
        // and r = READ_ONCE_ACQ(*x) / READ_ONCE_REL(*x) / READ_ONCE_RELACQ(*x). The suffix
        // maps to the access's MemoryOrder; these are matched before the bare macros above.
        // (Mutually exclusive with them anyway: the bare pattern requires '(' right after
        // the macro name, which the suffixed spellings never have.)
        private static final Pattern C_WRITE_ONCE_ORD = Pattern.compile(
                "(?i)WRITE_ONCE_(REL|ACQ|RELACQ|ACQ_REL)\\s*\\(\\s*\\*?\\s*(\\w+)\\s*,\\s*([^)]+)\\)");
        private static final Pattern C_READ_ONCE_ORD = Pattern.compile(
                "(?i)(\\w+)\\s*=\\s*READ_ONCE_(ACQ|REL|RELACQ|ACQ_REL)\\s*\\(\\s*\\*?\\s*(\\w+)\\s*\\)");
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
            if ((m = C_WRITE_ONCE_ORD.matcher(t)).find()) {
                return store(m.group(2), m.group(3), moFromSuffix(m.group(1)), t);
            }
            if ((m = C_READ_ONCE_ORD.matcher(t)).find()) {
                return load(m.group(1), m.group(3), moFromSuffix(m.group(2)), t);
            }
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
            int p = curThreadId - 1;
            if (p != armWidthThread) armEnterThread(p);
            if (ARM_SP.matcher(t).find()) {
                throw armUnsupported(ri.line(), "stack pointer SP/WSP is not modelled", t);
            }
            String[] head = t.split("[\\s,]+", 2);
            String op = head[0].toLowerCase(Locale.ROOT);
            List<String> ops = splitOperands(head.length > 1 ? head[1] : "");
            Set<String> unsupported = armUnsupportedInitRegs.getOrDefault(p, Set.of());
            Matcher g = ARM_GPR.matcher(t);
            while (g.find()) {
                if (unsupported.contains(armCanonical(g.group()))) {
                    throw armUnsupported(ri.line(), "register " + g.group()
                            + " has an initial value the model does not represent", t);
                }
            }
            boolean zeroDest = armTrackWidths(op, ops, ri, t);
            return lexRegisterAsm(armRewrite(t, zeroDest), ARM_LEX);
        }

        // ── AArch64 register identity and widths ───────────────────────────────────
        //
        // Wn and Xn are the 32- and 64-bit views of one register; every dataflow map is keyed
        // by the canonical name Xn. The model's values are 32 bits wide, which is exact for
        // the low 32 bits of every register under + - * & | ^ ~ and moves (truncation
        // commutes with them), and for W views (a W write zero-extends). Where the full
        // 64-bit X value is observed — a 64-bit store, or an X-named outcome — it is
        // representable only if the upper 32 bits are known to be zero; that is tracked per
        // register (armUpperZero) and anything else is rejected at the observation point.
        // WZR/XZR read as 0 and discard writes; SP/WSP are not modelled.

        private static final Pattern ARM_GPR = Pattern.compile("(?<![\\w.#])[WwXx](?:\\d{1,2}|[Zz][Rr])(?!\\w)");
        private static final Pattern ARM_SP = Pattern.compile("(?<![\\w.#])[Ww]?[Ss][Pp](?!\\w)");

        /** Canonical register for a W/X general-purpose register name ({@code W3}, {@code x3} → {@code X3}; {@code WZR} → {@code XZR}); {@code null} otherwise. */
        static String armCanonical(String tok) {
            String s = tok.strip();
            if (s.matches("[WwXx]\\d{1,2}")) return "X" + Integer.parseInt(s.substring(1));
            if (s.matches("[WwXx][Zz][Rr]")) return "XZR";
            return null;
        }

        private void armEnterThread(int p) {
            armWidthThread = p;
            armUpperZero = new LinkedHashMap<>();
            armInit64.getOrDefault(p, Map.of()).forEach((r, v64) -> armUpperZero.put(r, (v64 >>> 32) == 0));
        }

        /** Whether operand {@code o}, as read, has zero upper 32 bits. */
        private boolean armUpperZeroOperand(String o) {
            String s = o.strip();
            if (s.startsWith("#") || isIntLit(s)) {
                String d = hashImmediate(s.startsWith("#") ? s : "#" + s);
                return d.matches("\\d+");                         // non-negative literal
            }
            String c = armCanonical(s);
            if (c == null || c.equals("XZR") || s.matches("[Ww].*")) return true;
            return armUpperZero.getOrDefault(c, true);             // never written: herd's 0
        }

        /**
         * Update the width state for one instruction and reject 64-bit observations of values
         * whose upper bits are unknown. Returns whether the destination is a zero register
         * (its write is discarded).
         */
        private boolean armTrackWidths(String op, List<String> ops, RawInstr ri, String t) {
            if (ops.isEmpty()) return false;
            String first = ops.get(0).strip();
            String dest = armCanonical(first);
            boolean wDest = first.matches("[Ww].*");
            if (ARM_LEX.isStore(op)) {
                if (first.matches("[Xx]\\d{1,2}") && !armUpperZeroOperand(first)) {
                    throw armUnsupported(ri.line(), "64-bit store of " + first
                            + ", whose upper 32 bits are not known to be zero", t);
                }
                return false;
            }
            if (dest == null) return false;                          // B0/H0 SIMD, R0, labels …
            boolean zero = dest.equals("XZR");
            boolean uz;
            if (ARM_LEX.isLoad(op)) {
                uz = true;
                if (!wDest) {                                        // 64-bit load
                    MemRef ref = ARM_LEX.memRef(ops);
                    String base = ref == null ? null : armCanonical(ref.base());
                    String loc = base == null ? null
                            : regAddrInit.getOrDefault(armWidthThread, Map.of()).get(base);
                    if (loc != null) uz = initValues.getOrDefault(loc, 0) >= 0;
                }
            } else if (ARM_LEX.isRmw(op)) {
                uz = true;                                           // constant placeholder
            } else if (ARM_LEX.isLoadImm(op)) {
                uz = wDest || (ops.size() >= 2 && armUpperZeroOperand(ops.get(1)));
            } else if (ARM_LEX.isArith(op)) {
                if (wDest) {
                    uz = true;
                } else {
                    List<Boolean> src = new ArrayList<>();
                    for (int i = 1; i < Math.min(ops.size(), 3); i++) src.add(armUpperZeroOperand(ops.get(i)));
                    if (ops.size() > 3 && ops.get(3).strip().matches("(?i)sxt[wx]")) src.set(src.size() - 1, false);
                    uz = switch (op) {
                        case "and" -> src.contains(true);
                        case "orr", "eor" -> !src.contains(false);
                        default -> false;                            // add / sub / mvn: carries
                    };
                }
            } else {
                return false;                                        // cmp, branches, fences …
            }
            if (!zero) armUpperZero.put(dest, uz);
            return zero;
        }

        /**
         * Rewrite register tokens to canonical names. Zero-register reads become the literal
         * {@code 0}; a zero-register destination becomes a name nothing reads, so the write
         * is discarded.
         */
        private static String armRewrite(String t, boolean zeroDest) {
            Matcher m = ARM_GPR.matcher(t);
            StringBuilder out = new StringBuilder();
            boolean first = true;
            while (m.find()) {
                String c = armCanonical(m.group());
                String rep = c.equals("XZR") ? (first && zeroDest ? "XZR_DISCARD" : "0") : c;
                m.appendReplacement(out, rep);
                first = false;
            }
            m.appendTail(out);
            return out.toString();
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
            // AArch64 immediate addressing the location model cannot represent: a non-zero
            // offset ([Xn,#8] addresses x+8, not x) or pre-index writeback ([Xn,#8]!).
            // Reject rather than silently resolve the access to the base variable.
            // (Post-index [Xn],#8 accesses x itself; see writeBack.)
            if (lex == ARM_LEX && (lex.isLoad(op) || lex.isStore(op) || lex.isRmw(op))
                    && armUnmodelledAddressing(ops)) {
                return null;
            }
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
                // AArch64 shifted / extended operand (EOR W1,W0,W0,LSL #2; ADD W0,W0,#1,LSL 12;
                // ADD X0,X1,W2,UXTB): the value is not op(a, b), so reject rather than drop
                // the modifier. Only the word extends SXTW/UXTW/SXTX/UXTX without an amount
                // are kept: they are the identity on the 32-bit values the model tracks.
                if (lex == ARM_LEX && ops.size() > 3 && (ops.size() > 4
                        || !ops.get(3).strip().matches("(?i)[su]xt[wx]"))) {
                    return null;
                }
                String dst = ops.get(0);
                String expr = ops.size() >= 3
                        ? hashImmediate(ops.get(1)) + opSym(op) + hashImmediate(ops.get(2))
                        : dst + opSym(op) + hashImmediate(ops.get(1));
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

        /**
         * Split an operand list on top-level commas only. Commas inside a bracketed
         * address expression — AArch64 indexed forms like {@code [X5,W4,SXTW]} or
         * {@code [X5,#8]}, and the parenthesised {@code disp(rB)} of PPC/RISC-V — are
         * <em>not</em> separators: the whole {@code [...]} / {@code (...)} stays one
         * operand so {@link AsmLexicon#memRef} can still see the index register. Splitting
         * on every comma (the old behaviour) severed {@code [X5} from {@code W4,SXTW]},
         * dropping the index register and every address dependency it carried.
         */
        private static List<String> splitOperands(String rest) {
            List<String> out = new ArrayList<>();
            if (rest == null || rest.isBlank()) return out;
            int depth = 0, start = 0;
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '[' || c == '(') depth++;
                else if (c == ']' || c == ')') { if (depth > 0) depth--; }
                else if (c == ',' && depth == 0) {
                    String s = rest.substring(start, i).strip();
                    if (!s.isEmpty()) out.add(s);
                    start = i + 1;
                }
            }
            String s = rest.substring(start).strip();
            if (!s.isEmpty()) out.add(s);
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

        /**
         * An AArch64 {@code #}-immediate operand as a decimal literal of the expression
         * grammar ({@code #1} ⇒ {@code 1}, {@code #0x10} ⇒ {@code 16}, {@code #-1} ⇒
         * {@code (-1)}), wrapped to 32 bits like every other value; any other operand is
         * returned unchanged, so dialects without {@code #} immediates lower exactly as
         * before. Unparseable immediates are left as-is (outside the grammar, hence
         * classified conservatively).
         */
        private static String hashImmediate(String op) {
            String s = op.strip();
            if (!s.startsWith("#")) return op;
            String t = s.substring(1).strip();
            boolean neg = t.startsWith("-");
            if (neg || t.startsWith("+")) t = t.substring(1);
            long v;
            try {
                v = (t.startsWith("0x") || t.startsWith("0X"))
                        ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
            } catch (NumberFormatException e) {
                return op;
            }
            int w = (int) (neg ? -v : v);
            return w < 0 ? "(" + w + ")" : Integer.toString(w);
        }

        /**
         * Whether an AArch64 load/store operand list uses addressing the location model
         * cannot represent: a base-plus-immediate offset other than {@code #0}
         * ({@code [X1,#4]}) or pre-index writeback ({@code [X1,#4]!}), both of which access
         * {@code x+imm}. Post-index writeback ({@code [X1],#4}) accesses the base itself and
         * is handled by {@link #armPostIndexed} instead. Register-index forms
         * ({@code [X5,W4,SXTW]}, with or without a shift amount) are unaffected: they keep
         * their existing address-dependency treatment.
         */
        private static boolean armUnmodelledAddressing(List<String> ops) {
            for (int i = 1; i < ops.size(); i++) {
                String o = ops.get(i);
                int l = o.indexOf('['), r = o.indexOf(']');
                if (l < 0 || r < l) continue;
                if (o.substring(r + 1).contains("!")) return true;
                String[] parts = o.substring(l + 1, r).split(",");
                if (parts.length == 2) {
                    String off = hashImmediate(parts[1]);
                    if (parts[1].strip().startsWith("#") && !off.equals("0")) return true;
                }
                return false;
            }
            return false;
        }

        /** Whether an AArch64 access is post-indexed ({@code LDR W0,[X1],#4}): base written back after the access. */
        private static boolean armPostIndexed(String raw) {
            String[] head = raw.strip().split("[\\s,]+", 2);
            List<String> ops = splitOperands(head.length > 1 ? head[1] : "");
            for (int i = 1; i + 1 < ops.size(); i++) {
                if (ops.get(i).contains("[") && ops.get(i + 1).strip().startsWith("#")) return true;
            }
            return false;
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

        /**
         * Map a {@code READ_ONCE_*} / {@code WRITE_ONCE_*} macro suffix to a
         * {@link MemoryOrder}: {@code ACQ}→acquire, {@code REL}→release, and the combined
         * {@code RELACQ}/{@code ACQ_REL} (a release-and-acquire access) to {@code SC}, the
         * strongest order we model — {@link MemoryOrder} has no dedicated {@code ACQ_REL},
         * and SC is the sound over-approximation for a full-barrier access.
         */
        private static MemoryOrder moFromSuffix(String suffix) {
            return switch (suffix.toUpperCase(Locale.ROOT)) {
                case "ACQ" -> MemoryOrder.ACQUIRE;
                case "REL" -> MemoryOrder.RELEASE;
                default -> MemoryOrder.SC;      // RELACQ / ACQ_REL
            };
        }

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
         *
         * <p>A second, complementary canceller handles idioms the adjacency-based pattern
         * folder misses: <em>linear-arithmetic</em> forms where {@code reg}'s occurrences
         * cancel across intervening terms, e.g. the {@code a+1-a} store value of the
         * Leaky-Semicolon LBfd test (Jeffrey et al., "The Leaky Semicolon", POPL 2022).
         * Adjacency folding cannot see {@code a-a} in {@code a+1-a}; the linear pass
         * computes {@code reg}'s net coefficient over {@code + -} and calls the dependency
         * fake iff that coefficient is exactly zero — a value that is provably invariant
         * in {@code reg} over the integers (the cancellation is symbolic and holds under
         * two's-complement wraparound too). It is <em>sound in the soundness-critical
         * direction</em>: it only ever demotes an edge to fake on a proof of invariance and
         * bails (leaving the edge semantic) on anything non-linear, so no genuinely
         * value-carrying dependency is dropped — {@code sdep_true ⊆ sdep_impl} is preserved.
         */
        private static boolean dependsReally(String expr, String reg) {
            return classify(expr, reg).real();
        }

        /**
         * The detector's verdict for one {@code (expr, reg)} use: whether {@code reg} is a
         * <em>real</em> dependency, plus the idiom {@code pattern} the verdict rests on.
         * {@code "REAL"} when nothing cancels; otherwise the specific cancellation
         * ({@code "r^r"}, {@code "r&0"}, {@code "LINEAR_CANCEL"}, a {@code +}-joined
         * combination, …). Pure classification split out from {@link #dependsReally} so the
         * detector-quality instrumentation can name the matched pattern; the boolean result
         * is identical to the pre-split logic.
         */
        private record Verdict(boolean real, String pattern) { }

        private static Verdict classify(String expr, String reg) {
            String e = expr.replaceAll("\\s+", "");
            String R = Pattern.quote(reg);
            String[] toZero = {
                    R + "\\^" + R, R + "-" + R, R + "&~" + R, "~" + R + "&" + R,
                    R + "\\*0", "0\\*" + R, R + "&0", "0&" + R
            };
            String[] toZeroName = { "r^r", "r-r", "r&~r", "~r&r", "r*0", "0*r", "r&0", "0&r" };
            // Precedence of each idiom's TOP-LEVEL operator, used by foldSubterms to check
            // that a textual match is really a sub-term. Order matches toZero/toOnes.
            int[] toZeroPrec = { PREC_XOR, PREC_ADD, PREC_AND, PREC_AND,
                                 PREC_MUL, PREC_MUL, PREC_AND, PREC_AND };
            String[] toOnes = { R + "\\|~" + R, "~" + R + "\\|" + R };
            String[] toOnesName = { "r|~r", "~r|r" };
            int[] toOnesPrec = { PREC_OR, PREC_OR };
            LinkedHashSet<String> fired = new LinkedHashSet<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < toZero.length; i++) {
                    String n = foldSubterms(e, bounded(toZero[i]), "0", toZeroPrec[i]);
                    if (!n.equals(e)) { e = n; changed = true; fired.add(toZeroName[i]); }
                }
                for (int i = 0; i < toOnes.length; i++) {
                    String n = foldSubterms(e, bounded(toOnes[i]), "1", toOnesPrec[i]);
                    if (!n.equals(e)) { e = n; changed = true; fired.add(toOnesName[i]); }
                }
            }
            boolean mentionsReg =
                    Pattern.compile("(?<![A-Za-z0-9_])" + R + "(?![A-Za-z0-9_])").matcher(e).find();
            if (!mentionsReg) {
                return new Verdict(false, fired.isEmpty() ? "ABSENT" : String.join("+", fired));
            }
            // Primary path: the exact QF_BV constancy oracle, when one is installed. It
            // subsumes the linear-cancellation heuristic below (it also proves non-linear
            // invariance), so sdep_impl = sdep_true on the modelled bitvector fragment.
            ConstancyOracle oracle = CONSTANCY_ORACLE;
            if (oracle != null) {
                Boolean constant = oracle.isConstant(expr, reg);
                if (constant == null) {                       // outside the grammar: conservative
                    fired.add("CONSERVATIVE");
                    return new Verdict(true, String.join("+", fired));
                }
                if (constant) {                               // provably invariant ⇒ fake
                    fired.add("QFBV_CONST");
                    return new Verdict(false, String.join("+", fired));
                }
                fired.add("QFBV_VARIES");                     // provably varies ⇒ real
                return new Verdict(true, String.join("+", fired));
            }
            // Solver-free fallback (no oracle installed): linear-arithmetic normalization on
            // the idiom-folded expression — a net zero coefficient for reg proves invariance.
            Integer coeff = linearCoefficient(e, reg);
            if (coeff != null && coeff == 0) {
                fired.add("LINEAR_CANCEL");
                return new Verdict(false, String.join("+", fired));
            }
            return new Verdict(true, fired.isEmpty() ? "REAL" : String.join("+", fired) + "+REAL");
        }

        /**
         * The net coefficient of {@code reg} when {@code e} (whitespace-free) is a flat
         * sum/difference of integer literals and bare register identifiers — {@code a+1-a}
         * ⇒ 0, {@code a+a-1} ⇒ 2, {@code r0+r1-r0} ⇒ 0 (for {@code reg=r0}). Returns
         * {@code null} when {@code e} is <em>not</em> such a purely additive form: any
         * {@code * ^ & | ~ ( )}, or an identifier abutting more text, makes the expression
         * non-linear and the caller must then treat {@code reg} conservatively as real. A
         * returned {@code 0} is therefore a proof of invariance, never a guess.
         */
        private static Integer linearCoefficient(String e, String reg) {
            int coeff = 0;
            int sign = 1;
            boolean expectTerm = true;         // at a position where a term may begin
            int i = 0, n = e.length();
            while (i < n) {
                char c = e.charAt(i);
                if (c == '+' || c == '-') {
                    if (expectTerm) {          // unary sign on the upcoming term
                        if (c == '-') sign = -sign;
                    } else {                    // binary additive operator between terms
                        sign = (c == '-') ? -1 : 1;
                        expectTerm = true;
                    }
                    i++;
                    continue;
                }
                if (!expectTerm) return null;   // two adjacent terms, no operator ⇒ non-linear
                if (Character.isLetter(c) || c == '_') {          // identifier term
                    int start = i;
                    while (i < n && (Character.isLetterOrDigit(e.charAt(i)) || e.charAt(i) == '_')) i++;
                    if (e.substring(start, i).equals(reg)) coeff += sign;
                    expectTerm = false;
                    sign = 1;
                    continue;
                }
                if (Character.isDigit(c)) {                        // integer literal term
                    if (c == '0' && i + 1 < n && (e.charAt(i + 1) == 'x' || e.charAt(i + 1) == 'X')) {
                        i += 2;
                        while (i < n && isHexDigit(e.charAt(i))) i++;
                    } else {
                        while (i < n && Character.isDigit(e.charAt(i))) i++;
                    }
                    expectTerm = false;
                    sign = 1;
                    continue;
                }
                return null;                    // * ^ & | ~ ( ) … ⇒ not a flat additive form
            }
            return expectTerm ? null : coeff;   // trailing operator ⇒ malformed
        }

        private static boolean isHexDigit(char c) {
            return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private static String bounded(String p) {
            return "(?<![A-Za-z0-9_])(?:" + p + ")(?![A-Za-z0-9_])";
        }

        // ── Precedence-aware idiom folding ───────────────────────────────────────────
        //
        // Operator precedence of the modelled QF_BV grammar, low → high:
        //     |   ^   &   + -   *   unary ~ -
        // (identical to QfbvConstancyOracle's recursive-descent parser).

        private static final int PREC_OR = 1, PREC_XOR = 2, PREC_AND = 3,
                                 PREC_ADD = 4, PREC_MUL = 5, PREC_UNARY = 6;

        private static int precOf(char c) {
            return switch (c) {
                case '|' -> PREC_OR;
                case '^' -> PREC_XOR;
                case '&' -> PREC_AND;
                case '+', '-' -> PREC_ADD;
                case '*' -> PREC_MUL;
                case '~' -> PREC_UNARY;
                default -> -1;
            };
        }

        /**
         * Replace every occurrence of {@code pattern} in {@code e} that is a genuine
         * <em>sub-term</em> by {@code repl}, leaving occurrences that merely appear as
         * substrings untouched.
         *
         * <p>The cancellation idioms ({@code r^r}, {@code r-r}, {@code r&~r}, …) are only
         * value-preserving rewrites when the matched text is the complete operand structure
         * the idiom describes. Matching on the raw string is not sufficient: {@code
         * ~r0-r0} <em>contains</em> {@code r0-r0}, but parses as {@code (~r0)-r0 =
         * -2*r0-1}, which is not constant. Folding it to {@code ~0} loses the register and
         * the detector then reports a genuinely value-carrying dependency as fake — an
         * <em>unsound</em> classification, since it removes an edge from {@code sdep}.
         *
         * <p>Given the precedence {@code p} of the idiom's top-level operator, a match at
         * {@code [s,t)} is a sub-term exactly when neither neighbour steals an operand:
         * <ul>
         *   <li><b>left</b>: start-of-string, {@code '('}, or an operator binding
         *       <em>strictly looser</em> than {@code p}. Equal precedence is rejected
         *       because the grammar is left-associative — in {@code a^r0^r0} the parse is
         *       {@code (a^r0)^r0}, so the trailing {@code r0^r0} is not a sub-term.</li>
         *   <li><b>right</b>: end-of-string, {@code ')'}, or an operator binding
         *       <em>no tighter</em> than {@code p}. Equal precedence is accepted here, for
         *       the same left-associativity reason — in {@code r0^r0^1} the leading
         *       {@code r0^r0} <em>is</em> the left child.</li>
         * </ul>
         *
         * <p>The check is conservative: when in doubt the fold is skipped, leaving the
         * register mentioned and the dependency classified semantic. Skipping a legitimate
         * fold costs precision (a fake edge reported real); performing an illegitimate one
         * costs soundness. Only the latter is unacceptable.
         */
        private static String foldSubterms(String e, String pattern, String repl, int p) {
            Matcher m = Pattern.compile(pattern).matcher(e);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (m.find()) {
                if (!isSubterm(e, m.start(), m.end(), p)) continue;
                out.append(e, last, m.start()).append(repl);
                last = m.end();
            }
            if (last == 0) return e;
            return out.append(e.substring(last)).toString();
        }

        /** Whether the match {@code e[s,t)} of an idiom with top precedence {@code p} is a sub-term. */
        private static boolean isSubterm(String e, int s, int t, int p) {
            if (s > 0) {
                char l = e.charAt(s - 1);
                if (l != '(') {
                    int lp = precOf(l);
                    if (lp < 0 || lp >= p) return false;   // binds at least as tightly
                }
            }
            if (t < e.length()) {
                char r = e.charAt(t);
                if (r != ')') {
                    int rp = precOf(r);
                    if (rp < 0 || rp > p) return false;    // binds more tightly
                }
            }
            return true;
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
