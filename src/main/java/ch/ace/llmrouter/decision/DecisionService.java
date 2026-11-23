package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.config.RouterConfig;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Step 2 of the flow: classify with the configured decision model and apply the routing policy. Fails closed.
 */
@Singleton
public class DecisionService {

    private static final Logger LOG = Logger.getLogger(DecisionService.class);

    @Inject
    RouterConfig config;

    @Inject
    OllamaDecisionModel ollamaModel;

    @Inject
    KevClient kevServer;

    @Inject
    RoutingPolicy policy;

    private DecisionModel model;

    @PostConstruct
    void init() {
        model = switch (config.decision().backend()) {
            case "ollama" -> ollamaModel;
            case "kev-server" -> kevServer;
            default -> throw new IllegalStateException(
                    "router.decision.backend must be 'ollama' or 'kev-server', not '" + config.decision().backend() + "'");
        };
        LOG.infof("Decision backend: %s", config.decision().backend());
    }

    public Decision decide(String text) {
        int estimated = estimateTokens(text);
        if (estimated > model.maxInputTokens()) {
            return policy.unclassified(Decision.Reason.TOO_LONG,
                    "estimated " + estimated + " tokens > limit " + model.maxInputTokens());
        }
        try {
            return policy.decide(model.classify(text));
        } catch (DecisionModel.InputTooLongException e) {
            return policy.unclassified(Decision.Reason.TOO_LONG, e.getMessage());
        } catch (RuntimeException e) {
            LOG.warnf("Decision model failed, routing locally: %s", e.getMessage());
            return policy.unclassified(Decision.Reason.KEV_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / config.kev().charsPerToken());
    }
}
