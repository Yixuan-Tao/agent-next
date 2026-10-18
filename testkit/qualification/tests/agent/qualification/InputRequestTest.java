package agent.qualification;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class InputRequestTest {
    private static int checks;
    private static int failures;

    private InputRequestTest() {
    }

    public static void main(String[] args) {
        allows("library namespace is independent of app package", valid(), "京东 search😀");
        allows("empty text clears the field", change(7, ""), "");
        allows("whitespace is preserved", change(7, encode(" \t\n ")), " \t\n ");
        allows("standard base64 plus and slash", change(7, encode("\uffff")), "\uffff");
        allows("maximum window integer", change(5, "2147483647"), "京东 search😀");
        allows("leading zero window digits", change(5, "0007"), "京东 search😀");
        allows("zero window ID", change(5, "0"), "京东 search😀");
        allows("platform namespace", change(6, "android:id/input"), "京东 search😀");
        allows("underscores and case in resource name", change(6, "com.example_lib:id/_Input2"), "京东 search😀");
        String boundary = repeat("😀", 256);
        allows("256 supplementary code points", change(7, encode(boundary)), boundary);
        allows("256 ASCII code points", change(7, encode(repeat("a", 256))), repeat("a", 256));
        String[] foreground = valid();
        foreground[0] = "foreground";
        foreground[1] = "0";
        foreground[4] = "foreground-selected=yes";
        allows("explicit foreground route", foreground, "京东 search😀");
        rejects("missing request", null);
        rejects("missing arguments", new String[0]);
        rejects("seven arguments", new String[]{"background", "7", "virtual:a", "com.example", "foreground-selected=no", "7", "com.example:id/input"});
        rejects("nine arguments", new String[]{"background", "7", "virtual:a", "com.example", "foreground-selected=no", "7", "com.example:id/input", "", "extra"});
        for (int i = 0; i < 8; i++) {
            rejects("null argument " + i, change(i, null));
        }
        rejects("background cannot route to main", change(1, "0"));
        rejects("foreground cannot route to virtual display", change(0, "foreground"));
        String[] unselected = foreground.clone();
        unselected[4] = "foreground-selected=no";
        rejects("foreground requires explicit selection", unselected);
        rejects("display identity cannot be blank", change(2, "\u2003"));
        rejects("invalid app package", change(3, "com.example-app"));
        rejects("selection flag remains exact", change(4, "foreground-selected=false"));
        for (String invalid : new String[]{"", "-1", "-0", "+7", " 7", "7 ", "7.0", "0x7", "７", "2147483648", "99999999999999999999999"}) {
            rejects("invalid window ID " + invalid, change(5, invalid));
        }
        for (String invalid : new String[]{"", "input", "id/input", "com.example:id/", ":id/input", "com..example:id/input", "com.example:layout/input", "com.example:id/has space", "com.example:id/input/child", "com.example:id/2input", "com.example:id/输入", " com.example:id/input", "com.example:id/input\n"}) {
            rejects("invalid resource ID " + invalid, change(6, invalid));
        }
        rejects("257 ASCII code points", change(7, encode(repeat("a", 257))));
        rejects("257 supplementary code points", change(7, encode(repeat("😀", 257))));
        rejects("NUL text", change(7, encode("a\u0000b")));
        rejects("overlong UTF8", change(7, Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc0, (byte) 0xaf})));
        rejects("truncated UTF8", change(7, Base64.getEncoder().encodeToString(new byte[]{(byte) 0xe4, (byte) 0xb8})));
        rejects("surrogate UTF8", change(7, Base64.getEncoder().encodeToString(new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80})));
        rejects("isolated continuation UTF8", change(7, "gA=="));
        for (String invalid : new String[]{"YQ", "YQ=", "YQ===", "YR==", "YQ==\n", " YQ==", "Y Q==", "YQ==YQ==", "____", "----", "=", "A", "AA=A", "!!!!"}) {
            rejects("noncanonical base64 " + invalid, change(7, invalid));
        }
        rejects("payload bound applies before decoding", change(7, repeat("A", 4097)));
        rejects("large canonical payload exceeds code point limit", change(7, repeat("YWFh", 1024)));
        System.out.println("InputRequestTest: " + (checks - failures) + " passed, " + failures + " failed");
        if (failures != 0) {
            throw new AssertionError("InputRequest validation failures: " + failures);
        }
    }

    private static String[] valid() {
        return new String[]{"background", "7", "virtual:session-a", "com.jingdong.app.mall",
                "foreground-selected=no", "2242", "com.jd.lib.search.feature:id/zt", encode("京东 search😀")};
    }

    private static String[] change(int index, String value) {
        String[] args = valid();
        args[index] = value;
        return args;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(String value, int count) {
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < count; i++) {
            repeated.append(value);
        }
        return repeated.toString();
    }

    private static void allows(String scenario, String[] args, String expectedText) {
        checks++;
        try {
            InputRequest request = InputRequest.parse(args);
            if (request.windowId != Integer.parseInt(args[5]) || !args[6].equals(request.viewId)
                    || !expectedText.equals(request.text) || !args[2].equals(request.probe.uniqueId)
                    || !args[3].equals(request.probe.packageName)
                    || request.probe.displayId != Integer.parseInt(args[1])
                    || request.probe.mode != ("foreground".equals(args[0]) ? SessionPolicy.Mode.FOREGROUND : SessionPolicy.Mode.BACKGROUND)
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
            InputRequest.parse(args);
            fail(scenario + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Invalid requests must fail before Android bootstrap or action dispatch.
        } catch (RuntimeException wrongFailure) {
            fail(scenario + " failed with " + wrongFailure.getClass().getSimpleName());
        }
    }

    private static void fail(String message) {
        failures++;
        System.err.println("FAIL: " + message);
    }
}
