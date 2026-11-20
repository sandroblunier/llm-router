package ch.ace.llmrouter.api;

import ch.ace.llmrouter.decision.Decision;
import ch.ace.llmrouter.model.RequestStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Request and response bodies of the REST API. */
public final class Dtos {

    private Dtos() {
    }

    public record RouteRequest(String prompt, Boolean autoConfirm, Boolean dryRun) {
    }

    public record FeedbackRequest(String feedback) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reasons(
            String reason,
            Double cloudProbability,
            List<String> piiTypes,
            Map<String, Double> privacyProbabilities,
            Map<String, Double> complexityProbabilities) {

        static Reasons of(Decision d) {
            return new Reasons(d.reason(), d.cloudProbability(), d.piiTypes(),
                    d.classification() == null ? null : d.classification().privacyProbabilities(),
                    d.classification() == null ? null : d.classification().complexityProbabilities());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(Integer inputTokens, Integer outputTokens) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RouteResponse(
            String id,
            RequestStatus status,
            String privacyLevel,
            String complexity,
            String target,
            String model,
            Reasons reasons,
            String answer,
            Usage usage,
            Long latencyMs) {

        public static RouteResponse of(String id, RequestStatus status, Decision d) {
            return withAnswer(id, status, d, null, null, null);
        }

        public static RouteResponse withAnswer(String id, RequestStatus status, Decision d,
                                               String answer, Usage usage, Long latencyMs) {
            return new RouteResponse(id, status,
                    d.privacyLevel() == null ? null : d.privacyLevel().name(),
                    d.complexity() == null ? null : d.complexity().name(),
                    d.target().name(), d.model(), Reasons.of(d), answer, usage, latencyMs);
        }
    }

    public record ErrorResponse(String id, String error) {
    }

    public record RequestLog(String id, List<ch.ace.llmrouter.log.LogEvent> events) {
    }
}
