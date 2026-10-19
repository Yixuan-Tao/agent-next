package agent.next.brain;

import agent.next.brain.KnownDefectsTest;
import agent.next.brain.action.ActionExecutorTest;
import agent.next.brain.contract.TaskClarificationTest;
import agent.next.brain.contract.TaskContractTest;
import agent.next.brain.contract.TaskReportTest;
import agent.next.brain.contract.TaskTimeTest;
import agent.next.brain.action.SequenceActionTest;
import agent.next.brain.grounding.GroundingTest;
import agent.next.brain.loop.LoopTest;
import agent.next.brain.loop.SlowLoopTest;
import agent.next.brain.model.ModelApiTest;
import agent.next.brain.plan.TaskPlannerTest;
import agent.next.brain.session.SessionBridgeTest;
import agent.next.brain.session.SessionIntegrationTest;
import agent.next.brain.session.SessionRunnerTest;
import agent.next.brain.skill.SkillFeatureTest;

/** Entry point for the dependency-free brain host tests. */
public final class BrainHostTest {

    private BrainHostTest() {
    }

    public static void main(String[] args) {
        TaskGraphTest.run();
        AutonomyPolicyTest.run();
        TaskSpecTest.run();
        RiskGateTest.run();
        EffectEvaluatorTest.run();
        ConfirmationTest.run();
        MemoryTest.run();
        SlowLoopSignalsTest.run();
        SkillTest.run();
        LoopTest.run();
        SlowLoopTest.run();
        ActionExecutorTest.run();
        SequenceActionTest.run();
        TaskContractTest.run();
        TaskClarificationTest.run();
        TaskReportTest.run();
        KnownDefectsTest.run();
        TaskTimeTest.run();
        GroundingTest.run();
        SkillFeatureTest.run();
        ModelApiTest.run();
        TaskPlannerTest.run();
        SessionRunnerTest.run();
        SessionBridgeTest.run();
        SessionIntegrationTest.run();
        System.out.println("BrainHostTest: PASS (25 suites)");
    }
}
