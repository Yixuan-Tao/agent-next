package agent.phone.connection;

import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import agent.phone.executor.ProtocolPolicy;
import agent.phone.ipc.IAgentShell;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;

public final class ShizukuBackendTest {
    private ShizukuBackendTest() { }

    public static void main(String[] args) throws Exception {
        coordinatorMustConfirmDeathForKnownAndUnknownDisplayCleanup();
        fixedAidlTransactionIdsRemainStable();
        destroyReleasesKnownDisplayAndReportsUnconfirmedCleanup();
        displayPacketsUseGeneratedAidlAndValidateResponses();
        targetPacketsUseGeneratedAidlAndValidateResponses();
        framePacketsUseGeneratedAidlAndCloseMemory();
        fixedPacketsMatchActualThirteenOneFiveTypes();
        managerCallerAndCapabilitiesAreValidated();
        cancelledRegistrationCannotReturnOrDeleteAFreshConnection();
        callbackAndCancellationCanRunConcurrently();
        remoteFailuresAndUnexpectedResultsRemainFailures();
        System.out.println("ShizukuBackendTest: PASS (actual 13.1.5 AIDL, fixed Bundle mapping, caller UID, callback/cancel races)");
    }

    private static void coordinatorMustConfirmDeathForKnownAndUnknownDisplayCleanup() throws Exception {
        for (boolean unknown : new boolean[] {true, false}) {
            try (CoordinatorFixture f = new CoordinatorFixture()) {
                f.shell.exitOnDestroy = false;
                f.shell.releaseStatus = "CLEANUP_FAILED";
                f.engine.connect(); f.until(() -> f.state.status == ConnectionState.Status.CONNECTED);
                f.shell.blockCreate = unknown;
                f.engine.createDisplay();
                f.until(() -> unknown ? f.shell.createStarted.getCount() == 0 : f.state.display != null);
                f.engine.disconnect();
                f.until(() -> f.shell.destroyed.get() > 0);
                f.shell.createRelease.countDown();
                f.until(() -> f.state.canConnect || f.state.reason == ConnectionState.Reason.CLEANUP_FAILED);
                check(!f.state.canConnect && f.state.display == null,
                        (unknown ? "unknown late create" : "known failed release") + " cannot bypass live Binder cleanup");
                f.engine.check();
                f.until(() -> f.state.status != ConnectionState.Status.DISCONNECTING);
                check(f.state.reason == ConnectionState.Reason.CLEANUP_FAILED && !f.state.canConnect
                                && f.shell.isBinderAlive(),
                        "cleanup retry must recheck live Binder instead of trusting a previous destroy request");
                check(f.shell.destroyed.get() == 1, "oneway close request is idempotent across confirmation retries");
                f.shell.alive = false;
                f.engine.check(); f.until(() -> f.state.canConnect);
                check(f.state.status == ConnectionState.Status.DISCONNECTED,
                        "a fresh Binder death confirmation permits reconnect");
            }
        }
    }

    private static final class CoordinatorFixture implements AutoCloseable {
        final Manager manager = new Manager();
        final ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        final FakeShell shell = new FakeShell();
        final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();
        final ExecutorService rpc = Executors.newSingleThreadExecutor();
        final ExecutorService cleanup = Executors.newSingleThreadExecutor();
        final ConnectionCoordinator engine;
        ConnectionState state;
        CoordinatorFixture() {
            ConnectionCoordinator.Backend wired = new ConnectionCoordinator.Backend() {
                @Override public ConnectionState inspect() {
                    return new ConnectionState(ConnectionState.Status.DISCONNECTED,
                            ConnectionState.Reason.AVAILABLE, null, false);
                }
                @Override public void requestPermission(int code) { }
                @Override public void bind(ConnectionCoordinator.Binding binding) throws Exception {
                    backend.bind(binding); Binder.setTestCallingUid(2000); manager.callback.connected(shell);
                }
                @Override public void unbind(ConnectionCoordinator.Binding binding, boolean remove) throws Exception {
                    backend.unbind(binding, remove);
                }
                @Override public void retire(ConnectionCoordinator.Binding binding) { backend.retire(binding); }
            };
            engine = new ConnectionCoordinator(wired, new ConnectionCoordinator.Dispatcher() {
                @Override public void post(Runnable action) { events.add(action); }
                @Override public ConnectionCoordinator.Cancellation after(long delay, Runnable action) { return () -> { }; }
            }, rpc, cleanup);
            engine.attach(next -> state = next);
        }
        void until(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!done.getAsBoolean()) {
                Runnable event; while ((event = events.poll()) != null) event.run();
                if (System.nanoTime() >= deadline) throw new AssertionError("coordinator adapter callback did not arrive");
                Thread.sleep(1);
            }
            Runnable event; while ((event = events.poll()) != null) event.run();
        }
        @Override public void close() {
            shell.alive = false; shell.createRelease.countDown();
            engine.disconnect(); rpc.shutdown(); cleanup.shutdown();
            try {
                check(rpc.awaitTermination(2, TimeUnit.SECONDS), "coordinator RPC test lane terminates");
                check(cleanup.awaitTermination(2, TimeUnit.SECONDS), "coordinator cleanup test lane terminates");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }

    private static void fixedAidlTransactionIdsRemainStable() throws Exception {
        String[] methods = {"getCapabilities", "createDisplay", "checkDisplay", "releaseDisplay", "launchTarget", "observeTarget", "bindTarget", "captureTarget", "destroy"};
        int[] codes = {1, 2, 3, 4, 5, 6, 7, 8, 16777115};
        for (int i = 0; i < methods.length; i++) {
            java.lang.reflect.Field field = IAgentShell.Stub.class.getDeclaredField("TRANSACTION_" + methods[i]);
            field.setAccessible(true);
            check(field.getInt(null) == codes[i], "actual generated AIDL transaction ID " + methods[i]);
        }
    }

    private static void framePacketsUseGeneratedAidlAndCloseMemory() throws Exception {
        Manager manager = new Manager(); ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding binding = new RecordingBinding(); backend.bind(binding);
        Binder.setTestCallingUid(2000); FakeShell shell = new FakeShell(); manager.callback.connected(shell);
        ConnectionCoordinator.Service remote = binding.service.get(); remote.read();
        DisplaySession display = remote.createDisplay();
        TargetSession target = remote.bindTarget(display,"frame-task",new String[] {"com.example.reader"},"com.example.reader");
        FrameEvidence.Capture frame = remote.readFrame(target);
        check(frame.evidence.target.sameIdentity(target) && frame.png().length == frame.evidence.byteLength,
                "production adapter decodes frame via generated AIDL");
        check(shell.frameMemory.closed && android.os.SharedMemory.mappings == 0,"adapter closes decoded memory and maps");
        check(target.token.equals(shell.lastToken) && target.generation == shell.lastGeneration,"frame uses target credentials");
        TargetTap tap = remote.tapTarget(target,"observation",100,200);
        check(tap.displayId == 7 && tap.windowId == 6 && tap.x == 100 && tap.y == 200
                && "observation".equals(tap.observationId),"production adapter decodes tap receipt");
        check(target.token.equals(shell.lastToken) && target.generation == shell.lastGeneration,"tap uses target credentials");
        TargetText text = remote.setTextTarget(target,"observation","ref:0","hello");
        check(text.displayId == 7 && text.windowId == 6 && "ref:0".equals(text.ref) && text.textLength == 5
                && "observation".equals(text.observationId),"production adapter decodes text receipt");
        check(target.token.equals(shell.lastToken) && target.generation == shell.lastGeneration,"text uses target credentials");
        TargetSwipe swipe = remote.swipeTarget(target,"observation",100,200,300,500,400);
        check(swipe.displayId == 7 && swipe.windowId == 6 && swipe.startX == 100 && swipe.endX == 300
                && swipe.durationMs == 400,"production adapter decodes swipe receipt");
        TargetBack back = remote.backTarget(target,"observation");
        check(back.displayId == 7 && back.windowId == 6 && "observation".equals(back.observationId),
                "production adapter decodes back receipt");
        shell.malformedText = true;
        malformed(() -> remote.setTextTarget(target,"observation","ref:0","hello"));
        shell.malformedTap = true;
        malformed(() -> remote.tapTarget(target,"observation",100,200));
        shell.malformedFrame = true;
        malformed(() -> remote.readFrame(target));
        check(shell.frameMemory.closed && android.os.SharedMemory.mappings == 0,"malformed frame closes memory");
        manager.authorized = false;
        malformed(() -> remote.readFrame(target));
        check(shell.frameCalls == 2,"revoked permission cannot request screenshot");
    }

    private static void destroyReleasesKnownDisplayAndReportsUnconfirmedCleanup() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            Manager manager = new Manager(); ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
            RecordingBinding binding = new RecordingBinding(); backend.bind(binding);
            Binder.setTestCallingUid(2000); FakeShell shell = new FakeShell(); manager.callback.connected(shell);
            ConnectionCoordinator.Service remote = binding.service.get(); remote.read(); remote.createDisplay();
            shell.releaseStatus = fail ? "CLEANUP_FAILED" : "RELEASED";
            shell.exitOnDestroy = !fail;
            if (fail) invalid(remote::destroy); else remote.destroy();
            check(shell.releases.get() == 1 && shell.destroyed.get() == 1,
                    "known display is released before service exit even when cleanup is unconfirmed");
            check(shell.releasedBeforeDestroy, "release precedes oneway destroy");
            if (fail) invalid(remote::destroy); else remote.destroy();
            shell.alive = false;
            remote.destroy();
            check(shell.destroyed.get() == 1, "duplicate cleanup requests remain idempotent while death is rechecked");
        }
    }

    private static void displayPacketsUseGeneratedAidlAndValidateResponses() throws Exception {
        Manager manager = new Manager(); ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding binding = new RecordingBinding(); backend.bind(binding);
        Binder.setTestCallingUid(2000); FakeShell shell = new FakeShell(); manager.callback.connected(shell);
        ConnectionCoordinator.Service remote = binding.service.get(); remote.read();
        DisplaySession created = remote.createDisplay();
        check(created.displayId == 7 && created.status == DisplaySession.Status.ACTIVE,
                "actual generated display AIDL is decoded");
        remote.checkDisplay(created);
        check(created.token.equals(shell.lastToken) && created.generation == shell.lastGeneration,
                "only service-issued token and generation are forwarded");
        check(remote.releaseDisplay(created).status == DisplaySession.Status.RELEASED, "release status decoded");
        shell.display = displayPacket(); shell.display.putInt("display_id", 0);
        malformed(remote::createDisplay);
        shell.display = displayPacket(); shell.display.putInt("generation", 1);
        malformed(remote::createDisplay);
        shell.display = displayPacket(); shell.display.putString("status", "unknown");
        malformed(remote::createDisplay);
        shell.display = displayPacket(); shell.display.putInt("owner_uid", 1000);
        malformed(remote::createDisplay);
        shell.display = displayPacket(); manager.authorized = false;
        malformed(remote::createDisplay);
    }

    private static void targetPacketsUseGeneratedAidlAndValidateResponses() throws Exception {
        Manager manager = new Manager(); ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding binding = new RecordingBinding(); backend.bind(binding);
        Binder.setTestCallingUid(2000); FakeShell shell = new FakeShell(); manager.callback.connected(shell);
        ConnectionCoordinator.Service remote = binding.service.get(); remote.read(); DisplaySession display = remote.createDisplay();
        String[] allowed = {"com.example.first","com.example.second"};
        TargetSession target = remote.bindTarget(display,"task",allowed,"com.example.first");
        check("task".equals(shell.taskId) && Arrays.equals(allowed,shell.allowed) && display.token.equals(shell.lastToken)
                && display.generation == shell.lastGeneration,"authorized task and display reach local AIDL service");
        TargetObservation launched = remote.readTarget(target,true);
        check(launched.target.sameIdentity(target) && shell.launches == 1 && shell.observes == 0,"launch observation decoded");
        check(remote.readTarget(target,false).target.sameIdentity(target) && shell.observes == 1
                && target.token.equals(shell.lastToken) && target.generation == shell.lastGeneration,"target credentials forwarded");
        shell.targetReply = TargetPackets.encode(target); shell.targetReply.putInt("target_uid",2000);
        malformed(() -> remote.bindTarget(display,"task",allowed,"com.example.first"));
        shell.targetReply = TargetPackets.encode(target); shell.targetReply.putString("package_name","com.other.app");
        malformed(() -> remote.bindTarget(display,"task",allowed,"com.example.first"));
        shell.targetReply = TargetPackets.encode(target); shell.targetReply.putString("session_token","old-display");
        malformed(() -> remote.bindTarget(display,"task",allowed,"com.example.first"));
        shell.targetReply = TargetPackets.encode(target); shell.targetReply.putString("arbitrary_intent","forged");
        malformed(() -> remote.bindTarget(display,"task",allowed,"com.example.first"));
        for (String field : new String[]{"target_token","package_name","task_id","session_token"}) {
            shell.observationReply = ObservationPackets.encode(fakeObservation(target));
            ((Bundle) shell.observationReply.get("target")).putString(field,"com.forged.value");
            malformed(() -> remote.readTarget(target,false));
        }
        shell.observationReply = ObservationPackets.encode(fakeObservation(target));
        ((Bundle) shell.observationReply.get("target")).putInt("target_uid",target.uid + 1);
        malformed(() -> remote.readTarget(target,false));
        shell.observationReply = ObservationPackets.encode(fakeObservation(target));
        shell.observationReply.putString("unknown","rejected"); malformed(() -> remote.readTarget(target,false));
        shell.observationReply = null; shell.targetReply = null;
        TargetSession second = remote.bindTarget(display,"task2",allowed,"com.example.second");
        check(!target.sameIdentity(second) && remote.readTarget(second,false).target.packageName.equals("com.example.second"),
                "second authorized app uses its own target identity");
        manager.authorized = false; malformed(() -> remote.readTarget(second,false));
    }

    private static TargetObservation fakeObservation(TargetSession target) {
        return new TargetObservation(target,"observation",1,100,200,TargetObservation.Status.READY,6,
                new TargetObservation.Bounds(0,0,720,1280),2,false,java.util.Collections.emptyList());
    }

    private static Bundle displayPacket() {
        Bundle packet = new Bundle(); packet.putString("session_token", "service-token");
        packet.putLong("generation", 3L); packet.putInt("display_id", 7);
        packet.putString("unique_id", "virtual:test"); packet.putString("status", "ACTIVE");
        packet.putInt("owner_uid", 2000); packet.putInt("width", 720); packet.putInt("height", 1280);
        packet.putInt("density_dpi", 240); packet.putInt("flags", 24456); return packet;
    }

    private static void fixedPacketsMatchActualThirteenOneFiveTypes() throws Exception {
        Bundle add = UserServiceProtocol.add();
        assertAddPacket(add);
        rikka.shizuku.Shizuku.UserServiceArgs reference = new rikka.shizuku.Shizuku.UserServiceArgs(
                new ComponentName("agent.next.app", "agent.phone.executor.ShellService"))
                .tag("agent.next.connection.v12").version(12).processNameSuffix("agent_next_shell")
                .debuggable(false).daemon(false);
        java.lang.reflect.Method forAdd = reference.getClass().getDeclaredMethod("forAdd");
        forAdd.setAccessible(true);
        samePacket((Bundle) forAdd.invoke(reference), add);
        java.lang.reflect.Method forRemove = reference.getClass().getDeclaredMethod("forRemove", boolean.class);
        forRemove.setAccessible(true);
        for (boolean remove : new boolean[] {true, false}) {
            samePacket((Bundle) forRemove.invoke(reference, remove), UserServiceProtocol.remove(remove));
        }
        add.putString("shizuku:user-service-arg-tag", "forged");
        check("agent.next.connection.v12".equals(UserServiceProtocol.add().get("shizuku:user-service-arg-tag")),
                "packets are fresh and cannot mutate subsequent requests");
        for (boolean remove : new boolean[] {true, false}) assertRemovePacket(UserServiceProtocol.remove(remove), remove);
    }

    private static void samePacket(Bundle expected, Bundle actual) {
        check(expected.keySet().equals(actual.keySet()), "packet keys match the actual SDK implementation");
        for (String key : expected.keySet()) {
            Object left = expected.get(key);
            Object right = actual.get(key);
            if (left instanceof ComponentName && right instanceof ComponentName) {
                ComponentName component = (ComponentName) left;
                ComponentName other = (ComponentName) right;
                check(component.getPackageName().equals(other.getPackageName())
                        && component.getClassName().equals(other.getClassName()), "SDK component matches");
            } else {
                check(java.util.Objects.equals(left, right), "SDK field type/value matches: " + key);
            }
        }
    }

    private static void assertAddPacket(Bundle add) {
        Set<String> expected = new HashSet<>(Arrays.asList("shizuku:user-service-arg-component",
                "shizuku:user-service-arg-debuggable", "shizuku:user-service-arg-version-code",
                "shizuku:user-service-arg-daemon", "shizuku:user-service-arg-use-32-bit-app-process",
                "shizuku:user-service-arg-process-name", "shizuku:user-service-arg-tag"));
        check(add.keySet().equals(expected), "add packet must contain exactly the seven SDK 13.1.5 fields");
        ComponentName component = (ComponentName) add.get("shizuku:user-service-arg-component");
        check("agent.next.app".equals(component.getPackageName())
                && "agent.phone.executor.ShellService".equals(component.getClassName()), "fixed service component");
        check(Boolean.FALSE.equals(add.get("shizuku:user-service-arg-debuggable")), "debug false is a Boolean");
        check(Integer.valueOf(12).equals(add.get("shizuku:user-service-arg-version-code")), "version is Integer 12");
        check(Boolean.FALSE.equals(add.get("shizuku:user-service-arg-daemon")), "daemon false is explicit");
        check(Boolean.FALSE.equals(add.get("shizuku:user-service-arg-use-32-bit-app-process")), "32-bit flag false");
        check("agent_next_shell".equals(add.get("shizuku:user-service-arg-process-name")), "fixed process suffix");
        check("agent.next.connection.v12".equals(add.get("shizuku:user-service-arg-tag")), "stable tag");
    }

    private static void assertRemovePacket(Bundle packet, boolean remove) {
        check(packet.keySet().equals(new HashSet<>(Arrays.asList("shizuku:user-service-arg-component",
                "shizuku:user-service-arg-tag", "shizuku:user-service-remove"))), "remove packet fields");
        check(Boolean.valueOf(remove).equals(packet.get("shizuku:user-service-remove")), "remove Boolean value");
    }

    private static void managerCallerAndCapabilitiesAreValidated() throws Exception {
        Manager manager = new Manager();
        ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding binding = new RecordingBinding();
        backend.bind(binding);
        check(manager.adds.get() == 1 && manager.addOptions != null, "actual public addUserService AIDL receives packet");
        assertAddPacket(manager.addOptions);
        FakeShell shell = new FakeShell();
        for (int uid : new int[] {-1, 0, 1000, 10234, 110234}) {
            Binder.setTestCallingUid(uid);
            denied(() -> manager.callback.connected(shell));
            denied(() -> manager.callback.died());
        }
        check(binding.services.get() == 0 && shell.reads.get() == 0, "untrusted callbacks cannot publish or query service");
        Binder.setTestCallingUid(2000);
        invalid(() -> manager.callback.connected(null));
        manager.callback.connected(shell);
        manager.callback.connected(new FakeShell());
        check(binding.services.get() == 1, "one registration publishes one service");
        check(binding.service.get().read().supported, "real generated IAgentShell local binder is parsed by production adapter");
        shell.capabilities.putString(ProtocolPolicy.KEY_SCOPE, "arbitrary");
        malformed(() -> binding.service.get().read());
        shell.capabilities = capabilities();
        shell.capabilities.putInt(ProtocolPolicy.KEY_APP_UID, 10567);
        malformed(() -> binding.service.get().read());
        shell.capabilities = capabilities();
        shell.capabilities.putString(ProtocolPolicy.KEY_SUPPORTED, "true");
        malformed(() -> binding.service.get().read());
        shell.capabilities = capabilities();
        shell.descriptor = "untrusted.interface";
        malformed(() -> binding.service.get().read());
        shell.descriptor = IAgentShell.DESCRIPTOR;
        backend.unbind(binding, true);
        backend.unbind(binding, false);
        backend.retire(binding);
        check(shell.destroyed.get() == 1, "accepted Binder is closed idempotently on cancellation");
        assertRemovePacket(manager.removeOptions[0], true);
        assertRemovePacket(manager.removeOptions[1], false);
        check(manager.removeCallbacks[0] == null && manager.removeCallbacks[1] == manager.callback,
                "remove mirrors SDK null callback, detach uses our actual callback");
    }

    private static void cancelledRegistrationCannotReturnOrDeleteAFreshConnection() throws Exception {
        Manager manager = new Manager();
        manager.blockAdd = true;
        ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding old = new RecordingBinding();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread binder = new Thread(() -> {
            try { backend.bind(old); } catch (Throwable failure) { failed.set(failure); }
        }, "adapter-bind");
        binder.start();
        IShizukuServiceConnection oldCallback;
        try {
            check(manager.addStarted.await(2, TimeUnit.SECONDS), "add RPC reaches controlled wait");
            oldCallback = manager.callback;
            backend.unbind(old, true);
            backend.unbind(old, false);
            Binder.setTestCallingUid(2000);
            oldCallback.connected(new FakeShell());
            check(old.services.get() == 0, "cancel rejects callback while add RPC is still blocked");
        } finally {
            manager.addRelease.countDown();
            binder.join(2000);
        }
        check(!binder.isAlive() && failed.get() == null, "old bind completes without uncaught failure");
        backend.unbind(old, true);
        backend.unbind(old, false);
        backend.retire(old);
        manager.blockAdd = false;
        RecordingBinding fresh = new RecordingBinding();
        backend.bind(fresh);
        manager.callback.connected(new FakeShell());
        int removes = manager.removes.get();
        oldCallback.connected(new FakeShell());
        oldCallback.died();
        check(fresh.services.get() == 1 && fresh.disconnects.get() == 0 && manager.removes.get() == removes,
                "retired callback cannot revive old service or delete fresh stable tag");
        backend.unbind(fresh, true);
        backend.unbind(fresh, false);
        backend.retire(fresh);

        RecordingBinding notStarted = new RecordingBinding();
        int adds = manager.adds.get();
        backend.unbind(notStarted, true);
        backend.unbind(notStarted, false);
        backend.bind(notStarted);
        check(manager.adds.get() == adds, "cancellation before bind registration remains a tombstone until retirement");
        backend.retire(notStarted);
    }

    private static void callbackAndCancellationCanRunConcurrently() throws Exception {
        Manager manager = new Manager();
        ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding binding = new RecordingBinding();
        backend.bind(binding);
        IShizukuServiceConnection callback = manager.callback;
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger afterCancellation = new AtomicInteger();
        binding.afterCancellation = () -> { if (cancelled.get()) afterCancellation.incrementAndGet(); };
        AtomicReference<Throwable> failed = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread callbacks = new Thread(() -> {
            try {
                Binder.setTestCallingUid(2000);
                start.await();
                for (int i = 0; i < 10000; i++) { callback.connected(new FakeShell()); callback.died(); }
            } catch (Throwable failure) { failed.set(failure); }
        }, "adapter-callbacks");
        callbacks.start();
        start.countDown();
        backend.unbind(binding, true);
        backend.unbind(binding, false);
        cancelled.set(true);
        callbacks.join(2000);
        check(!callbacks.isAlive() && failed.get() == null, "production adapter callback/cancel race cannot throw CME");
        check(afterCancellation.get() == 0, "cancelled registration cannot publish callbacks after cancellation returns");
        backend.retire(binding);
    }

    private static void remoteFailuresAndUnexpectedResultsRemainFailures() throws Exception {
        Manager manager = new Manager();
        ShizukuBackend backend = new ShizukuBackend(() -> manager.api);
        RecordingBinding binding = new RecordingBinding();
        manager.uid = 0;
        denied(() -> backend.bind(binding));
        manager.uid = 2000;
        manager.authorized = false;
        denied(() -> backend.bind(binding));
        manager.authorized = true;
        manager.addResult = 7;
        invalid(() -> backend.bind(binding));
        manager.addResult = 0;
        manager.failDetach = true;
        remoteFailure(() -> backend.unbind(binding, false));
        Binder.setTestCallingUid(2000);
        manager.callback.connected(new FakeShell());
        check(binding.services.get() == 0, "failed remote detach still invalidates local callback");
        manager.failDetach = false;
        manager.removeResult = 7;
        invalid(() -> backend.unbind(binding, true));
        manager.removeResult = 1;
        backend.unbind(binding, true);
        backend.unbind(binding, false);
        backend.retire(binding);
    }

    private static final class Manager {
        final AtomicInteger adds = new AtomicInteger();
        final AtomicInteger removes = new AtomicInteger();
        final CountDownLatch addStarted = new CountDownLatch(1);
        final CountDownLatch addRelease = new CountDownLatch(1);
        final Bundle[] removeOptions = new Bundle[128];
        final IShizukuServiceConnection[] removeCallbacks = new IShizukuServiceConnection[128];
        volatile IShizukuServiceConnection callback;
        volatile Bundle addOptions;
        volatile int uid = 2000;
        volatile int addResult;
        volatile int removeResult;
        volatile boolean authorized = true;
        volatile boolean failDetach;
        volatile boolean blockAdd;
        final IShizukuService api = (IShizukuService) Proxy.newProxyInstance(
                IShizukuService.class.getClassLoader(), new Class<?>[] {IShizukuService.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getUid": return uid;
                        case "checkSelfPermission": return authorized;
                        case "addUserService":
                            adds.incrementAndGet();
                            callback = (IShizukuServiceConnection) arguments[0];
                            addOptions = (Bundle) arguments[1];
                            addStarted.countDown();
                            if (blockAdd) addRelease.await();
                            return addResult;
                        case "removeUserService":
                            int index = removes.getAndIncrement();
                            removeCallbacks[index] = (IShizukuServiceConnection) arguments[0];
                            removeOptions[index] = (Bundle) arguments[1];
                            if (failDetach && !removeOptions[index].getBoolean("shizuku:user-service-remove")) {
                                throw new RemoteException("controlled manager detach failure");
                            }
                            return removeResult;
                        default: throw new AssertionError("unexpected manager API: " + method.getName());
                    }
                });
    }

    private static final class RecordingBinding implements ConnectionCoordinator.Binding {
        final AtomicReference<ConnectionCoordinator.Service> service = new AtomicReference<>();
        final AtomicInteger services = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        volatile Runnable afterCancellation = () -> { };
        @Override public void connected(ConnectionCoordinator.Service value) { afterCancellation.run(); service.set(value); services.incrementAndGet(); }
        @Override public void disconnected() { afterCancellation.run(); disconnects.incrementAndGet(); }
    }

    private static final class FakeShell extends IAgentShell.Stub {
        android.os.SharedMemory frameMemory;
        boolean malformedFrame;
        boolean malformedTap;
        boolean malformedText;
        int frameCalls;
        @Override public Bundle tapTarget(String token,long generation,String observationId,int x,int y) {
            lastToken = token; lastGeneration = generation;
            Bundle packet = TargetTapPackets.encode(new TargetTap(7,6,x,y,1000,observationId));
            if (malformedTap) packet.putString("unexpected","value");
            return packet;
        }
        @Override public Bundle setTextTarget(String token,long generation,String observationId,String ref,String text) {
            lastToken = token; lastGeneration = generation;
            Bundle packet = TargetTextPackets.encode(new TargetText(7,6,ref,text.length(),1000,observationId));
            if (malformedText) packet.putString("unexpected","value");
            return packet;
        }
        @Override public Bundle swipeTarget(String token,long generation,String observationId,int startX,int startY,
                int endX,int endY,int durationMs) {
            lastToken = token; lastGeneration = generation;
            return TargetSwipePackets.encode(new TargetSwipe(7,6,startX,startY,endX,endY,durationMs,1000,observationId));
        }
        @Override public Bundle backTarget(String token,long generation,String observationId) {
            lastToken = token; lastGeneration = generation;
            return TargetBackPackets.encode(new TargetBack(7,6,1000,observationId));
        }
        @Override public Bundle startPreviewMirror(String token,long generation,android.view.Surface surface) {
            lastToken = token; lastGeneration = generation;
            Bundle packet = new Bundle();
            packet.putBoolean("ok",true);
            packet.putInt("display_id",7);
            return packet;
        }
        @Override public void stopPreviewMirror(String token,long generation) {
            lastToken = token; lastGeneration = generation;
        }
        @Override public void userTap(String token,long generation,int x,int y) {
            lastToken = token; lastGeneration = generation;
        }
        @Override public void userSwipe(String token,long generation,int x1,int y1,int x2,int y2,int durationMs) {
            lastToken = token; lastGeneration = generation;
        }
        @Override public Bundle moveTargetToDisplay(String token,long generation,int displayId) {
            lastToken = token; lastGeneration = generation;
            Bundle packet = new Bundle();
            packet.putBoolean("ok",true);
            packet.putInt("root_task_id",42);
            packet.putInt("display_id",displayId);
            return packet;
        }
        @Override public Bundle captureTarget(String token,long generation) {
            lastToken = token; lastGeneration = generation; frameCalls++;
            try {
                byte[] png = new byte[33];
                byte[] header = {(byte)137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82};
                System.arraycopy(header,0,png,0,header.length);
                png[18]=2; png[19]=(byte)208; png[22]=5; png[24]=8; png[25]=6;
                frameMemory = android.os.SharedMemory.create("test-frame",png.length);
                java.nio.ByteBuffer write = frameMemory.mapReadWrite();
                try { write.put(png); } finally { android.os.SharedMemory.unmap(write); }
                check(frameMemory.setProtect(android.system.OsConstants.PROT_READ),"seal fixture");
                Bundle packet = FramePackets.encode(new FrameEvidence(target,"frame",1,100,200,
                        "frame-observation",6,720,1280,png.length),frameMemory);
                if (malformedFrame) packet.putString("unexpected","value");
                return packet;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        volatile Bundle capabilities = capabilities();
        volatile Bundle display = displayPacket();
        String lastToken; long lastGeneration;
        final AtomicInteger releases = new AtomicInteger();
        String releaseStatus = "RELEASED";
        boolean releasedBeforeDestroy;
        final CountDownLatch createStarted = new CountDownLatch(1);
        final CountDownLatch createRelease = new CountDownLatch(1);
        volatile boolean blockCreate;
        volatile boolean alive = true;
        volatile boolean exitOnDestroy = true;
        @Override public boolean isBinderAlive() { return alive; }
        @Override public boolean pingBinder() { return alive; }
        @Override public Bundle createDisplay() {
            createStarted.countDown();
            try { if (blockCreate) createRelease.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return display;
        }
        @Override public Bundle checkDisplay(String token, long generation) {
            lastToken = token; lastGeneration = generation; return display;
        }
        @Override public Bundle releaseDisplay(String token, long generation) {
            lastToken = token; lastGeneration = generation; releases.incrementAndGet();
            Bundle released = displayPacket(); released.putString("status", releaseStatus); return released;
        }
        Bundle targetReply,observationReply;
        TargetSession target;
        String taskId; String[] allowed; int launches,observes;
        @Override public Bundle bindTarget(String requestedTaskId,String displayToken,long displayGeneration,
                String[] allowedPackages,String selectedPackage) {
            taskId = requestedTaskId; allowed = allowedPackages.clone(); lastToken = displayToken; lastGeneration = displayGeneration;
            if (targetReply != null) return targetReply;
            DisplaySession owned = new DisplaySession(displayToken,displayGeneration,7,"virtual:test",2000,720,1280,240,
                    24456,DisplaySession.Status.ACTIVE);
            target = new TargetSession(owned,taskId,"target-" + taskId,4,selectedPackage,10123,selectedPackage + ".Main");
            return TargetPackets.encode(target);
        }
        @Override public Bundle launchTarget(String token,long generation) {
            lastToken = token; lastGeneration = generation; launches++;
            return observationReply == null ? ObservationPackets.encode(fakeObservation(target)) : observationReply;
        }
        @Override public Bundle observeTarget(String token,long generation) {
            lastToken = token; lastGeneration = generation; observes++;
            return observationReply == null ? ObservationPackets.encode(fakeObservation(target)) : observationReply;
        }
        volatile String descriptor = IAgentShell.DESCRIPTOR;
        @Override public String getInterfaceDescriptor() { return descriptor; }
        @Override public Bundle getCapabilities(int protocol) { reads.incrementAndGet(); return capabilities; }
        @Override public void destroy() {
            releasedBeforeDestroy = releases.get() > 0; destroyed.incrementAndGet();
            if (exitOnDestroy) alive = false;
        }
    }

    private static Bundle capabilities() {
        Bundle bundle = new Bundle();
        bundle.putInt(ProtocolPolicy.KEY_PROTOCOL, ProtocolPolicy.VERSION);
        bundle.putInt(ProtocolPolicy.KEY_API, 36);
        bundle.putInt(ProtocolPolicy.KEY_SERVICE_UID, 2000);
        bundle.putInt(ProtocolPolicy.KEY_APP_UID, 10234);
        bundle.putBoolean(ProtocolPolicy.KEY_SUPPORTED, true);
        bundle.putString(ProtocolPolicy.KEY_SCOPE, ProtocolPolicy.SCOPE);
        return bundle;
    }

    private interface Call { void run() throws Exception; }
    private static void denied(Call call) throws Exception {
        try { call.run(); throw new AssertionError("expected SecurityException"); }
        catch (SecurityException expected) { }
    }
    private static void invalid(Call call) throws Exception {
        try { call.run(); throw new AssertionError("expected IllegalStateException/IllegalArgumentException"); }
        catch (IllegalStateException | IllegalArgumentException expected) { }
    }
    private static void malformed(Call call) throws Exception {
        try { call.run(); throw new AssertionError("expected malformed handshake failure"); }
        catch (ConnectionCoordinator.Failure expected) { check(expected.reason == ConnectionState.Reason.MALFORMED, "malformed reason"); }
    }
    private static void remoteFailure(Call call) throws Exception {
        try { call.run(); throw new AssertionError("expected RemoteException"); }
        catch (RemoteException expected) { }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
