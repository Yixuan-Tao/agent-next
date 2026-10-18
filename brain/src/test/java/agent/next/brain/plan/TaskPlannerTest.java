package agent.next.brain.plan;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.ChatCompletionTransport;
import agent.next.brain.model.Json;
import agent.next.brain.model.ModelConfig;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Planner schema, allowed-app, DAG, and clarification tests. */
public final class TaskPlannerTest {

    private TaskPlannerTest() {
    }

    public static void run() {
        validPlan();
        clarification();
        rejectsDisallowedApp();
        rejectsFirstTargetMismatch();
        rejectsCycle();
        rejectsUnknownFields();
        System.out.println("TaskPlannerTest: PASS (plan, clarification, scope, DAG)");
    }

    private static void validPlan() {
        String content = "{\"task\":{\"successCriteria\":[\"天气显示\",\"日历提醒存在\"]},"
                + "\"subgoals\":["
                + "{\"id\":\"s1\",\"intent\":\"查天气\",\"dependsOn\":[],"
                + "\"targetPackage\":\"com.quark.browser\",\"verifierId\":\"visual\",\"required\":true},"
                + "{\"id\":\"s2\",\"intent\":\"建提醒\",\"dependsOn\":[\"s1\"],"
                + "\"targetPackage\":\"com.android.calendar\",\"verifierId\":\"calendar.event\",\"required\":true}],"
                + "\"assumptions\":[]}";
        PlanningResult result = plan(content);
        check(!result.needsClarification(), "valid plan is not clarification");
        TaskPlan plan = result.plan();
        check(plan.subgoals().size() == 2, "two subgoals");
        check(plan.subgoals().get(0).targetPackage().equals("com.quark.browser"),
                "first target");
        check(plan.subgoals().get(1).verifierId().equals("calendar.event"),
                "second verifier");
        check(plan.task().successCriteria().size() == 2, "success criteria");
        check(plan.subgoals().get(1).dependsOn().contains("s1"), "dependency");
    }

    private static void clarification() {
        PlanningResult result = plan("{\"clarification\":\"哪个城市？\"}");
        check(result.needsClarification(), "clarification detected");
        check(result.clarification().contains("城市"), "clarification text");
    }

    private static void rejectsDisallowedApp() {
        String content = planWithFirstTarget("com.evil.app", "s1", "[]");
        expect(IllegalArgumentException.class,
                () -> plan(content), "disallowed app rejected");
    }

    private static void rejectsFirstTargetMismatch() {
        String content = planWithFirstTarget("com.jingdong.app.mall", "s1", "[]");
        expect(IllegalArgumentException.class,
                () -> plan(content), "first target must be primary");
    }

    private static void rejectsCycle() {
        String content = "{\"task\":{\"successCriteria\":[\"done\"]},"
                + "\"subgoals\":["
                + "{\"id\":\"s1\",\"intent\":\"one\",\"dependsOn\":[\"s2\"],"
                + "\"targetPackage\":\"com.quark.browser\",\"verifierId\":\"visual\",\"required\":true},"
                + "{\"id\":\"s2\",\"intent\":\"two\",\"dependsOn\":[\"s1\"],"
                + "\"targetPackage\":\"com.jingdong.app.mall\",\"verifierId\":\"visual\",\"required\":true}],"
                + "\"assumptions\":[]}";
        expect(IllegalArgumentException.class,
                () -> plan(content), "cycle rejected");
    }

    private static void rejectsUnknownFields() {
        String content = "{\"task\":{\"successCriteria\":[\"done\"]},"
                + "\"subgoals\":[{\"id\":\"s1\",\"intent\":\"one\",\"dependsOn\":[],"
                + "\"targetPackage\":\"com.quark.browser\",\"verifierId\":\"visual\","
                + "\"required\":true,\"x\":1}],\"assumptions\":[]}";
        expect(IllegalArgumentException.class,
                () -> plan(content), "unknown fields rejected");
    }

    private static String planWithFirstTarget(String target, String id, String dependsOn) {
        return "{\"task\":{\"successCriteria\":[\"done\"]},"
                + "\"subgoals\":[{\"id\":\"" + id + "\",\"intent\":\"one\",\"dependsOn\":"
                + dependsOn + ",\"targetPackage\":\"" + target
                + "\",\"verifierId\":\"visual\",\"required\":true}],"
                + "\"assumptions\":[]}";
    }

    private static DeepSeekTaskPlanner planner(String content) {
        ChatCompletionTransport transport = (url, auth, body) -> completion(content);
        return new DeepSeekTaskPlanner(new ChatCompletionClient(
                new ModelConfig("https://example.test", "deepseek-flash", "test-key"),
                transport));
    }

    private static PlanningRequest request() {
        return new PlanningRequest(
                "task-plan",
                "查明天天气并在日历建提醒",
                List.of("明天"),
                Set.of("com.quark.browser", "com.jingdong.app.mall", "com.android.calendar"),
                List.of(
                        new InstalledApp("com.quark.browser", "Quark"),
                        new InstalledApp("com.jingdong.app.mall", "京东"),
                        new InstalledApp("com.android.calendar", "日历")),
                "com.quark.browser",
                Set.of(),
                Set.of("visual", "calendar.event", "jd.cart"),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT));
    }

    private static PlanningResult plan(String content) {
        try {
            return planner(content).plan(request());
        } catch (IOException failure) {
            throw new AssertionError("planner I/O", failure);
        }
    }

    private static String completion(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + Json.quote(content) + "}}]}";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action,
            String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(message + " (got " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }
}
