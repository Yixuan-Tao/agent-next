package agent.phone.executor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityOptions;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import agent.phone.connection.DisplaySession;
import agent.phone.connection.TargetSession;
import agent.phone.connection.TargetObservation;
import agent.phone.connection.FrameEvidence;
import agent.phone.connection.TargetTap;
import agent.phone.connection.TargetSwipe;
import agent.phone.connection.TargetBack;
import agent.phone.connection.TargetText;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android 16 only. System interfaces are pinned to the android-16.0.0_r1 AOSP contract. */
final class AndroidTargetObserver implements TargetLifecycle.Platform {
    @Override public TargetLifecycle.Identity resolve(String packageName) throws Exception {
        TargetSession.requirePackage(packageName);
        Object manager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class<?> type = Class.forName("android.content.pm.IPackageManager");
        int uid = (Integer) type.getMethod("getPackageUid",String.class,long.class,int.class)
                .invoke(manager,packageName,0L,0);
        if (uid < 10000 || uid >= 100000) throw new IllegalStateException("Target installation unavailable");
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
        // API 36 IPackageManager.aidl; flags 0 excludes disabled components for user 0.
        Object slice = type.getMethod("queryIntentActivities",Intent.class,String.class,long.class,int.class)
                .invoke(manager,query,null,0L,0);
        if (slice == null) throw new IllegalStateException("Target launcher unavailable");
        Method getList = Class.forName("android.content.pm.ParceledListSlice").getMethod("getList");
        getList.setAccessible(true); // Declared on package-private BaseParceledListSlice.
        List<?> candidates = (List<?>) getList.invoke(slice);
        String selected = null;
        for (Object value : candidates) {
            if (!(value instanceof ResolveInfo)) throw new IllegalStateException("Malformed launcher query");
            ActivityInfo info = ((ResolveInfo) value).activityInfo;
            ApplicationInfo app = info == null ? null : info.applicationInfo;
            if (app == null || !info.exported || !info.enabled || !app.enabled || app.uid != uid
                    || !packageName.equals(info.packageName) || !packageName.equals(app.packageName)
                    || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0 || (app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) continue;
            String name = info.name;
            if (name == null) continue;
            if (selected == null || name.compareTo(selected) < 0) selected = name;
        }
        if (selected == null) throw new IllegalStateException("No enabled exported MAIN/LAUNCHER activity");
        return new TargetLifecycle.Identity(uid,selected);
    }
    static Object service(String name,String type) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if (binder == null) throw new IllegalStateException("Required Android service unavailable");
        return Class.forName(type + "$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
    @Override public boolean foregroundTarget(String packageName) throws Exception {
        Class<?> type = Class.forName("android.app.IActivityTaskManager");
        List<?> roots = (List<?>) type.getMethod("getAllRootTaskInfosOnDisplay",int.class)
                .invoke(service("activity_task",type.getName()),0);
        if (roots == null || roots.isEmpty()) throw new IllegalStateException("Primary task identity unavailable");
        for (Object root : roots) {
            if (root == null) throw new IllegalStateException("Primary task identity unavailable");
            int displayId = root.getClass().getField("displayId").getInt(root);
            boolean visible = root.getClass().getField("visible").getBoolean(root);
            int[] children = (int[]) root.getClass().getField("childTaskIds").get(root);
            ComponentName top = (ComponentName) root.getClass().getField("topActivity").get(root);
            // RootTaskInfo exposes leaf children; topActivity alone cannot clear a split root.
            if (PrimaryTaskPolicy.isTarget(displayId,visible,children,top == null ? null : top.getPackageName(),packageName)) return true;
        }
        return false;
    }

    @Override public boolean supportsSecondaryDisplays() throws Exception {
        Object manager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        return (Boolean) Class.forName("android.content.pm.IPackageManager")
                .getMethod("hasSystemFeature",String.class,int.class)
                .invoke(manager,PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,0);
    }
    @Override public void launch(TargetSession target) throws Exception {
        DisplaySession session = target.display;
        if (session.displayId <= 0 || !supportsSecondaryDisplays()) {
            throw new SecurityException("Secondary activity display is not an allowed target");
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(target.packageName,target.launcherClass))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(session.displayId);
        Class<?> type = Class.forName("android.app.IActivityTaskManager");
        int result = (Integer) type.getMethod("startActivityAsUser",Class.forName("android.app.IApplicationThread"),
                String.class,String.class,Intent.class,String.class,IBinder.class,String.class,int.class,int.class,
                Class.forName("android.app.ProfilerInfo"),android.os.Bundle.class,int.class)
                .invoke(service("activity_task",type.getName()),null,"com.android.shell",null,intent,null,null,null,
                        0,0,null,options.toBundle(),0);
        if (result < 0) throw new IllegalStateException("Target launch was rejected");
    }
    @Override public long nowNanos() { return SystemClock.elapsedRealtimeNanos(); }
    @Override public void pause() throws InterruptedException { Thread.sleep(100); }
    @Override public TargetLifecycle.Observer allocate(TargetSession target) { return new Observer(target); }

    private static final class Observer implements TargetLifecycle.Observer {
        private final DisplaySession display;
        private final TargetSession target;
        private final AndroidInputDispatcher input = new AndroidInputDispatcher();
        private UiAutomation automation;
        private Object connection;
        private HandlerThread callbacks;
        Observer(TargetSession target) { this.target = target; this.display = target.display; }
        @Override public void open() throws Exception {
            if (Looper.getMainLooper() == null || Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null) == null) {
                throw new IllegalStateException("Shizuku runtime is not initialized");
            }
            connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            Constructor<?> constructor = UiAutomation.class.getDeclaredConstructor(int.class,Looper.class,
                    Class.forName("android.app.IUiAutomationConnection"));
            constructor.setAccessible(true);
            // The existing main looper handles local callbacks; UiAutomation owns one bounded callback thread.
            automation = (UiAutomation) constructor.newInstance(display.displayId,Looper.getMainLooper(),connection);
            try {
                UiAutomation.class.getMethod("connectWithTimeout",int.class,long.class).invoke(automation,
                        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,2000L);
            } finally { callbacks = (HandlerThread) field(automation,"mRemoteCallbackThread"); }
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if (info == null) throw new IllegalStateException("Observer service configuration unavailable");
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.packageNames = new String[] { target.packageName };
            automation.setServiceInfo(info);
        }
        private static Object field(Object object,String name) throws Exception {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        }
        @Override public void close() throws Exception {
            Exception failure = null;
            try {
                if (automation != null) UiAutomation.class.getMethod("disconnect").invoke(automation);
            } catch (Exception caught) { failure = caught; }
            finally {
                if (callbacks != null) { callbacks.quitSafely(); callbacks.join(500); }
            }
            // Also handles registration rejection before connectWithTimeout reached its wait loop.
            // Retain the object on uncertain remote registration or callback-thread shutdown.
            if (connection != null && field(connection,"mClient") != null || callbacks != null && callbacks.isAlive()) {
                throw new IllegalStateException("Observer cleanup is not confirmed",failure);
            }
            automation = null; connection = null; callbacks = null;
        }
        @Override public TargetObservation read(String id,long sequence,long started,long deadline,boolean includeNodes)
                throws Exception {
            ObservationBudget budget = new ObservationBudget();
            List<AccessibilityWindowInfo> windows = null;
            Window initial = null;
            try {
                checkDeadline(deadline);
                automation.clearCache();
                windows = automation.getWindows(); // mDisplayId, never all displays.
                initial = focused(windows,budget,false,deadline);
                if (initial == null) return refusal(id,sequence,started,TargetObservation.Status.TARGET_NOT_READY,budget);
                if (!initial.allowed) return refusal(id,sequence,started,TargetObservation.Status.FOREIGN_WINDOW,budget);
                ArrayList<TargetObservation.Node> nodes = new ArrayList<>();
                if (includeNodes) traverse(initial.root,initial.id,id,nodes,budget,deadline);
                automation.clearCache();
                List<AccessibilityWindowInfo> finalWindows = automation.getWindows();
                Window last = null;
                try {
                    last = focused(finalWindows,budget,true,deadline);
                    if (last == null || !last.allowed || last.id != initial.id || !last.bounds.equals(initial.bounds)) {
                        return refusal(id,sequence,started,TargetObservation.Status.WINDOW_CHANGED,budget);
                    }
                } finally { if (last != null) recycle(last.root); recycleWindows(finalWindows); }
                checkDeadline(deadline);
                return new TargetObservation(target,id,sequence,started,SystemClock.elapsedRealtimeNanos(),
                        TargetObservation.Status.READY,initial.id,initial.bounds,budget.lookups(),budget.truncated,nodes);
            } catch (IllegalStateException | IllegalArgumentException transientWindowChange) {
                // Window transitions can momentarily expose two focused windows, a display
                // mismatch, a root that changes under traversal, or window bounds outside the
                // logical display. That is not a broken target: return a non-actionable
                // observation so the caller can re-observe.
                return refusal(id,sequence,started,TargetObservation.Status.WINDOW_CHANGED,budget);
            } finally { if (initial != null) recycle(initial.root); recycleWindows(windows); }
        }
        @Override public FrameEvidence.Capture capture(String frameId,long sequence,long started,long deadline,String observationId)
                throws Exception {
            checkDeadline(deadline);
            FrameSafetyPolicy.Snapshot initial = frameWindows(deadline);
            Bitmap bitmap = null;
            try {
                // This persistent UiAutomation was constructed with display.displayId; there is no default-display fallback.
                bitmap = automation.takeScreenshot();
                checkDeadline(deadline);
                if (bitmap == null || bitmap.getWidth() != FrameEvidence.WIDTH || bitmap.getHeight() != FrameEvidence.HEIGHT) {
                    throw new IllegalStateException("Screenshot dimensions are not confirmed");
                }
                FrameSafetyPolicy.requireSame(initial,frameWindows(deadline));
                requireNonBlack(bitmap,deadline);
                CappedPng output = new CappedPng();
                if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw new IllegalStateException("PNG encoding failed");
                checkDeadline(deadline);
                // Encoding may be slow: do not export a frame after an intervening window or overlay change.
                FrameSafetyPolicy.requireSame(initial,frameWindows(deadline));
                byte[] png = output.bytes();
                return new FrameEvidence.Capture(new FrameEvidence(target,frameId,sequence,started,
                        SystemClock.elapsedRealtimeNanos(),observationId,initial.focusedWindowId,
                        FrameEvidence.WIDTH,FrameEvidence.HEIGHT,png.length),png);
            } finally { if (bitmap != null) bitmap.recycle(); }
        }
        @Override public TargetTap tap(String observationId,int x,int y,int expectedWindowId,long deadline)
                throws Exception {
            checkDeadline(deadline);
            FrameSafetyPolicy.Snapshot before = frameWindows(deadline);
            if (before.focusedWindowId != expectedWindowId) {
                throw new IllegalStateException("Observed window changed before the tap");
            }
            checkDeadline(deadline);
            if (!input.tap(display.displayId,target.uid,x,y)) {
                throw new IllegalStateException("Tap injection was refused");
            }
            return new TargetTap(display.displayId,before.focusedWindowId,x,y,
                    SystemClock.elapsedRealtimeNanos(),observationId);
        }
        @Override public TargetSwipe swipe(String observationId,int startX,int startY,int endX,int endY,
                int durationMs,int expectedWindowId,long deadline) throws Exception {
            checkDeadline(deadline);
            FrameSafetyPolicy.Snapshot before = frameWindows(deadline);
            if (before.focusedWindowId != expectedWindowId) {
                throw new IllegalStateException("Observed window changed before the swipe");
            }
            checkDeadline(deadline);
            if (!input.swipe(display.displayId,target.uid,startX,startY,endX,endY,durationMs)) {
                throw new IllegalStateException("Swipe injection was refused");
            }
            return new TargetSwipe(display.displayId,before.focusedWindowId,startX,startY,endX,endY,durationMs,
                    SystemClock.elapsedRealtimeNanos(),observationId);
        }

        @Override public TargetBack back(String observationId,int expectedWindowId,long deadline) throws Exception {
            checkDeadline(deadline);
            FrameSafetyPolicy.Snapshot before = frameWindows(deadline);
            if (before.focusedWindowId != expectedWindowId) {
                throw new IllegalStateException("Observed window changed before back");
            }
            checkDeadline(deadline);
            if (!input.key(display.displayId,target.uid,KeyEvent.KEYCODE_BACK)) {
                throw new IllegalStateException("Back injection was refused");
            }
            return new TargetBack(display.displayId,before.focusedWindowId,
                    SystemClock.elapsedRealtimeNanos(),observationId);
        }

        @Override public TargetText setText(String observationId,String ref,String text,int expectedWindowId,long deadline)
                throws Exception {
            checkDeadline(deadline);
            FrameSafetyPolicy.Snapshot before = frameWindows(deadline);
            if (before.focusedWindowId != expectedWindowId) {
                throw new IllegalStateException("Observed window changed before text input");
            }
            automation.clearCache();
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            AccessibilityWindowInfo targetWindow = null;
            AccessibilityNodeInfo root = null;
            try {
                if (windows == null || windows.isEmpty() || windows.size() > 32) {
                    throw new IllegalStateException("Frame windows unavailable");
                }
                for (AccessibilityWindowInfo window : windows) {
                    if (window != null && window.getId() == expectedWindowId) { targetWindow = window; break; }
                }
                if (targetWindow == null || targetWindow.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    throw new IllegalStateException("Observed target window is unavailable");
                }
                root = targetWindow.getRoot(0);
                if (root == null) throw new IllegalStateException("Observed target window has no root");
                CharSequence packageName = root.getPackageName();
                if (packageName == null || !target.packageName.contentEquals(packageName)) {
                    throw new IllegalStateException("Observed window does not belong to the target");
                }
                AccessibilityNodeInfo field = findEditable(root,deadline);
                if (field == null) throw new IllegalStateException("No editable field is focused or uniquely available");
                try {
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
                    if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments)) {
                        throw new IllegalStateException("Text insertion was refused");
                    }
                    // Require two equal readbacks so a transient intermediate value is not accepted.
                    int matches = 0;
                    long readbackDeadline = SystemClock.elapsedRealtimeNanos() + 2000000000L;
                    while (SystemClock.elapsedRealtimeNanos() < readbackDeadline) {
                        checkDeadline(deadline);
                        SystemClock.sleep(50);
                        if (!field.refresh()) continue;
                        CharSequence value = field.getText();
                        String actual = value == null ? "" : value.toString();
                        if (actual.equals(text)) {
                            if (++matches >= 2) break;
                        } else {
                            matches = 0;
                        }
                    }
                    if (matches < 2) throw new IllegalStateException("Text readback did not match");
                    return new TargetText(display.displayId,before.focusedWindowId,ref,text.length(),
                            SystemClock.elapsedRealtimeNanos(),observationId);
                } finally { field.recycle(); }
            } finally {
                if (root != null) root.recycle();
                recycleWindows(windows);
            }
        }
        /** Prefer the focused editable node; accept a single unambiguous editable field otherwise. */
        private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root,long deadline) {
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
            queue.add(AccessibilityNodeInfo.obtain(root));
            AccessibilityNodeInfo fallback = null;
            int count = 0;
            try {
                while (!queue.isEmpty()) {
                    checkDeadline(deadline);
                    AccessibilityNodeInfo node = queue.removeFirst();
                    try {
                        if (node.isEditable() && node.isEnabled() && node.isVisibleToUser() && !node.isPassword()) {
                            count++;
                            if (node.isFocused()) {
                                if (fallback != null) { fallback.recycle(); fallback = null; }
                                return AccessibilityNodeInfo.obtain(node);
                            }
                            if (fallback == null) fallback = AccessibilityNodeInfo.obtain(node);
                        }
                        for (int index = 0; index < node.getChildCount(); index++) {
                            AccessibilityNodeInfo child = node.getChild(index);
                            if (child != null) queue.addLast(child);
                        }
                    } finally { node.recycle(); }
                }
                if (count == 1 && fallback != null) {
                    AccessibilityNodeInfo result = fallback;
                    fallback = null;
                    return result;
                }
                return null;
            } finally {
                if (fallback != null) fallback.recycle();
                while (!queue.isEmpty()) queue.removeFirst().recycle();
            }
        }
        private FrameSafetyPolicy.Snapshot frameWindows(long deadline) {
            checkDeadline(deadline); automation.clearCache();
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            ArrayList<FrameSafetyPolicy.Window> identities = new ArrayList<>();
            try {
                if (windows == null || windows.isEmpty() || windows.size() > 32) throw new IllegalStateException("Frame windows unavailable");
                for (AccessibilityWindowInfo window : windows) {
                    checkDeadline(deadline);
                    if (window == null) throw new IllegalStateException("Unknown frame window");
                    AccessibilityNodeInfo root = window.getRoot(0);
                    try {
                        Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                        boolean application = window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION;
                        if (root == null) {
                            // Some target popups expose pixels but no accessibility root (for example
                            // JD's banner dots). Keep them as explicitly unconfirmed for FrameSafetyPolicy.
                            identities.add(new FrameSafetyPolicy.Window(window.getDisplayId(),window.getId(),-1,
                                    window.isFocused(),application,false,null,window.getLayer(),toBounds(bounds),true));
                        } else {
                            CharSequence packageName = root.getPackageName();
                            identities.add(new FrameSafetyPolicy.Window(window.getDisplayId(),window.getId(),root.getWindowId(),
                                    window.isFocused(),application,root.isVisibleToUser(),
                                    packageName == null ? null : packageName.toString(),window.getLayer(),toBounds(bounds)));
                        }
                    } finally { recycle(root); }
                }
                return FrameSafetyPolicy.validate(target,identities);
            } finally { recycleWindows(windows); }
        }
        private static void requireNonBlack(Bitmap bitmap,long deadline) {
            int[] row = new int[FrameEvidence.WIDTH];
            for (int y = 0; y < FrameEvidence.HEIGHT; y++) {
                checkDeadline(deadline); bitmap.getPixels(row,0,row.length,0,y,row.length,1);
                for (int pixel : row) if ((pixel >>> 24) != 0 && (pixel & 0x00ffffff) != 0) return;
            }
            throw new IllegalStateException("Black or transparent frame cannot be exported");
        }
        private static final class CappedPng extends OutputStream {
            private final byte[] buffer = new byte[FrameEvidence.MAX_PNG_BYTES];
            private int size;
            @Override public void write(int value) throws IOException {
                if (size == buffer.length) throw new IOException("PNG exceeds frame budget");
                buffer[size++] = (byte) value;
            }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                if (length > buffer.length - size) throw new IOException("PNG exceeds frame budget");
                System.arraycopy(bytes,offset,buffer,size,length); size += length;
            }
            byte[] bytes() { return Arrays.copyOf(buffer,size); }
        }
        private TargetObservation refusal(String id,long sequence,long started,TargetObservation.Status status,ObservationBudget budget) {
            return new TargetObservation(target,id,sequence,started,SystemClock.elapsedRealtimeNanos(),status,
                    -1,null,budget.lookups(),budget.truncated,Collections.emptyList());
        }
        private Window focused(List<AccessibilityWindowInfo> windows,ObservationBudget budget,boolean last,long deadline) {
            if (windows == null || windows.isEmpty()) return null;
            if (windows.size() > 32) throw new IllegalStateException("Observer window limit exceeded");
            AccessibilityWindowInfo focused = null;
            for (AccessibilityWindowInfo window : windows) {
                checkDeadline(deadline);
                if (window.getDisplayId() != display.displayId) throw new IllegalStateException("Observer display changed");
                if (window.isFocused()) {
                    if (focused != null) throw new IllegalStateException("Multiple focused windows");
                    focused = window;
                }
            }
            if (focused == null) return null;
            if (last) budget.finalGuard();
            else if (!budget.lookup()) throw new IllegalStateException("Initial root budget exhausted");
            AccessibilityNodeInfo root = focused.getRoot(0);
            if (root == null) return null;
            boolean retained = false;
            try {
                if (root.getWindowId() != focused.getId()) throw new IllegalStateException("Root window identity changed");
                Rect bounds = new Rect(); focused.getBoundsInScreen(bounds);
                boolean allowed = focused.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && target.packageName.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                        && root.isVisibleToUser();
                Window result = new Window(focused.getId(),bounds,root,allowed);
                retained = true;
                return result;
            } finally { if (!retained) recycle(root); }
        }
        private void traverse(AccessibilityNodeInfo root,int windowId,String id,ArrayList<TargetObservation.Node> nodes,
                ObservationBudget budget,long deadline) {
            ArrayDeque<Frame> stack = new ArrayDeque<>();
            // Root remains owned by Window; only children are recycled here.
            stack.addLast(new Frame(root,-1,false));
            try {
                while (!stack.isEmpty()) {
                    checkDeadline(deadline);
                    Frame frame = stack.peekLast();
                    if (!frame.recorded) {
                        AccessibilityNodeInfo node = frame.node;
                        if (node.getWindowId() != windowId || !target.packageName.contentEquals(
                                node.getPackageName() == null ? "" : node.getPackageName())) {
                            throw new IllegalStateException("Observed node identity changed");
                        }
                        TargetObservation.Node record = nodeRecord(node,id,nodes.size(),frame.parent,windowId);
                        if (!budget.record(record.estimatedBytes())) break;
                        nodes.add(record); frame.index = record.index; frame.count = node.getChildCount(); frame.recorded = true;
                        if (frame.count < 0) throw new IllegalStateException("Invalid child count");
                    }
                    if (frame.next >= frame.count) { pop(stack); continue; }
                    if (!budget.lookup()) break;
                    AccessibilityNodeInfo child = frame.node.getChild(frame.next++,0);
                    if (child != null) stack.addLast(new Frame(child,frame.index,true));
                }
            } finally { while (!stack.isEmpty()) pop(stack); }
        }
        private static void pop(ArrayDeque<Frame> stack) { Frame f = stack.removeLast(); if (f.recycle) recycle(f.node); }
        private static TargetObservation.Node nodeRecord(AccessibilityNodeInfo node,String id,int index,int parent,int windowId) {
            boolean password = node.isPassword();
            CharSequence text = password ? null : node.getText(), description = password ? null : node.getContentDescription();
            CharSequence resource = node.getViewIdResourceName(), type = node.getClassName();
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            return new TargetObservation.Node(id + ":" + index,index,windowId,parent,
                    TargetObservation.nodeBounds(bounds.left,bounds.top,bounds.right,bounds.bottom,node.isVisibleToUser()),
                    ObservationBudget.limit(text),ObservationBudget.limit(description),ObservationBudget.limit(resource),
                    ObservationBudget.limit(type),node.isClickable(),node.isEditable(),node.isEnabled(),password,
                    node.isVisibleToUser(),node.isFocused(),node.isScrollable(),ObservationBudget.isLong(text)
                    || ObservationBudget.isLong(description) || ObservationBudget.isLong(resource) || ObservationBudget.isLong(type));
        }
        private static TargetObservation.Bounds toBounds(Rect b) { return new TargetObservation.Bounds(b.left,b.top,b.right,b.bottom); }
        private static void checkDeadline(long deadline) {
            if (SystemClock.elapsedRealtimeNanos() >= deadline || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Observer deadline expired");
            }
        }
        @SuppressWarnings("deprecation") private static void recycle(AccessibilityNodeInfo node) { if (node != null) node.recycle(); }
        @SuppressWarnings("deprecation") private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
            if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        private static final class Window {
            final int id; final TargetObservation.Bounds bounds; final AccessibilityNodeInfo root; final boolean allowed;
            Window(int id,Rect bounds,AccessibilityNodeInfo root,boolean allowed) {
                this.id = id; this.bounds = toBounds(bounds); this.root = root; this.allowed = allowed;
            }
        }
        private static final class Frame {
            final AccessibilityNodeInfo node; final int parent; final boolean recycle;
            int index,next,count; boolean recorded;
            Frame(AccessibilityNodeInfo node,int parent,boolean recycle) { this.node = node; this.parent = parent; this.recycle = recycle; }
        }
    }
}
