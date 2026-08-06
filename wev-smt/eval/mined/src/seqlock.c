/* PROVENANCE
 * origin   : Linux kernel seqlock (include/linux/seqlock.h), described in
 *            H.-J. Boehm, "Can Seqlocks Get Along With Programming Language Memory
 *            Models?", MSPC 2012.
 * form     : faithful transcription of the published/kernel algorithm into C11 atomics.
 * license  : Linux kernel seqlock is GPL-2.0; this is an independent reimplementation of
 *            the published algorithm, not a copy of kernel source text.
 * why here : the reader re-reads the sequence counter and compares it to the first read;
 *            the comparison (seq1 ^ seq2), (seq2 - seq1) and (seq1 & 1) are precisely the
 *            xor/sub/and idioms the fake-dependency detector pattern-matches.
 */
#include <stdatomic.h>

struct seqlock {
    _Atomic unsigned seq;
    _Atomic int a;
    _Atomic int b;
};

void seq_write(struct seqlock *s, int a, int b)
{
    unsigned q = atomic_load_explicit(&s->seq, memory_order_relaxed);
    atomic_store_explicit(&s->seq, q + 1u, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&s->a, a, memory_order_relaxed);
    atomic_store_explicit(&s->b, b, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&s->seq, q + 2u, memory_order_relaxed);
}

int seq_read(struct seqlock *s, int *pa, int *pb)
{
    unsigned s1, s2;
    do {
        s1 = atomic_load_explicit(&s->seq, memory_order_acquire);
        if (s1 & 1u)
            continue;
        *pa = atomic_load_explicit(&s->a, memory_order_relaxed);
        *pb = atomic_load_explicit(&s->b, memory_order_relaxed);
        atomic_thread_fence(memory_order_acquire);
        s2 = atomic_load_explicit(&s->seq, memory_order_relaxed);
    } while (s1 ^ s2);
    return 1;
}

/* Retry-count variant: the difference of the two sequence reads is folded back into a
 * relaxed store, which is the load->store value flow the detector must classify. */
void seq_read_report(struct seqlock *s, _Atomic unsigned *retries)
{
    unsigned s1 = atomic_load_explicit(&s->seq, memory_order_acquire);
    unsigned s2 = atomic_load_explicit(&s->seq, memory_order_relaxed);
    atomic_store_explicit(retries, s2 - s1, memory_order_relaxed);
}
