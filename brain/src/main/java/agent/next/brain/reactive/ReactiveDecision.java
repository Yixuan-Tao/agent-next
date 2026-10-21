package agent.next.brain.reactive;

import agent.next.brain.action.ActionProposal;
import java.util.Objects;
import java.util.List;

/** Exactly one decision for the currently observed page. */
public sealed interface ReactiveDecision permits ReactiveDecision.Act,
        ReactiveDecision.OpenApp, ReactiveDecision.Done, ReactiveDecision.NeedUser,
        ReactiveDecision.CheckResult, ReactiveDecision.Failed {
    record Act(ActionProposal proposal, String expectedChange, String progress, BusinessEffect effect) implements ReactiveDecision {
        public Act(ActionProposal proposal, String expectedChange, String progress) { this(proposal, expectedChange, progress, null); }
        public Act(ActionProposal proposal) { this(proposal, "根据新页面检查动作目的是否达成", ""); }
        public Act(ActionProposal proposal, String expectedChange) { this(proposal, expectedChange, ""); }
        public Act {
            Objects.requireNonNull(proposal, "proposal");
            expectedChange = text(expectedChange);
            progress = Objects.requireNonNull(progress, "progress");
            if (progress.length() > 60) throw new IllegalArgumentException("progress is too long");
            if (effect != null && !(proposal instanceof ActionProposal.Tap) && !(proposal instanceof ActionProposal.VisualTap)) {
                throw new IllegalArgumentException("business effect requires a tap action");
            }
        }
    }
    record OpenApp(String packageName) implements ReactiveDecision {
        public OpenApp { packageName = text(packageName); }
    }
    record Done(String evidence) implements ReactiveDecision {
        public Done { evidence = text(evidence); }
    }
    record NeedUser(String reason) implements ReactiveDecision {
        public NeedUser { reason = text(reason); }
    }
    record Failed(String reason) implements ReactiveDecision {
        public Failed { reason = text(reason); }
    }
    record CheckResult(List<String> criterionIds) implements ReactiveDecision {
        public CheckResult {
            criterionIds = List.copyOf(criterionIds);
            if (criterionIds.isEmpty() || criterionIds.size() > 8
                    || criterionIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("invalid result criteria");
            }
        }
    }
    private static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing decision detail");
        return value;
    }
}
