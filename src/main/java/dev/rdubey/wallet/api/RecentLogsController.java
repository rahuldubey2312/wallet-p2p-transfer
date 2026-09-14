package dev.rdubey.wallet.api;

import dev.rdubey.wallet.infrastructure.observability.RecentEvents;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the recent domain events over HTTP, without authentication.
 * <p>
 * The exercise asks for logs a reviewer can actually view. The hosting
 * provider's log console requires an account, so a link to it proves nothing
 * to someone who does not have one. This endpoint is that link: open it and
 * see the events, or run the burst script and refresh to watch your own
 * traffic appear.
 * <p>
 * Public on purpose. Nothing here is a secret: tokens are never logged, and
 * identities are opaque ids over play money. On a service holding real
 * customer data this would be behind an operator role.
 */
@RestController
public class RecentLogsController
{
    private final RecentEvents recentEvents;

    public RecentLogsController(RecentEvents recentEvents)
    {
        this.recentEvents = recentEvents;
    }

    @GetMapping("/logs/recent")
    public Map<String, Object> recent(@RequestParam(name = "limit", defaultValue = "100") int limit)
    {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("note", "Newest first. In-memory and capped, so it resets when the instance restarts; "
                             + "run ./burst.sh against this URL and refresh to populate it. "
                             + "The authoritative log is structured JSON on stdout.");
        response.put("buffered", recentEvents.size());
        response.put("events", recentEvents.tail(limit));
        return response;
    }
}
