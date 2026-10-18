package agent.qualification;

import agent.qualification.SessionPolicy.Mode;

public final class FrameRequestTest {
    private static int checks;
    private static int failures;

    private FrameRequestTest() {
    }

    public static void main(String[] args) {
        allows("background request preserves route and nonce", valid());
        String[] foreground = valid();
        foreground[0] = "foreground";
        foreground[1] = "0";
        foreground[4] = "foreground-selected=yes";
        allows("explicit foreground route", foreground);
        allows("maximum display integer", change(1, "2147483647"));
        allows("Unicode display identity", change(2, "virtual:显示器😀"));
        rejects("missing request", null);
        rejects("missing arguments", new String[0]);
        rejects("five arguments", new String[]{"background", "7", "virtual:a", "com.example", "foreground-selected=no"});
        rejects("seven arguments", new String[]{"background", "7", "virtual:a", "com.example", "foreground-selected=no", nonce(), "extra"});
        for (int i = 0; i < 6; i++) {
            rejects("null argument " + i, change(i, null));
        }
        rejects("unknown mode", change(0, "auto"));
        rejects("background cannot route to main", change(1, "0"));
        rejects("foreground cannot route to virtual display", change(0, "foreground"));
        String[] unselected = foreground.clone();
        unselected[4] = "foreground-selected=no";
        rejects("foreground requires explicit choice", unselected);
        rejects("display rejects signed integer", change(1, "+7"));
        rejects("display rejects overflow", change(1, "2147483648"));
        rejects("display rejects Unicode digits", change(1, "７"));
        rejects("display identity cannot be blank", change(2, "\u00a0"));
        rejects("package is validated by shared parser", change(3, "com..example"));
        rejects("selection flag remains exact", change(4, "foreground-selected=false"));
        rejects("nonce cannot be empty", change(5, ""));
        rejects("nonce cannot be short", change(5, "0123456789abcdef0123456789abcde"));
        rejects("nonce cannot be long", change(5, nonce() + "0"));
        rejects("nonce rejects uppercase", change(5, "0123456789ABCDEF0123456789abcdef"));
        rejects("nonce rejects nonhex", change(5, "g123456789abcdef0123456789abcdef"));
        rejects("nonce rejects path traversal", change(5, "../../../../../../../../../../../x"));
        rejects("nonce rejects whitespace", change(5, nonce() + "\n"));
        rejects("inspect command is not a nonce", change(5, "inspect"));
        System.out.println("FrameRequestTest: " + (checks - failures) + " passed, " + failures + " failed");
        if (failures != 0) {
            throw new AssertionError("FrameRequest validation failures: " + failures);
        }
    }

    private static String nonce() {
        return "0123456789abcdef0123456789abcdef";
    }

    private static String[] valid() {
        return new String[]{"background", "7", "virtual:session-a", "com.jingdong.app.mall",
                "foreground-selected=no", nonce()};
    }

    private static String[] change(int index, String value) {
        String[] args = valid();
        args[index] = value;
        return args;
    }

    private static void allows(String scenario, String[] args) {
        checks++;
        try {
            FrameRequest request = FrameRequest.parse(args);
            if (!args[5].equals(request.nonce) || !args[2].equals(request.probe.uniqueId)
                    || !args[3].equals(request.probe.packageName)
                    || request.probe.displayId != Integer.parseInt(args[1])
                    || request.probe.mode != ("foreground".equals(args[0]) ? Mode.FOREGROUND : Mode.BACKGROUND)
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
            FrameRequest.parse(args);
            fail(scenario + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Invalid requests must fail before Android bootstrap.
        } catch (RuntimeException wrongFailure) {
            fail(scenario + " failed with " + wrongFailure.getClass().getSimpleName());
        }
    }

    private static void fail(String message) {
        failures++;
        System.err.println("FAIL: " + message);
    }
}
