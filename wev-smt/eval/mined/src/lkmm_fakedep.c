/* PROVENANCE
 * origin   : the fake-dependency idiom catalogue named in
 *            (a) Linux Documentation/RCU/rcu_dereference.rst ("dependency-killing"),
 *            (b) Linux Documentation/memory-barriers.txt, "CONTROL DEPENDENCIES",
 *            (c) Jeffrey et al., "The Leaky Semicolon", POPL 2022, §2 (LBfd/LBxor/LBfc),
 *            (d) Chakraborty & Vafeiadis, "Grounding Thin-Air Reads with Event
 *                Structures", POPL 2019, §2.
 * form     : each function is one named idiom from those sources, written on a
 *            load-buffering skeleton so that the load->store flow is on a cycle.
 * license  : released under wev-smt terms.
 * why here : this is the PROBE. Every idiom the wev-smt detector pattern-matches is
 *            written here on an LB shape; compiling at -O2 answers the question the
 *            mining exercise exists to answer — does a real optimiser PRESERVE any of
 *            these as machine-level load->store edges, or does it fold them away before
 *            code generation? The answer is data, not assumption.
 */
#include <stdatomic.h>

/* Every kernel below is one thread of a two-thread load-buffering test:
 *      P0:  r0 = X;  Y = f(r0);
 *      P1:  r1 = Y;  X = g(r1);
 * with f/g the idiom under test. Split into separate functions so each compiles to an
 * isolated basic block that the extractor can attribute unambiguously. */

/* (c) LBxor — self-xor cancellation */
void lb_xor(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, r ^ r ^ 1, memory_order_relaxed);
}

/* (c) LBfd — linear cancellation r + 1 - r */
void lb_linear(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, r + 1 - r, memory_order_relaxed);
}

/* (a) self-subtraction */
void lb_sub(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, (r - r) + 1, memory_order_relaxed);
}

/* (a) and-with-zero */
void lb_andzero(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, (r & 0) | 1, memory_order_relaxed);
}

/* multiply-by-zero */
void lb_mulzero(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, r * 0 + 1, memory_order_relaxed);
}

/* self-masking r & r — value-preserving, so this one is a REAL dependency; included as
 * the negative control that must NOT be classified fake. */
void lb_selfand(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, r & r, memory_order_relaxed);
}

/* copy-through: r flows verbatim. Negative control (REAL). */
void lb_copy(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    int t = r;
    atomic_store_explicit(y, t, memory_order_relaxed);
}

/* or-with-complement: r | ~r == -1 for every r. */
void lb_orcompl(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, (r | ~r) & 1, memory_order_relaxed);
}

/* (b)(c) LBfc — fake CONTROL dependency: both arms store the same value. */
void lb_fakectrl(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    if (r == 1)
        atomic_store_explicit(y, 1, memory_order_relaxed);
    else
        atomic_store_explicit(y, 1, memory_order_relaxed);
}

/* genuine control dependency: arms differ. Negative control (REAL). */
void lb_realctrl(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    if (r == 1)
        atomic_store_explicit(y, 1, memory_order_relaxed);
    else
        atomic_store_explicit(y, 2, memory_order_relaxed);
}

/* shift-out: on 32-bit int, (r << 31) >> 31 keeps only the low bit — still REAL. But
 * (r & 0) << 4 is fake. Both spellings included. */
void lb_shiftmask(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, ((r & 0) << 4) + 1, memory_order_relaxed);
}

/* modulo-1: r % 1 == 0 for every r. */
void lb_modone(_Atomic int *x, _Atomic int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, (r % 1) + 1, memory_order_relaxed);
}
