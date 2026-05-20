package com.weakest.model;

import java.util.*;

public class EventStructure {
    private List<Event> events;
    private Map<Integer, List<Integer>> programOrder;
    private Map<Integer, Integer> readsFrom;
    private Map<String, List<Integer>> coherenceOrder;

    // jf (justifies-from): read → write. Mirrors readsFrom in shape but is allowed
    // to point at speculative/promised writes that have not yet committed in the
    // execution under construction. Cf. Chakraborty & Vafeiadis, POPL'19 §3-4.
    private Map<Event, Event> justifiesFrom;

    // ew (event-wise): equivalence classes of events that "happen together"
    // (a single observable step in the event structure semantics).
    private Set<Set<Event>> eventWise;

    public EventStructure() {
        events = new ArrayList<>();
        programOrder = new HashMap<>();
        readsFrom = new HashMap<>();
        coherenceOrder = new HashMap<>();
        justifiesFrom = new LinkedHashMap<>();
        eventWise = new LinkedHashSet<>();
    }

    public void addEvent(Event event) {
        events.add(event);
        programOrder.put(event.getId(), new ArrayList<>());
    }

    public void addProgramOrder(Event from, Event to) {
        programOrder.computeIfAbsent(from.getId(), k -> new ArrayList<>()).add(to.getId());
    }

    public void addReadsFrom(ReadEvent read, Event write) {
        readsFrom.put(read.getId(), write.getId());
        read.setJustifiedBy(write);
    }

    public void addCoherenceOrder(String variable, WriteEvent write) {
        coherenceOrder.computeIfAbsent(variable, k -> new ArrayList<>()).add(write.getId());
    }

    public void addJustifiesFrom(ReadEvent read, Event write) {
        justifiesFrom.put(read, write);
    }

    public void addEventWiseClass(Set<Event> cls) {
        eventWise.add(new LinkedHashSet<>(cls));
    }

    public void addEventWiseClass(Event... members) {
        Set<Event> cls = new LinkedHashSet<>();
        Collections.addAll(cls, members);
        eventWise.add(cls);
    }

    public List<Event> getEvents() { return events; }
    public Map<Integer, List<Integer>> getProgramOrder() { return programOrder; }
    public Map<Integer, Integer> getReadsFrom() { return readsFrom; }
    public Map<String, List<Integer>> getCoherenceOrder() { return coherenceOrder; }
    public Map<Event, Event> getJustifiesFrom() { return justifiesFrom; }
    public Set<Set<Event>> getEventWise() { return eventWise; }

    public Event getEventById(int id) {
        return events.stream().filter(e -> e.getId() == id).findFirst().orElse(null);
    }

    public List<WriteEvent> getWritesTo(String variable) {
        List<WriteEvent> writes = new ArrayList<>();
        for (Event e : events) {
            if ((e instanceof WriteEvent || e.getType() == EventType.INIT) && e.getVariable().equals(variable))
                writes.add((WriteEvent) e);
        }
        return writes;
    }
}