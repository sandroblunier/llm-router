package ch.ace.llmrouter.decision;

/**
 * A decision model that classifies a request text (privacy level, PII types, complexity).
 * Implementations: {@link OllamaDecisionModel} (kev in Ollama, default) and {@link KevClient} (kev server).
 */
public interface DecisionModel {

    /**
     * @throws InputTooLongException if the text does not fit the model's context
     * @throws DecisionException     on any other error (the router then fails closed)
     */
    Classification classify(String text);

    /** Longest text (estimated tokens) that is sent to the model; longer texts go local unclassified. */
    int maxInputTokens();

    class DecisionException extends RuntimeException {
        public DecisionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class InputTooLongException extends DecisionException {
        public InputTooLongException(String message) {
            super(message, null);
        }
    }
}
