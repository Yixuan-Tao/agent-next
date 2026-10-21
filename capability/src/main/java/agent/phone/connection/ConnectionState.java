package agent.phone.connection;

public final class ConnectionState {
    public enum Status {
        DISCONNECTED, NEEDS_SHIZUKU, PERMISSION_REQUIRED, AUTHORIZING,
        CONNECTING, CONNECTED, DISCONNECTING, UNSUPPORTED, ERROR
    }

    public enum DisplayOperation { NONE, CREATING, CHECKING, RELEASING }
    public enum TargetOperation { NONE, BINDING, LAUNCHING, OBSERVING, CAPTURING, TAPPING, TEXTING, SWIPING, BACKING, MOVING }

    public enum Reason {
        CHECKING, AVAILABLE, NO_SHIZUKU, DENIED, BLOCKED, OLD_VERSION,
        ENVIRONMENT, LOST, MALFORMED, FAILURE, TIMEOUT, USER,
        RPC_BUSY, CLEANUP, CLEANUP_PENDING, CLEANUP_FAILED, STALE_INPUT
    }

    public final DisplaySession display;
    public final TargetSession target;
    public final TargetObservation observation;
    public final FrameEvidence.Capture frame;
    public final TargetOperation targetOperation;
    public final DisplayOperation displayOperation;
    public final boolean canUseDisplay;
    public final Status status;
    public final Reason reason;
    public final Capabilities capabilities;
    public final boolean canConnect;
    public final boolean canDisconnect;
    public final boolean canCheck;

    ConnectionState(Status status, Reason reason, Capabilities capabilities, boolean bound) {
        this(status, reason, capabilities, bound, false);
    }

    ConnectionState(Status status, Reason reason, Capabilities capabilities, boolean bound, boolean rpcOutstanding) {
        this(status, reason, capabilities, bound, rpcOutstanding, null, DisplayOperation.NONE);
    }

    ConnectionState(Status status, Reason reason, Capabilities capabilities, boolean bound, boolean rpcOutstanding,
            DisplaySession display, DisplayOperation displayOperation) {
        this(status,reason,capabilities,bound,rpcOutstanding,display,displayOperation,null,null,null,TargetOperation.NONE);
    }

    ConnectionState(Status status, Reason reason, Capabilities capabilities, boolean bound, boolean rpcOutstanding,
            DisplaySession display, DisplayOperation displayOperation, TargetSession target,
            TargetObservation observation, FrameEvidence.Capture frame, TargetOperation targetOperation) {
        this.display = display;
        this.target = target;
        this.observation = observation;
        this.frame = frame;
        this.targetOperation = targetOperation;
        this.displayOperation = displayOperation;
        canUseDisplay = status == Status.CONNECTED && !rpcOutstanding && displayOperation == DisplayOperation.NONE
                && targetOperation == TargetOperation.NONE;
        this.status = status;
        this.reason = reason;
        this.capabilities = capabilities;
        boolean busy = status == Status.CONNECTING || status == Status.AUTHORIZING || status == Status.DISCONNECTING;
        canCheck = !busy && displayOperation == DisplayOperation.NONE && targetOperation == TargetOperation.NONE;
        canConnect = !busy && !bound && !rpcOutstanding && reason != Reason.BLOCKED && (status == Status.DISCONNECTED
                || status == Status.PERMISSION_REQUIRED || status == Status.ERROR);
        canDisconnect = status != Status.DISCONNECTING && (bound || status == Status.AUTHORIZING);
    }
}
