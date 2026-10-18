package agent.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Shell-only Android 16 experiment, not an ordinary APK API. */
public final class AndroidInputProbe {
    private static final String MARKER = "ANDROID_AGENT_INPUT_PROBE_V1";
    private static final String BROWSER = "com.android.browser";
    private final int displayId;
    private final String expectedId;

    private AndroidInputProbe(int displayId, String expectedId) {
        this.displayId = displayId;
        this.expectedId = expectedId;
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static Object invoke(Object target, String name, Class<?>[] signature, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object get(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object staticGet(String className, String name) throws Exception {
        return type(className).getMethod(name).invoke(null);
    }

    private void checkDisplay() throws Exception {
        if (displayId <= 0) throw new IllegalArgumentException("Physical/invalid display is forbidden");
        if (type("android.os.Build$VERSION").getField("SDK_INT").getInt(null) != 36)
            throw new IllegalArgumentException("This reflection adapter requires Android API 36");
        Object manager = staticGet("android.hardware.display.DisplayManagerGlobal", "getInstance");
        Object info = invoke(manager, "getDisplayInfo", new Class<?>[]{int.class}, displayId);
        if (info == null) throw new IllegalArgumentException("Display no longer exists");
        Class<?> infoType = type("android.view.DisplayInfo");
        ProbeGuards.display(displayId, expectedId,
                (String) infoType.getField("uniqueId").get(info),
                (String) infoType.getField("name").get(info),
                infoType.getField("ownerUid").getInt(info));
    }

    private void ime(String policy) throws Exception {
        checkDisplay();
        Object manager = staticGet("android.view.WindowManagerGlobal", "getWindowManagerService");
        if (policy != null) {
            String constant;
            if ("local".equals(policy)) constant = "DISPLAY_IME_POLICY_LOCAL";
            else if ("hide".equals(policy)) constant = "DISPLAY_IME_POLICY_HIDE";
            else throw new IllegalArgumentException("Policy must be local or hide");
            int value = type("android.view.WindowManager").getField(constant).getInt(null);
            invoke(manager, "setDisplayImePolicy", new Class<?>[]{int.class, int.class}, displayId, value);
            Object actual = invoke(manager, "getDisplayImePolicy", new Class<?>[]{int.class}, displayId);
            if (!Integer.valueOf(value).equals(actual)) throw new IllegalStateException("IME policy mismatch");
        }
        checkDisplay();
        System.out.println("ime_policy=" + invoke(manager, "getDisplayImePolicy", new Class<?>[]{int.class}, displayId));
    }

    private final class NodeSession implements AutoCloseable {
        private Object thread;
        private Object automation;
        private boolean connected;

        NodeSession() throws Exception {
            try {
                System.out.println("stage=callback_thread_start");
                thread = type("android.os.HandlerThread").getConstructor(String.class).newInstance("AgentProbeCallbacks");
                get(thread, "start");
                Object looper = get(thread, "getLooper");
                System.out.println("stage=connection_create");
                Object connection = type("android.app.UiAutomationConnection").getConstructor().newInstance();
                // Android 16 source: explicitly select the display rather than the deprecated display-0 constructor.
                Constructor<?> constructor = type("android.app.UiAutomation").getDeclaredConstructor(
                        int.class, type("android.os.Looper"), type("android.app.IUiAutomationConnection"));
                constructor.setAccessible(true);
                automation = constructor.newInstance(displayId, looper, connection);
                System.out.println("stage=automation_created");
                int flags = type("android.app.UiAutomation").getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null);
                System.out.println("stage=connect_start");
                invoke(automation, "connect", new Class<?>[]{int.class}, flags);
                connected = true;
                System.out.println("stage=connected");
                Object serviceInfo = get(automation, "getServiceInfo");
                if (serviceInfo == null) throw new IllegalStateException("Missing automation service info");
                Class<?> serviceType = type("android.accessibilityservice.AccessibilityServiceInfo");
                Field serviceFlags = serviceType.getField("flags");
                int retrieve = serviceType.getField("FLAG_RETRIEVE_INTERACTIVE_WINDOWS").getInt(null);
                serviceFlags.setInt(serviceInfo, serviceFlags.getInt(serviceInfo) | retrieve);
                invoke(automation, "setServiceInfo", new Class<?>[]{serviceType}, serviceInfo);
                System.out.println("stage=window_access_configured");
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private List<?> windows() throws Exception {
            long deadline = System.nanoTime() + 2000000000L;
            do {
                Object mapping = get(automation, "getWindowsOnAllDisplays");
                Object selected = invoke(mapping, "get", new Class<?>[]{int.class}, displayId);
                if (selected instanceof List && !((List<?>) selected).isEmpty()) return (List<?>) selected;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException("No windows on target display");
        }

        Object field() throws Exception {
            checkDisplay();
            List<Object> fields = new ArrayList<Object>();
            boolean marker = true;
            String targetPackage = null;
            int visited = 0;
            // Only the selected display is queried for root nodes and text.
            for (Object window : windows()) {
                if (!Integer.valueOf(displayId).equals(get(window, "getDisplayId")))
                    throw new IllegalStateException("Window display changed");
                System.out.println("window_id=" + get(window, "getId"));
                System.out.println("window_type=" + get(window, "getType"));
                System.out.println("window_focused=" + get(window, "isFocused"));
                Object root = get(window, "getRoot");
                System.out.println("window_root_null=" + (root == null));
                if (root != null) {
                    System.out.println("root_package=" + get(root, "getPackageName"));
                    System.out.println("root_class=" + get(root, "getClassName"));
                    System.out.println("root_children=" + get(root, "getChildCount"));
                }
                if (root == null || !BROWSER.equals(String.valueOf(get(root, "getPackageName")))) continue;
                boolean windowMarker = false;
                List<Object> windowFields = new ArrayList<Object>();
                ArrayDeque<Object> queue = new ArrayDeque<Object>();
                queue.add(root);
                while (!queue.isEmpty()) {
                    if (++visited > 2000) throw new IllegalStateException("Test page node limit exceeded");
                    Object node = queue.removeFirst();
                    if (!BROWSER.equals(String.valueOf(get(node, "getPackageName")))) continue;
                    if (String.valueOf(get(node, "getText")).contains(MARKER)) windowMarker = true;
                    if (Boolean.TRUE.equals(get(node, "isEditable"))) {
                        System.out.println("editable_id=" + get(node, "getViewIdResourceName"));
                        System.out.println("editable_visible=" + get(node, "isVisibleToUser"));
                    }
                    if (Boolean.TRUE.equals(get(node, "isEditable"))
                            && Boolean.TRUE.equals(get(node, "isVisibleToUser"))
                            && ProbeGuards.fixtureFieldId((String) get(node, "getViewIdResourceName"))) {
                        windowFields.add(node);
                        targetPackage = String.valueOf(get(node, "getPackageName"));
                    }
                    int count = (Integer) get(node, "getChildCount");
                    for (int i = 0; i < count; i++) {
                        Object child = invoke(node, "getChild", new Class<?>[]{int.class}, i);
                        if (child != null) queue.add(child);
                    }
                }
                if (!windowFields.isEmpty()) {
                    fields.addAll(windowFields);
                    marker = marker && windowMarker;
                }
            }
            Object node = fields.size() == 1 ? fields.get(0) : null;
            System.out.println("editable_candidates=" + fields.size());
            for (int i = 0; i < fields.size(); i++) {
                Object candidate = fields.get(i);
                System.out.println("candidate_" + i + "_class=" + get(candidate, "getClassName"));
                System.out.println("candidate_" + i + "_id=" + get(candidate, "getViewIdResourceName"));
                System.out.println("candidate_" + i + "_enabled=" + get(candidate, "isEnabled"));
            }
            ProbeGuards.target(targetPackage, marker, fields.size(),
                    node != null && Boolean.TRUE.equals(get(node, "isEnabled")),
                    node != null && Boolean.TRUE.equals(get(node, "isPassword")));
            checkDisplay();
            return node;
        }

        void setText(String text) throws Exception {
            Object node = field();
            Object arguments = type("android.os.Bundle").getConstructor().newInstance();
            Class<?> nodeType = type("android.view.accessibility.AccessibilityNodeInfo");
            String key = (String) nodeType.getField("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE").get(null);
            invoke(arguments, "putCharSequence", new Class<?>[]{String.class, CharSequence.class}, key, text);
            int action = nodeType.getField("ACTION_SET_TEXT").getInt(null);
            checkDisplay();
            Object accepted = invoke(node, "performAction", new Class<?>[]{int.class, type("android.os.Bundle")}, action, arguments);
            if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("SET_TEXT was rejected");
            long deadline = System.nanoTime() + 2000000000L;
            do {
                Object value = get(field(), "getText");
                String actual = value == null ? "" : value.toString();
                if (text.equals(actual)) {
                    System.out.println("verified_text_base64=" + Base64.getEncoder().encodeToString(actual.getBytes(StandardCharsets.UTF_8)));
                    return;
                }
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException("Actual text did not match within 2 seconds");
        }

        @Override public void close() throws Exception {
            try {
                if (connected) invoke(automation, "disconnect", new Class<?>[0]);
            } finally {
                if (thread != null) get(thread, "quitSafely");
            }
        }
    }

    private void run(String command, String payload) throws Exception {
        checkDisplay();
        System.out.println("stage=display_checked");
        if ("ime-get".equals(command)) ime(null);
        else if ("ime-set".equals(command)) {
            if (payload == null) throw new IllegalArgumentException("Missing IME policy");
            ime(payload);
        } else if ("inspect".equals(command) || "set-text".equals(command)) {
            String text = "set-text".equals(command) ? ProbeGuards.decodeText(payload) : null;
            try (NodeSession session = new NodeSession()) {
                Object field = session.field();
                System.out.println("fixture_verified=true");
                Object value = get(field, "getText");
                String actual = value == null ? "" : value.toString();
                System.out.println("inspected_text_base64=" + Base64.getEncoder().encodeToString(actual.getBytes(StandardCharsets.UTF_8)));
                System.out.println("field_focused=" + get(field, "isFocused"));
                if (text != null) session.setText(text);
            }
        } else throw new IllegalArgumentException("Unknown command");
        checkDisplay();
        System.out.println("status=ok");
    }

    private static void execute(String[] args) {
        int status = 0;
        try {
            if (args.length < 3 || args.length > 4)
                throw new IllegalArgumentException("Usage: display-id expected-unique-id ime-get|ime-set|inspect|set-text [policy|Base64]");
            new AndroidInputProbe(Integer.parseInt(args[0]), args[1]).run(args[2], args.length == 4 ? args[3] : null);
        } catch (Throwable error) {
            while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            System.err.println("error=" + error.getClass().getSimpleName() + ": " + error.getMessage());
            status = 1;
        }
        System.exit(status);
    }

    public static void main(final String[] args) {
        try {
            Class<?> looper = type("android.os.Looper");
            looper.getMethod("prepare").invoke(null);
            Object mainLoop = looper.getMethod("myLooper").invoke(null);
            // app_process has no application bootstrap; this hook is scoped to this test process.
            // AOSP Android 16 Looper.java: setMainLooperForTest, AccessibilityInteractionClient.java:171.
            looper.getMethod("setMainLooperForTest", looper).invoke(null, mainLoop);
            new Thread(new Runnable() {
                @Override public void run() { execute(args); }
            }, "AgentProbeCommand").start();
            looper.getMethod("loop").invoke(null);
        } catch (Throwable error) {
            while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            System.err.println("error=Bootstrap: " + error);
            System.exit(1);
        }
    }
}
