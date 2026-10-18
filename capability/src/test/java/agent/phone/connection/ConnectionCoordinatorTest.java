package agent.phone.connection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static agent.phone.connection.ConnectionState.Reason;
import static agent.phone.connection.ConnectionState.Status;

public final class ConnectionCoordinatorTest {
    private ConnectionCoordinatorTest() { }

    public static void main(String[] args) throws Exception {
        frameCaptureClearsOnNextOperationAndRejectsReplay();
        cancelledFrameCaptureNeverPublishes();
        targetBindingObservationAndRelease();
        cancelledTargetReadNeverPublishes();
        replacingOwnerCancelsInFlightDisplayBeforePublishingToNewOwner();
        displayResultsRespectCancellationAndOwnership();
        displayCreateCheckReleaseAndFailure();
        blockedHandshakeDoesNotBlockCleanupOrCreateRetryThreads();
        initialInspectionAndConnectedRecheckBothExpire();
        cancelledBindMustFinishAndClearBothSidesBeforeReconnect();
        authorizationWaitHasNoConnectionDeadline();
        cancelledPermissionCannotLeaveAnUnstartedBindReserved();
        failedCleanupDoesNotClaimDisconnected();
        blockedCleanupRemainsUnconfirmedAndDoesNotAccumulateRetries();
        repeatedCancellationAfterCleanupTimeoutKeepsAnEffectiveDeadline();
        System.out.println("ConnectionCoordinatorTest: PASS (blocked RPC, cleanup, deadlines, generations, reconnect)");
    }

    private static void frameCaptureClearsOnNextOperationAndRejectsReplay() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.engine.createDisplay(); f.until(() -> f.state.display != null && f.state.canUseDisplay);
            f.engine.bindTarget("frame-task",new String[] {"com.example.reader"},"com.example.reader");
            f.until(() -> f.state.target != null && f.state.canUseDisplay);
            f.engine.captureTarget(); f.until(() -> f.state.frame != null && f.state.canUseDisplay);
            check(f.state.frame.evidence.target.sameIdentity(f.state.target),"frame tied to current target");
            f.engine.observeTarget();
            check(f.state.frame == null,"a new observation clears the displayed old frame");
            f.until(() -> f.state.observation != null && f.state.canUseDisplay);
            f.backend.service.replayFrame = true;
            f.engine.captureTarget(); f.until(() -> f.state.status == Status.ERROR);
            check(f.state.frame == null && f.state.target == null,"replayed frame never becomes current evidence");
        }
    }

    private static void cancelledFrameCaptureNeverPublishes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.engine.createDisplay(); f.until(() -> f.state.display != null && f.state.canUseDisplay);
            f.engine.bindTarget("frame-task",new String[] {"com.example.reader"},"com.example.reader");
            f.until(() -> f.state.target != null && f.state.canUseDisplay);
            f.backend.service.blockFrame = true;
            f.engine.captureTarget(); f.until(() -> f.backend.service.frameStarted.getCount() == 0);
            check(!f.state.canUseDisplay && !f.state.canCheck,"capture shares bounded RPC lane");
            f.engine.disconnect(); f.until(() -> f.backend.service.destroyed.get() > 0);
            f.backend.service.release.countDown(); f.until(() -> f.state.canConnect);
            check(f.state.frame == null && f.state.target == null,"cancelled capture cannot republish image");
        }
    }

    private static void targetBindingObservationAndRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.engine.createDisplay(); f.until(() -> f.state.display != null && f.state.canUseDisplay);
            f.engine.bindTarget("read-task", new String[] {"com.example.reader"}, "com.example.reader");
            f.until(() -> f.state.target != null && f.state.canUseDisplay);
            TargetSession first = f.state.target;
            f.engine.observeTarget(); f.until(() -> f.state.observation != null);
            check(f.state.observation.target.sameIdentity(first), "observation belongs to bound target");
            f.engine.bindTarget("read-task", new String[] {"com.example.notes"}, "com.example.notes");
            check(f.state.observation == null, "rebind immediately removes old evidence");
            f.until(() -> f.state.target != null && f.state.canUseDisplay);
            check(!first.sameIdentity(f.state.target), "new target identity after rebind");
            f.engine.releaseDisplay(); f.until(() -> f.state.display.status == DisplaySession.Status.RELEASED);
            check(f.state.target == null && f.state.observation == null, "release clears target and evidence");
        }
    }

    private static void cancelledTargetReadNeverPublishes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.engine.createDisplay(); f.until(() -> f.state.display != null && f.state.canUseDisplay);
            f.engine.bindTarget("read-task", new String[] {"com.example.reader"}, "com.example.reader");
            f.until(() -> f.state.target != null && f.state.canUseDisplay);
            f.backend.service.blockTarget = true;
            f.engine.observeTarget(); f.until(() -> f.backend.service.targetStarted.getCount() == 0);
            check(!f.state.canUseDisplay && !f.state.canCheck, "target read uses the existing single RPC lane");
            f.time.advance(10000);
            check(f.state.target == null && f.state.observation == null, "timeout invalidates evidence");
            f.backend.service.release.countDown(); f.until(() -> f.state.canConnect);
            check(f.state.observation == null && f.state.status == Status.ERROR,
                    "late read cannot restore timed-out connection");
        }
    }

    private static void replacingOwnerCancelsInFlightDisplayBeforePublishingToNewOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.service.blockDisplay = true;
            f.engine.createDisplay(); f.until(() -> f.backend.service.displayStarted.getCount() == 0);
            long previousOwner = f.owner;
            AtomicInteger inheritedResults = new AtomicInteger();
            f.owner = f.engine.attach(next -> {
                f.receive(next);
                if (next.display != null && next.display.status == DisplaySession.Status.ACTIVE) {
                    inheritedResults.incrementAndGet();
                }
            });
            f.engine.detach(previousOwner);
            f.backend.service.release.countDown();
            f.until(() -> f.state.canConnect || f.state.display != null);
            check(inheritedResults.get() == 0 && f.state.display == null,
                    "replacement owner must never receive the old owner's in-flight display result");
            check(f.backend.service.destroyed.get() > 0, "replacing owner cleans the old display Attempt");
            f.backend.service = new FakeService();
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.engine.detach(previousOwner);
            f.engine.createDisplay(); f.until(() -> f.state.display != null);
            check(f.state.display.status == DisplaySession.Status.ACTIVE,
                    "late detach of the old owner cannot kill the replacement owner's fresh connection");
        }
    }

    private static void displayResultsRespectCancellationAndOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.service.blockDisplay = true;
            f.engine.createDisplay();
            f.until(() -> f.backend.service.displayStarted.getCount() == 0);
            check(!f.state.canUseDisplay && f.state.displayOperation == ConnectionState.DisplayOperation.CREATING,
                    "only bounded operation runs and UI reflects pending create");
            f.engine.disconnect();
            f.until(() -> f.backend.service.destroyed.get() > 0);
            f.backend.service.release.countDown();
            f.until(() -> f.state.canConnect);
            check(f.state.display == null && f.state.status != Status.CONNECTED,
                    "cancelled late create cannot publish usable session");
            f.backend.service = new FakeService();
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            long oldOwner = f.owner; f.owner = f.engine.attach(f::receive);
            f.engine.detach(oldOwner);
            f.engine.createDisplay(); f.until(() -> f.state.display != null);
            check(f.state.display.status == DisplaySession.Status.ACTIVE,
                    "old Activity owner cannot destroy new owner display");
        }
        try (Fixture f = new Fixture()) {
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.service.blockDisplay = true; f.engine.createDisplay();
            f.until(() -> f.backend.service.displayStarted.getCount() == 0);
            f.time.advance(10000);
            f.until(() -> f.backend.service.destroyed.get() > 0);
            check(f.state.reason == Reason.TIMEOUT && f.state.display == null,
                    "display RPC timeout closes same connection generation");
            f.backend.service.release.countDown();
        }
    }

    private static void displayCreateCheckReleaseAndFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.createDisplay(); check(f.backend.service.displayCalls.get() == 0, "disconnected action ignored");
            f.engine.connect(); f.until(() -> f.state.status == Status.CONNECTED);
            f.engine.createDisplay(); f.until(() -> f.state.display != null && f.state.canUseDisplay);
            f.engine.checkDisplay(); f.until(() -> f.backend.service.displayCalls.get() == 2 && f.state.canUseDisplay);
            f.engine.releaseDisplay(); f.until(() -> f.state.display.status == DisplaySession.Status.RELEASED);
            f.engine.createDisplay(); f.until(() -> f.state.display.status == DisplaySession.Status.ACTIVE);
            f.backend.service.wrongDisplayIdentity = true;
            f.engine.checkDisplay(); f.until(() -> f.state.status == Status.ERROR);
            check(f.state.display == null, "mismatched session reply cannot become usable");
        }
    }

    private static void blockedHandshakeDoesNotBlockCleanupOrCreateRetryThreads() throws Exception {
        try (Fixture f = new Fixture()) {
            f.backend.service.block = true;
            f.engine.connect();
            f.until(() -> f.backend.service.started.getCount() == 0);
            f.time.advance(10000);
            f.until(() -> f.backend.removes.get() > 0 && f.backend.detaches.get() > 0);
            check(f.state.status == Status.ERROR, "blocked handshake must expire");
            check(!f.state.canConnect, "in-flight synchronous RPC keeps its bounded slot");
            check(f.state.canCheck, "old RPC cannot hide actionable status and cleanup inspection");
            check(f.backend.service.destroyed.get() == 1, "cleanup closes independently of blocked read");
            for (int i = 0; i < 1000; i++) { f.engine.connect(); f.engine.check(); }
            check(f.backend.inspections.get() == 1, "retry cannot accumulate behind blocked RPC");
            check(f.threads.get() <= 2, "fixed RPC and cleanup lanes bound thread growth");
            f.engine.detach(f.owner);
            int delivered = f.delivered;
            f.backend.service.release.countDown();
            f.until(() -> f.backend.service.returned.getCount() == 0);
            f.time.drain();
            check(f.delivered == delivered, "closed UI receives no late result");
            check(f.state.status != Status.CONNECTED, "late successful response cannot resurrect old generation");
        }
    }

    private static void initialInspectionAndConnectedRecheckBothExpire() throws Exception {
        try (Fixture f = new Fixture()) {
            f.backend.blockInspection = true;
            f.engine.check();
            f.until(() -> f.backend.inspectionStarted.getCount() == 0);
            f.time.advance(10000);
            check(f.state.status == Status.ERROR, "initial inspect has a deadline");
            for (int i = 0; i < 50; i++) f.engine.check();
            check(f.backend.inspections.get() == 1, "blocked inspection owns the sole RPC slot");
            f.backend.inspectionRelease.countDown();
            f.until(() -> f.state.canConnect);
            check(f.state.status != Status.CONNECTED, "late inspection cannot auto-connect");
            check(f.state.reason != Reason.RPC_BUSY, "completed stale RPC must no longer be described as pending");
        }
        try (Fixture f = new Fixture()) {
            f.engine.connect();
            f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.service.block = true;
            f.engine.check();
            f.until(() -> f.backend.service.reads.get() == 2);
            f.time.advance(10000);
            f.until(() -> f.backend.detaches.get() > 0);
            check(f.state.status == Status.ERROR && f.state.capabilities == null,
                    "connected recheck expires and clears old capabilities");
            f.backend.service.release.countDown();
        }
    }

    private static void cancelledBindMustFinishAndClearBothSidesBeforeReconnect() throws Exception {
        try (Fixture f = new Fixture()) {
            f.backend.blockBind = true;
            f.engine.connect();
            f.until(() -> f.backend.bindStarted.getCount() == 0);
            f.engine.disconnect();
            f.until(() -> f.backend.detaches.get() > 0);
            check(f.backend.removes.get() > 0, "cancel without Binder removes remote and local registration");
            check(f.state.status != Status.DISCONNECTED, "in-flight bind is not falsely reported as cleaned");
            f.engine.detach(f.owner);
            f.owner = f.engine.attach(f::receive);
            for (int i = 0; i < 100; i++) { f.engine.check(); f.engine.connect(); }
            check(f.backend.binds.get() == 1, "reopened UI cannot bind same tag before old cleanup completes");
            f.backend.blockBind = false;
            f.backend.bindRelease.countDown();
            f.until(() -> f.backend.detaches.get() >= 2 && f.backend.bindReturned.getCount() == 0
                    && f.state.canConnect);
            int clearBeforeReconnect = f.backend.detaches.get();
            ConnectionCoordinator.Binding old = f.backend.lastBinding;
            f.backend.service = new FakeService();
            f.engine.connect();
            f.until(() -> f.state.status == Status.CONNECTED);
            check(f.backend.binds.get() == 2 && clearBeforeReconnect >= 2,
                    "final post-bind cleanup precedes fresh bind");
            old.connected(new FakeService());
            old.disconnected();
            f.time.drain();
            check(f.state.status == Status.CONNECTED, "late old callbacks cannot disconnect fresh binding");
            check(f.backend.detaches.get() == clearBeforeReconnect, "late old cleanup cannot remove fresh tag");
        }
    }

    private static void authorizationWaitHasNoConnectionDeadline() throws Exception {
        try (Fixture f = new Fixture()) {
            f.backend.authorized = false;
            f.engine.connect();
            f.until(() -> f.backend.requestCode != -1);
            f.time.advance(60000);
            check(f.state.status == Status.AUTHORIZING, "user authorization time is not RPC connection time");
            int oldCode = f.backend.requestCode;
            f.engine.disconnect();
            f.until(() -> f.state.canConnect);
            f.engine.connect();
            f.until(() -> f.backend.requestCode != oldCode);
            f.engine.permission(oldCode, true);
            check(f.backend.binds.get() == 0, "cancelled permission result cannot authorize a later attempt");
            f.backend.authorized = true;
            f.engine.permission(f.backend.requestCode, true);
            f.until(() -> f.state.status == Status.CONNECTED);
        }
    }

    private static void failedCleanupDoesNotClaimDisconnected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect();
            f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.failDetach = true;
            f.engine.disconnect();
            f.until(() -> f.state.status == Status.ERROR && f.backend.detaches.get() > 0);
            check(!f.state.canConnect, "failed detach prevents tag reuse");
            f.backend.failDetach = false;
            f.engine.check();
            f.until(() -> f.state.status == Status.DISCONNECTED && f.state.canConnect);
        }
    }

    private static void cancelledPermissionCannotLeaveAnUnstartedBindReserved() throws Exception {
        try (Fixture f = new Fixture()) {
            f.backend.authorized = false;
            f.backend.blockPermission = true;
            f.engine.connect();
            f.until(() -> f.backend.requestCode != -1);
            f.engine.permission(f.backend.requestCode, true);
            f.engine.disconnect();
            f.until(() -> f.backend.detaches.get() > 0);
            f.backend.permissionRelease.countDown();
            f.until(() -> f.state.status == Status.DISCONNECTED && f.state.canConnect);
            check(f.backend.binds.get() == 0, "cancel removes a deferred bind before it starts");
        }
    }

    private static void blockedCleanupRemainsUnconfirmedAndDoesNotAccumulateRetries() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect();
            f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.blockDetach = true;
            f.engine.disconnect();
            f.until(() -> f.backend.detaches.get() > 0);
            f.time.advance(10000);
            check(f.state.status == Status.ERROR && f.state.reason == Reason.CLEANUP_PENDING,
                    "blocked cleanup must be reported as unconfirmed");
            for (int i = 0; i < 1000; i++) { f.engine.check(); f.engine.connect(); }
            check(f.backend.detaches.get() == 1 && f.backend.binds.get() == 1,
                    "blocked cleanup cannot accumulate retries or permit fresh binds");
            f.backend.detachRelease.countDown();
            f.until(() -> f.state.status == Status.DISCONNECTED && f.state.canConnect);
        }
    }

    private static void repeatedCancellationAfterCleanupTimeoutKeepsAnEffectiveDeadline() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.connect();
            f.until(() -> f.state.status == Status.CONNECTED);
            f.backend.blockDetach = true;
            f.engine.disconnect();
            f.until(() -> f.backend.detaches.get() > 0);
            f.time.advance(10000);
            check(f.state.reason == Reason.CLEANUP_PENDING && f.state.canDisconnect,
                    "initial cleanup timeout permits an explicit repeated cancellation");

            f.engine.disconnect();
            f.time.advance(60000);
            checkUnconfirmed(f.state, "repeated disconnect");

            f.engine.detach(f.owner);
            f.owner = f.engine.attach(f::receive);
            f.time.advance(60000);
            checkUnconfirmed(f.state, "Activity detach and reopen");

            f.engine.managerDied();
            f.time.advance(60000);
            checkUnconfirmed(f.state, "manager death during pending cleanup");
            check(f.backend.detaches.get() == 1 && f.backend.binds.get() == 1,
                    "repeated cancellation does not enqueue extra work behind blocked cleanup");
            f.backend.detachRelease.countDown();
            f.until(() -> f.state.status == Status.NEEDS_SHIZUKU && !f.state.canDisconnect);
        }
    }

    private static void checkUnconfirmed(ConnectionState state, String trigger) {
        check(state.status == Status.ERROR && state.reason == Reason.CLEANUP_PENDING
                        && state.canCheck && state.canDisconnect && !state.canConnect,
                trigger + " must retain an actionable, honest cleanup status");
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger threads = new AtomicInteger();
        final ExecutorService rpc = Executors.newSingleThreadExecutor(r -> thread(r, "test-rpc"));
        final ExecutorService cleanup = Executors.newSingleThreadExecutor(r -> thread(r, "test-cleanup"));
        final ManualTime time = new ManualTime();
        final FakeBackend backend = new FakeBackend();
        final ConnectionCoordinator engine = new ConnectionCoordinator(backend, time, rpc, cleanup);
        ConnectionState state;
        long owner;
        int delivered;

        Fixture() { owner = engine.attach(this::receive); }
        void receive(ConnectionState next) { state = next; delivered++; }
        Thread thread(Runnable runnable, String name) { threads.incrementAndGet(); return new Thread(runnable, name); }
        void until(BooleanSupplier condition) throws Exception { time.until(condition); }

        @Override public void close() {
            engine.detach(owner);
            backend.inspectionRelease.countDown();
            backend.bindRelease.countDown();
            backend.permissionRelease.countDown();
            backend.detachRelease.countDown();
            backend.service.release.countDown();
            time.drain();
            rpc.shutdown();
            cleanup.shutdown();
            try {
                check(rpc.awaitTermination(2, TimeUnit.SECONDS), "test RPC thread terminates after release");
                time.drain();
                check(cleanup.awaitTermination(2, TimeUnit.SECONDS), "test cleanup thread terminates");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private static final class FakeBackend implements ConnectionCoordinator.Backend {
        final AtomicInteger inspections = new AtomicInteger();
        final AtomicInteger binds = new AtomicInteger();
        final AtomicInteger removes = new AtomicInteger();
        final AtomicInteger detaches = new AtomicInteger();
        final CountDownLatch inspectionStarted = new CountDownLatch(1);
        final CountDownLatch inspectionRelease = new CountDownLatch(1);
        final CountDownLatch inspectionReturned = new CountDownLatch(1);
        final CountDownLatch bindStarted = new CountDownLatch(1);
        final CountDownLatch bindRelease = new CountDownLatch(1);
        final CountDownLatch bindReturned = new CountDownLatch(1);
        final CountDownLatch permissionRelease = new CountDownLatch(1);
        final CountDownLatch detachRelease = new CountDownLatch(1);
        volatile boolean blockInspection;
        volatile boolean blockBind;
        volatile boolean failDetach;
        volatile boolean blockPermission;
        volatile boolean blockDetach;
        volatile boolean authorized = true;
        volatile int requestCode = -1;
        volatile FakeService service = new FakeService();
        volatile ConnectionCoordinator.Binding lastBinding;

        @Override public ConnectionState inspect() throws Exception {
            inspections.incrementAndGet();
            inspectionStarted.countDown();
            if (blockInspection) inspectionRelease.await();
            inspectionReturned.countDown();
            return new ConnectionState(authorized ? Status.DISCONNECTED : Status.PERMISSION_REQUIRED,
                    authorized ? Reason.AVAILABLE : Reason.DENIED, null, false);
        }
        @Override public void requestPermission(int code) throws Exception {
            requestCode = code;
            if (blockPermission) permissionRelease.await();
        }
        @Override public void bind(ConnectionCoordinator.Binding binding) throws Exception {
            binds.incrementAndGet();
            lastBinding = binding;
            bindStarted.countDown();
            if (blockBind) bindRelease.await();
            binding.connected(service);
            bindReturned.countDown();
        }
        @Override public void unbind(ConnectionCoordinator.Binding binding, boolean remove) throws Exception {
            if (remove) removes.incrementAndGet();
            else {
                detaches.incrementAndGet();
                if (blockDetach) detachRelease.await();
                if (failDetach) throw new IllegalStateException("controlled local detach failure");
            }
        }
    }

    private static final class FakeService implements ConnectionCoordinator.Service {
        final CountDownLatch frameStarted = new CountDownLatch(1);
        volatile boolean blockFrame,replayFrame;
        long frameSequence;
        @Override public FrameEvidence.Capture readFrame(TargetSession target) throws Exception {
            frameStarted.countDown();
            if (blockFrame) release.await();
            byte[] png = framePng();
            long next = replayFrame ? frameSequence : ++frameSequence;
            return new FrameEvidence.Capture(new FrameEvidence(target,"frame-" + next,next,100,200,
                    "frame-observation",6,720,1280,png.length),png);
        }
        @Override public TargetTap tapTarget(TargetSession target,String observationId,int x,int y) {
            return new TargetTap(target.display.displayId,6,x,y,1000,observationId);
        }
        @Override public TargetText setTextTarget(TargetSession target,String observationId,String ref,String text) {
            return new TargetText(target.display.displayId,6,ref,text.length(),1000,observationId);
        }
        @Override public TargetSwipe swipeTarget(TargetSession target,String observationId,int startX,int startY,
                int endX,int endY,int durationMs) {
            return new TargetSwipe(target.display.displayId,6,startX,startY,endX,endY,durationMs,1000,observationId);
        }
        @Override public TargetBack backTarget(TargetSession target,String observationId) {
            return new TargetBack(target.display.displayId,6,1000,observationId);
        }
        @Override public int moveTargetToDisplay(TargetSession target,int displayId) { return 42; }
        @Override public void startPreviewMirror(DisplaySession display,android.view.Surface surface) { }
        @Override public void stopPreviewMirror(DisplaySession display) { }
        @Override public void userTap(TargetSession target,int x,int y) { }
        @Override public void userSwipe(TargetSession target,int x1,int y1,int x2,int y2,int durationMs) { }
        final CountDownLatch targetStarted = new CountDownLatch(1);
        volatile boolean blockTarget;
        long targetGeneration;
        @Override public TargetSession bindTarget(DisplaySession display, String taskId, String[] allowed, String selected) {
            return new TargetSession(display,taskId,"target-" + (++targetGeneration),targetGeneration,selected,10123,
                    selected + ".MainActivity");
        }
        @Override public TargetObservation readTarget(TargetSession target, boolean launch) throws Exception {
            targetStarted.countDown();
            if (blockTarget) release.await();
            return new TargetObservation(target,"observation",1,100,200,TargetObservation.Status.READY,6,
                    new TargetObservation.Bounds(0,0,720,1280),2,false,java.util.Collections.emptyList());
        }
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        final AtomicInteger displayCalls = new AtomicInteger();
        final CountDownLatch displayStarted = new CountDownLatch(1);
        volatile boolean blockDisplay;
        volatile boolean wrongDisplayIdentity;
        volatile boolean block;
        @Override public DisplaySession createDisplay() throws Exception {
            displayCalls.incrementAndGet(); displayStarted.countDown();
            if (blockDisplay) release.await();
            return display(DisplaySession.Status.ACTIVE);
        }
        @Override public DisplaySession checkDisplay(DisplaySession expected) {
            displayCalls.incrementAndGet(); return display(DisplaySession.Status.ACTIVE);
        }
        @Override public DisplaySession releaseDisplay(DisplaySession expected) {
            displayCalls.incrementAndGet(); return display(DisplaySession.Status.RELEASED);
        }
        private DisplaySession display(DisplaySession.Status status) {
            return new DisplaySession(wrongDisplayIdentity ? "other" : "session-token", 1, 7,
                    "virtual:test", 2000, 720, 1280, 240, 24456, status);
        }
        @Override public Capabilities read() throws Exception {
            reads.incrementAndGet();
            started.countDown();
            if (block) release.await();
            returned.countDown();
            return new Capabilities(1, 36, 2000, 10234, true, true);
        }
        @Override public void destroy() { destroyed.incrementAndGet(); }
    }

    private static byte[] framePng() {
        byte[] png = new byte[33];
        byte[] header = {(byte)137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82};
        System.arraycopy(header,0,png,0,header.length);
        png[18] = 2; png[19] = (byte)208; // 720
        png[22] = 5; // 1280
        png[24] = 8; png[25] = 6;
        return png;
    }

    private static final class ManualTime implements ConnectionCoordinator.Dispatcher {
        final Queue<Runnable> posted = new ArrayDeque<>();
        final List<Timer> timers = new ArrayList<>();
        long now;
        @Override public synchronized void post(Runnable action) { posted.add(action); notifyAll(); }
        @Override public ConnectionCoordinator.Cancellation after(long delay, Runnable action) {
            Timer timer = new Timer(now + delay, action);
            timers.add(timer);
            return () -> timer.cancelled = true;
        }
        void drain() {
            for (;;) {
                Runnable action;
                synchronized (this) { action = posted.poll(); }
                if (action == null) return;
                action.run();
            }
        }
        void advance(long milliseconds) {
            now += milliseconds;
            for (Timer timer : new ArrayList<>(timers)) {
                if (!timer.cancelled && timer.when <= now) { timer.cancelled = true; post(timer.action); }
            }
            drain();
        }
        void until(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!condition.getAsBoolean()) {
                drain();
                if (System.nanoTime() >= deadline) throw new AssertionError("controlled callback did not arrive");
                synchronized (this) { if (posted.isEmpty()) wait(2); }
            }
            drain();
        }
        static final class Timer {
            final long when;
            final Runnable action;
            boolean cancelled;
            Timer(long when, Runnable action) { this.when = when; this.action = action; }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
