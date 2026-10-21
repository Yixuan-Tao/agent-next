package agent.next.brain.action;

import agent.next.device.DeviceException;
import agent.next.device.NodeBounds;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationRef;
import agent.next.device.ObservationStatus;
import agent.next.device.UiNode;
import agent.next.device.TargetHandle;
import agent.next.brain.TaskSpec;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.Budget;
import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.RiskClass;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static agent.next.brain.TestSupport.check;

/** Reproduces labels exposed as text on a non-clickable child of a product card. */
public final class ObservedNodeSelectorTest {
    private ObservedNodeSelectorTest() { }

    public static void run() {
        try {
            Observation before = page("old", "影视飓风 运动 Pro 速干T恤");
            ObservedNodeSelector selector = new ObservedNodeSelector(before, 1);
            List<UiNode> resolved = selector.resolve(page("fresh", "影视飓风 运动 Pro 速干T恤"),
                    NodeFlag.CLICKABLE);
            check(resolved.size() == 2 && resolved.get(1).index() == 0,
                    "ordinary text resolves to its actual clickable parent");
            check(resolved.get(0).ref().startsWith("fresh"), "uses fresh service references");
            TaskSpec task = new TaskSpec("task", "open actual product", List.of(),
                    List.of(new SuccessCriterion("product", "product page", true)),
                    AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE), Set.of("com.example.shop"), Set.of(), Budget.defaults());
            GroundedAction action = new ActionGrounder().ground(task,
                    new TargetHandle("target", 1, "task", "com.example.shop"), before,
                    new ActionProposal.Tap("tap", "open product", selector, RiskClass.LOW));
            check(((GroundedAction.Node) action.target()).bounds().equals(before.nodes().get(1).bounds()),
                    "click stays on observed label, not an unrelated area in the parent card");
            Observation shifted = shiftedPage("shifted", "影视飓风 运动 Pro 速干T恤");
            List<UiNode> rebound = selector.resolve(shifted,NodeFlag.CLICKABLE);
            check(rebound.get(0).index() == 2 && rebound.get(1).index() == 1,
                    "unrelated content inserted before the target must not invalidate the same control");
            GroundedAction fresh = new ActionGrounder().ground(task,
                    new TargetHandle("target",1,"task","com.example.shop"),shifted,action.proposal());
            check(fresh.descriptor().payloadHash().equals(action.descriptor().payloadHash()),
                    "renumbering the same observed target preserves its action binding");
            try {
                selector.resolve(page("changed", "相邻的另一件商品"), NodeFlag.CLICKABLE);
                throw new AssertionError("must reject a changed product at the same index");
            } catch (DeviceException expected) {
                check(expected.code() == DeviceException.Code.STALE, "changed label is stale");
            }
        } catch (DeviceException failure) {
            throw new AssertionError(failure);
        }
        System.out.println("ObservedNodeSelectorTest: PASS");
    }

    private static Observation page(String id, String label) {
        Set<NodeFlag> visible = Set.of(NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED);
        return new Observation(ObservationStatus.READY, new ObservationRef(id, 1),
                "com.example.shop", "Results", 1, 100, true, Instant.now(), 2, false,
                new NodeBounds(0, 0, 500, 900), List.of(
                        new UiNode(id + ":0", 0, -1, 100, "card", "Card", "", "",
                                new NodeBounds(10, 100, 490, 400),
                                Set.of(NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED, NodeFlag.CLICKABLE), false),
                        new UiNode(id + ":1", 1, 0, 100, "title", "TextView", label, "",
                                new NodeBounds(20, 120, 480, 180), visible, false)));
    }

    private static Observation shiftedPage(String id,String label) {
        Observation base = page(id,label);
        java.util.ArrayList<UiNode> nodes = new java.util.ArrayList<>();
        nodes.add(new UiNode(id + ":0",0,-1,100,"banner","TextView","新推荐","",
                new NodeBounds(10,0,490,40),Set.of(NodeFlag.VISIBLE_TO_USER,NodeFlag.ENABLED),false));
        for (UiNode node : base.nodes()) {
            int index = node.index() + 1;
            nodes.add(new UiNode(id + ":" + index,index,node.parentIndex() < 0 ? -1 : node.parentIndex() + 1,
                    node.windowId(),node.viewId(),node.className(),node.text(),node.contentDescription(),
                    node.bounds(),node.flags(),node.textTruncated()));
        }
        return new Observation(base.status(),base.reference().orElseThrow(),base.packageName(),base.activityName(),
                base.displayId(),base.windowId(),base.stable(),base.capturedAt(),3,false,
                base.windowBounds().orElseThrow(),nodes);
    }
}
