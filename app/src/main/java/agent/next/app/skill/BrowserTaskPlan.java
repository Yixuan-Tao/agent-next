package agent.next.app.skill;

import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.TaskPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/** Before confirmation, browser search/open/title plans use terminal page evidence. */
public final class BrowserTaskPlan {
    private static final String BROWSER = "com.quark.browser";
    // Deliberately narrow: unrecognized wording keeps the planner's complete requirements.
    private static final Pattern READ_ONLY_GOAL = Pattern.compile(
            "(?:在夸克(?:中)?|用夸克)?搜索([^，,。；;\\r\\n]{1,120})[，,]"
                    + "打开(?:一篇)?(?:相关内容|相关正文|正文|文章)"
                    + "(?:[，,]停留在正文)?(?:并|[，,])(?:告诉我|给出)(?:真实|实际|页面)?标题[。.]?"
                    + "(?:不要(?:登录|发送|购买|付款|删除)(?:[、，,或和](?:登录|发送|购买|付款|删除))*[。.]?)?");
    private static final Pattern QUERY_COMMAND = Pattern.compile(
            "并|然后|随后|接着|最后|同时|接下来|再|收藏|下载|分享|复制|截图|翻译|发送|点赞|登录|支付|购买|下单|保存|删除|打开");

    private BrowserTaskPlan() {
    }

    public static boolean supportsGoal(String goal) {
        Matcher match = READ_ONLY_GOAL.matcher(Objects.requireNonNull(goal, "goal").trim());
        return match.matches() && !QUERY_COMMAND.matcher(match.group(1)).find();
    }

    public static TaskPlan normalize(PlanningRequest request, TaskPlan plan) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        String goal = request.goal();
        if (!supportsGoal(goal)
                || !BROWSER.equals(request.primaryPackage())
                || !request.allowedVerifiers().contains("visual")
                || !plan.subgoals().stream().allMatch(subgoal -> BROWSER.equals(subgoal.targetPackage()))) {
            return plan;
        }
        List<SuccessCriterion> criteria = List.of(
                new SuccessCriterion("browser-topic", "已打开的正文主题与用户原始目标一致。", true),
                new SuccessCriterion("browser-article", "当前已打开可阅读的网页或图文正文，不是首页、搜索建议、搜索结果列表或只有播放器的视频页。", true),
                new SuccessCriterion("browser-title", "实际内容页的真实标题与正文均可见且清晰可读。", true));
        TaskSpec task = new TaskSpec(request.taskId(), goal, request.constraints(), criteria,
                request.autonomy(), request.allowedApps(), request.deniedActions(), plan.task().budget());
        List<Condition> postconditions = criteria.stream().map(criterion ->
                new Condition(criterion.id(), criterion.description(), criterion.required()))
                .collect(Collectors.toList());
        Subgoal finalPage = new Subgoal("browser-result", goal, Set.of(), List.of(), postconditions,
                new Fallback(Fallback.Strategy.ASK_USER, "缺少最终正文或标题证据时请用户检查"),
                RiskClass.LOW, true, BROWSER, "visual");
        List<String> assumptions = new ArrayList<>(plan.assumptions());
        assumptions.add("按用户原始目标核验最终正文和真实标题；动作由模型逐步决定，不要求中间搜索页持续存在。");
        return new TaskPlan(task, List.of(finalPage), assumptions);
    }
}
