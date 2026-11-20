package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.config.RouterConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the kev questions (privacy, complexity, PII types) from an external file or the bundled default
 * and builds the {@code questions} object for {@code POST /v1/systemone}.
 */
@Startup
@Singleton
public class KevQuestions {

    public static final String PRIVACY_ID = "datenschutzstufe";
    public static final String COMPLEXITY_ID = "komplexitaet";
    static final String PII_PREFIX = "pii_";

    @Inject
    RouterConfig config;

    @Inject
    ObjectMapper mapper;

    private ObjectNode questions;
    /** kev question id (pii_0, pii_1, ...) -> guideline term (Name, Adresse, ...). */
    private final Map<String, String> piiIds = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        JsonNode root = read();
        questions = mapper.createObjectNode();

        JsonNode privacy = required(root, "privacy");
        ObjectNode p = questions.putObject(PRIVACY_ID);
        p.put("type", "choice");
        p.set("instructions", required(privacy, "instructions"));
        p.set("criteria", required(privacy, "criteria"));

        JsonNode complexity = required(root, "complexity");
        if (required(complexity, "criteria").size() != 3) {
            throw new IllegalStateException("complexity.criteria must have exactly 3 levels (einfach, mittel, anspruchsvoll)");
        }
        ObjectNode c = questions.putObject(COMPLEXITY_ID);
        c.put("type", "score");
        c.set("instructions", required(complexity, "instructions"));
        c.set("criteria", required(complexity, "criteria"));

        int i = 0;
        Iterator<Map.Entry<String, JsonNode>> it = required(root, "pii").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String id = PII_PREFIX + i++;
            piiIds.put(id, e.getKey());
            ObjectNode q = questions.putObject(id);
            q.put("type", "noul");
            q.set("instructions", e.getValue());
        }
    }

    /** A fresh copy of the questions object, ready to put into a kev request. */
    public ObjectNode questions() {
        return questions.deepCopy();
    }

    public Map<String, String> piiIds() {
        return piiIds;
    }

    public List<String> piiTypes() {
        return new ArrayList<>(piiIds.values());
    }

    private JsonNode read() {
        try {
            if (config.kev().questionsFile().isPresent()) {
                Path path = Path.of(config.kev().questionsFile().get());
                return mapper.readTree(Files.readString(path));
            }
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("kev-questions.json")) {
                if (in == null) {
                    throw new IllegalStateException("kev-questions.json not found on classpath");
                }
                return mapper.readTree(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read kev questions", e);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("kev questions: missing field '" + field + "'");
        }
        return value;
    }
}
