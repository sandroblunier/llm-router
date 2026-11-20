package ch.ace.llmrouter.routing;

import ch.ace.llmrouter.api.Dtos.RouteResponse;
import ch.ace.llmrouter.api.Dtos.Usage;
import ch.ace.llmrouter.decision.Classification;
import ch.ace.llmrouter.decision.Decision;
import ch.ace.llmrouter.decision.DecisionService;
import ch.ace.llmrouter.llm.LlmProvider;
import ch.ace.llmrouter.log.DecisionLog;
import ch.ace.llmrouter.log.LogEvent;
import ch.ace.llmrouter.model.RequestStatus;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Orchestrates the flow: decide, log, wait for feedback, forward, log. */
@Singleton
public class RouterService {

    @Inject
    DecisionService decisions;

    @Inject
    DecisionLog log;

    @Inject
    PendingRequests pending;

    @Inject
    LlmProvider llm;

    public record Attachment(String fileName, String text) {
    }

    public RouteResponse route(String prompt, List<Attachment> attachments, boolean autoConfirm, boolean dryRun) {
        String text = buildText(prompt, attachments);
        String id = UUID.randomUUID().toString();
        Decision decision = decisions.decide(text);
        log.append(decisionEvent(id, decision, text.length(), attachments.size(), dryRun, autoConfirm));

        if (dryRun) {
            return RouteResponse.of(id, RequestStatus.DRY_RUN, decision);
        }
        if (autoConfirm) {
            log.append(LogEvent.builder(id, LogEvent.EventType.FEEDBACK).feedback("OK").autoConfirm(true).build());
            return forward(id, text, decision);
        }
        pending.put(id, text, decision);
        return RouteResponse.of(id, RequestStatus.PENDING_FEEDBACK, decision);
    }

    /** @return empty if the id is unknown or expired */
    public Optional<RouteResponse> feedback(String id, boolean ok) {
        Optional<PendingRequests.Pending> p = pending.take(id);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        Decision decision = p.get().decision();
        log.append(LogEvent.builder(id, LogEvent.EventType.FEEDBACK).feedback(ok ? "OK" : "NOK").build());
        if (!ok) {
            return Optional.of(RouteResponse.of(id, RequestStatus.ABORTED, decision));
        }
        return Optional.of(forward(id, p.get().text(), decision));
    }

    private RouteResponse forward(String id, String text, Decision decision) {
        try {
            LlmProvider.LlmResult r = llm.complete(decision.model(), text);
            log.append(LogEvent.builder(id, LogEvent.EventType.ANSWER)
                    .target(decision.target().name())
                    .model(r.model())
                    .inputTokens(r.inputTokens())
                    .outputTokens(r.outputTokens())
                    .latencyMs(r.latencyMs())
                    .build());
            return RouteResponse.withAnswer(id, RequestStatus.ANSWERED, decision, r.answer(),
                    new Usage(r.inputTokens(), r.outputTokens()), r.latencyMs());
        } catch (LlmProvider.LlmException e) {
            log.append(LogEvent.builder(id, LogEvent.EventType.ERROR)
                    .target(decision.target().name())
                    .model(decision.model())
                    .error(e.getMessage())
                    .build());
            throw new LlmCallFailedException(id, e.getMessage());
        }
    }

    static String buildText(String prompt, List<Attachment> attachments) {
        StringBuilder sb = new StringBuilder(prompt == null ? "" : prompt.strip());
        for (Attachment a : attachments) {
            sb.append("\n\n--- Anhang: ").append(a.fileName()).append(" ---\n").append(a.text());
        }
        return sb.toString();
    }

    private static LogEvent decisionEvent(String id, Decision d, int textLength, int attachmentCount,
                                          boolean dryRun, boolean autoConfirm) {
        Classification c = d.classification();
        return LogEvent.builder(id, LogEvent.EventType.DECISION)
                .privacyLevel(d.privacyLevel() == null ? null : d.privacyLevel().name())
                .complexity(d.complexity() == null ? null : d.complexity().name())
                .target(d.target().name())
                .model(d.model())
                .reason(d.reason())
                .cloudProbability(d.cloudProbability())
                .piiTypes(d.piiTypes())
                .privacyProbabilities(c == null ? null : c.privacyProbabilities())
                .complexityProbabilities(c == null ? null : c.complexityProbabilities())
                .piiProbabilities(c == null ? null : c.piiProbabilities())
                .decisionInputTokens(c == null ? null : c.inputTokens())
                .decisionLatencyMs(c == null ? null : c.latencyMs())
                .textLength(textLength)
                .attachmentCount(attachmentCount)
                .dryRun(dryRun ? true : null)
                .autoConfirm(autoConfirm ? true : null)
                .build();
    }

    public static class LlmCallFailedException extends RuntimeException {
        private final String id;

        public LlmCallFailedException(String id, String message) {
            super(message);
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
