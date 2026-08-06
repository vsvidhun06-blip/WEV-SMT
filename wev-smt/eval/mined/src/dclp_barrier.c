/* PROVENANCE
 * origin   : - double-checked locking: Schmidt & Harrison, "Double-Checked Locking",
 *              PLoP 1996; the C++11-correct form of Meyers & Alexandrescu, "C++ and the
 *              Perils of Double-Checked Locking", DDJ 2004.
 *            - sense-reversing barrier: Hensgen, Finkel, Manber, "Two Algorithms for
 *              Barrier Synchronization", IJPP 17(1), 1988.
 *            - atomic reference counting: the standard release/acquire fence idiom of
 *              Boost.Atomic's documentation, §"Reference counting".
 * form     : faithful transcriptions into C11 <stdatomic.h>.
 * license  : algorithms published; transcriptions under wev-smt terms.
 * why here : DCLP is the canonical publish/subscribe LB shape; the sense barrier flips a
 *            loaded flag with `!sense` and `sense ^ 1` — an xor with a CONSTANT, which is
 *            value-carrying and must not be confused with the self-xor cancellation.
 */
#include <stdatomic.h>
#include <stddef.h>

/* ---- double-checked locking ---- */
struct singleton {
    _Atomic(void *) inst;
    _Atomic int lock;
};

void *dclp_get(struct singleton *s, void *made)
{
    void *p = atomic_load_explicit(&s->inst, memory_order_acquire);
    if (p == NULL) {
        while (atomic_exchange_explicit(&s->lock, 1, memory_order_acquire))
            ;
        p = atomic_load_explicit(&s->inst, memory_order_relaxed);
        if (p == NULL) {
            p = made;
            atomic_store_explicit(&s->inst, p, memory_order_release);
        }
        atomic_store_explicit(&s->lock, 0, memory_order_release);
    }
    return p;
}

/* ---- sense-reversing barrier ---- */
struct barrier {
    _Atomic unsigned count;
    _Atomic int sense;
};

void barrier_wait(struct barrier *b, int n, int *local_sense)
{
    *local_sense = *local_sense ^ 1;          /* xor with a CONSTANT: value-carrying */
    unsigned arrived = atomic_fetch_add_explicit(&b->count, 1u, memory_order_acq_rel) + 1u;
    if (arrived == (unsigned)n) {
        atomic_store_explicit(&b->count, 0u, memory_order_relaxed);
        atomic_store_explicit(&b->sense, *local_sense, memory_order_release);
    } else {
        while (atomic_load_explicit(&b->sense, memory_order_acquire) != *local_sense)
            ;
    }
}

/* ---- atomic reference counting ---- */
struct refcnt {
    _Atomic int n;
    _Atomic int payload;
};

void rc_acquire(struct refcnt *r)
{
    atomic_fetch_add_explicit(&r->n, 1, memory_order_relaxed);
}

int rc_release(struct refcnt *r)
{
    if (atomic_fetch_sub_explicit(&r->n, 1, memory_order_release) == 1) {
        atomic_thread_fence(memory_order_acquire);
        return 1;                              /* last reference: safe to destroy */
    }
    return 0;
}

/* Self-cancelling refcount adjustment: add then subtract the same loaded delta. This is
 * a cancellation form embedded in an otherwise ordinary kernel. */
void rc_rebalance(struct refcnt *r, _Atomic int *other)
{
    int d = atomic_load_explicit(other, memory_order_acquire);
    atomic_store_explicit(&r->payload, d - d + 1, memory_order_relaxed);
}
