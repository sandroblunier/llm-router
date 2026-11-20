package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
import ch.ace.llmrouter.model.Target;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingPolicyTest {

    private final RoutingPolicy policy = new RoutingPolicy(
            Set.of(PrivacyLevel.D0), 0.95, Set.of(Complexity.anspruchsvoll), 0.5,
            t -> t.isCloud() ? "cloud-model" : "local-model");

    private static Classification c(double d0, double d1, double d2, double d3, Complexity complexity) {
        Map<String, Double> privacy = new LinkedHashMap<>();
        privacy.put("D0", d0);
        privacy.put("D1", d1);
        privacy.put("D2", d2);
        privacy.put("D3", d3);
        Map<String, Double> complexityP = new LinkedHashMap<>();
        for (Complexity x : Complexity.values()) {
            complexityP.put(x.name(), x == complexity ? 1.0 : 0.0);
        }
        Map<String, Double> pii = new LinkedHashMap<>();
        pii.put("Name", d1 + d2);
        pii.put("Gesundheit", d2);
        PrivacyLevel best = d0 >= d1 && d0 >= d2 && d0 >= d3 ? PrivacyLevel.D0
                : d1 >= d2 && d1 >= d3 ? PrivacyLevel.D1 : d2 >= d3 ? PrivacyLevel.D2 : PrivacyLevel.D3;
        return new Classification(best, privacy, complexity, complexityP, pii, 100, 5);
    }

    @Test
    void d0AboveThresholdAndDemandingGoesToCloud() {
        Decision d = policy.decide(c(0.97, 0.01, 0.01, 0.01, Complexity.anspruchsvoll));
        assertEquals(Target.CLOUD, d.target());
        assertEquals("cloud-model", d.model());
        assertEquals(Decision.Reason.CLOUD_ELIGIBLE, d.reason());
        assertEquals(0.97, d.cloudProbability(), 1e-9);
    }

    @Test
    void thresholdIsInclusive() {
        assertEquals(Target.CLOUD, policy.decide(c(0.95, 0.05, 0, 0, Complexity.anspruchsvoll)).target());
    }

    @Test
    void d0BelowThresholdStaysLocalEvenIfD0IsMostLikely() {
        Decision d = policy.decide(c(0.80, 0.10, 0.05, 0.05, Complexity.anspruchsvoll));
        assertEquals(PrivacyLevel.D0, d.privacyLevel());
        assertEquals(Target.LOCAL_COMPLEX, d.target());
        assertEquals(Decision.Reason.PRIVACY_BELOW_THRESHOLD, d.reason());
    }

    @Test
    void cloudEligibleButSimpleStaysLocalSimple() {
        Decision d = policy.decide(c(0.99, 0.01, 0, 0, Complexity.einfach));
        assertEquals(Target.LOCAL_SIMPLE, d.target());
        assertEquals(Decision.Reason.COMPLEXITY_LOCAL, d.reason());
    }

    @Test
    void personalDataNeverGoesToCloud() {
        for (Complexity x : Complexity.values()) {
            Decision d = policy.decide(c(0.02, 0.9, 0.05, 0.03, x));
            assertFalse(d.target().isCloud(), "D1 must stay local for " + x);
            assertEquals(Target.localFor(x), d.target());
        }
    }

    @Test
    void piiTypesAboveThresholdAreReported() {
        Decision d = policy.decide(c(0.0, 0.2, 0.8, 0.0, Complexity.mittel));
        assertEquals(java.util.List.of("Name", "Gesundheit"), d.piiTypes());
        assertEquals(Target.LOCAL_MEDIUM, d.target());
    }

    @Test
    void allowedLevelsAreSummed() {
        RoutingPolicy d0d3 = new RoutingPolicy(Set.of(PrivacyLevel.D0, PrivacyLevel.D3), 0.95,
                Set.of(Complexity.anspruchsvoll), 0.5, t -> "m");
        assertEquals(Target.CLOUD, d0d3.decide(c(0.5, 0.02, 0.01, 0.47, Complexity.anspruchsvoll)).target());
    }

    @Test
    void noAllowedLevelsMeansNeverCloud() {
        RoutingPolicy none = new RoutingPolicy(Set.of(), 0.0, Set.of(Complexity.anspruchsvoll), 0.5, t -> "m");
        assertFalse(none.decide(c(1.0, 0, 0, 0, Complexity.anspruchsvoll)).target().isCloud());
    }

    @Test
    void unclassifiedFailsClosed() {
        Decision d = policy.unclassified(Decision.Reason.KEV_ERROR);
        assertEquals(Target.LOCAL_COMPLEX, d.target());
        assertNull(d.privacyLevel());
        assertNull(d.cloudProbability());
    }

    @Test
    void invalidThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingPolicy(Set.of(PrivacyLevel.D0), 1.5, Set.of(), 0.5, t -> "m"));
    }
}
