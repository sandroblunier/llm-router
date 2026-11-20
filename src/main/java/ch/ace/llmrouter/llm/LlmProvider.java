package ch.ace.llmrouter.llm;

/**
 * Sends a prompt to a target LLM. Today only Ollama (local and Ollama cloud models);
 * Anthropic/OpenAI adapters can implement this interface later.
 */
public interface LlmProvider {

    /**
     * @throws LlmException if the call fails
     */
    LlmResult complete(String model, String prompt);

    record LlmResult(String answer, String model, Integer inputTokens, Integer outputTokens, long latencyMs) {
    }

    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
