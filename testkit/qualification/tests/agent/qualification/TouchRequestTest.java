package agent.qualification;

import java.util.ArrayList;
import java.util.List;

public final class TouchRequestTest {
    private static int checks;
    private static int failures;

    private TouchRequestTest() {
    }

    public static void main(String[] args) {
        allows("tap preserves validated session and coordinates", tap());
        allows("swipe accepts minimum duration", swipe("100"));
        allows("swipe accepts maximum duration", swipe("1000"));
        allows("leading zero digits", change(tap(), 7, "00010"));
        allows("maximum integer coordinate parses before bounds", change(tap(), 8, "2147483647"));
        allows("zero coordinate", change(tap(), 7, "0"));
        allows("maximum window ID", change(tap(), 5, "2147483647"));
        String[] foreground = tap();
        foreground[0] = "foreground";
        foreground[1] = "0";
        foreground[4] = "foreground-selected=yes";
        allows("explicit foreground main display", foreground);
        rejects("missing request", null);
        rejects("no arguments", new String[0]);
        rejects("tap lacks y", new String[]{"background", "7", "virtual:a", "com.example",
                "foreground-selected=no", "5", "tap", "10"});
        rejects("tap has duration", new String[]{"background", "7", "virtual:a", "com.example",
                "foreground-selected=no", "5", "tap", "10", "20", "100"});
        rejects("swipe lacks duration", new String[]{"background", "7", "virtual:a", "com.example",
                "foreground-selected=no", "5", "swipe", "10", "20", "30", "40"});
        for (int i = 0; i < tap().length; i++) {
            rejects("null tap argument " + i, change(tap(), i, null));
        }
        for (int i = 0; i < swipe("100").length; i++) {
            rejects("null swipe argument " + i, change(swipe("100"), i, null));
        }
        rejects("background cannot use main display", change(tap(), 1, "0"));
        rejects("foreground cannot use virtual display", change(tap(), 0, "foreground"));
        rejects("foreground must be actively selected", change(foreground, 4, "foreground-selected=no"));
        rejects("blank display identity", change(tap(), 2, "\u2003"));
        rejects("invalid package", change(tap(), 3, "com.example-app"));
        rejects("selection flag must be exact", change(tap(), 4, "foreground-selected=false"));
        for (String action : new String[]{"", "TAP", "tap ", "input", "tap\n"}) {
            rejects("unsupported action " + action, change(tap(), 6, action));
        }
        for (String invalid : new String[]{"", "-1", "-0", "+1", " 1", "1 ", "1.0", "0x1",
                "１", "١", "2147483648", "99999999999999999999", "1\n"}) {
            for (int index : new int[]{1, 5, 7, 8}) {
                rejects("invalid unsigned integer at " + index + ": " + invalid, change(tap(), index, invalid));
            }
            for (int index : new int[]{7, 8, 9, 10, 11}) {
                rejects("invalid swipe integer at " + index + ": " + invalid,
                        change(swipe("100"), index, invalid));
            }
        }
        rejects("duration below minimum", swipe("99"));
        rejects("duration above maximum", swipe("1001"));
        boundary("tap within half-open bounds", tap(), 10, 20, 31, 41, 100, 100, true);
        boundary("tap on left and top", tap(), 10, 20, 30, 40, 100, 100, true);
        boundary("tap on right edge", tap(), 0, 0, 10, 40, 100, 100, false);
        boundary("tap on bottom edge", tap(), 0, 0, 30, 20, 100, 100, false);
        boundary("tap outside left edge", tap(), 11, 0, 30, 40, 100, 100, false);
        boundary("tap outside top edge", tap(), 0, 21, 30, 40, 100, 100, false);
        boundary("empty window", tap(), 10, 20, 10, 40, 100, 100, false);
        boundary("invalid display size", tap(), 0, 0, 30, 40, 0, 100, false);
        boundary("window may extend beyond display", tap(), -20, -20, 200, 200, 100, 100, true);
        boundary("display width still limits oversized window", tap(), -20, -20, 200, 200, 10, 100, false);
        boundary("display height still limits oversized window", tap(), -20, -20, 200, 200, 100, 20, false);
        boundary("swipe endpoints within bounds", swipe("100"), 0, 0, 31, 41, 100, 100, true);
        boundary("swipe end outside window", swipe("100"), 0, 0, 30, 40, 100, 100, false);
        boundary("swipe end outside display", swipe("100"), 0, 0, 300, 400, 30, 100, false);
        uid("first ordinary app UID", 10000, true);
        uid("last ordinary app UID", 19999, true);
        for (int uid : new int[]{-1, 0, 1000, 2000, 9999, 20000, 99000, 110000, Integer.MAX_VALUE}) {
            uid("non-user0 ordinary app UID " + uid, uid, false);
        }
        sequence("tap emits down then up", tap());
        sequence("minimum swipe has bounded ordered moves", swipe("100"));
        sequence("maximum swipe has bounded ordered moves", swipe("1000"));
        stopsAfterFailure();
        System.out.println("TouchRequestTest: " + (checks - failures) + " passed, " + failures + " failed");
        if (failures != 0) {
            throw new AssertionError("TouchRequest validation failures: " + failures);
        }
    }

    private static String[] tap() {
        return new String[]{"background", "7", "virtual:session-a", "com.example",
                "foreground-selected=no", "2242", "tap", "10", "20"};
    }

    private static String[] swipe(String duration) {
        return new String[]{"background", "7", "virtual:session-a", "com.example",
                "foreground-selected=no", "2242", "swipe", "10", "20", "30", "40", duration};
    }

    private static String[] change(String[] source, int index, String value) {
        String[] args = source.clone();
        args[index] = value;
        return args;
    }

    private static void allows(String scenario, String[] args) {
        checks++;
        try {
            TouchRequest request = TouchRequest.parse(args);
            boolean swipe = "swipe".equals(args[6]);
            if (request.windowId != Integer.parseInt(args[5]) || !args[6].equals(request.action)
                    || request.x1 != Integer.parseInt(args[7]) || request.y1 != Integer.parseInt(args[8])
                    || request.x2 != Integer.parseInt(args[swipe ? 9 : 7])
                    || request.y2 != Integer.parseInt(args[swipe ? 10 : 8])
                    || request.durationMillis != (swipe ? Integer.parseInt(args[11]) : 0)
                    || request.probe.displayId != Integer.parseInt(args[1])
                    || !args[2].equals(request.probe.uniqueId) || !args[3].equals(request.probe.packageName)
                    || request.probe.mode != ("foreground".equals(args[0])
                            ? SessionPolicy.Mode.FOREGROUND : SessionPolicy.Mode.BACKGROUND)
                    || request.probe.foregroundSelected != "foreground-selected=yes".equals(args[4])
                    || !"inspect".equals(request.probe.command)) {
                fail(scenario + " changed validated request fields");
            }
        } catch (RuntimeException rejected) {
            fail(scenario + " was rejected: " + rejected.getMessage());
        }
    }

    private static void rejects(String scenario, String[] args) {
        checks++;
        try {
            TouchRequest.parse(args);
            fail(scenario + " was accepted");
        } catch (IllegalArgumentException expected) {
            // All malformed requests fail before Android bootstrap or dispatch.
        }
    }

    private static void boundary(String scenario, String[] args, int left, int top, int right, int bottom,
            int width, int height, boolean allowed) {
        checks++;
        try {
            TouchRequest.parse(args).checkBounds(left, top, right, bottom, width, height);
            if (!allowed) {
                fail(scenario + " was accepted");
            }
        } catch (IllegalArgumentException rejected) {
            if (allowed) {
                fail(scenario + " was rejected: " + rejected.getMessage());
            }
        }
    }

    private static void uid(String scenario, int targetUid, boolean allowed) {
        checks++;
        try {
            TouchRequest.checkTargetUid(targetUid);
            if (!allowed) {
                fail(scenario + " was accepted");
            }
        } catch (IllegalArgumentException rejected) {
            if (allowed) {
                fail(scenario + " was rejected: " + rejected.getMessage());
            }
        }
    }

    private static void sequence(String scenario, String[] args) {
        checks++;
        final List<EmittedEvent> events = new ArrayList<EmittedEvent>();
        final TouchRequest request = TouchRequest.parse(args);
        try {
            QualificationTouch.dispatchEvents(request, new QualificationTouch.EventDispatcher() {
                @Override
                public void emit(int action, float x, float y, int offsetMillis) {
                    events.add(new EmittedEvent(action, x, y, offsetMillis));
                }
            });
            boolean swipe = "swipe".equals(request.action);
            int count = events.size();
            if (count < (swipe ? 3 : 2) || count > (swipe ? 22 : 2)) {
                fail(scenario + " emitted an unbounded or incomplete sequence");
                return;
            }
            for (int i = 0; i < count; i++) {
                EmittedEvent event = events.get(i);
                int expectedAction = i == 0 ? 0 : (i == count - 1 ? 1 : 2);
                if (event.action != expectedAction || event.offsetMillis < 0
                        || event.offsetMillis > request.durationMillis
                        || (i > 0 && event.offsetMillis < events.get(i - 1).offsetMillis)) {
                    fail(scenario + " changed action ordering or monotonic offsets");
                    return;
                }
                double fraction = request.durationMillis == 0 ? 0
                        : (double) event.offsetMillis / request.durationMillis;
                if (Math.abs(event.x - (request.x1 + (request.x2 - request.x1) * fraction)) > 0.001
                        || Math.abs(event.y - (request.y1 + (request.y2 - request.y1) * fraction)) > 0.001) {
                    fail(scenario + " changed the linear gesture coordinates");
                    return;
                }
            }
            EmittedEvent first = events.get(0);
            EmittedEvent last = events.get(count - 1);
            if (first.x != request.x1 || first.y != request.y1 || first.offsetMillis != 0
                    || last.x != request.x2 || last.y != request.y2
                    || last.offsetMillis != request.durationMillis) {
                fail(scenario + " changed the requested endpoints or duration");
            }
        } catch (Exception failure) {
            fail(scenario + " failed: " + failure.getMessage());
        }
    }

    private static void stopsAfterFailure() {
        checks++;
        final int[] calls = new int[1];
        final Exception rejected = new Exception("injection rejected");
        try {
            QualificationTouch.dispatchEvents(TouchRequest.parse(swipe("1000")),
                    new QualificationTouch.EventDispatcher() {
                        @Override
                        public void emit(int action, float x, float y, int offsetMillis) throws Exception {
                            calls[0]++;
                            if (calls[0] == 2) {
                                throw rejected;
                            }
                        }
                    });
            fail("dispatch failure was ignored");
        } catch (Exception failure) {
            if (failure != rejected || calls[0] != 2) {
                fail("dispatch failure retried or continued the gesture");
            }
        }
    }

    private static final class EmittedEvent {
        private final int action;
        private final float x;
        private final float y;
        private final int offsetMillis;

        EmittedEvent(int action, float x, float y, int offsetMillis) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.offsetMillis = offsetMillis;
        }
    }

    private static void fail(String message) {
        failures++;
        System.err.println("FAIL: " + message);
    }
}
