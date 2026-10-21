package agent.next.brain.reactive;

import java.text.Normalizer;
import java.util.Locale;

/** Task-local identity and independently checkable conditions for a non-idempotent operation. */
public record BusinessEffect(String key, String postcondition, String absenceCondition) {
    public BusinessEffect {
        key = Normalizer.normalize(text(key, 160), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        postcondition = text(postcondition, 400);
        absenceCondition = text(absenceCondition, 400);
        if (postcondition.equals(absenceCondition)) throw new IllegalArgumentException("effect conditions must differ");
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("invalid business effect detail");
        }
        return value.strip();
    }
}
