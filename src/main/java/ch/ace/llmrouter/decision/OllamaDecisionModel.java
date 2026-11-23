package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.config.RouterConfig;
import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text-generating decision model in Ollama ({@code POST /api/chat}), e.g. gemma3:4b. Backend {@code ollama}.
 * kev itself cannot be used here: in Ollama it has no "completion" capability (classifier head missing).
 *
 * <p>One call, temperature 0, answer forced into a JSON schema
 * ({@code datenschutzstufe}, {@code pii_arten}, {@code komplexitaet}). The D0..D3 probabilities
 * for the cloud threshold come from the token logprobs. If Ollama returns no logprobs the call
 * fails and the router routes locally.</p>
 */
@Singleton
public class OllamaDecisionModel implements DecisionModel {

    @Inject
    RouterConfig config;

    @Inject
    ObjectMapper mapper;

    @Inject
    KevQuestions questions;

    private HttpClient http;
    private String systemPrompt;

    @PostConstruct
    void init() {
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        systemPrompt = loadPrompt();
    }

    @Override
    public int maxInputTokens() {
        RouterConfig.DecisionOllama c = config.decision().ollama();
        return Math.max(0, c.numCtx() - c.reservedTokens());
    }

    @Override
    public Classification classify(String text) {
        RouterConfig.DecisionOllama c = config.decision().ollama();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", c.model());
        body.put("stream", false);
        body.set("format", schema());
        body.put("logprobs", true);
        body.put("top_logprobs", c.topLogprobs());
        c.think().ifPresent(t -> body.put("think", t));
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);
        options.put("num_ctx", c.numCtx());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", text);

        String base = config.ollama().url();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/chat"))
                .timeout(c.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DecisionException("Ollama (decision) not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecisionException("Ollama (decision) call interrupted", e);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() / 100 != 2) {
            String b = response.body() == null ? "" : response.body();
            throw new DecisionException("Ollama (decision) HTTP " + response.statusCode() + ": "
                    + (b.length() > 300 ? b.substring(0, 300) : b), null);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new DecisionException("Cannot parse Ollama response: " + e.getMessage(), e);
        }
        return parse(root, latencyMs, c.numCtx());
    }

    Classification parse(JsonNode root, long latencyMs, int numCtx) {
        int promptTokens = root.path("prompt_eval_count").asInt(0);
        int outputTokens = root.path("eval_count").asInt(0);
        if (promptTokens + outputTokens >= numCtx) {
            // Ollama silently truncates input that does not fit num_ctx
            throw new InputTooLongException("prompt (" + promptTokens + " tokens) fills num_ctx " + numCtx);
        }

        JsonNode answer;
        try {
            answer = mapper.readTree(root.path("message").path("content").asText(""));
        } catch (IOException e) {
            throw new DecisionException("Decision answer is not valid JSON: " + e.getMessage(), e);
        }

        PrivacyLevel privacyLevel;
        Complexity complexity;
        try {
            privacyLevel = PrivacyLevel.valueOf(answer.path("datenschutzstufe").asText());
            complexity = Complexity.valueOf(answer.path("komplexitaet").asText());
        } catch (IllegalArgumentException e) {
            throw new DecisionException("Decision answer has an unknown value: " + answer, e);
        }

        Map<String, Double> pii = new LinkedHashMap<>();
        List<String> allowed = questions.piiTypes();
        for (String type : allowed) {
            pii.put(type, 0.0);
        }
        for (JsonNode n : answer.path("pii_arten")) {
            if (allowed.contains(n.asText())) {
                pii.put(n.asText(), 1.0);
            }
        }

        Map<String, Double> privacyProbabilities;
        try {
            privacyProbabilities = LevelProbabilities.fromLogprobs(tokens(root));
        } catch (IllegalStateException e) {
            throw new DecisionException("No usable logprobs for datenschutzstufe: " + e.getMessage(), e);
        }

        Map<String, Double> complexityProbabilities = new LinkedHashMap<>();
        for (Complexity x : Complexity.values()) {
            complexityProbabilities.put(x.name(), x == complexity ? 1.0 : 0.0);
        }

        return new Classification(privacyLevel, privacyProbabilities, complexity, complexityProbabilities,
                pii, promptTokens, latencyMs);
    }

    /** Ollama puts logprobs at the top level of the /api/chat response; accept message.logprobs too. */
    static List<LevelProbabilities.Token> tokens(JsonNode root) {
        JsonNode lp = root.path("logprobs");
        if (!lp.isArray()) {
            lp = root.path("message").path("logprobs");
        }
        if (!lp.isArray() || lp.isEmpty()) {
            throw new IllegalStateException("Ollama returned no logprobs (Ollama version too old?)");
        }
        List<LevelProbabilities.Token> tokens = new ArrayList<>();
        for (JsonNode t : lp) {
            List<LevelProbabilities.Alternative> top = new ArrayList<>();
            for (JsonNode a : t.path("top_logprobs")) {
                top.add(new LevelProbabilities.Alternative(a.path("token").asText(), a.path("logprob").asDouble()));
            }
            tokens.add(new LevelProbabilities.Token(t.path("token").asText(), t.path("logprob").asDouble(), top));
        }
        return tokens;
    }

    private ObjectNode schema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode levels = props.putObject("datenschutzstufe").put("type", "string").putArray("enum");
        for (PrivacyLevel l : PrivacyLevel.values()) {
            levels.add(l.name());
        }
        ObjectNode pii = props.putObject("pii_arten");
        pii.put("type", "array");
        ArrayNode piiEnum = pii.putObject("items").put("type", "string").putArray("enum");
        questions.piiTypes().forEach(piiEnum::add);
        ArrayNode complexities = props.putObject("komplexitaet").put("type", "string").putArray("enum");
        for (Complexity x : Complexity.values()) {
            complexities.add(x.name());
        }
        schema.putArray("required").add("datenschutzstufe").add("pii_arten").add("komplexitaet");
        return schema;
    }

    private String loadPrompt() {
        try {
            if (config.decision().ollama().promptFile().isPresent()) {
                return Files.readString(Path.of(config.decision().ollama().promptFile().get()), StandardCharsets.UTF_8);
            }
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("decision-prompt.txt")) {
                if (in == null) {
                    throw new IllegalStateException("decision-prompt.txt not found on classpath");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read decision prompt", e);
        }
    }
}
