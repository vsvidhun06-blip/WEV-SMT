package wev.smt.bench;

import com.weakest.model.EventStructure;
import com.weakest.model.MemoryOrder;
import com.weakest.model.ReadEvent;
import com.weakest.model.WriteEvent;

import wev.smt.DependencyInfo;

/**
 * Parametric weak-memory program generators for the Day-9 scalability sweep
 * ({@code wev.smt.cli.ScalabilitySweep}). Each family is a canonical litmus shape
 * grown to {@code n} threads so the SMT decision procedure can be timed against a
 * monotonically increasing event count.
 *
 * <p><b>Return type.</b> The builders return {@link Program}, a pair of
 * {@link EventStructure} and {@link DependencyInfo}, rather than a bare
 * {@code EventStructure}: the load-buffering families carry syntactic data
 * dependencies that are integral to their meaning (real vs. fake distinguishes
 * forbidden from allowed under WEAKEST) and a bare {@code EventStructure} cannot
 * carry them — the encoder needs both halves. Non-dependency families
 * ({@link #buildSBNThread}, {@link #buildIRIWFan}) carry {@link DependencyInfo#empty()}.
 *
 * <p><b>Event counts.</b> Every family wires an initial write (value {@code 0}) per
 * location on thread {@code 0}, exactly as {@link wev.smt.LitmusCorpus} does for the
 * hand-crafted classics, so that each read has a same-location write to read its
 * {@code 0} from and each location has a coherence baseline. The real event totals
 * are therefore: LB/SB chains {@code 3n} (n inits + n reads + n writes), IRIW fan
 * {@code 4n} (n inits + n writer writes + 2n reads). At {@code n = 2} the LB chain
 * reproduces the corpus {@code LB} and at {@code n = 3} the corpus {@code 3.LB}.
 *
 * <p>These builders do <strong>not</strong> reset the global event-id counter; the
 * caller is responsible for {@link com.weakest.model.Event#resetCounter()} discipline
 * (the sweep gives every {@code (family, n)} case its own {@code SolverContext}, so
 * ids need only be unique within a case).
 */
public final class ParametricPrograms {

    private ParametricPrograms() { }

    /** An event structure paired with its syntactic dependency sidecar. */
    public record Program(String family, int n, EventStructure es, DependencyInfo deps) {
        /** The true number of events in the structure (includes initial writes). */
        public int eventCount() {
            return es.getEvents().size();
        }
    }

    private static final MemoryOrder RLX = MemoryOrder.RELAXED;

    private static WriteEvent w(int thread, String var, int value) {
        return new WriteEvent(thread, var, RLX, value, Integer.toString(value));
    }

    private static ReadEvent r(int thread, String var, String local) {
        return new ReadEvent(thread, var, RLX, local);
    }

    // ── Load-buffering chain ────────────────────────────────────────────────

    /**
     * An {@code n}-thread load-buffering chain with <em>real</em> (semantic) data
     * dependencies: thread {@code p} reads location {@code x_p} and then writes
     * {@code x_{p+1 mod n}} with a value that genuinely depends on what it read. Each
     * read observes the "future" write of the previous thread, closing a dependency
     * cycle {@code read_p → write_p → read_{p+1} → … → read_p} through {@code sdep ∪ jf}
     * that WEAKEST forbids (out-of-thin-air) for every {@code n ≥ 2}.
     *
     * <p>At {@code n = 2} this is structurally the corpus {@code LB} (with {@code x_0,
     * x_1} for {@code x, y}); at {@code n = 3} the corpus {@code 3.LB}.
     */
    public static Program buildLBChain(int n) {
        return lbChain(n, true, "LBChain");
    }

    /**
     * As {@link #buildLBChain} but with <em>fake</em> (identity, value-irrelevant) data
     * dependencies recorded with {@code isSemantic = false} (e.g. {@code x = r ^ r + 1}).
     * The event structure is byte-for-byte the same shape as {@link #buildLBChain}, so
     * its encoding cost is identical, but {@code jfCoherence} excludes the fake edges
     * from {@code sdep}: no dependency cycle, so WEAKEST <em>allows</em> it for every
     * {@code n}. The pair is the scalability evidence that fake vs. real deps cost the
     * same to encode yet resolve to opposite WEAKEST verdicts.
     */
    public static Program buildLBFakeChain(int n) {
        return lbChain(n, false, "LBFakeChain");
    }

    private static Program lbChain(int n, boolean semantic, String family) {
        requireChain(n);
        EventStructure es = new EventStructure();

        WriteEvent[] init = new WriteEvent[n];
        for (int j = 0; j < n; j++) {
            init[j] = w(0, "x" + j, 0);
            es.addEvent(init[j]);
        }

        ReadEvent[] rd = new ReadEvent[n];
        WriteEvent[] wr = new WriteEvent[n];
        for (int p = 0; p < n; p++) {
            int thread = p + 1;
            rd[p] = r(thread, "x" + p, "r" + p);              // reads x_p
            wr[p] = w(thread, "x" + ((p + 1) % n), 1);        // writes x_{p+1}
            es.addEvent(rd[p]);
            es.addEvent(wr[p]);
            es.addProgramOrder(rd[p], wr[p]);                 // read →po write
        }

        // rf: read x_p observes the write to x_p, produced by thread (p-1).
        for (int p = 0; p < n; p++) {
            int producer = (p - 1 + n) % n;
            es.addReadsFrom(rd[p], wr[producer]);
        }

        // co per location: init_j < the (single) write to x_j, produced by thread (j-1).
        for (int j = 0; j < n; j++) {
            int producer = (j - 1 + n) % n;
            es.addCoherenceOrder("x" + j, init[j]);
            es.addCoherenceOrder("x" + j, wr[producer]);
        }

        // dep: each write depends on its own thread's prior read (consumer ← producer).
        DependencyInfo deps = new DependencyInfo();
        for (int p = 0; p < n; p++) {
            deps.addDataDep(wr[p], rd[p], semantic);
        }

        return new Program(family, n, es, deps);
    }

    // ── Store-buffering ring ─────────────────────────────────────────────────

    /**
     * An {@code n}-thread store-buffering ring: thread {@code p} writes its own
     * location {@code x_p = 1} and then reads the next thread's location
     * {@code x_{p+1 mod n}}, observing the initial {@code 0} (every read misses its
     * neighbour's write). At {@code n = 2} this is the corpus {@code SB}. No
     * dependencies. SC forbids the all-reads-see-0 outcome; the weaker models permit it.
     */
    public static Program buildSBNThread(int n) {
        requireChain(n);
        EventStructure es = new EventStructure();

        WriteEvent[] init = new WriteEvent[n];
        for (int j = 0; j < n; j++) {
            init[j] = w(0, "x" + j, 0);
            es.addEvent(init[j]);
        }

        WriteEvent[] wr = new WriteEvent[n];
        ReadEvent[] rd = new ReadEvent[n];
        for (int p = 0; p < n; p++) {
            int thread = p + 1;
            wr[p] = w(thread, "x" + p, 1);                    // writes own x_p
            rd[p] = r(thread, "x" + ((p + 1) % n), "r" + p);  // reads neighbour x_{p+1}
            es.addEvent(wr[p]);
            es.addEvent(rd[p]);
            es.addProgramOrder(wr[p], rd[p]);                 // write →po read
        }

        // rf: each read sees the initial 0 of the neighbour's location (misses the write).
        for (int p = 0; p < n; p++) {
            es.addReadsFrom(rd[p], init[(p + 1) % n]);
        }

        // co per location: init_p < own write to x_p.
        for (int p = 0; p < n; p++) {
            es.addCoherenceOrder("x" + p, init[p]);
            es.addCoherenceOrder("x" + p, wr[p]);
        }

        return new Program("SBNThread", n, es, DependencyInfo.empty());
    }

    // ── IRIW fan ──────────────────────────────────────────────────────────────

    /**
     * An IRIW fan with {@code n} independent writers and two readers that each read all
     * {@code n} locations — reader A in forward order, reader B in reverse. The wired
     * execution has A observe {@code x_0 = 1} (writer 0) but the rest {@code 0}, while B
     * observes {@code x_{n-1} = 1} (writer n-1) but the rest {@code 0}: the two readers
     * disagree on the global order of the independent writes, which SC forbids. At
     * {@code n = 2} this is the corpus {@code IRIW}. No dependencies. Events {@code 4n}
     * (n inits + n writers + 2n reads).
     */
    public static Program buildIRIWFan(int n) {
        requireChain(n);
        EventStructure es = new EventStructure();

        WriteEvent[] init = new WriteEvent[n];
        WriteEvent[] writer = new WriteEvent[n];
        for (int j = 0; j < n; j++) {
            init[j] = w(0, "x" + j, 0);
            es.addEvent(init[j]);
        }
        for (int j = 0; j < n; j++) {
            writer[j] = w(j + 1, "x" + j, 1);                 // thread j+1 writes x_j = 1
            es.addEvent(writer[j]);
            es.addCoherenceOrder("x" + j, init[j]);
            es.addCoherenceOrder("x" + j, writer[j]);
        }

        int threadA = n + 1;
        int threadB = n + 2;
        ReadEvent[] a = new ReadEvent[n];
        ReadEvent[] b = new ReadEvent[n];
        for (int k = 0; k < n; k++) {
            a[k] = r(threadA, "x" + k, "a" + k);              // forward: x_0 .. x_{n-1}
            es.addEvent(a[k]);
            if (k > 0) es.addProgramOrder(a[k - 1], a[k]);
        }
        for (int k = 0; k < n; k++) {
            int loc = n - 1 - k;                              // reverse: x_{n-1} .. x_0
            b[k] = r(threadB, "x" + loc, "b" + k);
            es.addEvent(b[k]);
            if (k > 0) es.addProgramOrder(b[k - 1], b[k]);
        }

        // rf: A sees only x_0=1; B sees only x_{n-1}=1; all other reads see init 0.
        for (int k = 0; k < n; k++) {
            es.addReadsFrom(a[k], k == 0 ? writer[0] : init[k]);
        }
        for (int k = 0; k < n; k++) {
            int loc = n - 1 - k;
            es.addReadsFrom(b[k], loc == n - 1 ? writer[n - 1] : init[loc]);
        }

        return new Program("IRIWFan", n, es, DependencyInfo.empty());
    }

    private static void requireChain(int n) {
        if (n < 2) {
            throw new IllegalArgumentException(
                    "parametric families need n >= 2 (a cycle/fan needs two threads); got " + n);
        }
    }
}
