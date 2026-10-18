package agent.next.brain.loop;

import agent.next.brain.EpisodicMemory;
import agent.next.brain.HumanGateway;
import agent.next.brain.RiskGate;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.device.DeviceApi;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.util.Objects;

/**
 * Runs a fast loop, applies one slow-loop decision when the fast loop escalates,
 * and retries until the graph completes, a human is needed, or attempts run out.
 */
public final class LoopRunner {

    private final DeviceApi api;
    private final TargetHandle target;
    private final TaskSpec task;
    private final TaskGraph graph;
    private final LoopPolicyFactory policyFactory;
    private final SlowLoopPolicy slowLoopPolicy;
    private final RiskGate riskGate;
    private final HumanGateway humanGateway;
    private final WorkingMemory workingMemory;
    private final EpisodicMemory episodicMemory;
    private final ObservationOptions observationOptions;
    private final int observationRetries;
    private final int maxAttempts;

    public LoopRunner(DeviceApi api, TargetHandle target, TaskSpec task, TaskGraph graph,
            LoopPolicyFactory policyFactory, SlowLoopPolicy slowLoopPolicy,
            RiskGate riskGate, HumanGateway humanGateway, WorkingMemory workingMemory,
            EpisodicMemory episodicMemory, int maxAttempts) {
        this(api, target, task, graph, policyFactory, slowLoopPolicy, riskGate, humanGateway,
                workingMemory, episodicMemory, ObservationOptions.defaults(), 20, maxAttempts);
    }

    public LoopRunner(DeviceApi api, TargetHandle target, TaskSpec task, TaskGraph graph,
            LoopPolicyFactory policyFactory, SlowLoopPolicy slowLoopPolicy,
            RiskGate riskGate, HumanGateway humanGateway, WorkingMemory workingMemory,
            EpisodicMemory episodicMemory, ObservationOptions observationOptions,
            int observationRetries, int maxAttempts) {
        this.api = Objects.requireNonNull(api, "api");
        this.target = Objects.requireNonNull(target, "target");
        this.task = Objects.requireNonNull(task, "task");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.policyFactory = Objects.requireNonNull(policyFactory, "policyFactory");
        this.slowLoopPolicy = Objects.requireNonNull(slowLoopPolicy, "slowLoopPolicy");
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.observationOptions = Objects.requireNonNull(observationOptions, "observationOptions");
        if (observationRetries <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("retry and attempt limits must be positive");
        }
        this.observationRetries = observationRetries;
        this.maxAttempts = maxAttempts;
    }

    public LoopRunResult run() {
        SlowLoop slowLoop = new SlowLoop(slowLoopPolicy);
        int attempts = 0;
        int replans = 0;
        LoopResult last = null;
        while (attempts < maxAttempts) {
            attempts++;
            LoopPolicy policy = policyFactory.create(attempts, graph);
            if (policy == null) {
                return failed("policy factory returned no policy", attempts, replans, last);
            }
            last = new FastLoop(api, target, task, graph, policy, riskGate, humanGateway,
                    workingMemory, episodicMemory, observationOptions, observationRetries).run();
            if (last.status() != LoopResult.Status.SLOW_LOOP) {
                return new LoopRunResult(last, attempts, replans, graph);
            }
            SlowLoopResult slow = slowLoop.run(new SlowLoopContext(
                    task, graph, graph.current(), last, attempts, workingMemory));
            switch (slow.status()) {
                case REPLANNED:
                    replans++;
                    continue;
                case CONTINUE:
                    continue;
                case NEED_USER:
                    return new LoopRunResult(LoopResult.needUser(
                            slow.detail(), last.actions(), last.lastObservation()),
                            attempts, replans, graph);
                case STOPPED:
                    return failed("slow loop stopped: " + slow.detail(), attempts, replans, last);
                case FAILED:
                default:
                    return failed("slow loop failed: " + slow.detail(), attempts, replans, last);
            }
        }
        return failed("slow-loop attempt limit reached", attempts, replans, last);
    }

    private LoopRunResult failed(String detail, int attempts, int replans, LoopResult last) {
        int actions = last == null ? 0 : last.actions();
        return new LoopRunResult(
                LoopResult.failed(detail, actions, last == null ? null : last.lastObservation()),
                attempts, replans, graph);
    }
}
