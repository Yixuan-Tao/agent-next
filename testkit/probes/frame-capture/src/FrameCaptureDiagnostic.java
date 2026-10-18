package agent.phone.executor;

import agent.phone.connection.DisplaySession;
import agent.phone.connection.FrameEvidence;
import agent.phone.connection.TargetObservation;
import agent.phone.connection.TargetSession;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import java.io.FileOutputStream;
import java.util.UUID;

/** Temporary read-only probe: capture one authorized JD frame and report the failing check. */
public final class FrameCaptureDiagnostic {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        try { Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null); }
        catch (Exception failure) { failure.printStackTrace(System.out); System.exit(1); }
        new Thread(() -> runProbe(), "frame-diagnostic").start();
        Looper.loop();
    }

    private static void runProbe() {
        DisplayLifecycle display = new DisplayLifecycle(new AndroidDisplayResources());
        TargetLifecycle.Observer observer = null;
        try {
            DisplayLifecycle.Session session = display.create();
            DisplayLifecycle.Info info = session.info;
            DisplaySession d = new DisplaySession(session.token, session.generation, info.displayId,
                    info.uniqueId, info.ownerUid, info.width, info.height, info.densityDpi, info.flags,
                    DisplaySession.Status.ACTIVE);
            System.out.println("displayCreated=" + info.displayId + " " + info.width + "x" + info.height);
            AndroidTargetObserver platform = new AndroidTargetObserver();
            TargetLifecycle.Identity identity = platform.resolve("com.jingdong.app.mall");
            TargetSession target = new TargetSession(d, "diagnostic-frame", UUID.randomUUID().toString(),
                    1, "com.jingdong.app.mall", identity.uid, identity.launcherClass);
            observer = platform.allocate(target);
            observer.open();
            System.out.println("observerOpen=PASS");
            platform.launch(target);
            Thread.sleep(3000);

            long now = SystemClock.elapsedRealtimeNanos();
            TargetObservation observation = observer.read(UUID.randomUUID().toString(), 1, now,
                    now + 8_000_000_000L, true);
            System.out.println("observe status=" + observation.status + " nodes=" + observation.nodes.size()
                    + " truncated=" + observation.truncated);

            Thread.sleep(2000);
            saveRaw(observer);
            for (int attempt = 0; attempt < 1; attempt++) {
                Thread.sleep(1000);
                long started = SystemClock.elapsedRealtimeNanos();
                String frameId = UUID.randomUUID().toString();
                String observationId = UUID.randomUUID().toString();
                try {
                    FrameEvidence.Capture capture = observer.capture(frameId, attempt + 2, started,
                            started + 8_000_000_000L, observationId);
                    System.out.println("capture=PASS attempt=" + attempt + " bytes=" + capture.png().length
                            + " sequence=" + capture.evidence.sequence
                            + " window=" + capture.evidence.focusedWindowId
                            + " " + capture.evidence.width + "x" + capture.evidence.height);
                    break;
                } catch (Throwable failure) {
                    System.out.println("capture=FAIL attempt=" + attempt + " " + failure);
                    try { dumpWindows(observer); } catch (Throwable dump) { dump.printStackTrace(System.out); }
                }
            }
        } catch (Throwable failure) {
            System.out.println("probe=FAIL");
            failure.printStackTrace(System.out);
        } finally {
            try { if (observer != null) { observer.close(); System.out.println("observerClose=PASS"); } }
            catch (Throwable failure) { System.out.println("observerClose=FAIL"); failure.printStackTrace(System.out); }
            try { display.close(); System.out.println("displayClose=PASS"); }
            catch (Throwable failure) { System.out.println("displayClose=FAIL"); failure.printStackTrace(System.out); }
            System.exit(0);
        }
    }

    private static void saveRaw(TargetLifecycle.Observer observer) throws Exception {
        java.lang.reflect.Field field = observer.getClass().getDeclaredField("automation");
        field.setAccessible(true);
        android.app.UiAutomation automation = (android.app.UiAutomation) field.get(observer);
        Bitmap bitmap = automation.takeScreenshot();
        if (bitmap == null) { System.out.println("rawScreenshot=null"); return; }
        try (FileOutputStream out = new FileOutputStream("/data/local/tmp/frame-raw.png")) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        System.out.println("rawScreenshot=" + bitmap.getWidth() + "x" + bitmap.getHeight());
        bitmap.recycle();
    }

    private static void dumpWindows(TargetLifecycle.Observer observer) throws Exception {
        java.lang.reflect.Field field = observer.getClass().getDeclaredField("automation");
        field.setAccessible(true);
        android.app.UiAutomation automation = (android.app.UiAutomation) field.get(observer);
        automation.clearCache();
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = automation.getWindows();
        System.out.println("windows=" + (windows == null ? "null" : windows.size()));
        if (windows == null) return;
        for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            window.getBoundsInScreen(bounds);
            android.view.accessibility.AccessibilityNodeInfo root = window.getRoot(0);
            String pkg = root == null ? null : String.valueOf(root.getPackageName());
            android.view.accessibility.AccessibilityNodeInfo alt = window.getRoot();
            StringBuilder roots = new StringBuilder();
            for (int connection = 0; connection <= 8; connection++) {
                try {
                    android.view.accessibility.AccessibilityNodeInfo candidate = window.getRoot(connection);
                    if (candidate != null) {
                        roots.append(" c").append(connection).append("=").append(candidate.getPackageName());
                        candidate.recycle();
                    }
                } catch (Throwable ignored) { }
            }
            android.view.accessibility.AccessibilityNodeInfo anchor = window.getAnchor();
            System.out.println("window id=" + window.getId() + " root=" + (root == null ? -1 : root.getWindowId())
                    + " altRoot=" + (alt == null ? -1 : alt.getWindowId())
                    + " roots=[" + roots + " ] anchor=" + (anchor == null ? "null"
                        : anchor.getPackageName() + "@" + anchor.getWindowId())
                    + " title=" + window.getTitle() + " display=" + window.getDisplayId() + " type=" + window.getType()
                    + " layer=" + window.getLayer() + " focused=" + window.isFocused() + " active=" + window.isActive()
                    + " bounds=" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom
                    + " pkg=" + pkg + " rootVisible=" + (root != null && root.isVisibleToUser()));
            if (anchor != null) anchor.recycle();
            if (alt != null) alt.recycle();
            if (root != null) root.recycle();
            window.recycle();
        }
    }
}
