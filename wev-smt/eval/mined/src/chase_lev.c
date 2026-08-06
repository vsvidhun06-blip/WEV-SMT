/* PROVENANCE
 * origin   : D. Chase and Y. Lev, "Dynamic Circular Work-Stealing Deque", SPAA 2005;
 *            with the C11 memory-order annotations of Lê, Pop, Cohen, Zappa Nardelli,
 *            "Correct and Efficient Work-Stealing for Weak Memory Models", PPoPP 2013.
 * form     : faithful transcription of the PPoPP 2013 Figure 1 algorithm into C11 atomics.
 * license  : algorithm published; transcription under wev-smt terms.
 * why here : `size = bottom - top` is a difference of two atomic loads that genuinely
 *            carries value (negative control), while the buffer index `bottom & mask` is
 *            a preserved address dependency. take() and steal() form an LB cycle.
 */
#include <stdatomic.h>
#include <stddef.h>

#define DCAP 1024L

struct deque {
    _Atomic long top;
    _Atomic long bottom;
    _Atomic int buf[DCAP];
};

void cl_push(struct deque *q, int v)
{
    long b = atomic_load_explicit(&q->bottom, memory_order_relaxed);
    long t = atomic_load_explicit(&q->top, memory_order_acquire);
    if (b - t > DCAP - 1)
        return;
    atomic_store_explicit(&q->buf[b & (DCAP - 1)], v, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&q->bottom, b + 1, memory_order_relaxed);
}

int cl_steal(struct deque *q, int *out)
{
    long t = atomic_load_explicit(&q->top, memory_order_acquire);
    atomic_thread_fence(memory_order_seq_cst);
    long b = atomic_load_explicit(&q->bottom, memory_order_acquire);
    if (t >= b)
        return 0;
    int v = atomic_load_explicit(&q->buf[t & (DCAP - 1)], memory_order_relaxed);
    if (!atomic_compare_exchange_strong_explicit(
            &q->top, &t, t + 1, memory_order_seq_cst, memory_order_relaxed))
        return 0;
    *out = v;
    return 1;
}

int cl_take(struct deque *q, int *out)
{
    long b = atomic_load_explicit(&q->bottom, memory_order_relaxed) - 1;
    atomic_store_explicit(&q->bottom, b, memory_order_relaxed);
    atomic_thread_fence(memory_order_seq_cst);
    long t = atomic_load_explicit(&q->top, memory_order_relaxed);
    if (t > b) {
        atomic_store_explicit(&q->bottom, b + 1, memory_order_relaxed);
        return 0;
    }
    *out = atomic_load_explicit(&q->buf[b & (DCAP - 1)], memory_order_relaxed);
    if (t == b) {
        if (!atomic_compare_exchange_strong_explicit(
                &q->top, &t, t + 1, memory_order_seq_cst, memory_order_relaxed)) {
            atomic_store_explicit(&q->bottom, b + 1, memory_order_relaxed);
            return 0;
        }
        atomic_store_explicit(&q->bottom, b + 1, memory_order_relaxed);
    }
    return 1;
}
