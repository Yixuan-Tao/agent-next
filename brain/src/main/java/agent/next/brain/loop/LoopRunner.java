package agent.next.brain.loop;

import agent.next.brain.Budget;
import agent.next.brain.EpisodicMemory;
import agent.next.brain.HumanGateway;
import agent.next.brain.RiskGate;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.device.DeviceApi;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.time.Duration;
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
        long started = System.nanoTime();
        int attempts = 0;
        int replans = 0;
        int totalActions = 0;
        LoopResult last = null;
        while (attempts < maxAttempts) {
            Budget remaining = remainingBudget(started, totalActions);
            if (remaining == null) {
                return failed("cross-attempt budget exhausted", attempts, replans,
                        totalActions, last);
            }
            TaskSpec attemptTask = withBudget(task, remaining);
            attempts++;
            LoopPolicy policy = policyFactory.create(attempts, graph);
            if (policy == null) {
                return failed("policy factory returned no policy", attempts, replans,
                        totalActions, last);
            }
            last = new FastLoop(api, target, attemptTask, graph, policy, riskGate, humanGateway,
                    workingMemory, episodicMemory, observationOptions, observationRetries).run();
            totalActions += last.actions();
            if (last.status() != LoopResult.Status.SLOW_LOOP) {
                return new LoopRunResult(last, attempts, replans, totalActions, graph);
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
                            slow.detail(), totalActions, last.lastObservation()),
                            attempts, replans, totalActions, graph);
                case STOPPED:
                    return failed("slow loop stopped: " + slow.detail(), attempts, replans,
                            totalActions, last);
                case FAILED:
                default:
                    return failed("slow loop failed: " + slow.detail(), attempts, replans,
                            totalActions, last);
            }
        }
        return failed("slow-loop attempt limit reached", attempts, replans, totalActions, last);
    }

    private Budget remainingBudget(long started, int totalActions) {
        long remainingNanos = task.budget().maxDuration().toNanos()
                - (System.nanoTime() - started);
        int remainingActions = task.budget().maxActions() - totalActions;
        if (remainingNanos <= 0 || remainingActions <= 0) {
            return null;
        }
        return new Budget(remainingActions, Duration.ofNanos(remainingNanos),
                task.budget().maxConsecutiveNoEffect(),
                task.budget().maxConsecutiveNoProgress());
    }

    private static TaskSpec withBudget(TaskSpec source, Budget budget) {
        return new TaskSpec(source.id(), source.goal(), source.constraints(),
                source.successCriteria(), source.autonomy(), source.allowedApps(),
                source.deniedActions(), budget);
    }

    private LoopRunResult failed(String detail, int attempts, int replans,
            int totalActions, LoopResult last) {
        return new LoopRunResult(
                LoopResult.failed(detail, totalActions,
                        last == null ? null : last.lastObservation()),
                attempts, replans, totalActions, graph);
    }
}
