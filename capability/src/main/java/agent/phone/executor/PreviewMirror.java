package agent.phone.executor;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.view.Surface;
import java.lang.reflect.Constructor;

/** Mirrors a private display into an app-owned Surface; no screenshots and no target RPC. */
final class PreviewMirror {
    private VirtualDisplay display;

    synchronized void start(Context context, int displayId, int width, int height, int densityDpi,
            Surface surface) throws Exception {
        Context ctx = AndroidDisplayResources.shellContext();
        if (surface == null || !surface.isValid()) throw new IllegalArgumentException("Preview surface invalid");
        stop();
        Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Constructor<?> constructor = builderClass.getConstructor(String.class, int.class, int.class, int.class);
        Object builder = constructor.newInstance("agent-preview", width, height, densityDpi);
        try {
            builderClass.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
        } catch (NoSuchMethodException ignored) { }
        builderClass.getMethod("setDisplayIdToMirror", int.class).invoke(builder, displayId);
        builderClass.getMethod("setFlags", int.class).invoke(builder,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
        builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
        Object config = builderClass.getMethod("build").invoke(builder);
        java.lang.reflect.Constructor<DisplayManager> displayManagerConstructor =
                DisplayManager.class.getDeclaredConstructor(Context.class);
        displayManagerConstructor.setAccessible(true);
        DisplayManager manager = displayManagerConstructor.newInstance(ctx);
        display = manager.createVirtualDisplay((VirtualDisplayConfig) config);
        if (display == null) throw new IllegalStateException("Mirror creation was rejected");
    }

    synchronized void stop() {
        if (display != null) {
            try { display.release(); } catch (RuntimeException ignored) { }
            display = null;
        }
    }
}
