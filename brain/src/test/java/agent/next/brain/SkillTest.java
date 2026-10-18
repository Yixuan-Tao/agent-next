package agent.next.brain;

import java.util.List;
import java.util.Map;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class SkillTest {

    private SkillTest() {
    }

    static void run() {
        validSkill();
        validation();
        System.out.println("SkillTest: PASS (pre/postconditions, steps, fallback, cost)");
    }

    private static void validSkill() {
        Skill skill = new Skill(
                "search",
                "search for a product",
                List.of(new Condition("app-open", "shop is open", true)),
                List.of(new SkillStep("tap", "tap the search field", Map.of("target", "search"))),
                List.of(new Condition("results", "results are visible", true)),
                new Fallback(Fallback.Strategy.RETRY, "retry once"),
                RiskClass.LOW,
                2);
        check(skill.steps().size() == 1, "one step");
        check(skill.preconditions().size() == 1, "precondition");
        check(skill.postconditions().size() == 1, "postcondition");
        check(skill.cost() == 2, "cost");
        expect(UnsupportedOperationException.class,
                () -> skill.steps().get(0).parameters().put("new", "value"), "parameters immutable");
    }

    private static void validation() {
        expect(IllegalArgumentException.class,
                () -> new Skill("search", "search", List.of(), List.of(), List.of(),
                        new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, 1),
                "steps required");
        expect(IllegalArgumentException.class,
                () -> new Skill("search", "search", List.of(),
                        List.of(new SkillStep("tap", "tap", Map.of())), List.of(),
                        new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, -1),
                "negative cost");
        expect(IllegalArgumentException.class,
                () -> new Skill("search", "search",
                        List.of(new Condition("same", "pre", true)),
                        List.of(new SkillStep("tap", "tap", Map.of())),
                        List.of(new Condition("same", "post", true)),
                        new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, 1),
                "duplicate condition ids");
        expect(IllegalArgumentException.class,
                () -> new SkillStep(" ", "tap", Map.of()), "blank action id");
        expect(IllegalArgumentException.class,
                () -> new SkillStep("tap", "tap", Map.of(" ", "value")), "blank parameter name");
        expect(IllegalArgumentException.class,
                () -> new Skill("search", "search", List.of(), List.of(), List.of(),
                        new Fallback(Fallback.Strategy.ASK_USER, " "), RiskClass.LOW, 1),
                "blank fallback description");
    }
}
