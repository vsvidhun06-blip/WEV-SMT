package wev.smt;

import com.weakest.model.Event;

import java.util.Map;
import java.util.Set;

/**
 * The minimum-cardinality consistent (or separating) execution extracted by
 * {@link MinimalWitnessExtractor}. Cardinality is the number of {@link #activeEvents}.
 */
public record MinimalWitness(
        Set<Event> activeEvents,
        Map<EventPair, Boolean> activeRf,
        Map<EventPair, Boolean> activeJf,
        Map<EventPair, Boolean> activeCo,
        int cardinality,
        long solveTimeMs,
        String summary) {
}
