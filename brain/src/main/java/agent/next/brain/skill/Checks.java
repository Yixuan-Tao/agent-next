package agent.next.brain.skill;

import java.util.Objects;

/** Small validation helpers local to the skill layer. */
final class Checks {

    private Checks() {
    }

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String nonNull(String value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
