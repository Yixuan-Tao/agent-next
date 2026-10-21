package agent.phone.executor;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import agent.phone.capability.AndroidPreviewController;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Mirrors a private display into an app-owned Surface; no screenshots and no target RPC. */
final class PreviewMirror {
    private VirtualDisplay interactiveDisplay;
    private VirtualDisplay phoneReadOnlyDisplay;
    private VirtualDisplay desktopReadOnlyDisplay;

    synchronized void start(Context context, int displayId, int width, int height, int densityDpi,
            Surface surface, int channel) throws Exception {
        requireChannel(channel);
        Context ctx = AndroidDisplayResources.shellContext();
        if (surface == null || !surface.isValid()) throw new IllegalArgumentException("Preview surface invalid");
        stop(channel);
        Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Class<?> configClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
        Method createMethod = DisplayManager.class.getMethod("createVirtualDisplay", configClass);
        Constructor<?> constructor = builderClass.getConstructor(String.class, int.class, int.class, int.class);
        Object builder = constructor.newInstance("agent-preview-" + channel, width, height, densityDpi);
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
        VirtualDisplay created = (VirtualDisplay) createMethod.invoke(manager, config);
        if (created == null) throw new IllegalStateException("Mirror creation was rejected");
        if (channel == AndroidPreviewController.MIRROR_INTERACTIVE) interactiveDisplay = created;
        else if (channel == AndroidPreviewController.MIRROR_PHONE_READ_ONLY) phoneReadOnlyDisplay = created;
        else desktopReadOnlyDisplay = created;
    }

    synchronized void stop(int channel) {
        requireChannel(channel);
        VirtualDisplay display = channel == AndroidPreviewController.MIRROR_INTERACTIVE ? interactiveDisplay
                : channel == AndroidPreviewController.MIRROR_PHONE_READ_ONLY
                ? phoneReadOnlyDisplay : desktopReadOnlyDisplay;
        if (display == null) return;
        try { display.release(); } catch (RuntimeException ignored) { }
        if (channel == AndroidPreviewController.MIRROR_INTERACTIVE) interactiveDisplay = null;
        else if (channel == AndroidPreviewController.MIRROR_PHONE_READ_ONLY) phoneReadOnlyDisplay = null;
        else desktopReadOnlyDisplay = null;
    }

    synchronized void stop() {
        stop(AndroidPreviewController.MIRROR_INTERACTIVE);
        stop(AndroidPreviewController.MIRROR_PHONE_READ_ONLY);
        stop(AndroidPreviewController.MIRROR_DESKTOP_READ_ONLY);
    }

    private static void requireChannel(int channel) {
        if (channel != AndroidPreviewController.MIRROR_INTERACTIVE
                && channel != AndroidPreviewController.MIRROR_PHONE_READ_ONLY
                && channel != AndroidPreviewController.MIRROR_DESKTOP_READ_ONLY) {
            throw new IllegalArgumentException("Unknown preview channel");
        }
    }
}
