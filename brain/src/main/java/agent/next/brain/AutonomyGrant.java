package agent.next.brain;

import java.util.Objects;
import java.util.Set;

/**
 * The autonomy part of a {@link TaskSpec}.
 *
 * <p>Explicit action authorizations are only meaningful at A4 and must be
 * enumerated by id; an empty A4 grant is rejected instead of silently becoming
 * unrestricted.</p>
 */
public record AutonomyGrant(AutonomyLevel level, Set<String> autoAuthorizedActions) {

    public AutonomyGrant {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(autoAuthorizedActions, "autoAuthorizedActions");
        autoAuthorizedActions = Set.copyOf(autoAuthorizedActions);
        for (String action : autoAuthorizedActions) {
            if (action.isBlank()) {
                throw new IllegalArgumentException("auto-authorized action id must not be blank");
            }
        }
        if (level == AutonomyLevel.A4_AUTHORIZED_AUTO && autoAuthorizedActions.isEmpty()) {
            throw new IllegalArgumentException("A4 requires at least one explicit action authorization");
        }
        if (level != AutonomyLevel.A4_AUTHORIZED_AUTO && !autoAuthorizedActions.isEmpty()) {
            throw new IllegalArgumentException("action authorizations only apply to A4");
        }
    }

    public static AutonomyGrant of(AutonomyLevel level) {
        return new AutonomyGrant(level, Set.of());
    }

    public boolean authorizes(String actionId) {
        return actionId != null && autoAuthorizedActions.contains(actionId);
    }
}
