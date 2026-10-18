package agent.next.brain.model;

import java.io.IOException;

/** HTTP failure with status/retry-after so the client can apply a bounded backoff. */
public final class ModelHttpException extends IOException {

    private static final long serialVersionUID = 1L;
    private final int statusCode;
    private final long retryAfterMillis;

    public ModelHttpException(int statusCode, long retryAfterMillis) {
        super("model service HTTP " + statusCode);
        this.statusCode = statusCode;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public int statusCode() {
        return statusCode;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean retryable() {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }
}
