package agent.next.brain;

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
        System.out.println("BrainHostTest: PASS (9 suites)");
    }
}
