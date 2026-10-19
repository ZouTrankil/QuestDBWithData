package com.zoutrankil.data.client;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded transport diagnostics. Contains no URL, request/response bodies or headers. */
@Component
public class HttpObservations {
    public record Event(Instant observedAt, String protocol, String connectionId, int status) {}
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    public synchronized void record(String protocol, String connectionId, int status) {
        if (events.size() == 200) events.removeFirst();
        events.addLast(new Event(Instant.now(), protocol, connectionId, status));
    }
    public synchronized List<Event> snapshot() { return List.copyOf(events); }
}
