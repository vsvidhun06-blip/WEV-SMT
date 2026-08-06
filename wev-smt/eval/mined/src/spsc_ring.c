/* PROVENANCE
 * origin   : Lamport single-producer/single-consumer circular buffer, as presented in
 *            L. Lamport, "Specifying Concurrent Program Modules", TOPLAS 5(2), 1983,
 *            and in the standard C11-atomics formulation used by Boost.Lockfree's
 *            spsc_queue and by the Linux kernel's kfifo.
 * form     : faithful transcription of the published algorithm into C11 <stdatomic.h>.
 *            NOT copied verbatim from any one implementation.
 * license  : algorithm is public domain / unencumbered; this transcription is released
 *            under the same terms as the wev-smt repository.
 * why here : the producer's store to the data slot is ordered after the consumer's
 *            index load only through arithmetic on that index (mask/modulo), which is
 *            exactly where compilers emit and/or eliminate dependency-shaped code.
 */
#include <stdatomic.h>
#include <stddef.h>

#define CAP 64u

struct spsc {
    _Atomic unsigned head;
    _Atomic unsigned tail;
    int buf[CAP];
};

int spsc_push(struct spsc *q, int v)
{
    unsigned h = atomic_load_explicit(&q->head, memory_order_relaxed);
    unsigned t = atomic_load_explicit(&q->tail, memory_order_acquire);
    if (h - t >= CAP)
        return 0;
    q->buf[h & (CAP - 1u)] = v;
    atomic_store_explicit(&q->head, h + 1u, memory_order_release);
    return 1;
}

int spsc_pop(struct spsc *q, int *out)
{
    unsigned t = atomic_load_explicit(&q->tail, memory_order_relaxed);
    unsigned h = atomic_load_explicit(&q->head, memory_order_acquire);
    if (h == t)
        return 0;
    *out = q->buf[t & (CAP - 1u)];
    atomic_store_explicit(&q->tail, t + 1u, memory_order_release);
    return 1;
}

/* Index-recycling variant: the slot index is recomputed as (t + CAP - t) style
 * arithmetic that a strength reducer may or may not cancel. This is the shape the
 * fake-dependency detector cares about, written the way ring-buffer code actually
 * gets written when the author is avoiding a modulo. */
int spsc_pop_recycle(struct spsc *q, int *out)
{
    unsigned t = atomic_load_explicit(&q->tail, memory_order_relaxed);
    unsigned h = atomic_load_explicit(&q->head, memory_order_acquire);
    if (h == t)
        return 0;
    *out = q->buf[(t + CAP - t) & (CAP - 1u)];
    atomic_store_explicit(&q->tail, t + 1u, memory_order_release);
    return 1;
}
