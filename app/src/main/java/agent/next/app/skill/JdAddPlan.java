package agent.next.app.skill;

import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.plan.TaskPlan;

import java.util.List;
import java.util.Set;

/** Deterministic JD add plan to avoid planner-shaped subgoal drift. */
public final class JdAddPlan {

    private static final String PKG = "com.jingdong.app.mall";

    private JdAddPlan() {
    }

    public static TaskPlan replace(TaskPlan planned) {
        if (planned == null || !planned.task().goal().contains("加入购物车")
                || !planned.task().goal().contains("京东")) {
            return null;
        }
        Subgoal open = subgoal("s1", "打开京东并进入首页", Set.of(),
                "京东首页可见", "jd.home", RiskClass.LOW);
        Subgoal search = subgoal("s2", "在搜索框输入关键词并提交搜索", Set.of("s1"),
                "出现搜索结果", "", RiskClass.LOW);
        Subgoal detail = subgoal("s3", "打开搜索结果中的第一款商品详情", Set.of("s2"),
                "商品详情页可见", "jd.detail", RiskClass.LOW);
        Subgoal add = subgoal("s4", "将当前商品加入购物车", Set.of("s3"),
                "出现已加入购物车/数量变化", "", RiskClass.REVERSIBLE_SIDE_EFFECT);
        return new TaskPlan(planned.task(), List.of(open, search, detail, add),
                List.of("JD add path is deterministic"));
    }

    private static Subgoal subgoal(String id, String intent, Set<String> deps,
            String post, String verifier, RiskClass risk) {
        return new Subgoal(id, intent, deps,
                List.of(new Condition(id + ".pre", "ready", true)),
                List.of(new Condition(id + ".post", post, true)),
                new Fallback(Fallback.Strategy.RETRY, "retry once"),
                risk, true, PKG, verifier);
    }
}
