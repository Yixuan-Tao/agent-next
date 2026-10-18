package agent.next.brain;

/** Dependency-free assertions shared by the host tests. */
final class TestSupport {

    private TestSupport() {
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static <T extends Throwable> T expect(Class<T> type, Runnable action, String message) {
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
