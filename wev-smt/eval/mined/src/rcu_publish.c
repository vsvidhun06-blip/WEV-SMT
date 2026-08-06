/* PROVENANCE
 * origin   : Linux-kernel RCU publish/subscribe pattern — rcu_assign_pointer() /
 *            rcu_dereference(); documented in Documentation/RCU/rcu_dereference.rst and
 *            in P. E. McKenney, "Is Parallel Programming Hard, And, If So, What Can You
 *            Do About It?", ch. 9.
 * form     : independent reimplementation of the documented pattern in C11 atomics.
 * license  : pattern is documented prose; this code is released under wev-smt terms.
 * why here : rcu_dereference relies on the *address* dependency from the pointer load to
 *            the field load surviving the compiler. The kernel documentation explicitly
 *            warns about the dependency-killing transformations mined here:
 *            `p - p`, `p & 0`, `p ^ p`, and comparison-then-use-the-constant.
 */
#include <stdatomic.h>
#include <stddef.h>

struct cfg {
    int a;
    int b;
};

struct root {
    _Atomic(struct cfg *) gp;
    _Atomic int ready;
};

void rcu_publish(struct root *r, struct cfg *n, int a, int b)
{
    n->a = a;
    n->b = b;
    atomic_store_explicit(&r->gp, n, memory_order_release);
}

int rcu_consume(struct root *r)
{
    struct cfg *p = atomic_load_explicit(&r->gp, memory_order_consume);
    if (!p)
        return -1;
    return p->a;
}

/* The three dependency-killing idioms rcu_dereference.rst names by name.
 * These are the *documented hazards*, transcribed so the compiler can be asked what it
 * actually emits for them. */

int rcu_consume_selfsub(struct root *r, struct cfg *known)
{
    struct cfg *p = atomic_load_explicit(&r->gp, memory_order_consume);
    /* pointer difference cancels the loaded value entirely */
    return ((struct cfg *)((char *)known + ((char *)p - (char *)p)))->a;
}

int rcu_consume_andzero(struct root *r, struct cfg *known)
{
    unsigned long p = (unsigned long)atomic_load_explicit(&r->gp, memory_order_consume);
    return ((struct cfg *)((unsigned long)known + (p & 0UL)))->a;
}

int rcu_consume_selfxor(struct root *r, struct cfg *known)
{
    unsigned long p = (unsigned long)atomic_load_explicit(&r->gp, memory_order_consume);
    return ((struct cfg *)((unsigned long)known + (p ^ p)))->a;
}

/* Compare-then-use: the classic "the compiler knows p == known so it uses known"
 * dependency breaker that rcu_dereference.rst forbids. */
int rcu_consume_cmp(struct root *r, struct cfg *known)
{
    struct cfg *p = atomic_load_explicit(&r->gp, memory_order_consume);
    if (p == known)
        return p->a;
    return -1;
}
