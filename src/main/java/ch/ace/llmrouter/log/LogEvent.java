package ch.ace.llmrouter.log;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One line in the JSONL log. Never contains request or answer text.
 * Fields that do not apply to an event type are null and omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogEvent(
        Instant timestamp,
        String id,
        EventType type,
        // DECISION
        String privacyLevel,
        String complexity,
        String target,
        String model,
        String reason,
        Double cloudProbability,
        List<String> piiTypes,
        Map<String, Double> privacyProbabilities,
        Map<String, Double> complexityProbabilities,
        Map<String, Double> piiProbabilities,
        Integer textLength,
        Integer attachmentCount,
        Boolean dryRun,
        Boolean autoConfirm,
        Integer decisionInputTokens,
        Long decisionLatencyMs,
        // FEEDBACK
        String feedback,
        // ANSWER
        Integer inputTokens,
        Integer outputTokens,
        Long latencyMs,
        // ERROR
        String error) {

    public enum EventType {
        DECISION, FEEDBACK, ANSWER, ERROR, EXPIRED
    }

    public static Builder builder(String id, EventType type) {
        return new Builder(id, type);
    }

    public static final class Builder {
        private final String id;
        private final EventType type;
        private String privacyLevel;
        private String complexity;
        private String target;
        private String model;
        private String reason;
        private Double cloudProbability;
        private List<String> piiTypes;
        private Map<String, Double> privacyProbabilities;
        private Map<String, Double> complexityProbabilities;
        private Map<String, Double> piiProbabilities;
        private Integer textLength;
        private Integer attachmentCount;
        private Boolean dryRun;
        private Boolean autoConfirm;
        private Integer decisionInputTokens;
        private Long decisionLatencyMs;
        private String feedback;
        private Integer inputTokens;
        private Integer outputTokens;
        private Long latencyMs;
        private String error;

        private Builder(String id, EventType type) {
            this.id = id;
            this.type = type;
        }

        public Builder privacyLevel(String v) { privacyLevel = v; return this; }
        public Builder complexity(String v) { complexity = v; return this; }
        public Builder target(String v) { target = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder reason(String v) { reason = v; return this; }
        public Builder cloudProbability(Double v) { cloudProbability = v; return this; }
        public Builder piiTypes(List<String> v) { piiTypes = v; return this; }
        public Builder privacyProbabilities(Map<String, Double> v) { privacyProbabilities = v; return this; }
        public Builder complexityProbabilities(Map<String, Double> v) { complexityProbabilities = v; return this; }
        public Builder piiProbabilities(Map<String, Double> v) { piiProbabilities = v; return this; }
        public Builder textLength(Integer v) { textLength = v; return this; }
        public Builder attachmentCount(Integer v) { attachmentCount = v; return this; }
        public Builder dryRun(Boolean v) { dryRun = v; return this; }
        public Builder autoConfirm(Boolean v) { autoConfirm = v; return this; }
        public Builder decisionInputTokens(Integer v) { decisionInputTokens = v; return this; }
        public Builder decisionLatencyMs(Long v) { decisionLatencyMs = v; return this; }
        public Builder feedback(String v) { feedback = v; return this; }
        public Builder inputTokens(Integer v) { inputTokens = v; return this; }
        public Builder outputTokens(Integer v) { outputTokens = v; return this; }
        public Builder latencyMs(Long v) { latencyMs = v; return this; }
        public Builder error(String v) { error = v; return this; }

        public LogEvent build() {
            return new LogEvent(Instant.now(), id, type, privacyLevel, complexity, target, model, reason,
                    cloudProbability, piiTypes, privacyProbabilities, complexityProbabilities, piiProbabilities,
                    textLength, attachmentCount, dryRun, autoConfirm, decisionInputTokens, decisionLatencyMs,
                    feedback, inputTokens, outputTokens, latencyMs, error);
        }
    }
}
