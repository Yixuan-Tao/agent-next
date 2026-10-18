package agent.next.brain.skill;

import java.util.List;
import java.util.Objects;

/**
 * An immutable skill artifact. A workflow is validated against declared
 * permissions at construction; a prompt body is retained for a future
 * model-backed prompt policy and cannot be run by the deterministic policy.
 */
public record SkillPackage(
        String id,
        String version,
        String intent,
        SkillBody body,
        SkillPermissions permissions,
        SkillApplicability applicability,
        SkillVerifier verifier,
        SkillTrust trust,
        SkillProvenance provenance) {

    public SkillPackage {
        id = Checks.text(id, "id");
        version = Checks.text(version, "version");
        intent = Checks.text(intent, "intent");
        body = Objects.requireNonNull(body, "body");
        permissions = Objects.requireNonNull(permissions, "permissions");
        applicability = Objects.requireNonNull(applicability, "applicability");
        verifier = Objects.requireNonNull(verifier, "verifier");
        trust = Objects.requireNonNull(trust, "trust");
        provenance = Objects.requireNonNull(provenance, "provenance");
        for (SkillOperation operation : operations(body)) {
            if (!permissions.allows(operation.capability())) {
                throw new IllegalArgumentException("skill " + id + " uses undeclared capability "
                        + operation.capability());
            }
        }
    }

    public SkillKey key() {
        return new SkillKey(id, version);
    }

    private static List<SkillOperation> operations(SkillBody body) {
        if (body instanceof SkillBody.Workflow workflow) {
            return workflow.operations();
        }
        if (body instanceof SkillBody.Hybrid hybrid) {
            return hybrid.operations();
        }
        return List.of();
    }
}
