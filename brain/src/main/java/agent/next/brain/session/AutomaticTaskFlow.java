package agent.next.brain.session;

import agent.next.brain.contract.PlanValidator;
import agent.next.brain.contract.TaskContract;
import agent.next.brain.contract.TaskContractFactory;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.plan.TaskPlanner;
import agent.next.device.DeviceException;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/** Internal preparation flows directly into execution; only missing information leaves it pending. */
public final class AutomaticTaskFlow {
    @FunctionalInterface
    public interface Execution {
        void run(TaskPlan plan, TaskContract contract) throws DeviceException, LoopStoppedException;
    }

    private final TaskPlanner planner;

    public AutomaticTaskFlow(TaskPlanner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public void run(PlanningRequest request, LoopControl control, Consumer<String> clarify,
            Execution execute) throws IOException, DeviceException, LoopStoppedException {
        control.checkpoint();
        PlanningResult prepared = planner.plan(request);
        control.checkpoint();
        if (prepared.needsClarification()) {
            clarify.accept(prepared.clarification());
            return;
        }
        TaskPlan plan = prepared.plan();
        TaskContract contract = TaskContractFactory.from(request, plan);
        PlanValidator.Result validation = new PlanValidator().validate(contract, plan, request.allowedVerifiers());
        if (!validation.valid()) throw new IllegalArgumentException("invalid task envelope: " + validation.errors());
        control.checkpoint();
        execute.run(plan, contract);
    }
}
