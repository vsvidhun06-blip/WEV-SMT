package wev.smt;

import com.weakest.model.Event;

/** Directed pair (from → to) used as a key for relation decision variables. */
public record EventPair(Event from, Event to) {
}
