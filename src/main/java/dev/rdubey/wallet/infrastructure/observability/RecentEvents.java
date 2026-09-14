package dev.rdubey.wallet.infrastructure.observability;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded, in-memory tail of the domain events, so the log stream can be
 * read over HTTP without an account on the hosting provider.
 * <p>
 * The provider's own log viewer sits behind a login, which makes it useless as
 * the "publicly viewable logs" the exercise asks for. Structured JSON on
 * stdout remains the real log; this is a window onto the last few hundred
 * lines of it that anyone can open.
 * <p>
 * Deliberately in memory and capped: it must not grow without bound, must not
 * need a disk the free tier does not have, and must not become a second
 * source of truth. It empties when the instance restarts, which is why the
 * README tells a reader to run the burst script first.
 */
@Component
public class RecentEvents
{
    private static final int CAPACITY = 500;

    private final Deque<Map<String, Object>> events = new ArrayDeque<>(CAPACITY);

    /**
     * Records one event. Called on the request path, so it takes the lock only
     * to push and never does formatting or I/O while holding it.
     */
    public void record(String event, String correlationId, Map<String, ?> fields)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", Instant.now().toString());
        entry.put("event", event);
        if (correlationId != null)
        {
            entry.put("correlation_id", correlationId);
        }
        fields.forEach((key, value) -> entry.put(key, String.valueOf(value)));

        synchronized (events)
        {
            if (events.size() == CAPACITY)
            {
                events.removeFirst();
            }
            events.addLast(entry);
        }
    }

    /**
     * The most recent events, newest first.
     */
    public List<Map<String, Object>> tail(int limit)
    {
        int bounded = Math.clamp(limit, 1, CAPACITY);
        List<Map<String, Object>> snapshot;
        synchronized (events)
        {
            snapshot = new ArrayList<>(events);
        }

        List<Map<String, Object>> newestFirst = new ArrayList<>(Math.min(bounded, snapshot.size()));
        for (int i = snapshot.size() - 1; i >= 0 && newestFirst.size() < bounded; i--)
        {
            newestFirst.add(snapshot.get(i));
        }
        return newestFirst;
    }

    public int size()
    {
        synchronized (events)
        {
            return events.size();
        }
    }
}
