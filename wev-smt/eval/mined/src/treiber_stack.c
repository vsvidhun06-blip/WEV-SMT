/* PROVENANCE
 * origin   : R. K. Treiber, "Systems Programming: Coping with Parallelism",
 *            IBM Almaden Research Center RJ 5118, 1986. The canonical lock-free stack.
 * form     : faithful transcription of the published algorithm into C11 <stdatomic.h>.
 * license  : algorithm is public domain; this transcription is released under the same
 *            terms as the wev-smt repository.
 * why here : pop() loads the head pointer and then loads head->next — a genuine address
 *            dependency — while push() writes n->next from a loaded head, giving the
 *            load-buffering shape between the two CAS loops.
 */
#include <stdatomic.h>
#include <stddef.h>

struct node {
    _Atomic(struct node *) next;
    int val;
};

struct stack {
    _Atomic(struct node *) top;
};

void treiber_push(struct stack *s, struct node *n)
{
    struct node *old = atomic_load_explicit(&s->top, memory_order_relaxed);
    do {
        atomic_store_explicit(&n->next, old, memory_order_relaxed);
    } while (!atomic_compare_exchange_weak_explicit(
                 &s->top, &old, n, memory_order_release, memory_order_relaxed));
}

struct node *treiber_pop(struct stack *s)
{
    struct node *old = atomic_load_explicit(&s->top, memory_order_acquire);
    while (old) {
        struct node *nxt = atomic_load_explicit(&old->next, memory_order_relaxed);
        if (atomic_compare_exchange_weak_explicit(
                &s->top, &old, nxt, memory_order_acquire, memory_order_acquire))
            return old;
    }
    return NULL;
}

/* Tag-recycling ABA counter variant. The tag is advanced and then rolled back within
 * the same expression, which is where the strength reducer meets a cancelling form. */
struct tagged {
    _Atomic unsigned long packed;   /* ptr<<16 | tag */
};

void treiber_push_tagged(struct tagged *s, unsigned long ptr)
{
    unsigned long old = atomic_load_explicit(&s->packed, memory_order_relaxed);
    unsigned long tag = old & 0xffffUL;
    unsigned long nw = (ptr << 16) | ((tag + 1UL - tag) & 0xffffUL);
    atomic_store_explicit(&s->packed, nw, memory_order_release);
}
