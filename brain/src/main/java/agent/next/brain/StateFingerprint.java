package agent.next.brain;

/**
 * The stable, comparable part of an observation. The digest is produced by
 * the perception layer from the UI tree/screenshot/OCR; the brain does not
 * interpret raw pixels here.
 */
public record StateFingerprint(String app, String activity, String contentDigest, boolean stable) {

    public StateFingerprint {
        app = Preconditions.requireText(app, "app");
        activity = Preconditions.requireText(activity, "activity");
        contentDigest = Preconditions.requireText(contentDigest, "contentDigest");
    }
}
