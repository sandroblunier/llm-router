package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
import ch.ace.llmrouter.model.Target;

import java.util.List;

/**
 * Routing decision for one request.
 *
 * @param privacyLevel     kev privacy level, null if the request was not classified
 * @param complexity       kev complexity, null if the request was not classified
 * @param target           chosen target
 * @param model            model behind the target
 * @param reason           why this target was chosen (see {@link Reason})
 * @param cloudProbability summed kev probability of the cloud-allowed levels, null if not classified
 * @param piiTypes         PII types above the PII threshold
 * @param classification   raw kev result, null if not classified
 * @param detail           error text when not classified (too_long, kev_error), else null
 */
public record Decision(
        PrivacyLevel privacyLevel,
        Complexity complexity,
        Target target,
        String model,
        String reason,
        Double cloudProbability,
        List<String> piiTypes,
        Classification classification,
        String detail) {

    /** Reason codes written to the log and returned to the client. */
    public static final class Reason {
        public static final String CLOUD_ELIGIBLE = "cloud_eligible";
        public static final String PRIVACY_BELOW_THRESHOLD = "privacy_below_threshold";
        public static final String COMPLEXITY_LOCAL = "complexity_local";
        public static final String TOO_LONG = "too_long";
        public static final String KEV_ERROR = "kev_error";

        private Reason() {
        }
    }
}
