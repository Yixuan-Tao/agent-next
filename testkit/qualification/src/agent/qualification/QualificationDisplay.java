package agent.qualification;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Android 16 shell-only virtual display with top-focus stealing disabled. */
public final class QualificationDisplay {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int FLAGS = 1 | 2 | 8 | (1 << 6) | (1 << 7) | (1 << 8)
            | (1 << 10) | (1 << 11) | (1 << 12) | (1 << 13)
            | (1 << 14) | (1 << 15) | (1 << 16);

    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT != 36) {
            throw new IllegalStateException("This shell adapter requires Android API 36");
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: width height density");
        }
        int width = Integer.parseInt(args[0]);
        int height = Integer.parseInt(args[1]);
        int density = Integer.parseInt(args[2]);
        if (width < 640 || width > 2560 || height < 640 || height > 1600
                || density < 120 || density > 480 || (long) width * height > 4096000L) {
            throw new IllegalArgumentException("unsupported diagnostic display dimensions");
        }

        Looper.prepareMainLooper();
        Context shellContext = createShellContext();
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(QualificationDisplay::drainImage, new android.os.Handler());

        try {
            Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            DisplayManager manager = constructor.newInstance(shellContext);
            virtualDisplay = manager.createVirtualDisplay("agent-phone", width, height, density,
                    imageReader.getSurface(), FLAGS);
            if (virtualDisplay == null) {
                throw new IllegalStateException("DisplayManager returned no virtual display");
            }
            int displayId = virtualDisplay.getDisplay().getDisplayId();
            setImePolicy(displayId, 2);
            System.out.println("created display=" + displayId + " flags=" + FLAGS);
            System.out.flush();
            startStopReader(new android.os.Handler());
            Looper.loop();
        } finally {
            release();
        }
    }

    private static void drainImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image != null) {
            image.close();
        }
    }

    private static void startStopReader(android.os.Handler handler) {
        Thread thread = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                input.readLine();
            } catch (Exception ignored) {
                // Closing the ADB shell also requests a clean release.
            }
            handler.post(() -> {
                release();
                System.out.println("released");
                System.out.flush();
                System.exit(0);
            });
        }, "qualification-display-stop");
        thread.setDaemon(true);
        thread.start();
    }

    private static void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private static void setImePolicy(int displayId, int policy) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, "window");
        Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object windowManager = asInterface.invoke(null, binder);
        Method setPolicy = windowManager.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
        setPolicy.invoke(windowManager, displayId, policy);
    }

    private static Context createShellContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> activityThreadConstructor = activityThreadClass.getDeclaredConstructor();
        activityThreadConstructor.setAccessible(true);
        Object activityThread = activityThreadConstructor.newInstance();
        Field currentThread = activityThreadClass.getDeclaredField("sCurrentActivityThread");
        currentThread.setAccessible(true);
        currentThread.set(null, activityThread);
        Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(activityThread, true);

        Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
        Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
        Constructor<?> controllerConstructor = controllerClass.getDeclaredConstructor(internalClass);
        controllerConstructor.setAccessible(true);
        Object controller = controllerConstructor.newInstance(activityThread);
        Field controllerField = activityThreadClass.getDeclaredField("mConfigurationController");
        controllerField.setAccessible(true);
        controllerField.set(activityThread, controller);

        Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
        Constructor<?> bindDataConstructor = bindDataClass.getDeclaredConstructor();
        bindDataConstructor.setAccessible(true);
        Object bindData = bindDataConstructor.newInstance();
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = PACKAGE_NAME;
        Field appInfo = bindDataClass.getDeclaredField("appInfo");
        appInfo.setAccessible(true);
        appInfo.set(bindData, applicationInfo);
        Field boundApplication = activityThreadClass.getDeclaredField("mBoundApplication");
        boundApplication.setAccessible(true);
        boundApplication.set(activityThread, bindData);

        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context base = (Context) getSystemContext.invoke(activityThread);
        ShellContext context = new ShellContext(base);
        Application application = Instrumentation.newApplication(Application.class, context);
        Field initialApplication = activityThreadClass.getDeclaredField("mInitialApplication");
        initialApplication.setAccessible(true);
        initialApplication.set(activityThread, application);
        return context;
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return PACKAGE_NAME; }
        @Override public String getOpPackageName() { return PACKAGE_NAME; }
        @Override public Context getApplicationContext() { return this; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID)
                    .setPackageName(PACKAGE_NAME).build();
        }
        @SuppressWarnings("unused")
        public int getDeviceId() { return 0; }
    }
}
