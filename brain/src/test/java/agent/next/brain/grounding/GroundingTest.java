package agent.next.brain.grounding;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Host tests for visual candidate safety filtering. */
public final class GroundingTest {

    private GroundingTest() {
    }

    public static void run() {
        List<VisualCandidate> candidates = List.of(
                new VisualCandidate("显示模式大图", 0.93, 0.08, 0.91),
                new VisualCandidate("搜索提交按钮", 0.90, 0.09, 0.84),
                new VisualCandidate("拍照购", 0.83, 0.08, 0.88));
        Optional<VisualCandidate> chosen = VisualCandidateFilter.select(candidates,
                Set.of("搜索"), Set.of("大图", "拍照", "显示模式"), 0.5);
        check(chosen.isPresent(), "allowed candidate found");
        check(chosen.orElseThrow().label().equals("搜索提交按钮"), "forbidden labels rejected");

        check(VisualCandidateFilter.select(candidates, Set.of("搜索"),
                Set.of("搜索"), 0.5).isEmpty(), "forbidden wins");
        check(VisualCandidateFilter.select(candidates, Set.of("搜索"),
                Set.of(), 0.95).isEmpty(), "confidence threshold");
        check(VisualCandidateFilter.select(List.of(), Set.of(), Set.of(), 0.5).isEmpty(),
                "empty candidates");
        System.out.println("GroundingTest: PASS (candidate allow/forbid/confidence)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
