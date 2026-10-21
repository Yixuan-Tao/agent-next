package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.ActionKind;
import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.DefaultRiskGate;
import agent.next.brain.HandoffRequest;
import agent.next.brain.HandoffSession;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.RiskClass;
import agent.next.brain.RiskDecision;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.NodeSelector;
import agent.next.brain.skill.SkillSelector;
import agent.next.device.TargetHandle;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.NodeFlag;
import agent.next.device.NodeBounds;
import agent.next.device.Observation;
import agent.next.device.ObservationController;
import agent.next.device.UiNode;
import java.util.function.UnaryOperator;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.EnumSet;

import static agent.next.brain.TestSupport.check;

/** Host tests for the M1 action proposal/grounding/executor boundary. */
public final class ActionExecutorTest {

    private ActionExecutorTest() {
    }

    public static void run() {
        tapAndSetText();
        webTextTapUsesFreshObservedNode();
        webTextRejectsUnsafeTargets();
        webTextBindsAncestorAndActualRisk();
        paymentConfirmation();
        checkoutNavigationIsNotAPaymentCommitment();
        understatedPaymentIsDenied();
        observedFieldsRaiseRisk();
        changedSemanticsInvalidateConfirmation();
        changedTaskAndTargetInvalidateConfirmation();
        taskScopeIsEnforced();
        confirmationExpiryAfterRegrounding();
        proposalPayloadBindsActualArguments();
        confirmationMismatch();
        noHumanDoesNotTap();
        stopBeforeAction();
        stopAfterConfirmation();
        System.out.println("ActionExecutorTest: PASS (grounding, risk, confirmation, cancel)");
    }

    private static void tapAndSetText() {
        LoopFakeDevice api = new LoopFakeDevice();
        ActionExecutor executor = executor(api, new Human(true));
        ActionExecutor.Execution tap = execute(executor, new ActionProposal.Tap(
                "tap.submit", "submit search", NodeSelector.textEquals("Search"),
                RiskClass.LOW));
        check(tap.receipt().accepted(), "tap accepted");
        check(api.taps() == 1, "tap executed once");

        LoopFakeDevice textApi = new LoopFakeDevice();
        ActionExecutor textExecutor = executor(textApi, new Human(true));
        ActionExecutor.Execution text = execute(textExecutor, new ActionProposal.SetText(
                "type.query", "type query", NodeSelector.editable(), "短袖T恤",
                RiskClass.REVERSIBLE_SIDE_EFFECT));
        check(text.receipt().verified(), "setText verified");
        check("短袖T恤".equals(text.receipt().actualText().orElse("")), "setText value");
    }

    private static void paymentConfirmation() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        ActionExecutor executor = executor(api, human);
        ActionExecutor.Execution execution = execute(executor, new ActionProposal.Tap(
                "tap.pay", "立即支付", NodeSelector.textEquals("立即支付"), RiskClass.LOW));
        check(execution.confirmed(), "payment required confirmation");
        check(human.request != null, "confirmation request delivered");
        check(human.request.payloadHash().equals(execution.action().descriptor().payloadHash()),
                "confirmation bound to payload hash");
        check(execution.action().descriptor().kind() == ActionKind.COMMITMENT,
                "payment raised to commitment");
        check(execution.action().descriptor().risk() == RiskClass.COMMITMENT,
                "payment raised to commitment risk");
        check(api.taps() == 1, "payment tapped after confirmation");
    }

    private static void checkoutNavigationIsNotAPaymentCommitment() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec task = task();
        check(gate.evaluate(descriptor("tap.checkout", "去结算"), task)
                        instanceof RiskDecision.Allow,
                "entering checkout is reversible navigation");
        check(gate.evaluate(descriptor("tap.buy", "立即购买"), task)
                        instanceof RiskDecision.Allow,
                "buy-now navigation is reversible");
        check(gate.evaluate(descriptor("tap.submit-order", "提交订单"), task)
                        instanceof RiskDecision.Confirm,
                "submitting an order still requires confirmation");
        check(gate.evaluate(descriptor("tap.pay", "立即支付"), task)
                        instanceof RiskDecision.Confirm,
                "payment still requires confirmation");
    }

    private static ActionDescriptor descriptor(String id, String text) {
        return new ActionDescriptor(id, text, ActionKind.REVERSIBLE_WRITE,
                RiskClass.REVERSIBLE_SIDE_EFFECT, "com.example.shop", "payload", Set.of());
    }

    private static ActionProposal.Tap webTap(String text) {
        return new ActionProposal.Tap("web-open", "open observed text",
                new SkillSelector.WebTextContains(text), RiskClass.LOW);
    }

    private static void webTextTapUsesFreshObservedNode() {
        TransformDevice api = new TransformDevice(source -> {
            Observation observation = webObservation(source, "valid");
            check(ActionGrounder.webTextEligible(observation, observation.nodes().get(2)),
                    "trusted affordance agrees that observed Web text is eligible");
            return observation;
        });
        ActionExecutor.Execution execution = execute(new ActionExecutor(api, new DefaultRiskGate(),
                new Human(true), LoopControl.none()), webTap("Article title"));
        check(execution.receipt().accepted() && api.delegate.taps() == 1,
                "explicit Web text can tap a unique non-clickable title");
        check(((GroundedAction.Node) execution.action().target()).ref().startsWith(
                        "loop-observation-2"), "tap uses the new observation node");
        check(((GroundedAction.Node) execution.action().target()).bounds().equals(
                        new NodeBounds(20, 200, 700, 260)), "tap is grounded to actual text bounds");
    }

    private static void webTextRejectsUnsafeTargets() {
        for (String variant : List.of("no-web", "no-ancestor", "hidden", "disabled", "password",
                "editable", "truncated-text", "truncated-observation", "wrong-window",
                "wrong-ancestor-window", "outside-window", "outside-web", "empty-bounds",
                "duplicate", "unstable")) {
            TransformDevice api = new TransformDevice(source -> {
                Observation observation = webObservation(source, variant);
                if (!variant.equals("duplicate")) {
                    check(!ActionGrounder.webTextEligible(observation, observation.nodes().get(2)),
                            "trusted affordance rejects the same unsafe target: " + variant);
                }
                return observation;
            });
            try {
                new ActionExecutor(api, new DefaultRiskGate(), new Human(true), LoopControl.none())
                        .execute(task(), target(), webTap("Article title"));
                throw new AssertionError("unsafe Web text must be rejected: " + variant);
            } catch (DeviceException expected) {
                check(api.delegate.taps() == 0, "unsafe target performs no input: " + variant);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }
        TransformDevice ordinary = new TransformDevice(source -> webObservation(source, "valid"));
        try {
            new ActionExecutor(ordinary, new DefaultRiskGate(), new Human(true), LoopControl.none())
                    .execute(task(), target(), new ActionProposal.Tap("ordinary", "ordinary text",
                            new SkillSelector.TextContains("Article title"), RiskClass.LOW));
            throw new AssertionError("ordinary TextContains must still require CLICKABLE");
        } catch (DeviceException expected) {
            check(ordinary.delegate.taps() == 0, "ordinary Tap is not loosened");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void webTextBindsAncestorAndActualRisk() {
        TransformDevice changed = new TransformDevice(source -> webObservation(source,
                source.reference().orElseThrow().sequence() == 1 ? "valid" : "replaced-web"));
        try {
            new ActionExecutor(changed, new DefaultRiskGate(), new Human(true), LoopControl.none())
                    .execute(task(), target(), webTap("Article title"));
            throw new AssertionError("changing WebView context must invalidate the action");
        } catch (DeviceException expected) {
            check(expected.code() == DeviceException.Code.STALE && changed.delegate.taps() == 0,
                    "WebView identity is bound to the action fingerprint");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
        TransformDevice payment = new TransformDevice(source -> webObservation(source, "payment"));
        Human human = new Human(true);
        ActionExecutor.Execution execution = execute(new ActionExecutor(payment, new DefaultRiskGate(),
                human, LoopControl.none()), webTap("立即支付"));
        check(execution.confirmed() && execution.action().descriptor().risk() == RiskClass.COMMITMENT,
                "Web text preserves risk escalation from actual observed text");
    }

    private static Observation webObservation(Observation source, String variant) {
        Set<NodeFlag> flags = EnumSet.of(NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED);
        if (variant.equals("hidden")) flags.remove(NodeFlag.VISIBLE_TO_USER);
        if (variant.equals("disabled")) flags.remove(NodeFlag.ENABLED);
        if (variant.equals("password")) flags.add(NodeFlag.PASSWORD);
        if (variant.equals("editable")) flags.add(NodeFlag.EDITABLE);
        NodeBounds bounds = new NodeBounds(20, 200, 700, 260);
        if (variant.equals("outside-window")) bounds = new NodeBounds(20, 200, 721, 260);
        if (variant.equals("empty-bounds")) bounds = NodeBounds.EMPTY;
        String ref = source.reference().orElseThrow().observationId();
        UiNode web = new UiNode(ref + ":web", 1, 0,
                variant.equals("wrong-ancestor-window") ? 101 : 100,
                variant.equals("replaced-web") ? "another-web" : "web",
                variant.equals("no-web") ? "android.widget.FrameLayout" : "android.webkit.WebView",
                "", "", variant.equals("outside-web") ? new NodeBounds(0, 0, 10, 10)
                        : source.windowBounds().orElseThrow(),
                Set.of(NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED), false);
        UiNode text = new UiNode(ref + ":text", 2, variant.equals("no-ancestor") ? 0 : 1,
                variant.equals("wrong-window") ? 101 : 100, "article-title", "android.widget.TextView",
                variant.equals("payment") ? "立即支付" : "Article title", "", bounds, flags,
                variant.equals("truncated-text"));
        List<UiNode> nodes = new ArrayList<>(List.of(source.nodes().get(0), web, text));
        if (variant.equals("duplicate")) {
            nodes.add(new UiNode(ref + ":duplicate", 3, 1, 100, "another-title",
                    text.className(), text.text(), "", bounds, flags, false));
        }
        return new Observation(source.status(), source.reference().orElseThrow(), source.packageName(),
                source.activityName(), source.displayId(), source.windowId(), !variant.equals("unstable"),
                source.capturedAt(), nodes.size(), variant.equals("truncated-observation"),
                source.windowBounds().orElseThrow(), nodes);
    }

    private static void confirmationMismatch() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(false);
        ActionExecutor executor = executor(api, human);
        try {
            execute(executor, new ActionProposal.Tap("tap.pay", "立即支付",
                    NodeSelector.textEquals("立即支付"), RiskClass.LOW));
            throw new AssertionError("mismatched confirmation must fail");
        } catch (agent.next.brain.ConfirmationRejectedException expected) {
            check(api.taps() == 0, "mismatched confirmation does not tap");
        }
    }

    private static void understatedPaymentIsDenied() {
        for (String denial : List.of("payment", "place_order")) {
            LoopFakeDevice api = new LoopFakeDevice(true);
            Human human = new Human(true);
            TaskSpec base = task();
            TaskSpec restricted = new TaskSpec(base.id(), base.goal(), base.constraints(),
                    base.successCriteria(), base.autonomy(), base.allowedApps(),
                    Set.of(denial), base.budget());
            try {
                executor(api, human).execute(restricted, target(), new ActionProposal.Tap(
                        "step-1", "continue", NodeSelector.textEquals("立即支付"), RiskClass.LOW));
                throw new AssertionError("actual payment target must honor " + denial);
            } catch (agent.next.device.DeviceException expected) {
                check(expected.code() == agent.next.device.DeviceException.Code.REJECTED,
                        "payment policy denial is rejected");
                check(api.taps() == 0, "denied payment does not tap");
                check(human.request == null, "denied payment does not request confirmation");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }
    }

    private static ActionProposal.Tap neutralTap() {
        return new ActionProposal.Tap("step-1", "continue",
                new NodeSelector.Selector(node -> node.hasFlag(NodeFlag.CLICKABLE), "button"),
                RiskClass.LOW);
    }

    private static void observedFieldsRaiseRisk() {
        for (List<String> values : List.of(
                List.of("立即支付", "", "button"),
                List.of("Continue", "确认支付", "button"),
                List.of("Continue", "", "com.example:id/place_order"),
                List.of("Continue", "", "com.example:id/placeOrder"))) {
            TransformDevice api = new TransformDevice(source -> withNode(source, values));
            Human human = new Human(true);
            try {
                ActionExecutor.Execution execution = new ActionExecutor(api, new DefaultRiskGate(),
                        human, LoopControl.none()).execute(task(), target(), neutralTap());
                check(execution.confirmed(), "observed target fields raise understated payment risk");
                check(api.delegate.taps() == 1, "unchanged semantics survive new observation refs");
                check(human.request.summary().contains(values.get(0))
                                && human.request.summary().contains(values.get(1))
                                && human.request.summary().contains(values.get(2)),
                        "confirmation exposes actual observed semantics");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }
    }

    private static void changedSemanticsInvalidateConfirmation() {
        List<String> original = List.of("立即支付 ¥10", "recipient A", "pay");
        for (List<String> changed : List.of(
                List.of("立即支付 ¥20", "recipient A", "pay"),
                List.of("立即支付 ¥10", "recipient B", "pay"),
                List.of("立即支付 ¥10", "recipient A", "another_pay"))) {
            TransformDevice api = new TransformDevice(source -> withNode(source,
                    source.reference().orElseThrow().sequence() == 1 ? original : changed));
            Human human = new Human(true);
            assertStale(api, human);
            check(human.request != null, "initial payment was confirmed");
        }
        TransformDevice escalating = new TransformDevice(source -> withNode(source,
                source.reference().orElseThrow().sequence() == 1
                        ? List.of("Continue", "", "button") : original));
        Human human = new Human(true);
        assertStale(escalating, human);
        check(human.request == null, "risk escalation between observations cannot execute");
    }

    private static void assertStale(TransformDevice api, Human human) {
        try {
            new ActionExecutor(api, new DefaultRiskGate(), human, LoopControl.none())
                    .execute(task(), target(), neutralTap());
            throw new AssertionError("changed observed semantics must invalidate grounding");
        } catch (DeviceException expected) {
            check(expected.code() == DeviceException.Code.STALE, "changed target is stale");
            check(api.delegate.taps() == 0, "stale confirmation performs no tap");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void changedTaskAndTargetInvalidateConfirmation() {
        try {
            LoopFakeDevice api = new LoopFakeDevice(true);
            Observation observation = api.observations().observe(target(),
                    agent.next.device.ObservationOptions.defaults());
            ActionGrounder grounder = new ActionGrounder();
            String initial = grounder.ground(task(), target(), observation, neutralTap())
                    .descriptor().payloadHash();
            TaskSpec base = task();
            TaskSpec changedTask = new TaskSpec(base.id(), "new task revision",
                    base.constraints(), base.successCriteria(), base.autonomy(),
                    base.allowedApps(), base.deniedActions(), base.budget());
            List<String> changedHashes = List.of(
                    grounder.ground(changedTask, target(), observation, neutralTap())
                            .descriptor().payloadHash(),
                    grounder.ground(base, new TargetHandle("target-2", 1, base.id(),
                            target().packageName()), observation, neutralTap()).descriptor().payloadHash(),
                    grounder.ground(base, new TargetHandle(target().token(), 2, base.id(),
                            target().packageName()), observation, neutralTap()).descriptor().payloadHash());
            for (String changedHash : changedHashes) {
                Instant now = Instant.now();
                ConfirmationToken old = new ConfirmationToken("step-1", initial, now,
                        now.plusSeconds(60));
                ConfirmationRequest revised = new ConfirmationRequest("step-1", "continue",
                        "changed context", false, changedHash);
                check(!old.matches(revised), "task/target revision invalidates old approval");
            }
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void taskScopeIsEnforced() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        try {
            executor(api, human).execute(task(), new TargetHandle("other", 1,
                    "other-task", target().packageName()), neutralTap());
            throw new AssertionError("cross-task target must be rejected");
        } catch (DeviceException expected) {
            check(expected.code() == DeviceException.Code.REJECTED, "task mismatch is rejected");
            check(api.taps() == 0 && human.request == null, "out-of-scope target cannot execute");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void confirmationExpiryAfterRegrounding() {
        Human human = new Human(true);
        human.validityMillis = 200;
        TransformDevice api = new TransformDevice(source -> {
            if (source.reference().orElseThrow().sequence() > 1) {
                // Wait for the exact issued token to expire, after its initial validation.
                while (Instant.now().isBefore(human.token.expiresAt())) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
            }
            return source;
        });
        try {
            new ActionExecutor(api, new DefaultRiskGate(), human, LoopControl.none())
                    .execute(task(), target(), neutralTap());
            throw new AssertionError("expired approval must not reach input");
        } catch (agent.next.brain.ConfirmationRejectedException expected) {
            check(api.delegate.observationCount() == 2, "approval expired after re-observation");
            check(api.delegate.taps() == 0, "approval must still be valid at input time");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void proposalPayloadBindsActualArguments() {
        check(!new ActionProposal.SetText("type", "type", NodeSelector.editable(), "Aa", RiskClass.LOW)
                        .payloadHash().equals(new ActionProposal.SetText("type", "type",
                                NodeSelector.editable(), "BB", RiskClass.LOW).payloadHash()),
                "Java hash collisions cannot reuse text approval");
        check(!new ActionProposal.Swipe("move", "move", ActionProposal.Direction.UP, 100,
                        RiskClass.LOW).payloadHash().equals(new ActionProposal.Swipe("move", "move",
                                ActionProposal.Direction.DOWN, 100, RiskClass.LOW).payloadHash()),
                "swipe direction is bound to payload");
    }

    private static Observation withNode(Observation source, List<String> values) {
        UiNode node = source.nodes().get(1);
        UiNode changed = new UiNode(node.ref(), node.index(), node.parentIndex(), node.windowId(),
                values.get(2), node.className(), values.get(0), values.get(1), node.bounds(),
                node.flags(), node.textTruncated());
        return new Observation(source.status(), source.reference().orElseThrow(), source.packageName(),
                source.activityName(), source.displayId(), source.windowId(), source.stable(),
                source.capturedAt(), source.lookupCount(), source.truncated(),
                source.windowBounds().orElseThrow(), List.of(source.nodes().get(0), changed));
    }

    private static final class TransformDevice implements DeviceApi {
        private final LoopFakeDevice delegate = new LoopFakeDevice(true);
        private final UnaryOperator<Observation> transform;

        private TransformDevice(UnaryOperator<Observation> transform) {
            this.transform = transform;
        }

        @Override public void connect() throws DeviceException { delegate.connect(); }
        @Override public agent.next.device.DeviceCapabilities capabilities() throws DeviceException {
            return delegate.capabilities();
        }
        @Override public agent.next.device.DisplayController displays() { return delegate.displays(); }
        @Override public agent.next.device.TargetController targets() { return delegate.targets(); }
        @Override public agent.next.device.InputController input() { return delegate.input(); }
        @Override public agent.next.device.TaskController tasks() { return delegate.tasks(); }
        @Override public void close() { delegate.close(); }
        @Override public ObservationController observations() {
            return new ObservationController() {
                @Override public Observation observe(TargetHandle target,
                        agent.next.device.ObservationOptions options) throws DeviceException {
                    return transform.apply(delegate.observations().observe(target, options));
                }
                @Override public agent.next.device.Frame captureFrame(TargetHandle target)
                        throws DeviceException {
                    return delegate.observations().captureFrame(target);
                }
            };
        }
    }

    private static void noHumanDoesNotTap() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        human.unavailable = true;
        ActionExecutor executor = executor(api, human);
        try {
            execute(executor, new ActionProposal.Tap("tap.pay", "立即支付",
                    NodeSelector.textEquals("立即支付"), RiskClass.LOW));
            throw new AssertionError("unavailable human must fail");
        } catch (RuntimeException expected) {
            check(expected.getCause() instanceof HumanUnavailableException,
                    "human unavailable propagates");
            check(api.taps() == 0, "unavailable human does not tap");
        }
    }

    private static void stopAfterConfirmation() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        int[] checks = {0};
        LoopControl control = () -> {
            if (++checks[0] >= 2) {
                throw new agent.next.brain.loop.LoopStoppedException("stopped after confirm");
            }
        };
        ActionExecutor executor = new ActionExecutor(api, new DefaultRiskGate(), human, control);
        try {
            execute(executor, new ActionProposal.Tap("tap.pay", "立即支付",
                    NodeSelector.textEquals("立即支付"), RiskClass.LOW));
            throw new AssertionError("stop after confirmation must fail");
        } catch (RuntimeException expected) {
            check(human.request != null, "confirmation was requested");
            check(api.taps() == 0, "stop after confirmation performs no tap");
        }
    }

    private static void stopBeforeAction() {
        LoopFakeDevice api = new LoopFakeDevice();
        ActionExecutor executor = new ActionExecutor(api, new DefaultRiskGate(), new Human(true),
                () -> {
                    throw new agent.next.brain.loop.LoopStoppedException("stopped");
                });
        try {
            execute(executor, new ActionProposal.Tap("tap.submit", "submit",
                    NodeSelector.textEquals("Search"), RiskClass.LOW));
            throw new AssertionError("stop must fail before action");
        } catch (RuntimeException expected) {
            check(api.taps() == 0, "stop before action performs no tap");
        }
    }

    private static ActionExecutor.Execution execute(ActionExecutor executor,
            ActionProposal proposal) {
        try {
            return executor.execute(task(), target(), proposal);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static ActionExecutor executor(LoopFakeDevice api, Human human) {
        return new ActionExecutor(api, new DefaultRiskGate(), human, LoopControl.none());
    }

    private static TaskSpec task() {
        return new TaskSpec("action-task", "test action", List.of(),
                List.of(new SuccessCriterion("c1", "action done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
    }

    private static TargetHandle target() {
        return new TargetHandle("target-1", 1, "action-task", "com.example.shop");
    }

    private static final class Human implements HumanGateway {
        private final boolean approve;
        private boolean unavailable;
        private ConfirmationRequest request;
        private ConfirmationToken token;
        private long validityMillis = 60_000;

        private Human(boolean approve) {
            this.approve = approve;
        }

        @Override
        public ConfirmationToken confirm(ConfirmationRequest request)
                throws HumanUnavailableException {
            if (unavailable) {
                throw new HumanUnavailableException("no human");
            }
            this.request = request;
            Instant now = Instant.now();
            String payload = approve ? request.payloadHash() : "wrong-payload";
            token = new ConfirmationToken(request.actionId(), payload, now,
                    now.plusMillis(validityMillis));
            return token;
        }

        @Override
        public HandoffSession handoff(HandoffRequest request)
                throws HumanUnavailableException {
            throw new HumanUnavailableException("no human");
        }

        @Override
        public void endHandoff(HandoffSession session) {
        }

        @Override
        public void pause(String reason) {
        }

        @Override
        public void resume() {
        }

        @Override
        public void stop(String reason) {
        }
    }
}
