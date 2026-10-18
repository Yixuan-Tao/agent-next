package agent.diagnostics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reports a bounded, text-redacted accessibility tree for the synthetic secondary page. */
public final class AccessibilityTreeDiagnostic {
    private static Class<?> type(String name) throws Exception { return Class.forName(name); }

    private static Object invoke(Object target, String name, Class<?>[] signature, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object get(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static void inspect(int displayId) throws Exception {
        Object thread = type("android.os.HandlerThread").getConstructor(String.class)
                .newInstance("AgentTreeDiagnosticCallbacks");
        get(thread, "start");
        Object looper = get(thread, "getLooper");
        Object connection = type("android.app.UiAutomationConnection").getConstructor().newInstance();
        Constructor<?> constructor = type("android.app.UiAutomation").getDeclaredConstructor(
                int.class, type("android.os.Looper"), type("android.app.IUiAutomationConnection"));
        constructor.setAccessible(true);
        Object automation = constructor.newInstance(displayId, looper, connection);
        boolean connected = false;
        try {
            int flags = type("android.app.UiAutomation").getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null);
            invoke(automation, "connect", new Class<?>[]{int.class}, flags);
            connected = true;
            Object info = get(automation, "getServiceInfo");
            Class<?> serviceType = type("android.accessibilityservice.AccessibilityServiceInfo");
            int serviceFlags = serviceType.getField("flags").getInt(info);
            int retrieve = serviceType.getField("FLAG_RETRIEVE_INTERACTIVE_WINDOWS").getInt(null);
            serviceType.getField("flags").setInt(info, serviceFlags | retrieve);
            invoke(automation, "setServiceInfo", new Class<?>[]{serviceType}, info);

            Object mapping = get(automation, "getWindowsOnAllDisplays");
            Object selected = invoke(mapping, "get", new Class<?>[]{int.class}, displayId);
            if (!(selected instanceof java.util.List)) throw new IllegalStateException("No display window list");
            java.util.List<?> windows = (java.util.List<?>) selected;
            System.out.println("window_count=" + windows.size());
            int windowNumber = 0;
            for (Object window : windows) {
                int actualDisplay = (Integer) get(window, "getDisplayId");
                Object title = get(window, "getTitle");
                Object root = get(window, "getRoot");
                System.out.println("window_" + windowNumber + "_display=" + actualDisplay);
                System.out.println("window_" + windowNumber + "_title=" + title);
                if (root == null) {
                    System.out.println("window_" + windowNumber + "_root=null");
                    windowNumber++;
                    continue;
                }
                System.out.println("window_" + windowNumber + "_root_package=" + get(root, "getPackageName"));
                System.out.println("window_" + windowNumber + "_root_class=" + get(root, "getClassName"));
                Map<String, Integer> packages = new LinkedHashMap<String, Integer>();
                Map<String, Integer> classes = new LinkedHashMap<String, Integer>();
                int visited = 0;
                int editable = 0;
                boolean marker = false;
                ArrayDeque<Object> queue = new ArrayDeque<Object>();
                queue.add(root);
                while (!queue.isEmpty() && visited < 1500) {
                    Object node = queue.removeFirst();
                    visited++;
                    String pkg = String.valueOf(get(node, "getPackageName"));
                    String cls = String.valueOf(get(node, "getClassName"));
                    packages.put(pkg, packages.containsKey(pkg) ? packages.get(pkg) + 1 : 1);
                    classes.put(cls, classes.containsKey(cls) ? classes.get(cls) + 1 : 1);
                    Object nodeText = get(node, "getText");
                    Object nodeDescription = get(node, "getContentDescription");
                    marker |= (nodeText != null && nodeText.toString().contains("ANDROID_AGENT_INPUT_PROBE_V1"))
                            || (nodeDescription != null && nodeDescription.toString().contains("ANDROID_AGENT_INPUT_PROBE_V1"));
                    boolean isEditable = Boolean.TRUE.equals(get(node, "isEditable"));
                    if (isEditable) {
                        editable++;
                        System.out.println("window_" + windowNumber + "_editable_" + editable
                                + "_package=" + pkg + ",class=" + cls
                                + ",id=" + get(node, "getViewIdResourceName")
                                + ",visible=" + get(node, "isVisibleToUser")
                                + ",enabled=" + get(node, "isEnabled")
                                + ",focused=" + get(node, "isFocused")
                                + ",password=" + get(node, "isPassword")
                                + ",text_length=" + (nodeText == null ? 0 : nodeText.toString().length()));
                    }
                    int count = (Integer) get(node, "getChildCount");
                    for (int i = 0; i < count; i++) {
                        Object child = invoke(node, "getChild", new Class<?>[]{int.class}, i);
                        if (child != null) queue.add(child);
                    }
                }
                System.out.println("window_" + windowNumber + "_visited=" + visited);
                System.out.println("window_" + windowNumber + "_editable_count=" + editable);
                System.out.println("window_" + windowNumber + "_marker_visible=" + marker);
                System.out.println("window_" + windowNumber + "_packages=" + packages);
                System.out.println("window_" + windowNumber + "_classes=" + classes);
                windowNumber++;
            }
            System.out.println("status=ok");
        } finally {
            if (connected) invoke(automation, "disconnect", new Class<?>[0]);
            get(thread, "quitSafely");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: display-id");
        int displayId = Integer.parseInt(args[0]);
        Class<?> looperType = type("android.os.Looper");
        looperType.getMethod("prepare").invoke(null);
        Object mainLoop = looperType.getMethod("myLooper").invoke(null);
        looperType.getMethod("setMainLooperForTest", looperType).invoke(null, mainLoop);
        Thread command = new Thread(() -> {
            try { inspect(displayId); }
            catch (Throwable error) {
                System.err.println("diagnostic_error=" + error.getClass().getSimpleName() + ":" + error.getMessage());
            } finally { System.exit(0); }
        }, "AgentTreeDiagnosticCommand");
        command.start();
        looperType.getMethod("loop").invoke(null);
    }
}
