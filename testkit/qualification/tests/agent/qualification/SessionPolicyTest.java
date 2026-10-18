package agent.qualification;

import agent.qualification.SessionPolicy.Mode;

public final class SessionPolicyTest {
    private static int checks;
    private static int failures;

    private SessionPolicyTest() {
    }

    public static void main(String[] args) {
        allows("background diagnostic display", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 2000));
        allows("foreground selection does not disable background mode", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, true, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 2000));
        allows("explicit foreground selection on system-owned main display", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "Built-in Screen", 1000));
        allows("foreground permits a different physical display name and owner", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "Internal Display", -1));

        rejects("background cannot silently fall back to main display", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 0, "local:main", 0,
                "local:main", "agent-phone", 2000));
        rejects("foreground selection never authorizes background main-display routing", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, true, 36, 0, "local:main", 0,
                "local:main", "agent-phone", 2000));
        rejects("foreground cannot route into a background display", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 2000));
        rejects("main-display access requires explicit foreground selection", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, false, 36, 0, "local:main", 0,
                "local:main", "Built-in Screen", 1000));
        rejects("a recycled display number cannot reuse an old session identity", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:old-session", 7,
                "virtual:new-session", "agent-phone", 2000));
        rejects("identity matching is case-sensitive", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:Session-a", "agent-phone", 2000));
        rejects("identity matching does not trim observed values", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a ", "agent-phone", 2000));
        rejects("a matching identity cannot silently migrate to another display number", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 8,
                "virtual:session-a", "agent-phone", 2000));
        rejects("foreground identity must still match", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:expected", 0,
                "local:replacement", "Built-in Screen", 1000));
        rejects("system ownership cannot replace shell ownership in background mode", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 1000));
        rejects("root ownership cannot replace shell ownership in background mode", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 0));
        rejects("legacy background display names are not the diagnostic display", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-worker", 2000));
        rejects("background display names match exactly", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone ", 2000));
        rejects("background display requires a name", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                "virtual:session-a", null, 2000));
        rejects("foreground display requires a name", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", null, 1000));
        rejects("foreground display rejects an empty name", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "", 1000));
        rejects("foreground display rejects a blank name", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "  ", 1000));
        rejects("foreground display rejects an EM SPACE name", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "\u2003", 1000));
        rejects("foreground display rejects an IDEOGRAPHIC SPACE name", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "\u3000", 1000));
        rejects("foreground display rejects a NO-BREAK SPACE name", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, 0, "local:main", 0,
                "local:main", "\u00a0", 1000));
        rejects("mode must be selected", () -> SessionPolicy.checkDisplay(
                null, true, 36, 0, "local:main", 0,
                "local:main", "Built-in Screen", 1000));
        rejects("older SDK is unsupported", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 35, 7, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 2000));
        rejects("newer SDK needs separate qualification", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 37, 0, "local:main", 0,
                "local:main", "Built-in Screen", 1000));
        rejects("negative expected display number", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, -1, "virtual:session-a", 7,
                "virtual:session-a", "agent-phone", 2000));
        rejects("negative observed display number", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", -1,
                "virtual:session-a", "agent-phone", 2000));
        rejects("matching negative display numbers are invalid", () -> SessionPolicy.checkDisplay(
                Mode.FOREGROUND, true, 36, -1, "local:main", -1,
                "local:main", "Built-in Screen", 1000));
        rejects("background rejects matching negative display numbers", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, -1, "virtual:session-a", -1,
                "virtual:session-a", "agent-phone", 2000));
        rejects("missing expected identity is not an identity match", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, null, 7,
                null, "agent-phone", 2000));
        rejects("empty expected identity is not an identity match", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "", 7,
                "", "agent-phone", 2000));
        rejects("blank expected identity is not an identity match", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "  ", 7,
                "  ", "agent-phone", 2000));
        rejects("matching EM SPACE identities are blank", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "\u2003", 7,
                "\u2003", "agent-phone", 2000));
        rejects("matching IDEOGRAPHIC SPACE identities are blank", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "\u3000", 7,
                "\u3000", "agent-phone", 2000));
        rejects("matching NO-BREAK SPACE identities are blank", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "\u00a0", 7,
                "\u00a0", "agent-phone", 2000));
        rejects("missing observed identity", () -> SessionPolicy.checkDisplay(
                Mode.BACKGROUND, false, 36, 7, "virtual:session-a", 7,
                null, "agent-phone", 2000));

        allows("target package matches", () -> SessionPolicy.checkPackage(
                "com.example.target", "com.example.target"));
        rejects("another package cannot inherit session access", () -> SessionPolicy.checkPackage(
                "com.example.target", "com.example.other"));
        rejects("package names match with case sensitivity", () -> SessionPolicy.checkPackage(
                "com.example.target", "com.example.Target"));
        rejects("package matching does not trim observed values", () -> SessionPolicy.checkPackage(
                "com.example.target", "com.example.target "));
        rejects("missing allowed package", () -> SessionPolicy.checkPackage(null, null));
        rejects("empty allowed package", () -> SessionPolicy.checkPackage("", ""));
        rejects("blank allowed package", () -> SessionPolicy.checkPackage("  ", "  "));
        rejects("matching EM SPACE package names are blank", () -> SessionPolicy.checkPackage("\u2003", "\u2003"));
        rejects("matching IDEOGRAPHIC SPACE package names are blank", () -> SessionPolicy.checkPackage("\u3000", "\u3000"));
        rejects("matching NO-BREAK SPACE package names are blank", () -> SessionPolicy.checkPackage("\u00a0", "\u00a0"));
        rejects("missing observed package", () -> SessionPolicy.checkPackage("com.example.target", null));
        rejects("empty observed package", () -> SessionPolicy.checkPackage("com.example.target", ""));

        allows("initial generation is valid", () -> SessionPolicy.checkGeneration(0, 0));
        allows("current generation is valid", () -> SessionPolicy.checkGeneration(42, 42));
        allows("generation has no artificial upper bound", () -> SessionPolicy.checkGeneration(
                Long.MAX_VALUE, Long.MAX_VALUE));
        rejects("stale generation cannot enter the new session", () -> SessionPolicy.checkGeneration(42, 41));
        rejects("future generation cannot enter the current session", () -> SessionPolicy.checkGeneration(42, 43));
        rejects("negative expected generation", () -> SessionPolicy.checkGeneration(-1, 0));
        rejects("negative observed generation", () -> SessionPolicy.checkGeneration(0, -1));
        rejects("matching negative generations remain invalid", () -> SessionPolicy.checkGeneration(-1, -1));

        System.out.println("SessionPolicyTest: " + (checks - failures) + " passed, " + failures + " failed");
        if (failures != 0) {
            throw new AssertionError("SessionPolicy rejected-access checks failed: " + failures);
        }
    }

    private static void allows(String scenario, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException rejected) {
            failures++;
            System.err.println("FAIL: " + scenario + " was rejected: " + rejected.getMessage());
        }
    }

    private static void rejects(String scenario, Runnable action) {
        checks++;
        try {
            action.run();
            failures++;
            System.err.println("FAIL: " + scenario + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Rejected access is the expected behavior.
        }
    }
}
