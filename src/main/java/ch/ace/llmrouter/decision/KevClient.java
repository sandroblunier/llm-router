package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.config.RouterConfig;
import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
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
import java.util.LinkedHashMap;
import java.util.Map;

/** Client for the kev decision model server ({@code POST /v1/systemone}). Backend {@code kev-server}. */
@Singleton
public class KevClient implements DecisionModel {

    @Inject
    RouterConfig config;

    @Inject
    ObjectMapper mapper;

    @Inject
    KevQuestions questions;

    private HttpClient http;

    @PostConstruct
    void init() {
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.kev().timeout())
                .build();
    }

    @Override
    public int maxInputTokens() {
        return config.kev().maxInputTokens();
    }

    /** Classifies the text with one kev call. HTTP 422 from kev means the input is too long. */
    @Override
    public Classification classify(String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("state", text);
        body.put("model", config.kev().model());
        body.set("questions", questions.questions());

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(stripSlash(config.kev().url()) + "/v1/systemone"))
                .timeout(config.kev().timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        config.kev().apiKey().filter(k -> !k.isBlank())
                .ifPresent(k -> request.header("Authorization", "Bearer " + k));

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KevException("kev not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KevException("kev call interrupted", e);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() == 422) {
            throw new KevInputTooLongException("kev rejected input (HTTP 422): " + abbreviate(response.body()));
        }
        if (response.statusCode() / 100 != 2) {
            throw new KevException("kev HTTP " + response.statusCode() + ": " + abbreviate(response.body()), null);
        }
        try {
            return parse(mapper.readTree(response.body()), latencyMs);
        } catch (IOException | RuntimeException e) {
            throw new KevException("Cannot parse kev response: " + e.getMessage(), e);
        }
    }

    Classification parse(JsonNode root, long latencyMs) {
        JsonNode answers = root.path("answers");

        JsonNode privacy = answers.path(KevQuestions.PRIVACY_ID);
        Map<String, Double> privacyProbabilities = new LinkedHashMap<>();
        for (PrivacyLevel level : PrivacyLevel.values()) {
            privacyProbabilities.put(level.name(), privacy.path("probabilities").path(level.name()).asDouble(0.0));
        }
        if (!privacy.hasNonNull("choice")) {
            throw new IllegalStateException("missing answer " + KevQuestions.PRIVACY_ID);
        }
        PrivacyLevel privacyLevel = PrivacyLevel.valueOf(privacy.get("choice").asText());

        JsonNode complexity = answers.path(KevQuestions.COMPLEXITY_ID);
        if (!complexity.has("probabilities")) {
            throw new IllegalStateException("missing answer " + KevQuestions.COMPLEXITY_ID);
        }
        Map<String, Double> complexityProbabilities = new LinkedHashMap<>();
        Complexity best = Complexity.einfach;
        double bestP = -1.0;
        for (Complexity level : Complexity.values()) {
            double p = complexity.path("probabilities").path(String.valueOf(level.ordinal())).asDouble(0.0);
            complexityProbabilities.put(level.name(), p);
            if (p > bestP) {
                bestP = p;
                best = level;
            }
        }

        Map<String, Double> piiProbabilities = new LinkedHashMap<>();
        questions.piiIds().forEach((id, term) ->
                piiProbabilities.put(term, answers.path(id).path("noul").asDouble(0.0)));

        Integer inputTokens = root.path("usage").has("input_tokens")
                ? root.path("usage").get("input_tokens").asInt() : null;

        return new Classification(privacyLevel, privacyProbabilities, best, complexityProbabilities,
                piiProbabilities, inputTokens, latencyMs);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    public static class KevException extends DecisionException {
        public KevException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class KevInputTooLongException extends InputTooLongException {
        public KevInputTooLongException(String message) {
            super(message);
        }
    }
}
