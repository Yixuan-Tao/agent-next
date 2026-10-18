package agent.qualification;

import java.util.Arrays;

/** Pure validation for one display-bound, UID-targeted touch diagnostic. */
public final class TouchRequest {
    public final ProbeRequest probe;
    public final int windowId;
    public final String action;
    public final int x1;
    public final int y1;
    public final int x2;
    public final int y2;
    public final int durationMillis;

    private TouchRequest(ProbeRequest probe, int windowId, String action, int x1, int y1,
            int x2, int y2, int durationMillis) {
        this.probe = probe;
        this.windowId = windowId;
        this.action = action;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.durationMillis = durationMillis;
    }

    public static TouchRequest parse(String[] args) {
        if (args == null || (args.length != 9 && args.length != 12)) {
            throw new IllegalArgumentException("tap requires nine arguments; swipe requires twelve arguments");
        }
        for (String argument : args) {
            if (argument == null) {
                throw new IllegalArgumentException("arguments must not be null");
            }
        }
        String[] inspection = Arrays.copyOf(args, 6);
        inspection[5] = "inspect";
        ProbeRequest probe = ProbeRequest.parse(inspection);
        int windowId = unsigned(args[5], "window ID");
        boolean swipe = "swipe".equals(args[6]);
        if ((!swipe && !"tap".equals(args[6])) || args.length != (swipe ? 12 : 9)) {
            throw new IllegalArgumentException("only tap X Y or swipe X1 Y1 X2 Y2 DURATION_MS is supported");
        }
        int x1 = unsigned(args[7], "x1");
        int y1 = unsigned(args[8], "y1");
        int x2 = swipe ? unsigned(args[9], "x2") : x1;
        int y2 = swipe ? unsigned(args[10], "y2") : y1;
        int duration = swipe ? unsigned(args[11], "duration") : 0;
        if (swipe && (duration < 100 || duration > 1000)) {
            throw new IllegalArgumentException("swipe duration must be between 100 and 1000 milliseconds");
        }
        return new TouchRequest(probe, windowId, args[6], x1, y1, x2, y2, duration);
    }

    void checkBounds(int left, int top, int right, int bottom, int width, int height) {
        checkPoint(x1, y1, left, top, right, bottom, width, height);
        checkPoint(x2, y2, left, top, right, bottom, width, height);
    }

    static void checkPoint(double x, double y, int left, int top, int right, int bottom,
            int width, int height) {
        if (width <= 0 || height <= 0 || left >= right || top >= bottom) {
            throw new IllegalArgumentException("window bounds and display dimensions must be non-empty");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= width || y >= height
                || x < left || x >= right || y < top || y >= bottom) {
            throw new IllegalArgumentException("touch coordinate must lie inside window bounds and display dimensions");
        }
    }

    static void checkTargetUid(int targetUid) {
        // Android Process.FIRST_APPLICATION_UID..LAST_APPLICATION_UID, for user 0 only.
        if (targetUid < 10000 || targetUid > 19999) {
            throw new IllegalArgumentException("target UID must be an ordinary application UID for user 0");
        }
    }

    private static int unsigned(String value, String name) {
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException(name + " must be unsigned ASCII decimal digits");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " exceeds the non-negative integer range", invalid);
        }
    }
}
