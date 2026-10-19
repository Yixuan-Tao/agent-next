package agent.next.brain.plan;

import agent.next.brain.Budget;
import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict JSON planner. The model sees installed/allowed app metadata and may
 * only return a TaskPlan or one clarification question; it receives no device
 * handles, coordinates, or action executor.
 */
public final class DeepSeekTaskPlanner implements TaskPlanner {

    private final ChatCompletionClient client;

    public DeepSeekTaskPlanner(ChatCompletionClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public PlanningResult plan(PlanningRequest request) throws IOException {
        String content = stripFences(client.complete(prompt(request), 2048));
        Map<String, Object> root = Json.object(content);
        if (root.containsKey("clarification")) {
            requireKeys(root, Set.of("clarification"));
            Object value = root.get("clarification");
            if (!(value instanceof String question) || question.trim().isEmpty()
                    || question.trim().length() > 300) {
                throw new IllegalArgumentException("invalid clarification");
            }
            return PlanningResult.clarify(question.trim());
        }
        requireKeys(root, Set.of("task", "subgoals"));
        Map<String, Object> task = map(root.get("task"), "task");
        requireKeys(task, Set.of("successCriteria"));
        List<String> criteria = strings(task.get("successCriteria"), "successCriteria", 1, 8);
        List<Object> rawSubgoals = list(root.get("subgoals"), "subgoals");
        if (rawSubgoals.isEmpty() || rawSubgoals.size() > 8) {
            throw new IllegalArgumentException("subgoals must be 1..8");
        }
        List<String> assumptions = root.containsKey("assumptions")
                ? strings(root.get("assumptions"), "assumptions", 0, 8) : List.of();

        Map<String, InstalledApp> installed = new LinkedHashMap<>();
        for (InstalledApp app : request.installedApps()) {
            installed.put(app.packageName(), app);
        }
        List<Subgoal> subgoals = new ArrayList<>();
        for (Object raw : rawSubgoals) {
            Map<String, Object> value = map(raw, "subgoal");
            requireKeys(value, Set.of("id", "intent", "dependsOn", "postconditions",
                    "targetPackage", "verifierId", "required"));
            String id = Json.string(value, "id").trim();
            if (!id.matches("[a-z][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("invalid subgoal id");
            }
            String intent = Json.string(value, "intent").trim();
            if (intent.isEmpty() || intent.length() > 2000) {
                throw new IllegalArgumentException("invalid subgoal intent");
            }
            String target = Json.string(value, "targetPackage").trim();
            if (!request.allowedApps().contains(target) || !installed.containsKey(target)) {
                throw new IllegalArgumentException("subgoal target is not installed/allowed");
            }
            String verifier = Json.string(value, "verifierId").trim();
            if (!request.allowedVerifiers().contains(verifier)) {
                throw new IllegalArgumentException("subgoal verifier is not allowed");
            }
            Set<String> dependsOn = new HashSet<>();
            for (String dependency : strings(value.get("dependsOn"), "dependsOn", 0, 8)) {
                dependsOn.add(dependency);
            }
            List<String> conditionTexts = strings(value.get("postconditions"),
                    "postconditions", 1, 3);
            List<Condition> postconditions = new ArrayList<>();
            for (int index = 0; index < conditionTexts.size(); index++) {
                postconditions.add(new Condition("c" + (index + 1), conditionTexts.get(index), true));
            }
            boolean required = value.get("required") instanceof Boolean flag ? flag : true;
            subgoals.add(new Subgoal(id, intent, dependsOn, List.of(), postconditions,
                    new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW,
                    required, target, verifier));
        }
        if (!subgoals.get(0).targetPackage().equals(request.primaryPackage())) {
            throw new IllegalArgumentException("first subgoal must use the primary package");
        }
        TaskSpec spec = new TaskSpec(
                request.taskId(), request.goal(), request.constraints(),
                criteria.stream().map(new SuccessCriterionFactory()).collect(java.util.stream.Collectors.toList()),
                request.autonomy(), request.allowedApps(), request.deniedActions(),
                Budget.defaults());
        return PlanningResult.plan(new TaskPlan(spec, subgoals, assumptions));
    }

    private static String prompt(PlanningRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是手机 App 任务规划器，不控制设备。只输出一个 JSON 对象，不要 markdown。\n")
                .append("只可使用以下已安装且允许的 App：\n");
        for (InstalledApp app : request.installedApps()) {
            prompt.append("- ").append(app.packageName()).append(" | ")
                    .append(app.label()).append('\n');
        }
        prompt.append("第一段 targetPackage 必须等于当前主 App：")
                .append(request.primaryPackage()).append("。\n")
                .append("可使用 verifierId：").append(request.allowedVerifiers()).append("。\n")
                .append("生成 1..8 个顺序/依赖 subgoal；dependsOn 只能引用本计划中的 id。\n")
                .append("每个 subgoal 必须给出 1..3 个 postconditions，描述该阶段完成时屏幕上应出现的可见证据；不要用最终汇报条件。\n")
                .append("successCriteria 1..8 条，必须是页面上可观察/可验证的短文本；每条 successCriteria 必须原样出现在至少一个 subgoal 的 postconditions 中。\n")
                .append("不要计划支付、下单、发送、删除、登录、验证码或权限授予；遇到时停在需要人确认的边界。\n")
                .append("严禁输出坐标、像素、x/y、固定点击序列或原始设备操作。\n")
                .append("如果目标有实质歧义，只输出 {\"clarification\":\"一个最小问题\"}，不要输出计划。\n")
                .append("否则只输出：\n")
                .append("{\"task\":{\"successCriteria\":[\"...\"]},")
                .append("\"subgoals\":[{\"id\":\"s1\",\"intent\":\"...\",\"dependsOn\":[],")
                .append("\"postconditions\":[\"页面证据\"],\"targetPackage\":\"...\",\"verifierId\":\"visual\",\"required\":true}],")
                .append("\"assumptions\":[\"...\"]}\n");
        if (request.goal().contains("加入购物车")) {
            prompt.append("目标包含加入购物车时，必须单独生成一个“加入购物车”子目标，")
                    .append("排在打开商品详情之后，不要合并进详情子目标。\n");
        }
        prompt.append("用户目标：").append(request.goal()).append('\n');
        if (!request.constraints().isEmpty()) {
            prompt.append("用户约束：").append(request.constraints()).append('\n');
        }
        return prompt.toString();
    }

    private static final class SuccessCriterionFactory
            implements java.util.function.Function<String, SuccessCriterion> {
        private int index;

        @Override
        public SuccessCriterion apply(String description) {
            index++;
            return new SuccessCriterion("criterion-" + index, description, true);
        }
    }

    private static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> list(Object value, String name) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return new ArrayList<>(raw);
    }

    private static List<String> strings(Object value, String name, int min, int max) {
        List<Object> raw = list(value, name);
        if (raw.size() < min || raw.size() > max) {
            throw new IllegalArgumentException(name + " has invalid length");
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(name + " must contain strings");
            }
            String text = ((String) item).trim();
            if (text.isEmpty() || text.length() > 200) {
                throw new IllegalArgumentException(name + " contains an invalid string");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static void requireKeys(Map<String, Object> value, Set<String> allowed) {
        for (String key : allowed) {
            if (!value.containsKey(key)) {
                throw new IllegalArgumentException("missing planner field " + key);
            }
        }
    }

    private static String stripFences(String content) {
        String result = content == null ? "" : content.trim();
        if (result.startsWith("```")) {
            int firstLine = result.indexOf('\n');
            if (firstLine >= 0) {
                result = result.substring(firstLine + 1).trim();
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3).trim();
            }
        }
        return result;
    }
}
