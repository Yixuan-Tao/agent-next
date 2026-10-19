package agent.next.brain.loop;

import agent.next.brain.Budget;
import agent.next.brain.ConfirmationRejectedException;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.Effect;
import agent.next.brain.EffectEvaluator;
import agent.next.brain.EpisodicMemory;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.RiskDecision;
import agent.next.brain.RiskGate;
import agent.next.brain.SlowLoopReason;
import agent.next.brain.SlowLoopSignals;
import agent.next.brain.StateFingerprint;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskEvent;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.brain.action.ActionExecutor;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.ObservationStatus;
import agent.next.device.TargetHandle;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic fast-loop skeleton:
 *
 * <pre>
 * PERCEIVE → policy.complete? → policy.next → risk gate → ACT
 *          → VERIFY_EFFECT → memory → SLOW_LOOP decision
 * </pre>
 *
 * <p>It has no model call and no built-in skill library; a {@link LoopPolicy}
 * decides the next semantic action. The slow loop is represented by a
 * {@link LoopResult} with {@code SLOW_LOOP} and a reason; replanning belongs to
 * a later slow-loop component.</p>
 */
public final class FastLoop {

    private static final long OBSERVE_RETRY_MILLIS = 200L;

    private final DeviceApi api;
    private final TargetHandle target;
    private final TaskSpec task;
    private final TaskGraph graph;
    private final LoopPolicy policy;
    private final RiskGate riskGate;
    private final HumanGateway humanGateway;
    private final WorkingMemory workingMemory;
    private final EpisodicMemory episodicMemory;
    private final ObservationOptions observationOptions;
    private final EffectEvaluator effectEvaluator = new EffectEvaluator();
    private final int observationRetries;
    private final LoopControl control;
    private final ActionExecutor actionExecutor;

    public FastLoop(DeviceApi api, TargetHandle target, TaskSpec task, TaskGraph graph,
            LoopPolicy policy, RiskGate riskGate, HumanGateway humanGateway,
            WorkingMemory workingMemory, EpisodicMemory episodicMemory) {
        this(api, target, task, graph, policy, riskGate, humanGateway, workingMemory,
                episodicMemory, ObservationOptions.defaults(), 3);
    }

    public FastLoop(DeviceApi api, TargetHandle target, TaskSpec task, TaskGraph graph,
            LoopPolicy policy, RiskGate riskGate, HumanGateway humanGateway,
            WorkingMemory workingMemory, EpisodicMemory episodicMemory,
            ObservationOptions observationOptions, int observationRetries) {
        this(api, target, task, graph, policy, riskGate, humanGateway, workingMemory,
                episodicMemory, observationOptions, observationRetries, LoopControl.none());
    }

    public FastLoop(DeviceApi api, TargetHandle target, TaskSpec task, TaskGraph graph,
            LoopPolicy policy, RiskGate riskGate, HumanGateway humanGateway,
            WorkingMemory workingMemory, EpisodicMemory episodicMemory,
            ObservationOptions observationOptions, int observationRetries, LoopControl control) {
        this.api = Objects.requireNonNull(api, "api");
        this.target = Objects.requireNonNull(target, "target");
        this.task = Objects.requireNonNull(task, "task");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.observationOptions = Objects.requireNonNull(observationOptions, "observationOptions");
        if (observationRetries <= 0) {
            throw new IllegalArgumentException("observationRetries must be positive");
        }
        this.observationRetries = observationRetries;
        this.control = Objects.requireNonNull(control, "control");
        this.actionExecutor = new ActionExecutor(api, riskGate, humanGateway, control);
    }

    public LoopResult run() {
        Budget budget = task.budget();
        long started = System.nanoTime();
        int actions = 0;
        int noEffect = 0;
        int noProgress = 0;
        int rejectedActions = 0;
        Observation last = null;

        while (true) {
            if (graph.isDone()) {
                event(TaskEvent.EventType.COMPLETION, "task complete", Map.of());
                return LoopResult.done(actions, last);
            }
            try {
                control.checkpoint();
            } catch (LoopStoppedException stopped) {
                event(TaskEvent.EventType.STOPPED, stopped.getMessage(), Map.of());
                return LoopResult.stopped(stopped.getMessage(), actions, last);
            }
            Optional<Subgoal> current = graph.current();
            if (current.isEmpty()) {
                return LoopResult.slowLoop(SlowLoopReason.GRAPH_INCONSISTENT,
                        "no runnable subgoal", actions, last);
            }
            Subgoal subgoal = current.get();

            Observation observation;
            try {
                observation = observe();
            } catch (DeviceException failure) {
                event(TaskEvent.EventType.FAILURE, "observe failed: " + failure.code(), Map.of());
                return LoopResult.failed("observe failed: " + failure.code(), actions, last);
            }
            last = observation;

            LoopContext context = new LoopContext(task, subgoal, observation, actions,
                    noEffect, noProgress, workingMemory);
            if (policy.complete(context)) {
                graph.complete(subgoal.id());
                event(TaskEvent.EventType.COMPLETION, "subgoal complete: " + subgoal.id(), Map.of());
                noEffect = 0;
                noProgress = 0;
                if (graph.isDone()) {
                    return LoopResult.done(actions, last);
                }
                continue;
            }

            if (elapsedNanos(started) >= budget.maxDuration().toNanos()) {
                return LoopResult.budgetExhausted("duration budget", actions, last);
            }
            if (actions >= budget.maxActions()) {
                return LoopResult.budgetExhausted("action budget", actions, last);
            }

            Optional<LoopAction> next;
            try {
                next = policy.next(context);
            } catch (HumanHandoffRequiredException handoff) {
                event(TaskEvent.EventType.HANDOFF, handoff.getMessage(), Map.of());
                return LoopResult.needUser(handoff.getMessage(), actions, last);
            }
            if (next.isEmpty()) {
                return LoopResult.slowLoop(SlowLoopReason.GRAPH_INCONSISTENT,
                        "policy produced no action", actions, last);
            }
            LoopAction action = next.get();

            boolean governed = action instanceof ProposalCarrier;
            if (!governed) {
                RiskDecision decision = riskGate.evaluate(
                        action.descriptor(target.packageName()), task);
                if (decision instanceof RiskDecision.Handoff handoff) {
                    event(TaskEvent.EventType.HANDOFF, handoff.reason(),
                            Map.of("action", action.id()));
                    return LoopResult.needUser(handoff.reason(), actions, last);
                }
                if (decision instanceof RiskDecision.Deny deny) {
                    event(TaskEvent.EventType.FAILURE, deny.reason(),
                            Map.of("action", action.id()));
                    return LoopResult.slowLoop(SlowLoopReason.RISK_GATE, deny.reason(),
                            actions, last);
                }
                if (decision instanceof RiskDecision.Confirm confirm) {
                    try {
                        ConfirmationToken token = humanGateway.confirm(confirm.request());
                        token.requireValidFor(confirm.request(), Instant.now());
                    } catch (HumanUnavailableException unavailable) {
                        return LoopResult.needUser("confirmation unavailable: "
                                + unavailable.getMessage(), actions, last);
                    } catch (ConfirmationRejectedException rejected) {
                        return LoopResult.needUser("confirmation rejected: "
                                + rejected.getMessage(), actions, last);
                    }
                    event(TaskEvent.EventType.CONFIRMATION, "confirmed: " + action.id(),
                            Map.of("action", action.id()));
                }
            }

            try {
                control.checkpoint();
            } catch (LoopStoppedException stopped) {
                event(TaskEvent.EventType.STOPPED, stopped.getMessage(), Map.of());
                return LoopResult.stopped(stopped.getMessage(), actions, last);
            }
            StateFingerprint before = ObservationFingerprint.of(observation);
            InputReceipt receipt;
            try {
                if (governed) {
                    ActionExecutor.Execution execution = actionExecutor.execute(
                            task, target, ((ProposalCarrier) action).proposal());
                    receipt = execution.receipt();
                    if (execution.confirmed()) {
                        event(TaskEvent.EventType.CONFIRMATION, "confirmed: " + action.id(),
                                Map.of("action", action.id()));
                    }
                } else {
                    receipt = action.execute(api, target, observation);
                }
            } catch (HumanHandoffRequiredException handoff) {
                event(TaskEvent.EventType.HANDOFF, handoff.getMessage(),
                        Map.of("action", action.id()));
                return LoopResult.needUser(handoff.getMessage(), actions, last);
            } catch (ConfirmationRejectedException rejected) {
                return LoopResult.needUser("confirmation rejected: "
                        + rejected.getMessage(), actions, last);
            } catch (HumanUnavailableException unavailable) {
                return LoopResult.needUser("confirmation unavailable: "
                        + unavailable.getMessage(), actions, last);
            } catch (LoopStoppedException stopped) {
                event(TaskEvent.EventType.STOPPED, stopped.getMessage(), Map.of());
                return LoopResult.stopped(stopped.getMessage(), actions, last);
            } catch (DeviceException failure) {
                if (failure.code() == DeviceException.Code.NEED_USER) {
                    event(TaskEvent.EventType.HANDOFF, failure.getMessage(),
                            Map.of("action", action.id()));
                    return LoopResult.needUser(failure.getMessage(), actions, last);
                }
                if (failure.code() == DeviceException.Code.CANCELLED) {
                    event(TaskEvent.EventType.STOPPED, failure.getMessage(),
                            Map.of("action", action.id()));
                    return LoopResult.stopped(failure.getMessage(), actions, last);
                }
                event(TaskEvent.EventType.FAILURE,
                        "action failed: " + failure.code() + " " + failure.getMessage(),
                        Map.of("action", action.id()));
                if ((failure.code() == DeviceException.Code.REJECTED
                        || failure.code() == DeviceException.Code.STALE)
                        && rejectedActions++ < 3) {
                    continue;
                }
                return LoopResult.failed("action failed: " + failure.code() + " "
                        + failure.getMessage(), actions, last);
            }
            if (!receipt.accepted()) {
                event(TaskEvent.EventType.FAILURE, "input rejected: " + receipt.status(),
                        Map.of("action", action.id()));
                if (rejectedActions++ < 3) {
                    continue;
                }
                return LoopResult.failed("input rejected: " + receipt.status(), actions, last);
            }
            actions++;
            workingMemory.put("last.action", action.id());
            event(TaskEvent.EventType.ACTION, action.id(),
                    Map.of("kind", action.kind().name()));

            Observation after;
            try {
                after = observe();
            } catch (DeviceException failure) {
                event(TaskEvent.EventType.FAILURE, "post-action observe failed: "
                        + failure.code(), Map.of("action", action.id()));
                return LoopResult.failed("post-action observe failed", actions, last);
            }
            last = after;
            Effect effect = effectEvaluator.evaluate(before, ObservationFingerprint.of(after));
            workingMemory.put("last.effect", effect.kind().name());
            event(TaskEvent.EventType.EFFECT, effect.kind().name(),
                    Map.of("detail", effect.detail()));

            if (effect.kind() == Effect.EffectKind.NO_CHANGE
                    || effect.kind() == Effect.EffectKind.LOADING) {
                noEffect++;
                noProgress++;
            } else {
                noEffect = 0;
                noProgress = 0;
            }

            LoopContext afterContext = new LoopContext(task, subgoal, after, actions,
                    noEffect, noProgress, workingMemory);
            if (policy.complete(afterContext)) {
                graph.complete(subgoal.id());
                event(TaskEvent.EventType.COMPLETION, "subgoal complete: " + subgoal.id(), Map.of());
                noEffect = 0;
                noProgress = 0;
                if (graph.isDone()) {
                    return LoopResult.done(actions, last);
                }
                continue;
            }

            if (actions >= budget.maxActions()) {
                return LoopResult.budgetExhausted("action budget", actions, last);
            }
            if (elapsedNanos(started) >= budget.maxDuration().toNanos()) {
                return LoopResult.budgetExhausted("duration budget", actions, last);
            }

            boolean budgetNear = actions >= budget.maxActions() - 1;
            SlowLoopSignals signals = new SlowLoopSignals(noEffect, noProgress,
                    false, false, false, false, budgetNear, false);
            Optional<SlowLoopReason> reason = signals.trigger(budget);
            if (reason.isPresent()) {
                return LoopResult.slowLoop(reason.get(), "slow loop: " + reason.get(),
                        actions, last);
            }
        }
    }

    private Observation observe() throws DeviceException {
        Observation last = null;
        for (int attempt = 0; attempt < observationRetries; attempt++) {
            last = api.observations().observe(target, observationOptions);
            if (last.status() == ObservationStatus.READY) {
                return last;
            }
            if (attempt + 1 < observationRetries) {
                sleep(OBSERVE_RETRY_MILLIS);
            }
        }
        throw new DeviceException(DeviceException.Code.TIMEOUT,
                "observation not READY: " + (last == null ? "none" : last.status()));
    }

    private static long elapsedNanos(long started) {
        return System.nanoTime() - started;
    }

    private static void sleep(long millis) throws DeviceException {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "loop interrupted", interrupted);
        }
    }

    private void event(TaskEvent.EventType type, String summary, Map<String, String> data) {
        episodicMemory.append(new TaskEvent(task.id(), Instant.now(), type, summary, data));
    }
}
