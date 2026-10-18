package agent.phone.executor;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SharedMemory;
import android.system.OsConstants;
import android.util.Log;
import agent.phone.ipc.IAgentShell;
import agent.phone.connection.ObservationPackets;
import agent.phone.connection.TargetPackets;
import agent.phone.connection.FrameEvidence;
import agent.phone.connection.FramePackets;
import agent.phone.connection.TargetTap;
import agent.phone.connection.TargetSwipe;
import agent.phone.connection.TargetBack;
import agent.phone.connection.TargetSwipe;
import agent.phone.connection.TargetBack;
import agent.phone.connection.TargetText;
import agent.phone.connection.TargetTapPackets;
import agent.phone.connection.TargetSwipePackets;
import agent.phone.connection.TargetBackPackets;
import agent.phone.connection.TargetTextPackets;
import java.nio.ByteBuffer;

public final class ShellService extends IAgentShell.Stub {
    private final android.content.Context context;
    private final PreviewMirror mirror = new PreviewMirror();
    private final int installedAppUid;
    private volatile boolean destroyed;
    private final DisplayLifecycle displays = new DisplayLifecycle(new AndroidDisplayResources());
    private final FrameLease<SharedMemory> frames = new FrameLease<>();

    private final TargetLifecycle targets = new TargetLifecycle(this::currentDisplay,
            new AndroidTargetObserver(), new AndroidInputDispatcher());

    public ShellService() {
        this((android.content.Context) null);
    }

    public ShellService(android.content.Context context) {
        this.context = context;
        installedAppUid = InstalledAppIdentity.resolveUid();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (ShellService.this) {
                try { mirror.stop(); frames.close(); targets.close(); displays.close(); } catch (Exception unconfirmed) { }
            }
        }, "agent-display-exit"));
    }



    @Override
    public synchronized Bundle getCapabilities(int clientProtocolVersion) {
        int caller = Binder.getCallingUid();
        ClientPolicy.requireAppCaller(caller, installedAppUid);
        ProtocolPolicy.requireProtocol(clientProtocolVersion);
        long identity = Binder.clearCallingIdentity();
        try {
            if (destroyed || InstalledAppIdentity.resolveUid() != installedAppUid) {
                throw new SecurityException("Service identity is no longer valid");
            }
            Bundle capabilities = new Bundle();
            capabilities.putInt(ProtocolPolicy.KEY_PROTOCOL, ProtocolPolicy.VERSION);
            capabilities.putInt(ProtocolPolicy.KEY_API, Build.VERSION.SDK_INT);
            capabilities.putInt(ProtocolPolicy.KEY_SERVICE_UID, Process.myUid());
            capabilities.putInt(ProtocolPolicy.KEY_APP_UID, installedAppUid);
            capabilities.putBoolean(ProtocolPolicy.KEY_SUPPORTED,
                    ProtocolPolicy.isSupported(Build.VERSION.SDK_INT, Process.myUid()));
            capabilities.putString(ProtocolPolicy.KEY_SCOPE, ProtocolPolicy.SCOPE);
            return capabilities;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized Bundle createDisplay() { return displayCall(null, 0, 0); }
    @Override public synchronized Bundle checkDisplay(String token, long generation) { return displayCall(token, generation, 1); }
    @Override public synchronized Bundle releaseDisplay(String token, long generation) { return displayCall(token, generation, 2); }

    private Bundle displayCall(String token, long generation, int operation) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(), installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            if (destroyed || InstalledAppIdentity.resolveUid() != installedAppUid
                    || !ProtocolPolicy.isSupported(Build.VERSION.SDK_INT, Process.myUid())) {
                throw new SecurityException("Display service identity or environment is no longer valid");
            }
            DisplayLifecycle.Session session;
            try {
                if (operation == 2) { displays.check(token,generation); frames.close(); targets.close(); }
                session = operation == 0 ? displays.create() : operation == 1
                        ? displays.check(token, generation) : displays.release(token, generation);
            } catch (SecurityException denied) { throw denied; }
            catch (Exception failure) {
                String stage = operation == 0 ? "create" : operation == 1 ? "check" : "release";
                Log.e("AgentShell", "Display operation failed: " + stage, failure);
                throw new IllegalStateException("Display operation failed; disconnect to clean up", failure);
            }
            return displayPacket(session);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private DisplayLifecycle.Session currentDisplay(String token, long generation) throws Exception {
        requireEnvironment();
        return displays.check(token, generation);
    }

    private void requireEnvironment() {
        if (destroyed || InstalledAppIdentity.resolveUid() != installedAppUid
                || !ProtocolPolicy.isSupported(Build.VERSION.SDK_INT, Process.myUid())) {
            throw new SecurityException("Service identity or environment is no longer valid");
        }
    }

    @Override public synchronized Bundle launchTarget(String token, long generation) { return targetCall(token,generation,true); }
    @Override public synchronized Bundle observeTarget(String token, long generation) { return targetCall(token,generation,false); }
    @Override public synchronized Bundle bindTarget(String taskId,String displayToken,long displayGeneration,
            String[] allowedPackages,String selectedPackage) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            frames.close();
            Bundle packet = TargetPackets.encode(targets.bind(taskId,displayToken,displayGeneration,allowedPackages,selectedPackage));
            requireEnvironment();
            return packet;
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Target binding failed; disconnect to clean up"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public synchronized Bundle captureTarget(String token,long generation) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        SharedMemory memory = null;
        ByteBuffer mapping = null;
        try {
            requireEnvironment(); frames.close();
            FrameEvidence.Capture captured = targets.capture(token,generation);
            requireEnvironment();
            memory = SharedMemory.create("agent-authorized-frame",captured.evidence.byteLength);
            mapping = memory.mapReadWrite(); mapping.put(captured.png());
            SharedMemory.unmap(mapping); mapping = null;
            if (!memory.setProtect(OsConstants.PROT_READ)) throw new IllegalStateException("Frame memory could not be sealed");
            Bundle packet = FramePackets.encode(captured.evidence,memory);
            requireEnvironment();
            // Binder marshals the returned Bundle after this method returns. Retain one sender FD until the next frame/cleanup.
            frames.replace(memory); memory = null;
            return packet;
        } catch (SecurityException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Frame capture failed; disconnect to clean up"); }
        finally {
            if (mapping != null) SharedMemory.unmap(mapping);
            if (memory != null) memory.close();
            Binder.restoreCallingIdentity(identity);
        }
    }
    @Override public synchronized Bundle tapTarget(String token,long generation,String observationId,int x,int y) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            TargetTap tap = targets.tap(token,generation,observationId,x,y);
            // The pre-action frame is no longer an honest view of the screen.
            frames.close();
            requireEnvironment();
            return TargetTapPackets.encode(tap);
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Target action failed; disconnect to clean up"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public synchronized Bundle setTextTarget(String token,long generation,String observationId,String ref,String text) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            TargetText receipt = targets.setText(token,generation,observationId,ref,text);
            // The pre-action frame is no longer an honest view of the screen.
            frames.close();
            requireEnvironment();
            return TargetTextPackets.encode(receipt);
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Target action failed; disconnect to clean up"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized Bundle swipeTarget(String token,long generation,String observationId,
            int startX,int startY,int endX,int endY,int durationMs) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            TargetSwipe swipe = targets.swipe(token,generation,observationId,startX,startY,endX,endY,durationMs);
            frames.close();
            requireEnvironment();
            return TargetSwipePackets.encode(swipe);
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Target action failed; disconnect to clean up"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized Bundle backTarget(String token,long generation,String observationId) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            TargetBack back = targets.back(token,generation,observationId);
            frames.close();
            requireEnvironment();
            return TargetBackPackets.encode(back);
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Target action failed; disconnect to clean up"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void userTap(String token,long generation,int x,int y) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try { requireEnvironment(); targets.userTap(token,generation,x,y); }
        catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("User tap failed"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void userSwipe(String token,long generation,int x1,int y1,int x2,int y2,int durationMs) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try { requireEnvironment(); targets.userSwipe(token,generation,x1,y1,x2,y2,durationMs); }
        catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("User swipe failed"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized Bundle startPreviewMirror(String token,long generation,android.view.Surface surface) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            DisplayLifecycle.Session current = displays.check(token,generation);
            if (current.status != DisplayLifecycle.Status.ACTIVE || current.info == null) {
                throw new IllegalStateException("Display session is not active");
            }
            mirror.start(context,current.info.displayId,current.info.width,current.info.height,current.info.densityDpi,surface);
            Bundle packet = new Bundle();
            packet.putBoolean("ok",true);
            packet.putInt("display_id",current.info.displayId);
            return packet;
        } catch (SecurityException | IllegalArgumentException denied) {
            Log.w("AgentShellService", "preview mirror rejected", denied);
            throw denied;
        }
        catch (Exception failure) {
            Log.w("AgentShellService", "preview mirror failed", failure);
            throw new IllegalStateException("Preview mirror failed");
        }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void stopPreviewMirror(String token,long generation) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            displays.check(token,generation);
            mirror.stop();
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Preview mirror stop failed"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized Bundle moveTargetToDisplay(String token,long generation,int displayId) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            int root = targets.moveToDisplay(token,generation,displayId);
            Bundle packet = new Bundle();
            packet.putBoolean("ok",true);
            packet.putInt("root_task_id",root);
            packet.putInt("display_id",displayId);
            requireEnvironment();
            return packet;
        } catch (SecurityException | IllegalArgumentException denied) { throw denied; }
        catch (Exception failure) { throw new IllegalStateException("Target move failed; disconnect to clean up"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    private Bundle targetCall(String token,long generation,boolean launch) {
        ClientPolicy.requireAppCaller(Binder.getCallingUid(),installedAppUid);
        long identity = Binder.clearCallingIdentity();
        try {
            requireEnvironment();
            Bundle result = ObservationPackets.encode(targets.run(token,generation,launch));
            requireEnvironment();
            return result;
        } catch (SecurityException denied) { throw denied; }
        catch (Exception failure) {
            // No remote exception messages or observed content are written to Logcat.
            throw new IllegalStateException("Target operation failed; disconnect to clean up");
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static Bundle displayPacket(DisplayLifecycle.Session session) {
        Bundle packet = new Bundle();
        packet.putString("session_token", session.token);
        packet.putLong("generation", session.generation);
        packet.putString("status", session.status.name());
        DisplayLifecycle.Info info = session.info;
        if (info == null) throw new IllegalStateException("Display session has no validated metadata");
        packet.putInt("display_id", info.displayId);
        packet.putString("unique_id", info.uniqueId);
        packet.putInt("owner_uid", info.ownerUid);
        packet.putInt("width", info.width);
        packet.putInt("height", info.height);
        packet.putInt("density_dpi", info.densityDpi);
        packet.putInt("flags", info.flags);
        return packet;
    }

    @Override
    public void destroy() {
        int caller = Binder.getCallingUid();
        synchronized (this) {
            int currentAppUid = (caller == 2000 || caller == 0) ? -1 : InstalledAppIdentity.resolveUid();
            ClientPolicy.requireCloseCaller(caller,currentAppUid);
            long identity = Binder.clearCallingIdentity();
            try {
                destroyed = true;
                try { frames.close(); targets.close(); displays.close(); } catch (Exception unconfirmed) { /* Process death owns final cleanup. */ }
            } finally { Binder.restoreCallingIdentity(identity); }
        }
        // Shutdown hooks take this same monitor, so exit must happen after releasing it.
        System.exit(0);
    }
}
