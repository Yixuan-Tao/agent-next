package agent.next.brain.skill;

import agent.next.device.Observation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Installed skill artifacts with trust-aware admission and revocation.
 *
 * <p>Official/verified artifacts may be installed directly. Community and
 * self-generated artifacts require a passing {@link SkillValidation}; a
 * candidate path also runs the validator before installation.</p>
 */
public final class SkillRegistry {

    private final Map<SkillKey, SkillPackage> installed = new LinkedHashMap<>();
    private final Map<SkillKey, SkillValidation> validations = new LinkedHashMap<>();

    public void install(SkillPackage skill, SkillValidation validation) {
        Objects.requireNonNull(skill, "skill");
        SkillKey key = skill.key();
        if (requiresValidation(skill.trust())
                && (validation == null || !validation.passed())) {
            throw new SkillRejectedException("untrusted skill requires passing validation: "
                    + key.id() + "@" + key.version());
        }
        installed.put(key, skill);
        if (validation != null) {
            validations.put(key, validation);
        }
    }

    public SkillPackage installCandidate(SkillCandidate candidate, SkillValidator validator) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(validator, "validator");
        SkillValidation validation = validator.validate(candidate.skill());
        if (validation == null || !validation.passed()) {
            String report = validation == null ? "validator returned no result" : validation.report();
            throw new SkillRejectedException("candidate rejected: " + report);
        }
        install(candidate.skill(), validation);
        return candidate.skill();
    }

    public Optional<SkillPackage> find(String id, String version) {
        return Optional.ofNullable(installed.get(new SkillKey(id, version)));
    }

    public Optional<SkillValidation> validation(String id, String version) {
        return Optional.ofNullable(validations.get(new SkillKey(id, version)));
    }

    public List<SkillPackage> applicable(Observation observation,
            Map<String, String> parameters) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(parameters, "parameters");
        List<SkillPackage> result = new ArrayList<>();
        for (SkillPackage skill : installed.values()) {
            if (!skill.permissions().allowsTarget(observation.packageName())) {
                continue;
            }
            try {
                if (skill.applicability()
                        .check(observation, observation.packageName(), parameters).applicable()) {
                    result.add(skill);
                }
            } catch (RuntimeException invalidCheck) {
                // A broken applicability check is not evidence of applicability.
            }
        }
        return List.copyOf(result);
    }

    public void revoke(String id, String version) {
        SkillKey key = new SkillKey(id, version);
        installed.remove(key);
        validations.remove(key);
    }

    public int size() {
        return installed.size();
    }

    private static boolean requiresValidation(SkillTrust trust) {
        return trust == SkillTrust.COMMUNITY || trust == SkillTrust.SELF_GENERATED;
    }
}
