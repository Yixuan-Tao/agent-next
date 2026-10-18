package agent.phone.executor;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import java.lang.reflect.Constructor;

/** API 36 shell adapter. Hidden names are pinned to the Android 16 sources in docs/device-capability.md. */
final class AndroidDisplayResources implements DisplayLifecycle.Factory {
    @Override public DisplayLifecycle.Resource allocate() { return new Resource(); }

    @Override public int expectedFlags() throws ReflectiveOperationException {
        return flags(Display.class, "FLAG_", "PRESENTATION", "TRUSTED", "OWN_DISPLAY_GROUP",
                "ALWAYS_UNLOCKED", "TOUCH_FEEDBACK_DISABLED", "OWN_FOCUS",
                "STEAL_TOP_FOCUS_DISABLED", "ROTATES_WITH_CONTENT");
    }

    static int creationFlags() throws ReflectiveOperationException {
        return flags(DisplayManager.class, "VIRTUAL_DISPLAY_FLAG_", "PUBLIC", "PRESENTATION",
                "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "ROTATES_WITH_CONTENT",
                "DESTROY_CONTENT_ON_REMOVAL", "TRUSTED", "OWN_DISPLAY_GROUP", "ALWAYS_UNLOCKED",
                "TOUCH_FEEDBACK_DISABLED", "OWN_FOCUS", "DEVICE_DISPLAY_GROUP", "STEAL_TOP_FOCUS_DISABLED");
    }

    private static int flags(Class<?> type, String prefix, String... names) throws ReflectiveOperationException {
        int value = 0;
        for (String name : names) value |= type.getField(prefix + name).getInt(null);
        return value;
    }

    private static Object systemService(String name, String interfaceName) throws ReflectiveOperationException {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("Required Android service is unavailable");
        return Class.forName(interfaceName + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static void requirePrimaryFocus() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.app.IActivityTaskManager");
        Object root = type.getMethod("getFocusedRootTaskInfo")
                .invoke(systemService("activity_task", type.getName()));
        // Only displayId is inspected; no task, package, activity or user content is retained.
        if (root == null || root.getClass().getField("displayId").getInt(root) != Display.DEFAULT_DISPLAY) {
            throw new IllegalStateException("Primary display focus could not be confirmed");
        }
    }

    static Context shellContext() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null || Looper.getMainLooper() == null) {
            throw new IllegalStateException("Shizuku UserService runtime is not initialized");
        }
        Context base = (Context) type.getMethod("getSystemContext").invoke(thread);
        return new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
    }

    private static final class Resource implements DisplayLifecycle.Resource {
        private VirtualDisplay display;
        private ImageReader reader;
        private Surface surface;
        private int displayId = -1;
        private String uniqueId;

        @Override public void open() throws Exception {
            requirePrimaryFocus();
            Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            DisplayManager manager = constructor.newInstance(shellContext());
            reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(this::drain, new Handler(Looper.getMainLooper()));
            surface = reader.getSurface();
            display = manager.createVirtualDisplay("agent-phone", 720, 1280, 240, surface, creationFlags());
            if (display == null) throw new IllegalStateException("DisplayManager did not create a display");
            displayId = display.getDisplay().getDisplayId();
            if (displayId <= 0) throw new IllegalStateException("Independent display must not be display 0");
            Object info = rawInfo();
            if (info == null) throw new IllegalStateException("Created display cannot be inspected");
            uniqueId = (String) info.getClass().getField("uniqueId").get(info);
            Class<?> window = Class.forName("android.view.IWindowManager");
            Object windowService = systemService("window", window.getName());
            int hide = Class.forName("android.view.WindowManager").getField("DISPLAY_IME_POLICY_HIDE").getInt(null);
            window.getMethod("setDisplayImePolicy", int.class, int.class).invoke(windowService, displayId, hide);
            if ((Integer) window.getMethod("getDisplayImePolicy", int.class).invoke(windowService, displayId) != hide) {
                throw new IllegalStateException("Independent display IME policy did not apply");
            }
            requirePrimaryFocus();
        }

        private synchronized void drain(ImageReader source) {
            if (reader != source) return;
            try (Image image = source.acquireLatestImage()) {
                // Drain only. No buffer, pixel, screenshot or metadata is read or exported.
            } catch (IllegalStateException closed) { }
        }

        private Object rawInfo() throws ReflectiveOperationException {
            // Direct Binder read avoids DisplayManagerGlobal's asynchronous property cache on release.
            Class<?> type = Class.forName("android.hardware.display.IDisplayManager");
            return type.getMethod("getDisplayInfo", int.class)
                    .invoke(systemService("display", type.getName()), displayId);
        }

        @Override public DisplayLifecycle.Info inspect() throws Exception {
            if (displayId <= 0) return null;
            Object info = rawInfo();
            if (info == null) return null;
            Class<?> type = info.getClass();
            if (type.getField("displayId").getInt(info) != displayId
                    || uniqueId == null || !uniqueId.equals(type.getField("uniqueId").get(info))) {
                throw new IllegalStateException("Created display identity changed");
            }
            if (type.getField("type").getInt(info) != Display.class.getField("TYPE_VIRTUAL").getInt(null)
                    || type.getField("removeMode").getInt(info)
                        != Display.class.getField("REMOVE_MODE_DESTROY_CONTENT").getInt(null)
                    || !"com.android.shell".equals(type.getField("ownerPackageName").get(info))) {
                throw new IllegalStateException("Display type, owner or removal policy changed");
            }
            return new DisplayLifecycle.Info(type.getField("displayId").getInt(info),
                    (String) type.getField("uniqueId").get(info), type.getField("ownerUid").getInt(info),
                    type.getField("logicalWidth").getInt(info), type.getField("logicalHeight").getInt(info),
                    type.getField("logicalDensityDpi").getInt(info), type.getField("flags").getInt(info),
                    (String) type.getField("name").get(info));
        }

        @Override public synchronized void close() throws Exception {
            Exception failure = null;
            try { if (display != null) { display.release(); display = null; } }
            catch (RuntimeException caught) { failure = caught; }
            try { if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; } }
            catch (RuntimeException caught) { failure = caught; }
            try { if (surface != null) { surface.release(); surface = null; } }
            catch (RuntimeException caught) { failure = caught; }
            if (displayId > 0) {
                Object info = rawInfo();
                if (info != null && (uniqueId == null || uniqueId.equals(info.getClass().getField("uniqueId").get(info)))) {
                    throw new IllegalStateException("Display removal is not yet confirmed", failure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
