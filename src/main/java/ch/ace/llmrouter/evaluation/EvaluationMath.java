package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.api.Dtos.RouteResponse;
import ch.ace.llmrouter.evaluation.EvaluationModel.EvaluationRow;
import ch.ace.llmrouter.evaluation.EvaluationModel.GoldLabels;
import ch.ace.llmrouter.evaluation.EvaluationModel.Prediction;
import ch.ace.llmrouter.evaluation.EvaluationModel.Summary;
import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure evaluation logic: row results, summary, CSV.
 * Mirrors frontend/src/app/evaluation/evaluation-logic.ts: keep both in sync.
 */
public final class EvaluationMath {

    public static final String NONE = "-";
    static final Set<String> UNCLASSIFIED_REASONS = Set.of("kev_error", "too_long");
    static final Set<String> SENSITIVE = Set.of("D1", "D2", "D3");
    static final List<String> CSV_COLUMNS = List.of(
            "nr", "gold_datenschutzstufe", "gold_pii_arten", "gold_komplexitaet", "gold_grenzfall",
            "pred_datenschutzstufe", "pred_komplexitaet", "target", "model", "cloud_probability", "pred_pii_arten",
            "reason", "detail", "privacy_match", "complexity_match", "latency_ms", "route_id", "error");

    private EvaluationMath() {
    }

    public static EvaluationRow toRow(String nr, GoldLabels gold, RouteResponse r, Long latencyMs, String error) {
        Prediction p = null;
        if (r != null) {
            p = new Prediction(r.privacyLevel(), r.complexity(), r.target(), r.model(),
                    r.reasons() == null ? null : r.reasons().cloudProbability(),
                    r.reasons() == null || r.reasons().piiTypes() == null ? List.of() : r.reasons().piiTypes(),
                    r.reasons() == null ? "" : r.reasons().reason(),
                    r.reasons() == null ? null : r.reasons().detail());
        }
        Boolean privacyMatch = gold.datenschutzstufe() == null ? null
                : p != null && gold.datenschutzstufe().equals(p.privacyLevel());
        Boolean complexityMatch = gold.komplexitaet() == null ? null
                : p != null && gold.komplexitaet().equals(p.complexity());
        return new EvaluationRow(nr, gold, p, r == null ? null : r.id(), latencyMs, error, privacyMatch, complexityMatch);
    }

    private static Map<String, Map<String, Integer>> matrix(List<String> keys) {
        Map<String, Map<String, Integer>> m = new LinkedHashMap<>();
        for (String r : keys) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (String c : keys) {
                row.put(c, 0);
            }
            row.put(NONE, 0);
            m.put(r, row);
        }
        return m;
    }

    private static Double ratio(int a, int b) {
        return b == 0 ? null : (double) a / b;
    }

    public static Summary summarize(List<EvaluationRow> rows) {
        List<String> levels = java.util.Arrays.stream(PrivacyLevel.values()).map(Enum::name).toList();
        List<String> complexities = java.util.Arrays.stream(Complexity.values()).map(Enum::name).toList();
        Map<String, Map<String, Integer>> privacyConfusion = matrix(levels);
        Map<String, Map<String, Integer>> complexityConfusion = matrix(complexities);
        int failed = 0, unclassified = 0, cloudCount = 0;
        int labeledPrivacy = 0, privacyCorrect = 0, labeledComplexity = 0, complexityCorrect = 0;
        int sensitiveTotal = 0, sensitiveLocal = 0, grenzfallCount = 0;
        List<String> wrongCloudNrs = new ArrayList<>();
        long latencySum = 0;
        int latencyCount = 0;
        Long maxLatency = null;

        for (EvaluationRow row : rows) {
            Prediction p = row.predicted();
            if (row.error() != null || p == null) failed++;
            if (p != null && UNCLASSIFIED_REASONS.contains(p.reason())) unclassified++;
            boolean isCloud = p != null && "CLOUD".equals(p.target());
            if (isCloud) cloudCount++;
            if (row.latencyMs() != null) {
                latencySum += row.latencyMs();
                latencyCount++;
                maxLatency = maxLatency == null ? row.latencyMs() : Math.max(maxLatency, row.latencyMs());
            }
            if (Boolean.TRUE.equals(row.gold().grenzfall())) grenzfallCount++;

            String g = row.gold().datenschutzstufe();
            if (g != null && privacyConfusion.containsKey(g)) {
                labeledPrivacy++;
                String pl = p == null ? null : p.privacyLevel();
                if (g.equals(pl)) privacyCorrect++;
                privacyConfusion.get(g).merge(pl == null ? NONE : pl, 1, Integer::sum);
                if (SENSITIVE.contains(g)) {
                    sensitiveTotal++;
                    if (isCloud) wrongCloudNrs.add(row.nr());
                    else sensitiveLocal++;
                }
            }
            String gc = row.gold().komplexitaet();
            if (gc != null && complexityConfusion.containsKey(gc)) {
                labeledComplexity++;
                String pc = p == null ? null : p.complexity();
                if (gc.equals(pc)) complexityCorrect++;
                complexityConfusion.get(gc).merge(pc == null ? NONE : pc, 1, Integer::sum);
            }
        }
        return new Summary(rows.size(), failed, unclassified, cloudCount,
                labeledPrivacy, privacyCorrect, ratio(privacyCorrect, labeledPrivacy),
                labeledComplexity, complexityCorrect, ratio(complexityCorrect, labeledComplexity),
                privacyConfusion, complexityConfusion,
                sensitiveTotal, sensitiveLocal, ratio(sensitiveLocal, sensitiveTotal),
                wrongCloudNrs.size(), wrongCloudNrs, grenzfallCount,
                latencyCount == 0 ? null : (double) latencySum / latencyCount, maxLatency);
    }

    /** CSV with ';' separator and UTF-8 BOM, same format as the frontend export. */
    public static String toCsv(List<EvaluationRow> rows) {
        StringBuilder sb = new StringBuilder("﻿").append(String.join(";", CSV_COLUMNS)).append("\r\n");
        for (EvaluationRow r : rows) {
            Prediction p = r.predicted();
            List<Object> values = new ArrayList<>();
            values.add(r.nr());
            values.add(r.gold().datenschutzstufe());
            values.add(r.gold().piiArten() == null ? null : String.join(", ", r.gold().piiArten()));
            values.add(r.gold().komplexitaet());
            values.add(yesNo(r.gold().grenzfall()));
            values.add(p == null ? null : p.privacyLevel());
            values.add(p == null ? null : p.complexity());
            values.add(p == null ? null : p.target());
            values.add(p == null ? null : p.model());
            values.add(p == null ? null : p.cloudProbability());
            values.add(p == null ? null : String.join(", ", p.piiTypes()));
            values.add(p == null ? null : p.reason());
            values.add(p == null ? null : p.detail());
            values.add(yesNo(r.privacyMatch()));
            values.add(yesNo(r.complexityMatch()));
            values.add(r.latencyMs());
            values.add(r.routeId());
            values.add(r.error());
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) line.append(';');
                line.append(csvField(values.get(i)));
            }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    private static String yesNo(Boolean b) {
        return b == null ? "" : b ? "ja" : "nein";
    }

    static String csvField(Object v) {
        String s = v == null ? "" : v instanceof Double d ? jsNumber(d) : String.valueOf(v);
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Number formatting like JavaScript's String(n): 0.5, 1, 0.97. */
    static String jsNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }
}
