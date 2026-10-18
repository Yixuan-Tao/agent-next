package agent.next.brain.session;

import agent.next.brain.EpisodicMemory;
import agent.next.brain.HumanGateway;
import agent.next.brain.RiskGate;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.brain.loop.FastLoop;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.LoopResult;
import agent.next.device.DeviceApi;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Runs exactly one subgoal attempt on one bound target. */
public final class SubgoalRunner {

    private final DeviceApi api;
    private final SessionPolicyFactory policyFactory;
    private final VerifierRegistry verifiers;
    private final RiskGate riskGate;
    private final HumanGateway humanGateway;
    private final LoopControl control;
    private final WorkingMemory workingMemory;
    private final EpisodicMemory episodicMemory;
    private final ObservationOptions observationOptions;
    private final int observationRetries;

    public SubgoalRunner(DeviceApi api, SessionPolicyFactory policyFactory,
            VerifierRegistry verifiers, RiskGate riskGate, HumanGateway humanGateway,
            LoopControl control, WorkingMemory workingMemory, EpisodicMemory episodicMemory,
            ObservationOptions observationOptions, int observationRetries) {
        this.api = Objects.requireNonNull(api, "api");
        this.policyFactory = Objects.requireNonNull(policyFactory, "policyFactory");
        this.verifiers = Objects.requireNonNull(verifiers, "verifiers");
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.control = Objects.requireNonNull(control, "control");
        this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.observationOptions = Objects.requireNonNull(observationOptions, "observationOptions");
        if (observationRetries <= 0) {
            throw new IllegalArgumentException("observationRetries must be positive");
        }
        this.observationRetries = observationRetries;
    }

    public SubgoalRunResult run(TargetHandle target, Subgoal subgoal, TaskSpec task,
            int attempt) {
        SubgoalVerifier verifier = verifiers.find(subgoal.verifierId()).orElse(null);
        if (verifier == null) {
            return new SubgoalRunResult(LoopResult.failed(
                    "verifier not registered: " + subgoal.verifierId(), 0, null), attempt);
        }
        LoopPolicy base = policyFactory.create(task, subgoal, target, attempt);
        if (base == null) {
            return new SubgoalRunResult(LoopResult.failed(
                    "policy factory returned no policy", 0, null), attempt);
        }
        Subgoal standalone = new Subgoal(subgoal.id(), subgoal.intent(), Set.of(),
                subgoal.preconditions(), subgoal.postconditions(), subgoal.fallback(),
                subgoal.risk(), subgoal.required(), subgoal.targetPackage(),
                subgoal.verifierId());
        TaskGraph graph = TaskGraph.of(standalone);
        LoopPolicy policy = new VerifiedLoopPolicy(base, verifier, task, standalone);
        LoopResult result = new FastLoop(api, target, task, graph, policy, riskGate,
                humanGateway, workingMemory, episodicMemory, observationOptions,
                observationRetries, control).run();
        return new SubgoalRunResult(result, attempt);
    }
}
