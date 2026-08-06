/* PROVENANCE
 * origin   : the "the optimiser cannot see the cancellation" cases. Every function here
 *            is a fake dependency whose vacuity is hidden from constant folding by a
 *            construct real code actually uses:
 *              - `volatile` reads (Linux READ_ONCE is a volatile access);
 *              - an opaque extern call the compiler cannot inline;
 *              - an inline-asm optimisation barrier (Linux `barrier()`, and the
 *                `OPTIMIZER_HIDE_VAR` macro of include/linux/compiler.h);
 *              - a runtime-unknown mask that happens to be zero.
 * form     : the hiding constructs are quoted from Linux include/linux/compiler.h
 *            semantics (independent reimplementation, not kernel source text).
 * license  : released under wev-smt terms.
 * why here : THIS is the population where a fake dependency can still be present in
 *            -O2 output: the value is provably constant to a solver reasoning about the
 *            whole expression, but NOT foldable by the compiler at compile time. If
 *            mined fake-dependency discriminators exist at -O2 at all, they live here.
 */
#include <stdatomic.h>

/* Linux include/linux/compiler.h OPTIMIZER_HIDE_VAR, semantics reimplemented. */
#define HIDE(v) __asm__ __volatile__("" : "+r" (v))

extern int opaque_identity(int);          /* not inlinable: no definition in this TU */

/* --- volatile-hidden self-xor --- */
void ofd_volatile_xor(volatile int *x, _Atomic int *y)
{
    int a = *x;
    int b = *x;                            /* volatile: compiler must re-read */
    atomic_store_explicit(y, (a ^ a) | 1, memory_order_relaxed);
    (void)b;
}

/* --- barrier-hidden self-subtraction --- */
void ofd_hidden_sub(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    int a = r;
    HIDE(a);                               /* compiler loses track of a == r */
    atomic_store_explicit(y, r - a + 1, memory_order_relaxed);
}

/* --- barrier-hidden and-with-zero --- */
void ofd_hidden_andzero(_Atomic int *x, _Atomic int *y, int zero)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    HIDE(zero);                            /* zero is 0 at every call site, opaque here */
    atomic_store_explicit(y, (r & zero) + 1, memory_order_relaxed);
}

/* --- opaque-call round trip: f(r) - f(r) --- */
void ofd_opaque_cancel(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, opaque_identity(r) - opaque_identity(r) + 1,
                          memory_order_relaxed);
}

/* --- hidden self-xor: the two operands are the same value, but the barrier stops the
 *     compiler proving it. Emits a genuine `eor` of two live registers. --- */
void ofd_hidden_xor(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    int a = r, b = r;
    HIDE(a);
    HIDE(b);
    atomic_store_explicit(y, (a ^ b) | 1, memory_order_relaxed);
}

/* --- negative control: genuinely value-carrying, also hidden --- */
void ofd_hidden_real(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    int a = r;
    HIDE(a);
    atomic_store_explicit(y, a + 1, memory_order_relaxed);
}
