package agent.next.device;

/**
 * Checked device failure. Callers must decide whether to retry, re-observe, or
 * stop; the port never silently turns a failure into success.
 */
public final class DeviceException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Code {
        UNAVAILABLE,
        TIMEOUT,
        REJECTED,
        STALE,
        BUSY,
        PROTOCOL,
        INTERNAL
    }

    private final Code code;

    public DeviceException(Code code, String message) {
        super(message);
        if (code == null) {
            throw new NullPointerException("code");
        }
        this.code = code;
    }

    public DeviceException(Code code, String message, Throwable cause) {
        super(message, cause);
        if (code == null) {
            throw new NullPointerException("code");
        }
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
