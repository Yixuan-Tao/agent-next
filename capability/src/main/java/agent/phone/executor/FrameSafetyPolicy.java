package agent.phone.executor;

import agent.phone.connection.TargetObservation;
import agent.phone.connection.TargetSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Screenshot export is stricter than node observation: every composited window needs an owner. */
public final class FrameSafetyPolicy {
    private static final int MAX_UNROOTED = 4;
    private FrameSafetyPolicy() { }
    public static Snapshot validate(TargetSession target,List<Window> windows) {
        if (target == null || windows == null || windows.isEmpty() || windows.size() > 32) throw denied();
        Window focused = null; Map<Integer,Window> identities = new HashMap<>(); int unrooted = 0; long unrootedArea = 0;
        for (Window window : windows) {
            if (window == null) throw denied();
            if (window.displayId != target.display.displayId || window.id < 0
                    || !window.application || window.bounds == null
                    || window.bounds.left < 0 || window.bounds.top < 0 || window.bounds.right > target.display.width
                    || window.bounds.bottom > target.display.height || window.bounds.left >= window.bounds.right
                    || window.bounds.top >= window.bounds.bottom || identities.put(window.id,window) != null) throw denied();
            if (window.focused) { if (focused != null) throw denied(); focused = window; }
            if (window.unrooted) {
                // A private display admits only the launched target and the system. An unrooted
                // application window may be target decoration (for example JD's banner dots) while
                // it cannot take focus, stays fully inside the target window, and remains small.
                if (window.focused || ++unrooted > MAX_UNROOTED) throw denied();
                unrootedArea += (long) (window.bounds.right - window.bounds.left)
                        * (window.bounds.bottom - window.bounds.top);
            } else if (window.rootWindowId != window.id || !window.visible
                    || !target.packageName.equals(window.packageName)) throw denied();
        }
        if (focused == null || focused.unrooted || focused.bounds.left != 0 || focused.bounds.top != 0
                || focused.bounds.right != target.display.width || focused.bounds.bottom != target.display.height
                || unrootedArea > (long) target.display.width * target.display.height / 16) throw denied();
        for (Window window : windows) {
            if (window.unrooted && (window.bounds.left < focused.bounds.left || window.bounds.top < focused.bounds.top
                    || window.bounds.right > focused.bounds.right || window.bounds.bottom > focused.bounds.bottom)) throw denied();
        }
        return new Snapshot(focused.id,new ArrayList<>(windows));
    }
    public static void requireSame(Snapshot before,Snapshot after) {
        if (before.focusedWindowId != after.focusedWindowId || before.windows.size() != after.windows.size()) throw denied();
        Map<Integer,Window> remaining = new HashMap<>();
        for (Window window : before.windows) remaining.put(window.id,window);
        for (Window window : after.windows) {
            Window prior = remaining.remove(window.id);
            if (prior == null || prior.layer != window.layer || prior.focused != window.focused
                    || prior.unrooted != window.unrooted || !prior.bounds.equals(window.bounds)) throw denied();
        }
    }
    public static final class Snapshot {
        public final int focusedWindowId;
        private final List<Window> windows;
        Snapshot(int focusedWindowId,List<Window> windows) { this.focusedWindowId = focusedWindowId; this.windows = windows; }
    }
    public static final class Window {
        final int displayId,id,rootWindowId,layer;
        final boolean focused,application,visible,unrooted;
        final String packageName;
        final TargetObservation.Bounds bounds;
        public Window(int displayId,int id,int rootWindowId,boolean focused,boolean application,boolean visible,
                String packageName,int layer,TargetObservation.Bounds bounds) {
            this(displayId,id,rootWindowId,focused,application,visible,packageName,layer,bounds,false);
        }
        public Window(int displayId,int id,int rootWindowId,boolean focused,boolean application,boolean visible,
                String packageName,int layer,TargetObservation.Bounds bounds,boolean unrooted) {
            this.displayId = displayId; this.id = id; this.rootWindowId = rootWindowId; this.focused = focused;
            this.application = application; this.visible = visible; this.packageName = packageName; this.layer = layer;
            this.bounds = bounds; this.unrooted = unrooted;
        }
    }
    private static IllegalStateException denied() { return new IllegalStateException("Frame window ownership is not confirmed"); }
}
