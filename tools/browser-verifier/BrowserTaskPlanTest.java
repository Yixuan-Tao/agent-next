import agent.next.app.skill.BrowserTaskPlan;
import agent.next.brain.*;
import agent.next.brain.contract.PlanValidator;
import agent.next.brain.contract.TaskContractFactory;
import agent.next.brain.plan.InstalledApp;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.TaskPlan;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class BrowserTaskPlanTest {
    private static final String BROWSER = "com.quark.browser";
    private static final String OTHER = "com.example.reader";

    public static void main(String[] args) {
        preservesOriginalEnvelopeAndFinalEvidence();
        preservesDifferentTopics();
        leavesAdditionalRequirementsUnchanged();
        leavesOtherScopesUnchanged();
        System.out.println("BrowserTaskPlanTest: PASS (original envelope, terminal criteria, topic, scope, contract)");
    }

    private static void preservesOriginalEnvelopeAndFinalEvidence() {
        PlanningRequest request = request("搜索陶瓷釉色资料，打开一篇正文并告诉我真实标题", BROWSER);
        TaskPlan original = plan(BROWSER, BROWSER);
        TaskPlan normalized = BrowserTaskPlan.normalize(request, original);
        require(normalized.subgoals().size() == 1, "browser plan must use one final-goal subgoal");
        TaskSpec task = normalized.task();
        require(task.id().equals(request.taskId()) && task.goal().equals(request.goal()),
                "original task identity and complete user goal are preserved");
        require(task.constraints().equals(request.constraints()), "constraints preserve original wording and order");
        require(task.allowedApps().equals(request.allowedApps()), "app permission scope is preserved");
        require(task.deniedActions().equals(request.deniedActions()), "original denials are preserved");
        require(task.autonomy().equals(request.autonomy()), "original autonomy cannot be expanded");
        require(task.budget().equals(original.task().budget()), "model plan budget is preserved");
        Subgoal goal = normalized.subgoals().get(0);
        require(goal.intent().contains(request.goal()), "subgoal retains the entire original goal");
        require(goal.dependsOn().isEmpty() && goal.preconditions().isEmpty(), "no fixed intermediate state is required");
        require(goal.required() && goal.targetPackage().equals(BROWSER)
                        && goal.verifierId().equals("visual"), "final subgoal is required and visually verified in the browser");
        require(task.successCriteria().size() == 3 && goal.postconditions().size() == 3,
                "three terminal conditions replace intermediate search-page conditions");
        for (int index = 0; index < 3; index++) {
            SuccessCriterion criterion = task.successCriteria().get(index);
            Condition condition = goal.postconditions().get(index);
            require(criterion.required() && condition.required()
                            && criterion.id().equals(condition.id())
                            && criterion.description().equals(condition.description()),
                    "each required criterion has an identical terminal postcondition");
        }
        String criteria = task.successCriteria().toString();
        require(criteria.contains("原始") && criteria.contains("正文")
                        && criteria.contains("不是") && criteria.contains("结果列表")
                        && criteria.contains("真实") && criteria.contains("标题"),
                "criteria cover original topic, actual article and readable real title");
        require(normalized.assumptions().containsAll(original.assumptions())
                        && normalized.assumptions().size() > original.assumptions().size(),
                "normalization appends an explanation to original assumptions");
        var contract = TaskContractFactory.from(request, normalized);
        var validation = new PlanValidator().validate(contract, normalized, request.allowedVerifiers());
        require(validation.valid(), "normalized plan passes contract validation: " + validation.errors());
        require(original.subgoals().size() == 2, "input plan is never mutated");
    }

    private static void preservesDifferentTopics() {
        for (String topic : List.of("黑洞照片", "城市步行路线", "古琴保养")) {
            PlanningRequest request = request("搜索" + topic + "，打开正文并给出标题", BROWSER);
            TaskPlan normalized = BrowserTaskPlan.normalize(request, plan(BROWSER, BROWSER));
            require(normalized.task().goal().equals(request.goal())
                            && normalized.subgoals().get(0).intent().contains(request.goal()),
                    "normalization never substitutes a fixed search topic");
        }
    }

    private static void leavesOtherScopesUnchanged() {
        TaskPlan browser = plan(BROWSER, BROWSER);
        for (String goal : List.of("搜索天气", "打开书签并告诉我标题", "搜索资料并打开正文")) {
            require(BrowserTaskPlan.normalize(request(goal, BROWSER), browser) == browser,
                    "outside the search/open/title scope the exact original plan is returned");
        }
        TaskPlan other = plan(OTHER, OTHER);
        require(BrowserTaskPlan.normalize(request("搜索资料，打开正文并告诉我标题", OTHER), other) == other,
                "other apps are unchanged");
        TaskPlan mixed = plan(BROWSER, OTHER);
        require(BrowserTaskPlan.normalize(request("搜索资料，打开正文并告诉我标题", BROWSER), mixed) == mixed,
                "mixed-app plans are unchanged");
    }

    private static void leavesAdditionalRequirementsUnchanged() {
        TaskPlan original = plan(BROWSER, BROWSER);
        for (String extra : List.of("，最后收藏文章", "，并下载正文", "，然后分享给朋友",
                "，复制标题到剪贴板", "，截图保存", "，把标题翻译成英文")) {
            String goal = "搜索古琴保养，打开正文并告诉我标题" + extra;
            require(BrowserTaskPlan.normalize(request(goal, BROWSER), original) == original,
                    "additional completion requirements must retain the original plan: " + extra);
        }
        for (String query : List.of("资料并收藏第一条结果", "资料然后下载第一篇", "资料收藏第一条结果",
                "资料同时截图保存", "资料并把标题翻译成英文")) {
            require(BrowserTaskPlan.normalize(request("搜索" + query + "，打开正文并告诉我标题", BROWSER),
                    original) == original, "commands inside the search clause retain the original plan");
        }
        String demo = "在夸克搜索西红柿炒鸡蛋的做法，打开一篇相关内容，停留在正文并告诉我标题。不要登录、发送或购买。";
        require(BrowserTaskPlan.normalize(request(demo, BROWSER), original) != original,
                "the read-only demo grammar remains eligible without a fixed topic");
    }

    private static PlanningRequest request(String goal, String primary) {
        return new PlanningRequest("user-task", goal,
                List.of("不要登录、付款或发送消息", " 保留这条约束的原文及空格 "),
                Set.of(BROWSER, OTHER), List.of(new InstalledApp(BROWSER, "Browser"),
                        new InstalledApp(OTHER, "Reader")), primary, Set.of("payment", "send", "delete"),
                Set.of("visual"), AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE));
    }

    private static TaskPlan plan(String first, String second) {
        TaskSpec task = new TaskSpec("model-task", "model shortened the request", List.of("model constraint"),
                List.of(new SuccessCriterion("search-page", "搜索框和结果列表可见", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT), Set.of(BROWSER, OTHER), Set.of(),
                new Budget(12, Duration.ofMinutes(2), 2, 4));
        Subgoal search = new Subgoal("s1", "search", Set.of(), List.of(),
                List.of(new Condition("search-page", "搜索框和结果列表可见", true)),
                new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, true, first, "visual");
        Subgoal open = new Subgoal("s2", "open", Set.of("s1"), List.of(), List.of(),
                new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, true, second, "visual");
        return new TaskPlan(task, List.of(search, open), List.of("原计划说明"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
