package agent.phone.connection;

import java.util.concurrent.Executor;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import static agent.phone.connection.ConnectionState.Reason;
import static agent.phone.connection.ConnectionState.Status;

/** Process-scoped lifecycle; callers and dispatcher callbacks run on the UI event thread. */
public final class ConnectionCoordinator {
    public interface Listener { void changed(ConnectionState state); }
    public interface Cancellation { void cancel(); }
    public interface Dispatcher {
        void post(Runnable action);
        Cancellation after(long milliseconds, Runnable action);
    }
    public interface Service {
        Capabilities read() throws Exception;
        DisplaySession createDisplay() throws Exception;
        DisplaySession checkDisplay(DisplaySession expected) throws Exception;
        DisplaySession releaseDisplay(DisplaySession expected) throws Exception;
        TargetSession bindTarget(DisplaySession display, String taskId, String[] allowedPackages, String selected) throws Exception;
        TargetObservation readTarget(TargetSession target, boolean launch) throws Exception;
        FrameEvidence.Capture readFrame(TargetSession target) throws Exception;
        TargetTap tapTarget(TargetSession target, String observationId, int x, int y) throws Exception;
        TargetText setTextTarget(TargetSession target, String observationId, String ref, String text) throws Exception;
        TargetSwipe swipeTarget(TargetSession target, String observationId, int startX, int startY, int endX, int endY, int durationMs) throws Exception;
        TargetBack backTarget(TargetSession target, String observationId) throws Exception;
        int moveTargetToDisplay(TargetSession target, int displayId) throws Exception;
        void startPreviewMirror(DisplaySession display, android.view.Surface surface) throws Exception;
        void stopPreviewMirror(DisplaySession display) throws Exception;
        void userTap(TargetSession target, int x, int y) throws Exception;
        void userSwipe(TargetSession target, int startX, int startY, int endX, int endY, int durationMs) throws Exception;
        void destroy() throws Exception;
    }
    public interface Binding {
        void connected(Service service);
        void disconnected();
    }
    public interface Backend {
        ConnectionState inspect() throws Exception;
        void requestPermission(int code) throws Exception;
        void bind(Binding binding) throws Exception;
        void unbind(Binding binding, boolean remove) throws Exception;
        default void retire(Binding binding) { }
    }

    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        public Failure(Reason reason, Throwable cause) { super(cause); this.reason = reason; }
    }

    private static final long DEADLINE_MS = 10000;
    private final Backend backend;
    private final Dispatcher dispatcher;
    private final Executor rpcExecutor;
    private final Executor cleanupExecutor;
    private Listener listener;
    private long ownerSequence;
    private long owner;
    private int operationSequence;
    private Operation operation;
    private Attempt attempt;
    private boolean rpcOutstanding;
    private DisplaySession display;
    private TargetSession target;
    private TargetObservation observation;
    private FrameEvidence.Capture frame;
    private long observationSequence;
    private long frameSequence;
    private ConnectionState.TargetOperation targetOperation = ConnectionState.TargetOperation.NONE;
    private ConnectionState.DisplayOperation displayOperation = ConnectionState.DisplayOperation.NONE;
    private Status afterCleanupStatus;
    private Reason afterCleanupReason;
    private ConnectionState state = new ConnectionState(Status.DISCONNECTED, Reason.AVAILABLE, null, false);

    public ConnectionCoordinator(Backend backend, Dispatcher dispatcher, Executor rpc, Executor cleanup) {
        this.backend = backend;
        this.dispatcher = dispatcher;
        rpcExecutor = rpc;
        cleanupExecutor = cleanup;
    }

    public long attach(Listener nextListener) {
        owner = ++ownerSequence;
        listener = nextListener;
        if (displayOperation != ConnectionState.DisplayOperation.NONE || targetOperation != ConnectionState.TargetOperation.NONE) {
            // A confirmed session can transfer, but a request from the old page cannot.
            cancelSession(Status.DISCONNECTED, Reason.USER);
        } else {
            refresh();
        }
        return owner;
    }

    public void detach(long previousOwner) {
        if (previousOwner != owner) return;
        listener = null;
        owner = 0;
        cancelSession(Status.DISCONNECTED, Reason.USER);
    }

    public void check() {
        if (operation != null) return;
        if (attempt != null && attempt.cancelled.get()) {
            if (!attempt.cleanupInFlight && !attempt.bindInFlight) {
                attempt.cleanupNeeded = true;
                armCleanupDeadline(attempt);
                update(Status.DISCONNECTING, Reason.CLEANUP, null);
                cleanup(attempt);
            }
            return;
        }
        if (rpcOutstanding) { update(Status.ERROR, Reason.RPC_BUSY, null); return; }
        inspect(false);
    }

    public void connect() {
        if (operation != null || !state.canConnect || attempt != null || rpcOutstanding) return;
        inspect(true);
    }

    private void inspect(boolean connectRequested) {
        Operation next = new Operation(++operationSequence);
        operation = next;
        armDeadline(next);
        update(Status.CONNECTING, Reason.CHECKING, null);
        rpc(next, backend::inspect, (environment, failure) -> {
            if (!current(next)) return;
            if (failure != null) { fail(next, reason(failure)); return; }
            if (environment.status == Status.PERMISSION_REQUIRED && connectRequested
                    && environment.reason != Reason.BLOCKED) {
                next.timer.cancel();
                next.awaitingPermission = true;
                update(Status.AUTHORIZING, Reason.DENIED, null);
                rpc(next, () -> { backend.requestPermission(next.code); return null; }, (ignored, requestFailure) -> {
                    if (current(next) && requestFailure != null) fail(next, reason(requestFailure));
                });
            } else if (environment.reason == Reason.AVAILABLE && (connectRequested || attempt != null)) {
                if (attempt == null) bind(next);
                else handshake(next, attempt);
            } else {
                cancelSession(environment.status, environment.reason);
            }
        });
    }

    public void permission(int code, boolean granted) {
        Operation active = operation;
        if (active == null || active.code != code || !active.awaitingPermission) return;
        active.awaitingPermission = false;
        if (!granted) { cancelSession(Status.PERMISSION_REQUIRED, Reason.DENIED); return; }
        armDeadline(active);
        bind(active);
    }

    private void bind(Operation active) {
        Attempt next = new Attempt();
        attempt = next;
        next.bindInFlight = true;
        update(Status.CONNECTING, Reason.AVAILABLE, null);
        rpc(active, () -> { backend.bind(next); return null; }, (ignored, failure) -> {
            next.bindInFlight = false;
            if (next.cancelled.get()) {
                next.cleanupNeeded = true;
                cleanup(next);
            } else if (current(active) && failure != null) {
                fail(active, reason(failure));
            } else if (current(active) && next.service != null) {
                handshake(active, next);
            }
        });
    }

    private void handshake(Operation active, Attempt bound) {
        if (active.handshakeQueued || bound.service == null || bound.cancelled.get()) return;
        active.handshakeQueued = true;
        rpc(active, bound.service::read, (capabilities, failure) -> {
            if (!current(active) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(active, reason(failure)); return; }
            if (capabilities == null) { fail(active, Reason.MALFORMED); return; }
            if (!capabilities.supported) { cancelSession(Status.UNSUPPORTED, Reason.ENVIRONMENT); return; }
            finishOperation();
            update(Status.CONNECTED, Reason.AVAILABLE, capabilities);
        });
    }

    private <T> void rpc(Operation active, Callable<T> call, BiConsumer<T, Exception> completion) {
        if (rpcOutstanding) {
            active.pending = () -> rpc(active, call, completion);
            active.pendingCancellation = () -> completion.accept(null, new CancellationException());
            return;
        }
        if (!current(active)) return;
        rpcOutstanding = true;
        try {
            rpcExecutor.execute(() -> {
                T value = null;
                Exception failure = null;
                try {
                    if (active.cancelled.get()) throw new CancellationException();
                    value = call.call();
                } catch (Exception caught) { failure = caught; }
                T result = value;
                Exception error = failure;
                dispatcher.post(() -> {
                    rpcOutstanding = false;
                    completion.accept(result, error);
                    Operation next = operation;
                    if (!rpcOutstanding && next != null && next.pending != null) {
                        Runnable pending = next.pending;
                        next.pending = null;
                        next.pendingCancellation = null;
                        pending.run();
                    }
                    if (!rpcOutstanding && operation == null && attempt == null && state.reason == Reason.RPC_BUSY) {
                        update(Status.DISCONNECTED, Reason.USER, null);
                    }
                    refresh();
                });
            });
        } catch (RuntimeException rejected) {
            rpcOutstanding = false;
            completion.accept(null, rejected);
            refresh();
        }
    }

    public void createDisplay() { displayOperation(ConnectionState.DisplayOperation.CREATING); }
    public void checkDisplay() { displayOperation(ConnectionState.DisplayOperation.CHECKING); }
    public void releaseDisplay() { displayOperation(ConnectionState.DisplayOperation.RELEASING); }

    private void displayOperation(ConnectionState.DisplayOperation requested) {
        Attempt bound = attempt;
        if (operation != null || !state.canUseDisplay || bound == null || bound.cancelled.get()
                || bound.service == null) return;
        DisplaySession expected = display;
        if (requested != ConnectionState.DisplayOperation.CREATING && expected == null) return;
        Operation next = new Operation(++operationSequence);
        operation = next;
        displayOperation = requested;
        Capabilities capabilities = state.capabilities;
        armDeadline(next);
        refresh();
        rpc(next, () -> {
            if (requested == ConnectionState.DisplayOperation.CREATING) return bound.service.createDisplay();
            if (requested == ConnectionState.DisplayOperation.CHECKING) return bound.service.checkDisplay(expected);
            return bound.service.releaseDisplay(expected);
        }, (result, failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next, reason(failure)); return; }
            boolean creating = requested == ConnectionState.DisplayOperation.CREATING;
            boolean releasing = requested == ConnectionState.DisplayOperation.RELEASING;
            if (result == null || !creating && !result.sameIdentity(expected)
                    || creating && result.status != DisplaySession.Status.ACTIVE
                    || releasing && result.status != DisplaySession.Status.RELEASED
                            && result.status != DisplaySession.Status.CLEANUP_FAILED) {
                fail(next, Reason.MALFORMED); return;
            }
            display = result;
            if (releasing || result.status != DisplaySession.Status.ACTIVE
                    || target != null && !target.display.sameIdentity(result)) clearTarget();
            finishOperation();
            update(Status.CONNECTED, Reason.AVAILABLE, capabilities);
        });
    }

    /** Trusted UI/coordinator entry; never exposed as a model tool. */
    public void bindTarget(String taskId, String[] allowedPackages, String selected) {
        Attempt bound = attempt;
        if (!canStartTarget(bound)) return;
        TargetSession.requireAuthorization(taskId,allowedPackages,selected);
        String[] allowed = allowedPackages.clone();
        DisplaySession expected = display;
        clearTarget();
        Operation next = startTarget(ConnectionState.TargetOperation.BINDING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.bindTarget(expected, taskId, allowed, selected), (result, failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next, reason(failure)); return; }
            if (result == null || !expected.sameIdentity(result.display) || !taskId.equals(result.taskId)
                    || !selected.equals(result.packageName)) { fail(next, Reason.MALFORMED); return; }
            target = result;
            finishOperation();
            update(Status.CONNECTED, Reason.AVAILABLE, capabilities);
        });
    }

    public void launchTarget() { readTarget(true); }
    public void observeTarget() { readTarget(false); }

    public void captureTarget() {
        Attempt bound = attempt;
        if (!canStartTarget(bound) || target == null) return;
        TargetSession expected = target;
        long previousSequence = frameSequence;
        frame = null;
        Operation next = startTarget(ConnectionState.TargetOperation.CAPTURING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.readFrame(expected), (result,failure) -> {
            // The service adapter closes shared memory before returning immutable PNG data.
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next,reason(failure)); return; }
            try {
                if (result == null) throw new IllegalArgumentException("Missing frame");
                result.evidence.requireFresh(expected,previousSequence);
            } catch (RuntimeException malformed) { fail(next,Reason.MALFORMED); return; }
            frame = result;
            frameSequence = result.evidence.sequence;
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
        });
    }

    public void tap(int x,int y) {
        Attempt bound = attempt;
        TargetObservation observed = observation;
        if (!canStartTarget(bound) || target == null || observed == null
                || observed.status != TargetObservation.Status.READY
                || x < 0 || y < 0 || x >= target.display.width || y >= target.display.height) return;
        TargetSession expected = target;
        String observationId = observed.observationId;
        int expectedWindow = observed.focusedWindowId;
        observation = null;
        frame = null;
        Operation next = startTarget(ConnectionState.TargetOperation.TAPPING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.tapTarget(expected,observationId,x,y), (result,failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next,reason(failure)); return; }
            try {
                if (result == null) throw new IllegalArgumentException("Missing tap receipt");
                result.requireFor(expected.display,observationId);
                if (result.windowId != expectedWindow) throw new IllegalArgumentException("Tap window changed");
            } catch (RuntimeException malformed) { fail(next,Reason.MALFORMED); return; }
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
        });
    }

    public void setText(String ref,String text) {
        Attempt bound = attempt;
        TargetObservation observed = observation;
        if (!canStartTarget(bound) || target == null || observed == null
                || observed.status != TargetObservation.Status.READY
                || ref == null || ref.isEmpty() || ref.length() > 80
                || text == null || text.isEmpty() || text.length() > 120
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return;
        TargetSession expected = target;
        String observationId = observed.observationId;
        int expectedWindow = observed.focusedWindowId;
        observation = null;
        frame = null;
        Operation next = startTarget(ConnectionState.TargetOperation.TEXTING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.setTextTarget(expected,observationId,ref,text), (result,failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next,reason(failure)); return; }
            try {
                if (result == null) throw new IllegalArgumentException("Missing text receipt");
                result.requireFor(expected.display,observationId,ref);
                if (result.windowId != expectedWindow) throw new IllegalArgumentException("Text window changed");
            } catch (RuntimeException malformed) { fail(next,Reason.MALFORMED); return; }
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
        });
    }

    public void swipe(int startX,int startY,int endX,int endY,int durationMs) {
        Attempt bound = attempt;
        TargetObservation observed = observation;
        if (!canStartTarget(bound) || target == null || observed == null
                || observed.status != TargetObservation.Status.READY
                || startX < 0 || startY < 0 || endX < 0 || endY < 0
                || startX >= target.display.width || endX >= target.display.width
                || startY >= target.display.height || endY >= target.display.height
                || durationMs < 100 || durationMs > 2000) return;
        TargetSession expected = target;
        String observationId = observed.observationId;
        int expectedWindow = observed.focusedWindowId;
        observation = null;
        frame = null;
        Operation next = startTarget(ConnectionState.TargetOperation.SWIPING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.swipeTarget(expected,observationId,startX,startY,endX,endY,durationMs),
                (result,failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next,reason(failure)); return; }
            try {
                if (result == null) throw new IllegalArgumentException("Missing swipe receipt");
                result.requireFor(expected.display,observationId);
                if (result.windowId != expectedWindow) throw new IllegalArgumentException("Swipe window changed");
            } catch (RuntimeException malformed) { fail(next,Reason.MALFORMED); return; }
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
        });
    }

    public void back() {
        Attempt bound = attempt;
        TargetObservation observed = observation;
        if (!canStartTarget(bound) || target == null || observed == null
                || observed.status != TargetObservation.Status.READY) return;
        TargetSession expected = target;
        String observationId = observed.observationId;
        int expectedWindow = observed.focusedWindowId;
        observation = null;
        frame = null;
        Operation next = startTarget(ConnectionState.TargetOperation.BACKING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.backTarget(expected,observationId), (result,failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next,reason(failure)); return; }
            try {
                if (result == null) throw new IllegalArgumentException("Missing back receipt");
                result.requireFor(expected.display,observationId);
                if (result.windowId != expectedWindow) throw new IllegalArgumentException("Back window changed");
            } catch (RuntimeException malformed) { fail(next,Reason.MALFORMED); return; }
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
        });
    }

    public void moveTargetToDisplay(int displayId, java.util.function.Consumer<Boolean> callback) {
        Attempt bound = attempt;
        if (!canStartTarget(bound) || target == null || displayId < 0) { callback.accept(false); return; }
        TargetSession expected = target;
        observation = null;
        frame = null;
        Operation next = startTarget(ConnectionState.TargetOperation.MOVING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.moveTargetToDisplay(expected,displayId), (root,failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) { callback.accept(false); return; }
            if (failure != null) { fail(next,reason(failure)); callback.accept(false); return; }
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
            callback.accept(root != null && root > 0);
        });
    }

    public boolean userTap(int x,int y) {
        Attempt bound = attempt;
        TargetSession current = target;
        if (bound == null || bound.service == null || current == null) return false;
        try { bound.service.userTap(current,x,y); return true; } catch (Exception failure) { return false; }
    }

    public boolean userSwipe(int startX,int startY,int endX,int endY,int durationMs) {
        Attempt bound = attempt;
        TargetSession current = target;
        if (bound == null || bound.service == null || current == null) return false;
        try { bound.service.userSwipe(current,startX,startY,endX,endY,durationMs); return true; }
        catch (Exception failure) { return false; }
    }

    public boolean startPreviewMirror(android.view.Surface surface) {
        Attempt bound = attempt;
        DisplaySession current = display;
        if (bound == null || bound.service == null || current == null || surface == null || !surface.isValid()) return false;
        try { bound.service.startPreviewMirror(current,surface); return true; } catch (Exception failure) { return false; }
    }

    public void stopPreviewMirror() {
        Attempt bound = attempt;
        DisplaySession current = display;
        if (bound == null || bound.service == null || current == null) return;
        try { bound.service.stopPreviewMirror(current); } catch (Exception ignored) { }
    }

    private void readTarget(boolean launch) {
        Attempt bound = attempt;
        if (!canStartTarget(bound) || target == null) return;
        TargetSession expected = target;
        long previousSequence = observationSequence;
        observation = null;
        frame = null;
        Operation next = startTarget(launch ? ConnectionState.TargetOperation.LAUNCHING : ConnectionState.TargetOperation.OBSERVING);
        Capabilities capabilities = state.capabilities;
        rpc(next, () -> bound.service.readTarget(expected,launch), (result, failure) -> {
            if (!current(next) || attempt != bound || bound.cancelled.get()) return;
            if (failure != null) { fail(next, reason(failure)); return; }
            try {
                if (result == null) throw new IllegalArgumentException("Missing observation");
                result.requireFresh(expected,previousSequence);
            } catch (RuntimeException malformed) { fail(next,Reason.MALFORMED); return; }
            observation = result;
            observationSequence = result.sequence;
            finishOperation();
            update(Status.CONNECTED,Reason.AVAILABLE,capabilities);
        });
    }

    private boolean canStartTarget(Attempt bound) {
        return operation == null && state.canUseDisplay && display != null
                && display.status == DisplaySession.Status.ACTIVE && bound != null
                && !bound.cancelled.get() && bound.service != null;
    }

    private Operation startTarget(ConnectionState.TargetOperation requested) {
        Operation next = new Operation(++operationSequence);
        operation = next;
        targetOperation = requested;
        armDeadline(next);
        refresh();
        return next;
    }

    private void clearTarget() {
        target = null; observation = null; frame = null; observationSequence = 0; frameSequence = 0;
    }

    public void disconnect() { cancelSession(Status.DISCONNECTED, Reason.USER); }
    public void managerDied() { cancelSession(Status.NEEDS_SHIZUKU, Reason.NO_SHIZUKU); }

    private void fail(Operation active, Reason reason) {
        if (current(active)) cancelSession(Status.ERROR, reason);
    }

    private void cancelSession(Status target, Reason reason) {
        finishOperation();
        display = null;
        clearTarget();
        Attempt previous = attempt;
        if (previous == null) { update(target, reason, null); return; }
        afterCleanupStatus = target;
        afterCleanupReason = reason;
        previous.cancelled.set(true);
        previous.cleanupNeeded = true;
        armCleanupDeadline(previous);
        update(target == Status.DISCONNECTED ? Status.DISCONNECTING : target,
                target == Status.DISCONNECTED ? Reason.CLEANUP : reason, null);
        cleanup(previous);
    }

    private void cleanup(Attempt previous) {
        if (attempt != previous || previous.cleanupInFlight || !previous.cleanupNeeded
                || previous.bindInFlight && previous.cleanedDuringBind) return;
        previous.cleanupNeeded = false;
        previous.cleanupInFlight = true;
        previous.cleanedDuringBind = previous.bindInFlight;
        Service remote = previous.service;
        try {
            cleanupExecutor.execute(() -> {
                Exception failure = null;
                try { if (remote != null) remote.destroy(); }
                catch (Exception caught) { failure = caught; }
                try { backend.unbind(previous, true); }
                catch (Exception caught) { failure = caught; }
                // remove=true does not detach Shizuku's local connection registry.
                try { backend.unbind(previous, false); }
                catch (Exception caught) { failure = caught; }
                Exception result = failure;
                dispatcher.post(() -> cleaned(previous, result));
            });
        } catch (RuntimeException rejected) { cleaned(previous, rejected); }
    }

    private void cleaned(Attempt previous, Exception failure) {
        if (attempt != previous) return;
        previous.cleanupInFlight = false;
        if (previous.bindInFlight) { previous.cleanupNeeded = true; return; }
        if (previous.cleanupNeeded) { cleanup(previous); return; }
        if (failure != null) { update(Status.ERROR, Reason.CLEANUP_FAILED, null); return; }
        if (previous.cleanupTimer != null) previous.cleanupTimer.cancel();
        backend.retire(previous);
        attempt = null;
        update(afterCleanupStatus, afterCleanupReason, null);
    }

    private void armDeadline(Operation active) {
        if (active.timer != null) active.timer.cancel();
        active.timer = dispatcher.after(DEADLINE_MS, () -> fail(active, Reason.TIMEOUT));
    }

    private void armCleanupDeadline(Attempt previous) {
        if (previous.cleanupTimer != null) previous.cleanupTimer.cancel();
        previous.cleanupTimer = dispatcher.after(DEADLINE_MS, () -> {
            if (attempt == previous && previous.cancelled.get()) update(Status.ERROR, Reason.CLEANUP_PENDING, null);
        });
    }

    private void finishOperation() {
        Operation previous = operation;
        operation = null;
        displayOperation = ConnectionState.DisplayOperation.NONE;
        targetOperation = ConnectionState.TargetOperation.NONE;
        if (previous == null) return;
        previous.cancelled.set(true);
        Runnable cancelPending = previous.pendingCancellation;
        previous.pending = null;
        previous.pendingCancellation = null;
        if (previous.timer != null) previous.timer.cancel();
        if (cancelPending != null) cancelPending.run();
    }

    private boolean current(Operation active) { return operation == active && !active.cancelled.get(); }

    private static Reason reason(Exception failure) {
        return failure instanceof Failure ? ((Failure) failure).reason : Reason.FAILURE;
    }

    private void update(Status status, Reason reason, Capabilities capabilities) {
        state = new ConnectionState(status, reason, capabilities, attempt != null, rpcOutstanding, display, displayOperation,
                target,observation,frame,targetOperation);
        if (listener != null) listener.changed(state);
    }

    private void refresh() { update(state.status, state.reason, state.capabilities); }

    private static final class Operation {
        final int code;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Cancellation timer;
        Runnable pending;
        Runnable pendingCancellation;
        boolean awaitingPermission;
        boolean handshakeQueued;
        Operation(int code) { this.code = code; }
    }

    private final class Attempt implements Binding {
        final AtomicBoolean cancelled = new AtomicBoolean();
        Service service;
        boolean bindInFlight;
        boolean cleanupInFlight;
        boolean cleanupNeeded;
        boolean cleanedDuringBind;
        Cancellation cleanupTimer;

        @Override public void connected(Service remote) {
            dispatcher.post(() -> {
                if (attempt != this) return;
                service = remote;
                if (cancelled.get()) { cleanupNeeded = true; cleanup(this); }
                else if (operation != null) handshake(operation, this);
            });
        }

        @Override public void disconnected() {
            dispatcher.post(() -> {
                if (attempt == this && !cancelled.get()) cancelSession(Status.DISCONNECTED, Reason.LOST);
            });
        }
    }
}
