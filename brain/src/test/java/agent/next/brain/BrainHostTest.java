package agent.next.brain;

import agent.next.brain.loop.LoopTest;
import agent.next.brain.loop.SlowLoopTest;
import agent.next.brain.model.ModelApiTest;
import agent.next.brain.plan.TaskPlannerTest;
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
        SkillFeatureTest.run();
        ModelApiTest.run();
        TaskPlannerTest.run();
        System.out.println("BrainHostTest: PASS (14 suites)");
    }
}
