package agent.phone.executor;

import agent.phone.connection.TargetObservation;
import agent.phone.connection.TargetSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Screenshot export is stricter than node observation: every composited window needs an owner. */
public final class FrameSafetyPolicy {
    /** A frame was refused by the safety checks; the target session itself may remain usable. */
    public static final class RejectedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        RejectedException(String reason) { super("Frame capture rejected: " + reason); }
    }
    private static final int MAX_UNROOTED = 4;
    private FrameSafetyPolicy() { }
    public static Snapshot validate(TargetSession target,List<Window> windows) {
        return validate(target, windows, true);
    }
    /** Capture still verifies window ownership, but permits app surfaces that do not fill the display. */
    public static Snapshot validateForCapture(TargetSession target,List<Window> windows) {
        return validate(target, windows, false);
    }
    private static Snapshot validate(TargetSession target,List<Window> windows,boolean forInput) {
        if (target == null || windows == null || windows.isEmpty() || windows.size() > 32) throw denied("WINDOW_LIST_INVALID");
        Window focused = null, targetSurface = null;
        Map<Integer,Window> identities = new HashMap<>(); int unrooted = 0; long unrootedArea = 0;
        for (Window window : windows) {
            if (window == null) throw denied("WINDOW_METADATA_MISSING");
            if (window.displayId != target.display.displayId || window.id < 0
                    || !window.application || window.bounds == null
                    || window.bounds.left < 0 || window.bounds.top < 0 || window.bounds.right > target.display.width
                    || window.bounds.bottom > target.display.height || window.bounds.left >= window.bounds.right
                    || window.bounds.top >= window.bounds.bottom || identities.put(window.id,window) != null) {
                throw denied("WINDOW_IDENTITY_OR_BOUNDS_INVALID");
            }
            if (window.focused) { if (focused != null) throw denied("MULTIPLE_FOCUSED_WINDOWS"); focused = window; }
            if (window.unrooted) {
                // A private display admits only the launched target and the system. Capture may
                // include small unrooted target decorations; input validation remains stricter.
                if (forInput && window.focused || ++unrooted > MAX_UNROOTED) {
                    throw denied("UNROOTED_WINDOW_LIMIT_EXCEEDED");
                }
                unrootedArea += (long) (window.bounds.right - window.bounds.left)
                        * (window.bounds.bottom - window.bounds.top);
            } else if (window.rootWindowId != window.id || !window.visible
                    || !target.packageName.equals(window.packageName)) throw denied("WINDOW_OWNER_OR_ROOT_UNCONFIRMED");
            else if (targetSurface == null || area(window) > area(targetSurface)) targetSurface = window;
        }
        if (forInput && (focused == null || focused.unrooted
                || unrootedArea > (long) target.display.width * target.display.height / 16)) {
            throw denied("FOCUSED_WINDOW_OR_DECORATION_UNSAFE");
        }
        if (targetSurface == null) throw denied("TARGET_WINDOW_UNCONFIRMED");
        int evidenceWindowId = focused != null && !focused.unrooted ? focused.id : targetSurface.id;
        return new Snapshot(evidenceWindowId,new ArrayList<>(windows));
    }
    public static void requirePoint(Snapshot snapshot,int x,int y) {
        for (Window window : snapshot.windows) {
            if (window.id == snapshot.focusedWindowId && !window.unrooted && window.focused
                    && x >= window.bounds.left && x < window.bounds.right
                    && y >= window.bounds.top && y < window.bounds.bottom) {
                // System captions can lie above a partial app dialog. They must not
                // prevent input elsewhere, but an unconfirmed surface cannot receive it.
                for (Window other : snapshot.windows) {
                    if (other.unrooted && other.layer >= window.layer
                            && x >= other.bounds.left && x < other.bounds.right
                            && y >= other.bounds.top && y < other.bounds.bottom) {
                        throw denied("POINT_OCCLUDED_BY_UNROOTED_WINDOW");
                    }
                }
                return;
            }
        }
        throw denied("POINT_OUTSIDE_FOCUSED_WINDOW");
    }
    private static long area(Window window) {
        return (long) (window.bounds.right - window.bounds.left)
                * (window.bounds.bottom - window.bounds.top);
    }
    public static void requireSame(Snapshot before,Snapshot after) {
        if (before.focusedWindowId != after.focusedWindowId || before.windows.size() != after.windows.size()) throw changed();
        Map<Integer,Window> remaining = new HashMap<>();
        for (Window window : before.windows) remaining.put(window.id,window);
        for (Window window : after.windows) {
            Window prior = remaining.remove(window.id);
            if (prior == null || prior.layer != window.layer || prior.focused != window.focused
                    || prior.unrooted != window.unrooted || !prior.bounds.equals(window.bounds)) throw changed();
        }
    }
    static final class FrameChangedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        FrameChangedException() { super("Frame changed while being captured"); }
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
    private static RejectedException denied(String reason) {
        return new RejectedException(reason);
    }
    private static FrameChangedException changed() { return new FrameChangedException(); }
}
