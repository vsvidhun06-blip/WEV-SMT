/* PROVENANCE
 * origin   : - reader/writer spinlock: Mellor-Crummey & Scott, "Scalable Reader-Writer
 *              Synchronization for Shared-Memory Multiprocessors", PPoPP 1991.
 *            - the bit-manipulation idioms are those of Linux include/linux/bitops.h and
 *              the Hacker's Delight (H. S. Warren, 2nd ed., 2012) catalogue: isolating
 *              the lowest set bit `x & -x`, clearing it `x & (x-1)`, and the
 *              self-cancelling `x ^ x` / `x & ~x` forms of ch. 2.
 * form     : independent reimplementations of the published algorithm and idioms.
 * license  : released under wev-smt terms.
 * why here : bit-twiddling code is where cancellation idioms genuinely occur in
 *            production sources; `x & ~x` and `x ^ x` appear as degenerate cases of
 *            mask arithmetic when a mask parameter is specialised to a constant.
 */
#include <stdatomic.h>

struct rwlock {
    _Atomic unsigned state;      /* bit0 = writer, bits1.. = reader count */
    _Atomic unsigned data;
};

void rw_read_lock(struct rwlock *l)
{
    for (;;) {
        unsigned s = atomic_load_explicit(&l->state, memory_order_acquire);
        if (s & 1u)
            continue;
        if (atomic_compare_exchange_weak_explicit(
                &l->state, &s, s + 2u, memory_order_acquire, memory_order_relaxed))
            return;
    }
}

void rw_read_unlock(struct rwlock *l)
{
    atomic_fetch_sub_explicit(&l->state, 2u, memory_order_release);
}

/* Hacker's Delight ch. 2: isolate lowest set bit — genuinely value-carrying. */
void rw_lowest_bit(struct rwlock *l, _Atomic unsigned *out)
{
    unsigned s = atomic_load_explicit(&l->state, memory_order_acquire);
    atomic_store_explicit(out, s & (unsigned)(-(int)s), memory_order_relaxed);
}

/* Clear lowest set bit — genuinely value-carrying. */
void rw_clear_lowest(struct rwlock *l, _Atomic unsigned *out)
{
    unsigned s = atomic_load_explicit(&l->state, memory_order_acquire);
    atomic_store_explicit(out, s & (s - 1u), memory_order_relaxed);
}

/* Degenerate mask specialisation: `s & ~s` is 0 for every s. Arises when a generic
 * mask-clearing helper is instantiated with mask == the value itself. */
void rw_mask_degenerate(struct rwlock *l, _Atomic unsigned *out)
{
    unsigned s = atomic_load_explicit(&l->state, memory_order_acquire);
    atomic_store_explicit(out, (s & ~s) + 1u, memory_order_relaxed);
}

/* Degenerate or specialisation: `s | ~s` is all-ones for every s. */
void rw_or_degenerate(struct rwlock *l, _Atomic unsigned *out)
{
    unsigned s = atomic_load_explicit(&l->state, memory_order_acquire);
    atomic_store_explicit(out, (s | ~s) & 1u, memory_order_relaxed);
}

/* Round-trip through a reversible transform: (s + k) - k == s. Value-carrying, but the
 * intermediate is a cancellation the linear pass must net to coefficient 1, not 0. */
void rw_roundtrip(struct rwlock *l, _Atomic unsigned *out, unsigned k)
{
    unsigned s = atomic_load_explicit(&l->state, memory_order_acquire);
    atomic_store_explicit(out, (s + k) - k, memory_order_relaxed);
}
