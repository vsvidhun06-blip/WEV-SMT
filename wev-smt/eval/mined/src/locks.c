/* PROVENANCE
 * origin   : four classical mutual-exclusion algorithms —
 *            - Dekker (1962), as reported in E. W. Dijkstra, "Cooperating Sequential
 *              Processes", EWD123, 1965;
 *            - G. L. Peterson, "Myths About the Mutual Exclusion Problem", IPL 12(3), 1981;
 *            - ticket lock: Mellor-Crummey & Scott, TOCS 9(1), 1991, §2.2;
 *            - MCS queue lock: Mellor-Crummey & Scott, TOCS 9(1), 1991, §3.
 * form     : faithful transcriptions of the published algorithms into C11 atomics.
 * license  : algorithms are public domain; transcriptions under wev-smt terms.
 * why here : Dekker and Peterson are the canonical store-buffering shapes; the ticket
 *            lock's `now_serving - my_ticket` is a subtraction of a loaded value that is
 *            genuinely value-carrying (a negative control for the detector); MCS writes a
 *            loaded predecessor pointer's field (load->store flow on a cycle).
 */
#include <stdatomic.h>
#include <stddef.h>

/* ---- Dekker ---- */
struct dekker {
    _Atomic int flag0, flag1;
    _Atomic int turn;
    _Atomic int crit;
};

int dekker_p0(struct dekker *d)
{
    atomic_store_explicit(&d->flag0, 1, memory_order_relaxed);
    int f = atomic_load_explicit(&d->flag1, memory_order_relaxed);
    if (f == 0) {
        atomic_store_explicit(&d->crit, 1, memory_order_relaxed);
        return 1;
    }
    return 0;
}

/* ---- Peterson ---- */
struct peterson {
    _Atomic int want0, want1;
    _Atomic int victim;
    _Atomic int crit;
};

int peterson_p0(struct peterson *p)
{
    atomic_store_explicit(&p->want0, 1, memory_order_relaxed);
    atomic_store_explicit(&p->victim, 0, memory_order_relaxed);
    int w = atomic_load_explicit(&p->want1, memory_order_acquire);
    int v = atomic_load_explicit(&p->victim, memory_order_acquire);
    if (w == 0 || v != 0) {
        atomic_store_explicit(&p->crit, 1, memory_order_relaxed);
        return 1;
    }
    return 0;
}

/* ---- ticket lock ---- */
struct ticket {
    _Atomic unsigned next;
    _Atomic unsigned serving;
};

void ticket_lock(struct ticket *t)
{
    unsigned my = atomic_fetch_add_explicit(&t->next, 1u, memory_order_relaxed);
    while (atomic_load_explicit(&t->serving, memory_order_acquire) != my)
        ;
}

void ticket_unlock(struct ticket *t)
{
    unsigned s = atomic_load_explicit(&t->serving, memory_order_relaxed);
    atomic_store_explicit(&t->serving, s + 1u, memory_order_release);
}

/* Waiter-count variant: `next - serving` is a genuine difference of two loads. */
unsigned ticket_waiters(struct ticket *t)
{
    unsigned n = atomic_load_explicit(&t->next, memory_order_relaxed);
    unsigned s = atomic_load_explicit(&t->serving, memory_order_relaxed);
    return n - s;
}

/* ---- MCS queue lock ---- */
struct mcs_node {
    _Atomic(struct mcs_node *) next;
    _Atomic int locked;
};

struct mcs_lock {
    _Atomic(struct mcs_node *) tail;
};

void mcs_acquire(struct mcs_lock *l, struct mcs_node *n)
{
    atomic_store_explicit(&n->next, NULL, memory_order_relaxed);
    struct mcs_node *pred = atomic_exchange_explicit(&l->tail, n, memory_order_acq_rel);
    if (pred) {
        atomic_store_explicit(&n->locked, 1, memory_order_relaxed);
        atomic_store_explicit(&pred->next, n, memory_order_release);
        while (atomic_load_explicit(&n->locked, memory_order_acquire))
            ;
    }
}

void mcs_release(struct mcs_lock *l, struct mcs_node *n)
{
    struct mcs_node *succ = atomic_load_explicit(&n->next, memory_order_acquire);
    if (succ)
        atomic_store_explicit(&succ->locked, 0, memory_order_release);
}
