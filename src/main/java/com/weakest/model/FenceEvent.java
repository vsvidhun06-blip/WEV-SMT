package com.weakest.model;

/**
 * A memory fence (barrier) event. Unlike {@link ReadEvent}/{@link WriteEvent} a fence
 * touches no location and produces no {@code rf}/{@code co}/{@code jf} edge; it
 * participates only in program order, where it restores the orderings a model otherwise
 * relaxes (per {@link FenceKind}). Day 12 (fence + RMW encoding).
 *
 * <p><b>Additive only.</b> This is a <em>new</em> subclass of the abstract {@link Event}
 * (the constraint permits new model classes, not edits to existing ones). The
 * {@link EventType} enum is closed (READ/WRITE/INIT) and cannot gain a {@code FENCE}
 * member without editing it, so the constructor passes {@link EventType#WRITE} purely as
 * a harmless sentinel: a fence is <em>not</em> {@code instanceof WriteEvent} and its type
 * is not {@code INIT}, so every writer-collecting predicate in the SMT layer
 * ({@code instanceof WriteEvent || type == INIT}) correctly excludes it. All fence
 * handling downstream is keyed on {@code instanceof FenceEvent}, never on the type tag.
 */
public class FenceEvent extends Event {

    /**
     * The ordering strength of a fence.
     * <ul>
     *   <li>{@link #FULL} — orders every access pair across it (W→R, W→W, R→W, R→R);</li>
     *   <li>{@link #ACQ} — orders reads-before → anything-after (acquire);</li>
     *   <li>{@link #REL} — orders anything-before → writes-after (release);</li>
     *   <li>{@link #ACQ_REL} — both (R→* ∪ *→W); note this is <em>not</em> W→R.</li>
     * </ul>
     */
    public enum FenceKind { FULL, ACQ, REL, ACQ_REL }

    private final FenceKind kind;

    public FenceEvent(int threadId, FenceKind kind) {
        // No location; WRITE is a sentinel EventType only (see class javadoc). A fence
        // is discriminated solely by instanceof FenceEvent downstream.
        super(threadId, EventType.WRITE, null, MemoryOrder.RELAXED, 0);
        this.kind = kind;
    }

    public FenceKind getKind() { return kind; }

    @Override
    public String toString() {
        return "F[T" + getThreadId() + "](" + kind.name().toLowerCase() + ")";
    }
}
