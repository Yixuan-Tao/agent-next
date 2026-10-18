package agent.next.device;

/** Validation helpers kept package-private so the public surface stays data-only. */
final class Text {

    private Text() {
    }

    static String require(String value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requirePackage(String value, String name) {
        require(value, name);
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException(name + " is not a valid Android package");
        }
        return value;
    }

    static String requireTaskId(String value) {
        require(value, "taskId");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("taskId contains unsupported characters");
        }
        return value;
    }

    static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
