package agent.next.brain;

/**
 * Result of comparing the state before and after an action. Error details are
 * explicit; the evaluator never turns missing evidence into success.
 */
public record Effect(EffectKind kind, String detail, boolean retryable) {

    public enum EffectKind {
        CHANGED,
        NO_CHANGE,
        LOADING,
        ERROR,
        NAVIGATED
    }

    public Effect {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        detail = Preconditions.requireText(detail, "detail");
        if (kind != EffectKind.ERROR && retryable) {
            throw new IllegalArgumentException("retryable is only meaningful for ERROR");
        }
    }

    public static Effect changed(String detail) {
        return new Effect(EffectKind.CHANGED, detail, false);
    }

    public static Effect noChange(String detail) {
        return new Effect(EffectKind.NO_CHANGE, detail, false);
    }

    public static Effect loading(String detail) {
        return new Effect(EffectKind.LOADING, detail, false);
    }

    public static Effect error(String detail, boolean retryable) {
        return new Effect(EffectKind.ERROR, detail, retryable);
    }

    public static Effect navigated(String detail) {
        return new Effect(EffectKind.NAVIGATED, detail, false);
    }

    public boolean failed() {
        return kind == EffectKind.ERROR;
    }
}
