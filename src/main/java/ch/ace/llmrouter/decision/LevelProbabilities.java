package ch.ace.llmrouter.decision;

import ch.ace.llmrouter.model.PrivacyLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives D0..D3 probabilities from the token logprobs of a generated JSON answer such as
 * {@code {"datenschutzstufe":"D1", ...}}. Pure logic, unit-testable.
 *
 * <p>The value may be one token ({@code D1}, {@code "D1}) or split ({@code D} + {@code 1}).
 * The alternatives at the deciding position ({@code top_logprobs}) give the distribution.
 * Probabilities are normalised over the levels found there; levels not among the
 * alternatives get 0.</p>
 */
public final class LevelProbabilities {

    public record Alternative(String token, double logprob) {
    }

    public record Token(String token, double logprob, List<Alternative> top) {
    }

    private static final Pattern KEY = Pattern.compile("\"datenschutzstufe\"\\s*:\\s*\"");
    private static final Pattern LEVEL_IN_TOKEN = Pattern.compile("^\\s*\"?D([0-3])");
    private static final Pattern DIGIT_TOKEN = Pattern.compile("^([0-3])");

    private LevelProbabilities() {
    }

    /**
     * @throws IllegalStateException if the privacy value or its alternatives cannot be located
     */
    public static Map<String, Double> fromLogprobs(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        int[] start = new int[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            start[i] = text.length();
            text.append(tokens.get(i).token());
        }
        Matcher m = KEY.matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("datenschutzstufe not found in generated tokens");
        }
        int valueStart = m.end(); // offset of the 'D'

        int i = tokenAt(start, text.length(), valueStart);
        Token t = tokens.get(i);
        String fromValue = t.token().substring(valueStart - start[i]);

        // Case 1: the token holding the 'D' also holds the digit -> alternatives at this position
        if (fromValue.length() >= 2 && fromValue.charAt(0) == 'D' && Character.isDigit(fromValue.charAt(1))) {
            return normalise(collect(t, LEVEL_IN_TOKEN));
        }
        // Case 2: 'D' is the last char of this token -> the digit is decided by the next token
        if (fromValue.equals("D") && i + 1 < tokens.size()) {
            return normalise(collect(tokens.get(i + 1), DIGIT_TOKEN));
        }
        throw new IllegalStateException("Unexpected tokenisation of datenschutzstufe: '" + fromValue + "'");
    }

    private static int tokenAt(int[] start, int length, int offset) {
        if (offset >= length) {
            throw new IllegalStateException("datenschutzstufe value missing");
        }
        int i = 0;
        while (i + 1 < start.length && start[i + 1] <= offset) {
            i++;
        }
        return i;
    }

    private static Map<String, Double> collect(Token t, Pattern levelPattern) {
        Map<String, Double> p = new LinkedHashMap<>();
        for (PrivacyLevel level : PrivacyLevel.values()) {
            p.put(level.name(), 0.0);
        }
        List<Alternative> alternatives = t.top() == null || t.top().isEmpty()
                ? List.of(new Alternative(t.token(), t.logprob())) : t.top();
        for (Alternative a : alternatives) {
            // strip a leading quote/space for case 1, nothing to strip for digits in case 2
            Matcher m = levelPattern.matcher(a.token());
            if (m.find()) {
                String level = "D" + m.group(1);
                p.merge(level, Math.exp(a.logprob()), Double::sum);
            }
        }
        // make sure the chosen token is counted even if missing from top_logprobs
        Matcher chosen = levelPattern.matcher(t.token());
        if (chosen.find() && p.get("D" + chosen.group(1)) == 0.0) {
            p.put("D" + chosen.group(1), Math.exp(t.logprob()));
        }
        return p;
    }

    private static Map<String, Double> normalise(Map<String, Double> p) {
        double sum = p.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0.0) {
            throw new IllegalStateException("no privacy level among the token alternatives");
        }
        p.replaceAll((k, v) -> v / sum);
        return p;
    }
}
