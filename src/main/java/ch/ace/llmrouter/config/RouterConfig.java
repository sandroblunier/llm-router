package ch.ace.llmrouter.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All router settings (prefix {@code router.}), see application.properties. */
@ConfigMapping(prefix = "router")
public interface RouterConfig {

    ApiKey apiKey();

    Decision decision();

    Kev kev();

    Routing routing();

    /** Target name (local-simple, local-medium, local-complex, cloud) -> model name. */
    Map<String, String> models();

    Ollama ollama();

    Pending pending();

    Log log();

    interface ApiKey {
        @WithDefault("true")
        boolean enabled();

        /** Expected value of the X-API-Key header. */
        Optional<String> value();
    }

    interface Decision {
        /** Which decision model to use: {@code ollama} (kev in Ollama) or {@code kev-server}. */
        @WithDefault("ollama")
        String backend();

        DecisionOllama ollama();
    }

    interface DecisionOllama {
        /** Ollama tag of the kev model (see {@code ollama list}). */
        String model();

        /** Context window passed to Ollama as num_ctx. */
        @WithDefault("8192")
        int numCtx();

        /** Tokens kept free for system prompt and JSON answer; the rest is the input limit. */
        @WithDefault("2048")
        int reservedTokens();

        @WithDefault("60s")
        Duration timeout();

        /** Number of alternative tokens per position (needed for the privacy probabilities). */
        @WithDefault("5")
        int topLogprobs();

        /** Optional path to an external system prompt; falls back to the bundled decision-prompt.txt. */
        Optional<String> promptFile();
    }

    interface Kev {
        String url();

        @WithDefault("kev-latest")
        String model();

        /** Bearer token if the kev server runs with KEV_API_KEY. */
        Optional<String> apiKey();

        @WithDefault("30s")
        Duration timeout();

        /** Validated context of Kev-0.8B/4B/9B. Longer texts go local without classification. */
        @WithDefault("8192")
        int maxInputTokens();

        /** Conservative estimate used to compare text length with maxInputTokens. */
        @WithDefault("3.0")
        double charsPerToken();

        /** Optional path to an external questions file; falls back to the bundled kev-questions.json. */
        Optional<String> questionsFile();
    }

    interface Routing {
        /** Privacy levels that may leave the company. */
        @WithDefault("D0")
        List<String> cloudAllowedLevels();

        /** Minimum summed kev probability of the allowed levels for a cloud decision. */
        @WithDefault("0.95")
        double cloudThreshold();

        /** Complexities that use the cloud when the request is cloud-eligible. */
        @WithDefault("anspruchsvoll")
        List<String> cloudComplexities();

        /** A PII type is reported when its kev probability is at least this value. */
        @WithDefault("0.5")
        double piiThreshold();
    }

    interface Ollama {
        String url();

        @WithDefault("300s")
        Duration timeout();
    }

    interface Pending {
        @WithDefault("10m")
        Duration ttl();
    }

    interface Log {
        String file();
    }
}
