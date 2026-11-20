package ch.ace.llmrouter.llm;

import ch.ace.llmrouter.config.RouterConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Ollama via its OpenAI-compatible API ({@code POST /v1/chat/completions}). */
@Singleton
public class OllamaProvider implements LlmProvider {

    @Inject
    RouterConfig config;

    @Inject
    ObjectMapper mapper;

    private HttpClient http;

    @PostConstruct
    void init() {
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    @Override
    public LlmResult complete(String model, String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", prompt);

        String base = config.ollama().url();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/chat/completions"))
                .timeout(config.ollama().timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("Ollama not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Ollama call interrupted", e);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() / 100 != 2) {
            String b = response.body() == null ? "" : response.body();
            throw new LlmException("Ollama HTTP " + response.statusCode() + ": "
                    + (b.length() > 300 ? b.substring(0, 300) : b), null);
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmException("Ollama response without choices[0].message.content", null);
            }
            JsonNode usage = root.path("usage");
            Integer in = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer out = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
            return new LlmResult(content.asText(), root.path("model").asText(model), in, out, latencyMs);
        } catch (IOException e) {
            throw new LlmException("Cannot parse Ollama response: " + e.getMessage(), e);
        }
    }
}
