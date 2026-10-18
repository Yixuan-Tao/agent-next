package agent.qualification;

import agent.qualification.SessionPolicy.Mode;

public final class ProbeRequestTest {
    private static int checks;
    private static int failures;

    private ProbeRequestTest() {
    }

    public static void main(String[] args) {
        ProbeRequest foreground = ProbeRequest.parse(new String[]{
                "foreground", "0", "local:main", "com.jingdong.app.mall",
                "foreground-selected=yes", "inspect"});
        equal("foreground mode", Mode.FOREGROUND, foreground.mode);
        equal("foreground display", 0, foreground.displayId);
        equal("foreground identity", "local:main", foreground.uniqueId);
        equal("package is preserved", "com.jingdong.app.mall", foreground.packageName);
        equal("explicit foreground choice", true, foreground.foregroundSelected);
        equal("read-only command", "inspect", foreground.command);

        ProbeRequest background = ProbeRequest.parse(valid());
        equal("background mode", Mode.BACKGROUND, background.mode);
        equal("background display", 7, background.displayId);
        equal("background choice", false, background.foregroundSelected);
        allows("foreground choice does not disable background", change(4, "foreground-selected=yes"));
        allows("maximum display integer", change(1, "2147483647"));
        allows("leading zero digits are unsigned decimal", change(1, "007"));
        allows("Unicode display identities are preserved", change(2, "virtual:显示器😀"));
        allows("a nonblank identity may contain spaces", change(2, " virtual:a "));
        allows("package segment permits internal underscore", change(3, "com.example_app.Target2"));

        rejects("missing request", null);
        rejects("missing arguments", new String[0]);
        rejects("five arguments", new String[]{"background", "7", "virtual:a", "com.example", "inspect"});
        rejects("seven arguments", new String[]{"background", "7", "virtual:a", "com.example",
                "foreground-selected=no", "inspect", "extra"});
        for (int i = 0; i < 6; i++) {
            rejects("null argument " + i, change(i, null));
        }
        rejects("mode case must be exact", change(0, "Background"));
        rejects("mode is not trimmed", change(0, " background"));
        rejects("unknown mode", change(0, "auto"));
        rejects("empty mode", change(0, ""));
        rejects("display cannot be negative", change(1, "-1"));
        rejects("display rejects negative zero", change(1, "-0"));
        rejects("display rejects plus sign", change(1, "+7"));
        rejects("display rejects overflow", change(1, "2147483648"));
        rejects("display rejects huge integer", change(1, "999999999999999999999999"));
        rejects("display rejects whitespace", change(1, " 7"));
        rejects("display rejects trailing whitespace", change(1, "7 "));
        rejects("display rejects a fraction", change(1, "7.0"));
        rejects("display rejects exponent", change(1, "7e0"));
        rejects("display rejects hexadecimal", change(1, "0x7"));
        rejects("display rejects non-ASCII digits", change(1, "７"));
        rejects("display is required", change(1, ""));
        rejects("identity is required", change(2, ""));
        rejects("identity rejects ASCII blank", change(2, " \t\n"));
        rejects("identity rejects EM SPACE", change(2, "\u2003"));
        rejects("identity rejects IDEOGRAPHIC SPACE", change(2, "\u3000"));
        rejects("identity rejects NO-BREAK SPACE", change(2, "\u00a0"));
        rejects("package must have dotted segments", change(3, "android"));
        rejects("package rejects empty", change(3, ""));
        rejects("package rejects leading dot", change(3, ".com.example"));
        rejects("package rejects trailing dot", change(3, "com.example."));
        rejects("package rejects empty segment", change(3, "com..example"));
        rejects("package segment starts with letter", change(3, "com.2example"));
        rejects("package segment cannot start underscore", change(3, "com._example"));
        rejects("package rejects hyphen", change(3, "com.example-app"));
        rejects("package rejects whitespace", change(3, " com.example"));
        rejects("package rejects trailing whitespace", change(3, "com.example "));
        rejects("package rejects slash", change(3, "com.example/Main"));
        rejects("package rejects Unicode segment", change(3, "com.应用"));
        rejects("foreground flag requires exact key", change(4, "selected=no"));
        rejects("foreground flag requires lowercase", change(4, "foreground-selected=NO"));
        rejects("foreground flag rejects booleans", change(4, "foreground-selected=false"));
        rejects("foreground flag is not trimmed", change(4, "foreground-selected=no "));
        rejects("foreground flag cannot be omitted", change(4, ""));
        rejects("only inspect is allowed", change(5, "click"));
        rejects("command is lowercase", change(5, "Inspect"));
        rejects("command is not trimmed", change(5, "inspect "));
        rejects("background cannot select display zero", change(1, "0"));
        String[] backgroundZero = change(1, "0");
        backgroundZero[4] = "foreground-selected=yes";
        rejects("explicit foreground choice never reroutes background to zero", backgroundZero);
        rejects("foreground requires main display", change(0, "foreground"));
        String[] unselectedForeground = change(0, "foreground");
        unselectedForeground[1] = "0";
        rejects("foreground requires explicit yes", unselectedForeground);

        System.out.println("ProbeRequestTest: " + (checks - failures) + " passed, " + failures + " failed");
        if (failures != 0) {
            throw new AssertionError("ProbeRequest validation failures: " + failures);
        }
    }

    private static String[] valid() {
        return new String[]{"background", "7", "virtual:session-a", "com.jingdong.app.mall",
                "foreground-selected=no", "inspect"};
    }

    private static String[] change(int index, String value) {
        String[] args = valid();
        args[index] = value;
        return args;
    }

    private static void equal(String scenario, Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) {
            failures++;
            System.err.println("FAIL: " + scenario + ": expected " + expected + ", got " + actual);
        }
    }

    private static void allows(String scenario, String[] args) {
        checks++;
        try {
            ProbeRequest.parse(args);
        } catch (IllegalArgumentException rejected) {
            failures++;
            System.err.println("FAIL: " + scenario + " was rejected: " + rejected.getMessage());
        }
    }

    private static void rejects(String scenario, String[] args) {
        checks++;
        try {
            ProbeRequest.parse(args);
            failures++;
            System.err.println("FAIL: " + scenario + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Rejecting malformed or unauthorized routing is the expected outcome.
        }
    }
}
