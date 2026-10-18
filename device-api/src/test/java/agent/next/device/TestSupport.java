package agent.next.device;

/** Dependency-free assertions shared by the device-api contract tests. */
final class TestSupport {

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private TestSupport() {
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown);
            }
            throw new AssertionError(
                    message + ": expected " + type.getSimpleName() + " but got " + thrown, thrown);
        }
        throw new AssertionError(
                message + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
