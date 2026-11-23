package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.api.Dtos.Reasons;
import ch.ace.llmrouter.api.Dtos.RouteResponse;
import ch.ace.llmrouter.evaluation.EvaluationModel.EvaluationRow;
import ch.ace.llmrouter.evaluation.EvaluationModel.GoldLabels;
import ch.ace.llmrouter.evaluation.EvaluationModel.Summary;
import ch.ace.llmrouter.model.RequestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same cases as frontend evaluation-logic.spec.ts, so both sides compute identical numbers. */
class EvaluationMathTest {

    private static GoldLabels gold(String d, String k, Boolean grenzfall) {
        return new GoldLabels(d, d == null ? null : List.of(), k, grenzfall);
    }

    private static RouteResponse route(String level, String complexity, String target, String reason, double p) {
        return new RouteResponse("id-" + level + target, RequestStatus.DRY_RUN, level, complexity, target,
                "CLOUD".equals(target) ? "gemma4:31b-cloud" : "gemma3:4b",
                new Reasons(reason, null, p, "D1".equals(level) ? List.of("Name") : List.of(), null, null),
                null, null, null);
    }

    @Test
    void rowComparesWithGold() {
        EvaluationRow r = EvaluationMath.toRow("p-01", gold("D1", "mittel", null),
                route("D1", "einfach", "LOCAL_SIMPLE", "complexity_local", 0.5), 120L, null);
        assertTrue(r.privacyMatch());
        assertFalse(r.complexityMatch());
        assertEquals(List.of("Name"), r.predicted().piiTypes());

        EvaluationRow e = EvaluationMath.toRow("p-02", gold(null, null, null), null, 30L, "HTTP 500");
        assertNull(e.privacyMatch());
        assertNull(e.predicted());
    }

    @Test
    void summaryMatchesFrontend() {
        List<EvaluationRow> rows = List.of(
                EvaluationMath.toRow("a", gold("D0", "anspruchsvoll", null), route("D0", "anspruchsvoll", "CLOUD", "cloud_eligible", 0.99), 100L, null),
                EvaluationMath.toRow("b", gold("D2", "mittel", true), route("D2", "mittel", "LOCAL_MEDIUM", "complexity_local", 0.5), 200L, null),
                EvaluationMath.toRow("c", gold("D1", "anspruchsvoll", null), route("D0", "anspruchsvoll", "CLOUD", "cloud_eligible", 0.97), 300L, null),
                EvaluationMath.toRow("d", gold("D3", "einfach", null), route(null, null, "LOCAL_COMPLEX", "kev_error", 0.0), 50L, null),
                EvaluationMath.toRow("e", gold(null, null, null), null, null, "Router nicht erreichbar."));
        Summary s = EvaluationMath.summarize(rows);
        assertEquals(5, s.total());
        assertEquals(4, s.labeledPrivacy());
        assertEquals(2, s.privacyCorrect());
        assertEquals(0.5, s.privacyAccuracy(), 1e-9);
        assertEquals(0.75, s.complexityAccuracy(), 1e-9);
        assertEquals(3, s.sensitiveTotal());
        assertEquals(2, s.sensitiveLocal());
        assertEquals(List.of("c"), s.wrongCloudNrs());
        assertEquals(1, s.privacyConfusion().get("D1").get("D0"));
        assertEquals(1, s.privacyConfusion().get("D3").get("-"));
        assertEquals(1, s.failed());
        assertEquals(1, s.unclassified());
        assertEquals(2, s.cloudCount());
        assertEquals(1, s.grenzfallCount());
        assertEquals(162.5, s.avgLatencyMs(), 1e-9);
        assertEquals(300L, s.maxLatencyMs());
    }

    @Test
    void noLabelsGiveNullRatios() {
        Summary s = EvaluationMath.summarize(List.of(EvaluationMath.toRow("x", gold(null, null, null),
                route("D0", "einfach", "LOCAL_SIMPLE", "complexity_local", 0.5), 1L, null)));
        assertNull(s.privacyAccuracy());
        assertNull(s.sensitiveLocalShare());
    }

    @Test
    void csvMatchesFrontendFormat() {
        RouteResponse r = new RouteResponse("rid", RequestStatus.DRY_RUN, "D1", "mittel", "LOCAL_MEDIUM", "gemma3:4b",
                new Reasons("x", "a;\"b\"", null, List.of("Name", "E-Mail"), null, null), null, null, null);
        String csv = EvaluationMath.toCsv(List.of(EvaluationMath.toRow("p-01", gold("D1", "mittel", null), r, 10L, null)));
        assertTrue(csv.startsWith("﻿nr;gold_datenschutzstufe;"));
        String line = csv.split("\r\n")[1];
        assertTrue(line.contains("p-01;D1;;mittel;;D1;mittel;LOCAL_MEDIUM;gemma3:4b;;Name, E-Mail;x;\"a;\"\"b\"\"\";ja;ja;10;"), line);
        assertEquals("0.98", EvaluationMath.csvField(0.98));
        assertEquals("1", EvaluationMath.csvField(1.0));
    }
}
