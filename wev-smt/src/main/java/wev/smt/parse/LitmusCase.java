package wev.smt.parse;

import com.weakest.model.EventStructure;

import wev.smt.DependencyInfo;
import wev.smt.LitmusCorpus.Outcome;
import wev.smt.MemoryModel;

import java.util.Map;

/**
 * The result of parsing one {@code .litmus} file: a WEV {@link EventStructure} (with
 * its candidate execution wired in from the {@code exists} clause), the syntactic
 * {@link DependencyInfo} sidecar, and the herd7 metadata needed to validate the
 * decision procedure against published ground truth.
 *
 * <p>The {@link EventStructure} mirrors what {@code wev.smt.LitmusCorpus} hand-builds:
 * one {@code WriteEvent(value 0)} per location on thread {@code 0} (the initial state),
 * the per-thread read/write events with program order linking consecutive accesses,
 * and a candidate {@code rf}/{@code co} fixing the outcome named by the {@code exists}
 * clause. Consistency of that wired execution under a model is the validated question
 * — exactly the {@code AtlasReconstruct} methodology — so a parsed case is a drop-in
 * for the hand-crafted catalogue.
 *
 * @param sourceName   the file (or test) the case was parsed from
 * @param arch         the architecture dialect it was written in
 * @param name         the test name from the header line
 * @param es           the wired event structure
 * @param deps         syntactic data/addr/ctrl dependencies; {@code empty()} if none
 * @param existsClause the raw {@code exists}/{@code forall} predicate text ("" if none);
 *                     stored as metadata — the candidate execution it names is already
 *                     wired into {@code es}, so final-state queries are not re-checked
 * @param expectations per-model herd7 ground truth where stated in comments; models
 *                     absent from the map have no recorded expectation
 * @param herdObservation the raw model-agnostic herd {@code Observation ...} line, or
 *                     "" — a coarse Always/Sometimes/Never verdict when no per-model
 *                     expectation is available
 */
public record LitmusCase(
        String sourceName,
        LitmusParser.Arch arch,
        String name,
        EventStructure es,
        DependencyInfo deps,
        String existsClause,
        Map<MemoryModel, Outcome> expectations,
        String herdObservation) {

    /** The true number of events in the structure (includes the initial writes). */
    public int eventCount() {
        return es.getEvents().size();
    }
}
