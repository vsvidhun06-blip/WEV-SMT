/* PROVENANCE
 * origin   : M. M. Michael and M. L. Scott, "Simple, Fast, and Practical Non-Blocking
 *            and Blocking Concurrent Queue Algorithms", PODC 1996.
 * form     : faithful transcription of the published algorithm into C11 <stdatomic.h>.
 * license  : algorithm is public domain; transcription released under the wev-smt terms.
 * why here : enqueue reads tail then writes tail->next (data flow load->store); dequeue
 *            reads head then reads head->next (address dependency). Between two threads
 *            this is the canonical LB shape over a linked structure.
 */
#include <stdatomic.h>
#include <stddef.h>

struct qnode {
    _Atomic(struct qnode *) next;
    int val;
};

struct msq {
    _Atomic(struct qnode *) head;
    _Atomic(struct qnode *) tail;
};

void ms_enqueue(struct msq *q, struct qnode *n)
{
    atomic_store_explicit(&n->next, NULL, memory_order_relaxed);
    for (;;) {
        struct qnode *t = atomic_load_explicit(&q->tail, memory_order_acquire);
        struct qnode *nxt = atomic_load_explicit(&t->next, memory_order_acquire);
        if (t == atomic_load_explicit(&q->tail, memory_order_acquire)) {
            if (nxt == NULL) {
                if (atomic_compare_exchange_weak_explicit(
                        &t->next, &nxt, n, memory_order_release, memory_order_relaxed)) {
                    atomic_compare_exchange_strong_explicit(
                        &q->tail, &t, n, memory_order_release, memory_order_relaxed);
                    return;
                }
            } else {
                atomic_compare_exchange_strong_explicit(
                    &q->tail, &t, nxt, memory_order_release, memory_order_relaxed);
            }
        }
    }
}

int ms_dequeue(struct msq *q, int *out)
{
    for (;;) {
        struct qnode *h = atomic_load_explicit(&q->head, memory_order_acquire);
        struct qnode *t = atomic_load_explicit(&q->tail, memory_order_acquire);
        struct qnode *nxt = atomic_load_explicit(&h->next, memory_order_acquire);
        if (h != atomic_load_explicit(&q->head, memory_order_acquire))
            continue;
        if (nxt == NULL)
            return 0;
        if (h == t) {
            atomic_compare_exchange_strong_explicit(
                &q->tail, &t, nxt, memory_order_release, memory_order_relaxed);
            continue;
        }
        *out = nxt->val;
        if (atomic_compare_exchange_weak_explicit(
                &q->head, &h, nxt, memory_order_release, memory_order_relaxed))
            return 1;
    }
}
