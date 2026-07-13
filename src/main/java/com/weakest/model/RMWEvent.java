package com.weakest.model;

/**
 * An atomic read-modify-write event (xchg / cmpxchg / fetch-and-add / LL-SC pair /
 * amo*). A single event that is simultaneously a read (consuming {@code readValue}) and
 * a write (producing the value carried by {@link #getValue()}), at the same location.
 * Day 12 (fence + RMW encoding).
 *
 * <p><b>Additive only, and intentionally {@code extends WriteEvent}.</b> The spec sketch
 * wrote {@code extends Event}; extending {@link WriteEvent} (itself a subclass of the
 * abstract {@link Event}) is the faithful and far less invasive choice: an RMW
 * <em>is</em> a write, so every writer-collecting predicate in the SMT layer
 * ({@code instanceof WriteEvent}) picks it up for {@code co}, modification order and
 * coherence automatically. The read side is handled explicitly in the encoder
 * ({@code instanceof RMWEvent} ⇒ also a reader, reading {@link #getReadValue()}).
 *
 * <p><b>Atomicity</b> (Lahav &amp; Vafeiadis, PLDI 2017, §3): no write to the location
 * may sit, in coherence order, between the write the RMW reads from and the RMW itself.
 * Because the read and write parts share one event id — hence one position / layer
 * variable — per-location coherence already forbids an intervening write; the explicit
 * {@code AxiomaticConsistency.rmwAtomicity} axiom is the textbook statement of the same
 * fact and a guard against encodings that split the pair.
 *
 * <p>{@link #isFullFence()} marks a strongly-ordered RMW (x86 {@code LOCK}-prefixed
 * ops, C/C++ {@code seq_cst} RMWs): such an RMW drains program order like a {@code FULL}
 * fence, ordering accesses before it ahead of accesses after it.
 */
public class RMWEvent extends WriteEvent {

    private final int readValue;     // the value consumed by the read part (oldVal)
    private final boolean fullFence; // a locked / seq_cst RMW orders like a full fence

    public RMWEvent(int threadId, String variable, MemoryOrder order,
                    int readValue, int writeValue) {
        this(threadId, variable, order, readValue, writeValue, true);
    }

    public RMWEvent(int threadId, String variable, MemoryOrder order,
                    int readValue, int writeValue, boolean fullFence) {
        super(threadId, variable, order, writeValue, "rmw");
        this.readValue = readValue;
        this.fullFence = fullFence;
    }

    /** The value the read part consumes (oldVal). The write part value is {@link #getValue()}. */
    public int getReadValue() { return readValue; }

    /** The value the write part produces (newVal); alias of {@link #getValue()}. */
    public int getWriteValue() { return getValue(); }

    /** Whether this RMW is strongly ordered (acts as a full fence in program order). */
    public boolean isFullFence() { return fullFence; }

    @Override
    public String toString() {
        return "RMW[T" + getThreadId() + "](" + getVariable() + ":" + readValue
                + "->" + getValue() + "," + getMemoryOrder().name().toLowerCase() + ")";
    }
}
