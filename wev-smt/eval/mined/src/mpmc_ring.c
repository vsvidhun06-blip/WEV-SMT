/* PROVENANCE
 * origin   : D. Vyukov, "Bounded MPMC queue", 1024cores.net (2010); the per-slot
 *            sequence-number bounded MPMC ring, widely reimplemented (folly, moodycamel).
 * form     : faithful transcription of the published algorithm into C11 <stdatomic.h>.
 * license  : Vyukov's publication is BSD-style / public; transcription under wev-smt terms.
 * why here : the slot index is `pos & mask` (address dependency from an atomic load) and
 *            the slot sequence is compared by `seq - (pos + 1)` — a subtraction of two
 *            loaded quantities, the shape a cancellation detector must NOT call fake.
 */
#include <stdatomic.h>
#include <stddef.h>

#define MCAP 64u

struct cell {
    _Atomic unsigned seq;
    int data;
};

struct mpmc {
    _Atomic unsigned enq;
    _Atomic unsigned deq;
    struct cell buf[MCAP];
};

int mpmc_push(struct mpmc *q, int v)
{
    unsigned pos = atomic_load_explicit(&q->enq, memory_order_relaxed);
    for (;;) {
        struct cell *c = &q->buf[pos & (MCAP - 1u)];
        unsigned seq = atomic_load_explicit(&c->seq, memory_order_acquire);
        int diff = (int)(seq - pos);
        if (diff == 0) {
            if (atomic_compare_exchange_weak_explicit(
                    &q->enq, &pos, pos + 1u, memory_order_relaxed, memory_order_relaxed)) {
                c->data = v;
                atomic_store_explicit(&c->seq, pos + 1u, memory_order_release);
                return 1;
            }
        } else if (diff < 0) {
            return 0;
        } else {
            pos = atomic_load_explicit(&q->enq, memory_order_relaxed);
        }
    }
}

int mpmc_pop(struct mpmc *q, int *out)
{
    unsigned pos = atomic_load_explicit(&q->deq, memory_order_relaxed);
    for (;;) {
        struct cell *c = &q->buf[pos & (MCAP - 1u)];
        unsigned seq = atomic_load_explicit(&c->seq, memory_order_acquire);
        int diff = (int)(seq - (pos + 1u));
        if (diff == 0) {
            if (atomic_compare_exchange_weak_explicit(
                    &q->deq, &pos, pos + 1u, memory_order_relaxed, memory_order_relaxed)) {
                *out = c->data;
                atomic_store_explicit(&c->seq, pos + MCAP, memory_order_release);
                return 1;
            }
        } else if (diff < 0) {
            return 0;
        } else {
            pos = atomic_load_explicit(&q->deq, memory_order_relaxed);
        }
    }
}
