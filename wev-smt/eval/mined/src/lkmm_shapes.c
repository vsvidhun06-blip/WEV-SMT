/* PROVENANCE
 * origin   : the named litmus shapes of the Linux-kernel memory model, as catalogued in
 *            tools/memory-model/litmus-tests/ (Alglave, Maranget, McKenney, Parri, Stern,
 *            "Frightening Small Children and Disconcerting Grown-ups: Concurrency in the
 *            Linux Kernel", ASPLOS 2018) — MP, LB, SB, R, S, WRC, ISA2, Z6.
 * form     : each shape written as the C thread bodies the LKMM litmus tests specify,
 *            using READ_ONCE/WRITE_ONCE spelled as relaxed C11 atomics.
 * license  : LKMM litmus tests are GPL-2.0; these are independent reimplementations of
 *            the published shapes, not copies of kernel source text.
 * why here : these are the shapes on which dependency classification actually matters —
 *            the compiler is asked what it emits for the dependency-carrying thread of
 *            each, at -O2, so the mining can tell preserved deps from erased ones.
 */
#include <stdatomic.h>

typedef _Atomic int a_int;

/* ---- MP: message passing, data + flag ---- */
void mp_writer(a_int *buf, a_int *flag)
{
    atomic_store_explicit(buf, 42, memory_order_relaxed);
    atomic_store_explicit(flag, 1, memory_order_release);
}

int mp_reader(a_int *buf, a_int *flag)
{
    if (atomic_load_explicit(flag, memory_order_acquire))
        return atomic_load_explicit(buf, memory_order_relaxed);
    return -1;
}

/* ---- LB: load buffering, genuine data dependency ---- */
void lb_thread_data(a_int *x, a_int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, r, memory_order_relaxed);
}

/* ---- LB with a genuine control dependency ---- */
void lb_thread_ctrl(a_int *x, a_int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    if (r)
        atomic_store_explicit(y, 1, memory_order_relaxed);
}

/* ---- LB with a genuine address dependency (pointer chase) ---- */
int lb_thread_addr(_Atomic(int *) *x, int *fallback)
{
    int *p = atomic_load_explicit(x, memory_order_relaxed);
    if (!p)
        return *fallback;
    return *p;
}

/* ---- SB: store buffering ---- */
int sb_thread(a_int *x, a_int *y)
{
    atomic_store_explicit(x, 1, memory_order_relaxed);
    return atomic_load_explicit(y, memory_order_relaxed);
}

/* ---- WRC: write-to-read causality ---- */
void wrc_p1(a_int *x, a_int *y)
{
    int r = atomic_load_explicit(x, memory_order_relaxed);
    atomic_store_explicit(y, r, memory_order_release);
}

/* ---- ISA2: chain of dependencies ---- */
void isa2_p1(a_int *y, a_int *z)
{
    int r = atomic_load_explicit(y, memory_order_acquire);
    atomic_store_explicit(z, r, memory_order_release);
}

/* ---- R shape ---- */
void r_p0(a_int *x, a_int *y)
{
    atomic_store_explicit(x, 1, memory_order_relaxed);
    atomic_store_explicit(y, 1, memory_order_release);
}

/* ---- Z6 / S shape: store then dependent store ---- */
void s_p1(a_int *y, a_int *z)
{
    int r = atomic_load_explicit(y, memory_order_relaxed);
    atomic_store_explicit(z, r + 1, memory_order_relaxed);
}
