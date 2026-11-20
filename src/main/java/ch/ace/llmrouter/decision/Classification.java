package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;

import java.util.Map;

/**
 * Result of one kev call.
 *
 * @param privacyLevel            most likely privacy level (kev choice)
 * @param privacyProbabilities    D0..D3 -> probability
 * @param complexity              most likely complexity
 * @param complexityProbabilities einfach/mittel/anspruchsvoll -> probability
 * @param piiProbabilities        PII type (guideline term) -> probability
 * @param inputTokens             tokens reported by kev
 * @param latencyMs               round trip time of the kev call
 */
public record Classification(
        PrivacyLevel privacyLevel,
        Map<String, Double> privacyProbabilities,
        Complexity complexity,
        Map<String, Double> complexityProbabilities,
        Map<String, Double> piiProbabilities,
        Integer inputTokens,
        long latencyMs) {
}
