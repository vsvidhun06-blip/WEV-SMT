/* PROVENANCE
 * origin   : M. M. Michael, "Hazard Pointers: Safe Memory Reclamation for Lock-Free
 *            Objects", IEEE TPDS 15(6), 2004.
 * form     : faithful transcription of the published algorithm into C11 <stdatomic.h>.
 * license  : algorithm published/patent expired; transcription under wev-smt terms.
 * why here : protect() loads the shared pointer, publishes it to the hazard slot, then
 *            RE-LOADS and compares. The compare-and-reuse is exactly the compare-then-use
 *            dependency-breaking shape rcu_dereference.rst warns about, here on a
 *            load-buffering cycle between the reader and the reclaimer.
 */
#include <stdatomic.h>
#include <stddef.h>

struct obj {
    int val;
};

struct hp_domain {
    _Atomic(struct obj *) shared;
    _Atomic(struct obj *) hazard;
};

struct obj *hp_protect(struct hp_domain *d)
{
    struct obj *p;
    for (;;) {
        p = atomic_load_explicit(&d->shared, memory_order_acquire);
        atomic_store_explicit(&d->hazard, p, memory_order_seq_cst);
        if (p == atomic_load_explicit(&d->shared, memory_order_acquire))
            return p;
    }
}

int hp_read(struct hp_domain *d)
{
    struct obj *p = hp_protect(d);
    if (!p)
        return -1;
    return p->val;
}

void hp_retire(struct hp_domain *d, struct obj *o)
{
    struct obj *h = atomic_load_explicit(&d->hazard, memory_order_acquire);
    if (h != o)
        atomic_store_explicit(&d->shared, NULL, memory_order_release);
}
