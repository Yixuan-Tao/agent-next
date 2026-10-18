package agent.qualification;

public final class SessionPolicy {
    public enum Mode {
        FOREGROUND,
        BACKGROUND
    }

    private SessionPolicy() {
    }

    public static void checkDisplay(Mode mode, boolean foregroundSelected, int sdk,
            int expectedDisplayId, String expectedUniqueId, int actualDisplayId,
            String actualUniqueId, String actualName, int ownerUid) {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        if (sdk != 36) {
            throw new IllegalArgumentException("SDK 36 is required");
        }
        if (expectedDisplayId < 0 || actualDisplayId < 0) {
            throw new IllegalArgumentException("display IDs must be non-negative");
        }
        if (isBlank(expectedUniqueId)) {
            throw new IllegalArgumentException("expected display unique ID is required");
        }
        if (expectedDisplayId != actualDisplayId) {
            throw new IllegalArgumentException("display ID does not match the session");
        }
        if (!expectedUniqueId.equals(actualUniqueId)) {
            throw new IllegalArgumentException("display unique ID does not match the session");
        }
        if (isBlank(actualName)) {
            throw new IllegalArgumentException("display name is required");
        }
        if (mode == Mode.BACKGROUND) {
            if (actualDisplayId == 0) {
                throw new IllegalArgumentException("background mode requires a non-main display");
            }
            if (ownerUid != 2000) {
                throw new IllegalArgumentException("background display must be owned by shell UID 2000");
            }
            if (!"agent-phone".equals(actualName)) {
                throw new IllegalArgumentException("background display name must be agent-phone");
            }
        } else if (actualDisplayId != 0 || !foregroundSelected) {
            throw new IllegalArgumentException("foreground mode requires explicit selection of the main display");
        }
    }

    public static void checkPackage(String allowedPackage, String actualPackage) {
        if (isBlank(allowedPackage) || !allowedPackage.equals(actualPackage)) {
            throw new IllegalArgumentException("package does not match the allowed package");
        }
    }

    public static void checkGeneration(long expected, long actual) {
        if (expected < 0 || actual < 0 || expected != actual) {
            throw new IllegalArgumentException("generation must be non-negative and match the session");
        }
    }

    private static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
