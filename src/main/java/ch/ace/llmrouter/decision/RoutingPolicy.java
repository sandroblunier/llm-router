package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
import ch.ace.llmrouter.model.Target;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure routing rules: classification -> target. No I/O, unit-testable without Quarkus.
 *
 * <ul>
 *   <li>A request is cloud-eligible only if the summed kev probability of the
 *       cloud-allowed privacy levels reaches the threshold.</li>
 *   <li>A cloud-eligible request goes to the cloud only if its complexity is one of the
 *       cloud complexities; otherwise it goes to the local tier for its complexity.</li>
 *   <li>Unclassified requests (too long, kev error) go to LOCAL_COMPLEX (fail closed).</li>
 * </ul>
 */
public class RoutingPolicy {

    private final Set<PrivacyLevel> cloudAllowedLevels;
    private final double cloudThreshold;
    private final Set<Complexity> cloudComplexities;
    private final double piiThreshold;
    private final Function<Target, String> modelLookup;

    public RoutingPolicy(Set<PrivacyLevel> cloudAllowedLevels,
                         double cloudThreshold,
                         Set<Complexity> cloudComplexities,
                         double piiThreshold,
                         Function<Target, String> modelLookup) {
        if (cloudThreshold < 0.0 || cloudThreshold > 1.0) {
            throw new IllegalArgumentException("cloud threshold must be between 0 and 1: " + cloudThreshold);
        }
        this.cloudAllowedLevels = cloudAllowedLevels.isEmpty()
                ? EnumSet.noneOf(PrivacyLevel.class) : EnumSet.copyOf(cloudAllowedLevels);
        this.cloudThreshold = cloudThreshold;
        this.cloudComplexities = cloudComplexities.isEmpty()
                ? EnumSet.noneOf(Complexity.class) : EnumSet.copyOf(cloudComplexities);
        this.piiThreshold = piiThreshold;
        this.modelLookup = modelLookup;
    }

    public Decision decide(Classification c) {
        double cloudProbability = cloudProbability(c.privacyProbabilities());
        List<String> piiTypes = piiTypes(c.piiProbabilities());

        Target target;
        String reason;
        if (cloudAllowedLevels.isEmpty() || cloudProbability < cloudThreshold) {
            target = Target.localFor(c.complexity());
            reason = Decision.Reason.PRIVACY_BELOW_THRESHOLD;
        } else if (cloudComplexities.contains(c.complexity())) {
            target = Target.CLOUD;
            reason = Decision.Reason.CLOUD_ELIGIBLE;
        } else {
            target = Target.localFor(c.complexity());
            reason = Decision.Reason.COMPLEXITY_LOCAL;
        }
        return new Decision(c.privacyLevel(), c.complexity(), target, modelLookup.apply(target),
                reason, cloudProbability, piiTypes, c);
    }

    /** Decision for a request that could not be classified. Never goes to the cloud. */
    public Decision unclassified(String reason) {
        Target target = Target.LOCAL_COMPLEX;
        return new Decision(null, null, target, modelLookup.apply(target), reason, null, List.of(), null);
    }

    double cloudProbability(Map<String, Double> privacyProbabilities) {
        double sum = 0.0;
        for (PrivacyLevel level : cloudAllowedLevels) {
            Double p = privacyProbabilities.get(level.name());
            if (p != null) {
                sum += p;
            }
        }
        return sum;
    }

    List<String> piiTypes(Map<String, Double> piiProbabilities) {
        return piiProbabilities.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= piiThreshold)
                .map(Map.Entry::getKey)
                .toList();
    }
}
