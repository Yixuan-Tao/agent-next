package agent.next.brain.session;

import agent.next.brain.Budget;
import agent.next.brain.EpisodicMemory;
import agent.next.brain.HumanGateway;
import agent.next.brain.RiskGate;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskEvent;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopResult;
import agent.next.brain.loop.SlowLoop;
import agent.next.brain.loop.SlowLoopContext;
import agent.next.brain.loop.SlowLoopPolicy;
import agent.next.brain.loop.SlowLoopResult;
import agent.next.brain.model.StageData;
import agent.next.brain.plan.TaskPlan;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes a confirmed TaskPlan serially: one target/app at a time, one
 * subgoal at a time, with per-subgoal verifier completion and main-graph slow
 * loop replanning.
 */
public final class SessionRunner {

    private final DeviceApi api;
    private final TaskPlan plan;
    private final TargetBinder targets;
    private final SessionPolicyFactory policyFactory;
    private final SlowLoopPolicy slowLoopPolicy;
    private final VerifierRegistry verifiers;
    private final RiskGate riskGate;
    private final HumanGateway humanGateway;
    private final LoopControl control;
    private final WorkingMemory workingMemory;
    private final EpisodicMemory episodicMemory;
    private final ObservationOptions observationOptions;
    private final int observationRetries;
    private final int maxAttempts;
    private final SessionStageExtractor stageExtractor;

    public SessionRunner(DeviceApi api, TaskPlan plan, TargetBinder targets,
            SessionPolicyFactory policyFactory, SlowLoopPolicy slowLoopPolicy,
            VerifierRegistry verifiers, RiskGate riskGate, HumanGateway humanGateway,
            LoopControl control, WorkingMemory workingMemory, EpisodicMemory episodicMemory,
            ObservationOptions observationOptions, int observationRetries, int maxAttempts) {
        this(api, plan, targets, policyFactory, slowLoopPolicy, verifiers, riskGate,
                humanGateway, control, workingMemory, episodicMemory, observationOptions,
                observationRetries, maxAttempts, null);
    }

    public SessionRunner(DeviceApi api, TaskPlan plan, TargetBinder targets,
            SessionPolicyFactory policyFactory, SlowLoopPolicy slowLoopPolicy,
            VerifierRegistry verifiers, RiskGate riskGate, HumanGateway humanGateway,
            LoopControl control, WorkingMemory workingMemory, EpisodicMemory episodicMemory,
            ObservationOptions observationOptions, int observationRetries, int maxAttempts,
            SessionStageExtractor stageExtractor) {
        this.api = Objects.requireNonNull(api, "api");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policyFactory = Objects.requireNonNull(policyFactory, "policyFactory");
        this.slowLoopPolicy = Objects.requireNonNull(slowLoopPolicy, "slowLoopPolicy");
        this.verifiers = Objects.requireNonNull(verifiers, "verifiers");
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.control = Objects.requireNonNull(control, "control");
        this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.observationOptions = Objects.requireNonNull(observationOptions, "observationOptions");
        if (observationRetries <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("retry and attempt limits must be positive");
        }
        this.observationRetries = observationRetries;
        this.maxAttempts = maxAttempts;
        this.stageExtractor = stageExtractor;
    }

    public SessionResult run() {
        TaskSpec task = plan.task();
        TaskGraph graph = new TaskGraph(plan.subgoals());
        SubgoalRunner runner = new SubgoalRunner(api, policyFactory, verifiers, riskGate,
                humanGateway, control, workingMemory, episodicMemory, observationOptions,
                observationRetries);
        SlowLoop slowLoop = new SlowLoop(slowLoopPolicy);
        long started = System.nanoTime();
        int actions = 0;
        int attempts = 0;
        TargetHandle target = null;
        LoopResult last = null;
        String subgoalId = "";

        try {
            while (!graph.isDone()) {
                Optional<Subgoal> currentValue = graph.current();
                if (currentValue.isEmpty()) {
                    return SessionResult.failed("no runnable subgoal", actions, attempts,
                            subgoalId, last);
                }
                Subgoal current = currentValue.get();
                subgoalId = current.id();
                String packageName = current.targetPackage();
                if (packageName.isEmpty() && target != null) {
                    packageName = target.packageName();
                }
                if (packageName.isEmpty()) {
                    packageName = plan.subgoals().get(0).targetPackage();
                }
                if (packageName.isEmpty()) {
                    return SessionResult.failed("subgoal has no target package", actions,
                            attempts, subgoalId, last);
                }
                if (target == null || !target.packageName().equals(packageName)) {
                    if (target != null) {
                        targets.release(target);
                        target = null;
                    }
                    target = targets.open(packageName);
                }

                Budget remaining = remainingBudget(task.budget(), started, actions);
                if (remaining == null) {
                    return SessionResult.budget("session budget exhausted", actions, attempts,
                            subgoalId, last);
                }
                if (attempts >= maxAttempts) {
                    return SessionResult.slowLoop("session attempt limit", actions, attempts,
                            subgoalId, last);
                }
                attempts++;
                TaskSpec attemptTask = withBudget(task, remaining);
                SubgoalRunResult run = runner.run(target, current, attemptTask, attempts);
                last = run.result();
                actions += last.actions();

                switch (last.status()) {
                    case DONE:
                        captureStage(task, current, target, last);
                        graph.complete(current.id());
                        continue;
                    case SLOW_LOOP:
                        SlowLoopResult slow = slowLoop.run(new SlowLoopContext(
                                task, graph, graph.current(), last, attempts, workingMemory));
                        switch (slow.status()) {
                            case REPLANNED:
                            case CONTINUE:
                                continue;
                            case NEED_USER:
                                return SessionResult.needUser(slow.detail(), actions, attempts,
                                        subgoalId, last);
                            case STOPPED:
                                return SessionResult.stopped(slow.detail(), actions, attempts,
                                        subgoalId, last);
                            case FAILED:
                            default:
                                return SessionResult.failed(slow.detail(), actions, attempts,
                                        subgoalId, last);
                        }
                    case NEED_USER:
                        return SessionResult.needUser(last.detail(), actions, attempts,
                                subgoalId, last);
                    case BUDGET_EXHAUSTED:
                        return SessionResult.budget(last.detail(), actions, attempts,
                                subgoalId, last);
                    case STOPPED:
                        return SessionResult.stopped(last.detail(), actions, attempts,
                                subgoalId, last);
                    case FAILED:
                    default:
                        return SessionResult.failed(last.detail(), actions, attempts,
                                subgoalId, last);
                }
            }
            return SessionResult.done(actions, attempts);
        } catch (DeviceException failure) {
            return SessionResult.failed("target/session operation failed: " + failure.code(),
                    actions, attempts, subgoalId, last);
        } catch (RuntimeException failure) {
            return SessionResult.failed("session failed: " + failure.getMessage(), actions,
                    attempts, subgoalId, last);
        } finally {
            if (target != null) {
                try {
                    targets.release(target);
                } catch (DeviceException ignored) {
                    // cleanup is best-effort; the service will also clean on disconnect
                }
            }
        }
    }

    private void captureStage(TaskSpec task, Subgoal subgoal, TargetHandle target,
            LoopResult last) {
        if (stageExtractor == null || last == null || last.lastObservation() == null) {
            return;
        }
        try {
            StageData data = stageExtractor.extract(task, subgoal, target, last.lastObservation());
            if (data == null) {
                return;
            }
            if (!data.summary().isEmpty()) {
                workingMemory.put("stage." + subgoal.id() + ".summary", data.summary());
                workingMemory.put("stage.last.summary", data.summary());
            }
            for (Map.Entry<String, String> entity : data.entities().entrySet()) {
                workingMemory.put("stage." + subgoal.id() + ".entity." + entity.getKey(),
                        entity.getValue());
                workingMemory.put("stage.last.entity." + entity.getKey(), entity.getValue());
            }
            episodicMemory.append(new TaskEvent(task.id(), Instant.now(),
                    TaskEvent.EventType.OBSERVATION, "stage extracted: " + subgoal.id(),
                    Map.of("summaryChars", Integer.toString(data.summary().length()),
                            "entities", Integer.toString(data.entities().size()))));
        } catch (DeviceException | RuntimeException failure) {
            episodicMemory.append(new TaskEvent(task.id(), Instant.now(),
                    TaskEvent.EventType.FAILURE, "stage extraction failed: " + subgoal.id(),
                    Map.of()));
        }
    }

    private static Budget remainingBudget(Budget budget, long started, int actions) {
        long remainingNanos = budget.maxDuration().toNanos() - (System.nanoTime() - started);
        int remainingActions = budget.maxActions() - actions;
        if (remainingNanos <= 0 || remainingActions <= 0) {
            return null;
        }
        return new Budget(remainingActions, Duration.ofNanos(remainingNanos),
                budget.maxConsecutiveNoEffect(), budget.maxConsecutiveNoProgress());
    }

    private static TaskSpec withBudget(TaskSpec source, Budget budget) {
        return new TaskSpec(source.id(), source.goal(), source.constraints(),
                source.successCriteria(), source.autonomy(), source.allowedApps(),
                source.deniedActions(), budget);
    }
}
