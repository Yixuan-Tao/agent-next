package agent.next.app;

import agent.next.brain.action.VisualTapResolution;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.Json;
import agent.next.brain.model.ModelConfig;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.TargetHandle;
import java.time.Instant;

/** Exercises the production locator with screenshot-grounded model replies. */
public final class ReactiveVisualTapLocatorTest {
    private ReactiveVisualTapLocatorTest() { }

    public static void main(String[] args) throws Exception {
        visualBusinessWriteUsesTheSameEffectTracking();
        TargetHandle target = new TargetHandle("target",1,"task","com.example.shop");
        Frame frame = new Frame(1080,1920,new byte[] {1},Instant.now(),1);
        VisualTapResolution search = locator("搜索",0.4,0.1,0.95).locate(target,
                "首页顶部的搜索输入框",frame);
        check(search.point().x() == 432 && search.point().y() == 192,
                "a descriptive request locates a control whose visible label is shorter");
        VisualTapResolution placeholder = locator("小米手环10",0.3,0.15,0.95).locate(target,
                "点击搜索输入框内的默认推荐词区域",frame);
        check(placeholder.label().equals("小米手环10"),
                "input fields can be located using their visible placeholder instead of matching the request text");
        rejected(() -> locator("搜索",0.4,0.1,0.5).locate(target,"搜索输入框",frame));
        rejected(() -> locator("提交订单",0.4,0.1,0.95).locate(target,"底部按钮",frame));
        rejected(() -> locator("",0.4,0.1,0).locate(target,"搜索输入框",frame));
        rejected(() -> locator("搜索",1.2,0.1,0.95).locate(target,"搜索输入框",frame));
        System.out.println("ReactiveVisualTapLocatorTest: PASS (descriptions, placeholders, confidence, labels, bounds)");
    }

    private static void visualBusinessWriteUsesTheSameEffectTracking() {
        String args = "{\"target\":\"规格弹层底部确认加购\",\"effect\":{\"businessWrite\":true,"
                + "\"key\":\"add:shirt:black:xl:1\",\"postcondition\":\"黑色XL商品在购物车总数1\","
                + "\"absenceCondition\":\"完整购物车中无该商品\"},\"expectedChange\":\"显示加购成功\",\"progress\":\"正在加购\"}";
        ChatCompletionClient client = new ChatCompletionClient(new ModelConfig("https://example.test","test","test-key"),
                (url,auth,payload) -> "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{"
                        + "\"function\":{\"name\":\"visual_tap\",\"arguments\":" + Json.quote(args) + "}}]}}]}");
        agent.next.brain.TaskSpec task = new agent.next.brain.TaskSpec("task","将指定商品加入购物车",java.util.List.of(),
                java.util.List.of(new agent.next.brain.SuccessCriterion("cart","购物车商品总数1",true)),
                agent.next.brain.AutonomyGrant.of(agent.next.brain.AutonomyLevel.A2_REVERSIBLE),
                java.util.Set.of("com.example.shop"),java.util.Set.of(),agent.next.brain.Budget.defaults());
        agent.next.device.Observation observed = new agent.next.device.Observation(agent.next.device.ObservationStatus.READY,
                new agent.next.device.ObservationRef("observation",1),"com.example.shop","Product",1,10,true,
                Instant.now(),0,false,new agent.next.device.NodeBounds(0,0,1080,1920),java.util.List.of());
        agent.next.brain.reactive.ReactiveDecision decision = new agent.next.brain.model.DeepSeekReactiveModel(client)
                .next(new agent.next.brain.reactive.ReactiveRequest(task,observed,null,java.util.List.of(),0));
        check(decision instanceof agent.next.brain.reactive.ReactiveDecision.Act act
                && act.proposal() instanceof agent.next.brain.action.ActionProposal.VisualTap
                && act.effect() != null && act.effect().key().equals("add:shirt:black:xl:1"),
                "a visible add button missing from the accessibility tree must retain its business effect record");
    }

    private static ReactiveVisualTapLocator locator(String label,double x,double y,double confidence) {
        String answer = "{\"label\":" + Json.quote(label) + ",\"x\":" + x
                + ",\"y\":" + y + ",\"confidence\":" + confidence + "}";
        return new ReactiveVisualTapLocator(new ChatCompletionClient(
                new ModelConfig("https://example.test","test","test-key"),
                (url,auth,payload) -> "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                        + Json.quote(answer) + "}}]}"));
    }

    @FunctionalInterface private interface Call { void run() throws DeviceException; }

    private static void rejected(Call call) throws DeviceException {
        try { call.run(); throw new AssertionError("expected rejection of an unconfirmed visual target"); }
        catch (DeviceException failure) { check(failure.code() == DeviceException.Code.REJECTED,"rejection code"); }
    }

    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
}
