package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.decision.LevelProbabilities.Alternative;
import ch.ace.llmrouter.decision.LevelProbabilities.Token;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelProbabilitiesTest {

    private static Token t(String token) {
        return new Token(token, 0.0, List.of());
    }

    @Test
    void levelAsSingleToken() {
        Token level = new Token("D1", Math.log(0.90), List.of(
                new Alternative("D1", Math.log(0.90)),
                new Alternative("D0", Math.log(0.06)),
                new Alternative("D2", Math.log(0.03)),
                new Alternative("\"", Math.log(0.01))));
        Map<String, Double> p = LevelProbabilities.fromLogprobs(List.of(
                t("{\""), t("datenschutzstufe"), t("\":\""), level, t("\",\""), t("pii_arten")));
        assertEquals(0.90 / 0.99, p.get("D1"), 1e-9);
        assertEquals(0.06 / 0.99, p.get("D0"), 1e-9);
        assertEquals(0.0, p.get("D3"), 1e-9);
        assertEquals(1.0, p.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    void levelWithLeadingQuoteInSameToken() {
        Token level = new Token("\"D0", Math.log(0.97), List.of(
                new Alternative("\"D0", Math.log(0.97)),
                new Alternative("\"D3", Math.log(0.03))));
        Map<String, Double> p = LevelProbabilities.fromLogprobs(List.of(
                t("{\"datenschutzstufe\":"), level, t("\",")));
        assertEquals(0.97, p.get("D0"), 1e-9);
        assertEquals(0.03, p.get("D3"), 1e-9);
    }

    @Test
    void levelSplitIntoLetterAndDigit() {
        Token digit = new Token("0", Math.log(0.80), List.of(
                new Alternative("0", Math.log(0.80)),
                new Alternative("1", Math.log(0.15)),
                new Alternative("2", Math.log(0.05))));
        Map<String, Double> p = LevelProbabilities.fromLogprobs(List.of(
                t("{\"datenschutzstufe\": \"D"), digit, t("\"")));
        assertEquals(0.80, p.get("D0"), 1e-9);
        assertEquals(0.15, p.get("D1"), 1e-9);
        assertTrue(p.get("D0") < 0.95, "below the default cloud threshold");
    }

    @Test
    void chosenTokenIsCountedEvenWithoutTopLogprobs() {
        Map<String, Double> p = LevelProbabilities.fromLogprobs(List.of(
                t("{\"datenschutzstufe\":\""), new Token("D2", Math.log(0.7), List.of()), t("\"")));
        assertEquals(1.0, p.get("D2"), 1e-9);
    }

    @Test
    void missingKeyFails() {
        assertThrows(IllegalStateException.class,
                () -> LevelProbabilities.fromLogprobs(List.of(t("{\"komplexitaet\":\"mittel\"}"))));
    }
}
