package ch.ace.llmrouter.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Data types of the gold-set evaluation. Field names match the frontend (models.ts).
 * No type contains the request text.
 */
public final class EvaluationModel {

    private EvaluationModel() {
    }

    public enum JobState { RUNNING, DONE, CANCELLED, FAILED }

    /** Labels from the gold set; every field may be null (unlabelled pilot files). */
    public record GoldLabels(String datenschutzstufe, List<String> piiArten, String komplexitaet, Boolean grenzfall) {
    }

    /** One row of sheet "Beschriftung". Holds the text only while the job runs; never exported. */
    public record GoldRow(String nr, String anfrage, GoldLabels gold) {
    }

    public record Prediction(String privacyLevel, String complexity, String target, String model,
                             Double cloudProbability, List<String> piiTypes, String reason, String detail) {
    }

    public record EvaluationRow(String nr, GoldLabels gold, Prediction predicted, String routeId, Long latencyMs,
                                String error, Boolean privacyMatch, Boolean complexityMatch) {
    }

    public record Summary(
            int total, int failed, int unclassified, int cloudCount,
            int labeledPrivacy, int privacyCorrect, Double privacyAccuracy,
            int labeledComplexity, int complexityCorrect, Double complexityAccuracy,
            Map<String, Map<String, Integer>> privacyConfusion,
            Map<String, Map<String, Integer>> complexityConfusion,
            int sensitiveTotal, int sensitiveLocal, Double sensitiveLocalShare,
            int wrongCloud, List<String> wrongCloudNrs, int grenzfallCount,
            Double avgLatencyMs, Long maxLatencyMs) {
    }

    /** Job status as returned by GET /evaluations/{id}. */
    public record JobView(String id, String fileName, String mode, JobState state, int total, int done,
                          String startedAt, String finishedAt, String error,
                          List<EvaluationRow> rows, Summary summary) {
    }

    /** Payload of one server-sent event. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(String type, JobState state, int total, int done, EvaluationRow row, Summary summary,
                        String error) {
    }

    /** JSON export and persisted result file: meta, summary, rows. */
    public record Export(Meta meta, Summary summary, List<EvaluationRow> rows) {
    }

    public record Meta(String id, String fileName, String mode, JobState state, String startedAt, String finishedAt,
                       int total) {
    }
}
