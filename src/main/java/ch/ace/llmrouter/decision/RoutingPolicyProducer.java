package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.config.RouterConfig;
import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
import ch.ace.llmrouter.model.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoutingPolicyProducer {

    @Produces
    @Singleton
    RoutingPolicy routingPolicy(RouterConfig config) {
        Set<PrivacyLevel> levels = config.routing().cloudAllowedLevels().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(PrivacyLevel::valueOf)
                .collect(Collectors.toSet());
        Set<Complexity> complexities = config.routing().cloudComplexities().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Complexity::valueOf)
                .collect(Collectors.toSet());
        for (Target t : Target.values()) {
            if (!config.models().containsKey(t.configKey())) {
                throw new IllegalStateException("Missing config router.models." + t.configKey());
            }
        }
        return new RoutingPolicy(levels, config.routing().cloudThreshold(), complexities,
                config.routing().piiThreshold(), t -> config.models().get(t.configKey()));
    }
}
