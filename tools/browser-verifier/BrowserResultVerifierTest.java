import agent.next.app.skill.BrowserResultVerifier;
import agent.next.brain.model.StageData;
import agent.next.brain.model.VisualVerification;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BrowserResultVerifierTest {
    private static final String ORIGINAL = "在夸克搜索西红柿炒鸡蛋，打开正文并告诉我标题";
    private static final String TITLE = "西红柿炒鸡蛋的家常做法";

    public static void main(String[] args) throws Exception {
        missingTitleCannotComplete();
        titleUsesTheExtractionFrameAndOriginalRequest();
        missingTitleClearsPreviousSuccess();
        failedCheckClearsPreviousSuccess();
        incompleteOrNegativeEvidenceCannotComplete();
        System.out.println("BrowserResultVerifierTest: PASS (required title, same frame, original request, stale evidence)");
    }

    private static void missingTitleCannotComplete() throws Exception {
        BrowserResultVerifier guard = new BrowserResultVerifier(ORIGINAL, request -> {
            throw new AssertionError("a missing/invalid title cannot be visually approved");
        });
        guard.check(frame(1), new StageData("已打开文章", Map.of()));
        require(!guard.satisfied() && guard.title().isEmpty(),
                "completion requires an extracted actual title");
        for (String title : List.of(" ", "a\nb", "a".repeat(121))) {
            guard.check(frame(1), new StageData("已打开文章", Map.of("title", title)));
            require(!guard.satisfied() && guard.title().isEmpty(), "invalid title cannot complete");
        }
    }

    private static void titleUsesTheExtractionFrameAndOriginalRequest() throws Exception {
        Frame extractedFrame = frame(42);
        BrowserResultVerifier guard = new BrowserResultVerifier(ORIGINAL, request -> {
            require(request.frame() == extractedFrame, "verification must use the exact extraction frame");
            require(request.task().contains(ORIGINAL), "verification retains the original user request");
            require(request.task().contains("逐字一致"), "title requires exact visible text");
            require(request.conditions().contains(TITLE), "extracted title is a verification condition");
            return new VisualVerification(true, request.conditions(), List.of());
        });
        require(!guard.satisfied() && guard.title().isEmpty(), "no initial evidence");
        guard.check(extractedFrame, data());
        require(guard.satisfied() && guard.title().equals(TITLE),
                "returned title is the one verified against the extraction frame");
        require(guard.evidence().contains("frame=42"), "evidence identifies the verified frame");
        guard.invalidate("new stage");
        require(!guard.satisfied() && guard.title().isEmpty(), "invalidation clears the old title");
    }

    private static void missingTitleClearsPreviousSuccess() throws Exception {
        int[] calls = {0};
        BrowserResultVerifier guard = new BrowserResultVerifier(ORIGINAL, request -> {
            calls[0]++;
            return new VisualVerification(true, request.conditions(), List.of());
        });
        guard.check(frame(1), data());
        require(guard.satisfied(), "fresh title can be verified");
        guard.check(frame(2), new StageData("没有可读标题", Map.of()));
        require(!guard.satisfied() && guard.title().isEmpty(), "missing new title clears old success");
        require(calls[0] == 1, "missing title does not call visual verifier");
    }

    private static void failedCheckClearsPreviousSuccess() throws Exception {
        int[] calls = {0};
        BrowserResultVerifier guard = new BrowserResultVerifier(ORIGINAL, request -> {
            if (++calls[0] == 1) return new VisualVerification(true, request.conditions(), List.of());
            throw new IOException("lost final evidence");
        });
        guard.check(frame(1), data());
        try {
            guard.check(frame(2), data());
            throw new AssertionError("verification failure must propagate");
        } catch (DeviceException expected) {
            require(!guard.satisfied() && guard.title().isEmpty(), "old title cannot survive a failed check");
        }
    }

    private static void incompleteOrNegativeEvidenceCannotComplete() throws Exception {
        for (boolean verified : List.of(false, true)) {
            BrowserResultVerifier guard = new BrowserResultVerifier(ORIGINAL, request ->
                    new VisualVerification(verified, List.of(TITLE), List.of()));
            guard.check(frame(1), data());
            require(!guard.satisfied() && guard.title().isEmpty(),
                    "title alone cannot prove an opened article or matching topic");
        }
        BrowserResultVerifier wrongTitle = new BrowserResultVerifier(ORIGINAL, request ->
                new VisualVerification(false, request.conditions().subList(0, 3), List.of(TITLE)));
        wrongTitle.check(frame(1), data());
        require(!wrongTitle.satisfied() && wrongTitle.title().isEmpty(),
                "a title that fails same-frame verification cannot be returned");
    }

    private static StageData data() {
        return new StageData("已打开文章", Map.of("title", TITLE));
    }

    private static Frame frame(long sequence) {
        return new Frame(720, 1280, new byte[] {1, 2, 3}, Instant.now(), sequence);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
