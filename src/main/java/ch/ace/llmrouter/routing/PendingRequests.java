package ch.ace.llmrouter.routing;

import ch.ace.llmrouter.config.RouterConfig;
import ch.ace.llmrouter.decision.Decision;
import ch.ace.llmrouter.log.DecisionLog;
import ch.ace.llmrouter.log.LogEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Requests waiting for OK/NOK feedback. In memory only; lost on restart. */
@Singleton
public class PendingRequests {

    @Inject
    RouterConfig config;

    @Inject
    DecisionLog log;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public record Pending(String text, Decision decision, Instant createdAt) {
    }

    public void put(String id, String text, Decision decision) {
        pending.put(id, new Pending(text, decision, Instant.now()));
    }

    /** Removes and returns the pending request, or empty if unknown or expired. */
    public Optional<Pending> take(String id) {
        Pending p = pending.remove(id);
        if (p == null) {
            return Optional.empty();
        }
        if (isExpired(p, Instant.now())) {
            logExpired(id);
            return Optional.empty();
        }
        return Optional.of(p);
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        Instant now = Instant.now();
        pending.forEach((id, p) -> {
            if (isExpired(p, now) && pending.remove(id, p)) {
                logExpired(id);
            }
        });
    }

    private boolean isExpired(Pending p, Instant now) {
        return p.createdAt().plus(config.pending().ttl()).isBefore(now);
    }

    private void logExpired(String id) {
        log.append(LogEvent.builder(id, LogEvent.EventType.EXPIRED).build());
    }
}
