package wev.smt;

/** Supported axiomatic memory models. Mirrors {@link AxiomaticConsistency}. */
public enum MemoryModel {
    SC, TSO, PSO, RA, WEAKEST,
    /**
     * The RC11 thin-air baseline (paper §6.4). Distinguishing axiom is the blanket
     * no-thin-air rule {@code acyclic(po ∪ rf)} — RC11's syntactic repair of SC-DRF,
     * which forbids <em>all</em> load-buffering cycles regardless of whether the
     * dependency carrying them is real or fake. Contrast {@link #WEAKEST}, whose
     * {@code acyclic(sdep ∪ jf)} recovers grounded LB. This is a deliberately
     * simplified RC11 (no release/acquire annotations in our litmus format); see
     * {@link AxiomaticConsistency#consistencyRC11}.
     */
    RC11
}
