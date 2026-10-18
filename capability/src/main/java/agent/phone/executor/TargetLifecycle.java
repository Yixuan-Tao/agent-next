package agent.phone.executor;

import agent.phone.connection.DisplaySession;
import agent.phone.connection.TargetSession;
import agent.phone.connection.TargetObservation;
import agent.phone.connection.FrameEvidence;
import agent.phone.connection.TargetTap;
import agent.phone.connection.TargetSwipe;
import agent.phone.connection.TargetBack;
import agent.phone.connection.TargetText;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One target and observer at a time; uncertain cleanup blocks new ownership. */
public final class TargetLifecycle {
    public interface Guard { DisplayLifecycle.Session current(String token,long generation) throws Exception; }
    public interface Observer {
        void open() throws Exception;
        TargetObservation read(String id,long sequence,long started,long deadline,boolean nodes) throws Exception;
        FrameEvidence.Capture capture(String frameId,long sequence,long started,long deadline,String observationId) throws Exception;
        TargetTap tap(String observationId,int x,int y,int expectedWindowId,long deadline) throws Exception;
        TargetText setText(String observationId,String ref,String text,int expectedWindowId,long deadline) throws Exception;
        TargetSwipe swipe(String observationId,int startX,int startY,int endX,int endY,int durationMs,int expectedWindowId,long deadline) throws Exception;
        TargetBack back(String observationId,int expectedWindowId,long deadline) throws Exception;
        void close() throws Exception;
    }
    public static final class Identity {
        public final int uid; public final String launcherClass;
        public Identity(int uid,String launcherClass) { this.uid = uid; this.launcherClass = launcherClass; }
    }
    public interface UserInput {
        boolean tap(int displayId,int targetUid,int x,int y) throws Exception;
        boolean swipe(int displayId,int targetUid,int startX,int startY,int endX,int endY,int durationMs) throws Exception;
    }
    private static final UserInput NO_USER_INPUT = new UserInput() {
        @Override public boolean tap(int displayId,int targetUid,int x,int y) { return false; }
        @Override public boolean swipe(int displayId,int targetUid,int startX,int startY,int endX,int endY,int durationMs) { return false; }
    };
    public interface Platform {
        Identity resolve(String packageName) throws Exception;
        boolean foregroundTarget(String packageName) throws Exception;
        boolean supportsSecondaryDisplays() throws Exception;
        void launch(TargetSession target) throws Exception;
        Observer allocate(TargetSession target) throws Exception;
        long nowNanos();
        void pause() throws Exception;
    }
    private final Guard guard;
    private final Platform platform;
    private final UserInput userInput;
    private Observer observer;
    private TargetSession target;
    private long generation,sequence,frameSequence;
    private String lastObservationId;
    private int lastObservationWindow = -1;
    private Set<String> lastEditableRefs = Collections.emptySet();
    private boolean invalid;
    public TargetLifecycle(Guard guard,Platform platform) { this(guard,platform,NO_USER_INPUT); }

    public TargetLifecycle(Guard guard,Platform platform,UserInput userInput) {
        this.guard = guard;
        this.platform = platform;
        this.userInput = userInput;
    }

    public synchronized TargetSession bind(String taskId,String displayToken,long displayGeneration,String[] allowed,String selected) throws Exception {
        // Reject caller mistakes before altering the valid target or its observer.
        TargetSession.requireAuthorization(taskId,allowed,selected);
        DisplaySession display = current(displayToken,displayGeneration);
        Identity identity = platform.resolve(selected);
        TargetSession next = new TargetSession(display,taskId,UUID.randomUUID().toString(),generation + 1,
                selected,identity.uid,identity.launcherClass);
        close(); // Remote registration must be confirmed gone before replacing target ownership.
        target = next; generation = next.generation; sequence = 0; frameSequence = 0;
        lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
        try { check(next); return next; }
        catch (Exception failure) { invalid = true; throw failure; }
    }
    public synchronized TargetObservation run(String token,long generation,boolean launch) throws Exception {
        // Stale/malformed credentials never invalidate someone else's valid session.
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        TargetSession expected = target;
        long started = platform.nowNanos(),deadline = started + 8000000000L;
        String id = UUID.randomUUID().toString(); long nextSequence = ++sequence;
        try {
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) return refused(expected,id,nextSequence,started);
            if (observer == null) { observer = platform.allocate(expected); observer.open(); }
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) return refused(expected,id,nextSequence,started);
            if (launch) { platform.launch(expected); check(expected); }
            TargetObservation result;
            do {
                checkDeadline(deadline); check(expected);
                if (platform.foregroundTarget(expected.packageName)) return refused(expected,id,nextSequence,started);
                result = observer.read(id,nextSequence,started,deadline,!launch);
                result.requireFresh(expected,nextSequence - 1);
                check(expected);
                if (platform.foregroundTarget(expected.packageName)) return refused(expected,id,nextSequence,started);
                if (!launch || result.status != TargetObservation.Status.TARGET_NOT_READY) break;
                if (platform.nowNanos() + 100000000L >= deadline) break;
                platform.pause();
            } while (true);
            checkDeadline(deadline);
            lastObservationId = result.status == TargetObservation.Status.READY ? result.observationId : null;
            lastObservationWindow = result.status == TargetObservation.Status.READY ? result.focusedWindowId : -1;
            HashSet<String> editables = new HashSet<String>();
            if (result.status == TargetObservation.Status.READY) {
                for (TargetObservation.Node node : result.nodes) {
                    if (node.editable && node.visible && !node.password) editables.add(node.ref);
                }
            }
            lastEditableRefs = editables;
            return result;
        } catch (Exception failure) { invalid = true; throw failure; }
    }
    private TargetObservation refused(TargetSession expected,String id,long sequence,long started) throws Exception {
        lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
        check(expected);
        return new TargetObservation(expected,id,sequence,started,platform.nowNanos(),
                TargetObservation.Status.FOREGROUND_CONFLICT,-1,null,0,false,Collections.emptyList());
    }
    public synchronized FrameEvidence.Capture capture(String token,long generation) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        TargetSession expected = target;
        long started = platform.nowNanos(),deadline = started + 8000000000L;
        long next = ++frameSequence;
        String frameId = UUID.randomUUID().toString(),observationId = UUID.randomUUID().toString();
        try {
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            if (observer == null) { observer = platform.allocate(expected); observer.open(); }
            check(expected); checkDeadline(deadline);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            FrameEvidence.Capture result = observer.capture(frameId,next,started,deadline,observationId);
            result.evidence.requireFresh(expected,next - 1);
            if (!frameId.equals(result.evidence.frameId) || !observationId.equals(result.evidence.observationId)
                    || result.evidence.sequence != next || result.evidence.startedNanos != started
                    || result.evidence.endedNanos > platform.nowNanos()) throw new IllegalStateException("Frame provenance changed");
            check(expected); checkDeadline(deadline);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            // A frame is evidence of the same window; it does not invalidate the node observation
            // that authorizes one action, but the tap still re-checks the live window.
            return result;
        } catch (Exception failure) { invalid = true; throw failure; }
    }
    public synchronized void userTap(String token,long generation,int x,int y) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        if (x < 0 || y < 0 || x >= target.display.width || y >= target.display.height) {
            throw new IllegalArgumentException("Tap outside the owned display");
        }
        check(target);
        if (platform.foregroundTarget(target.packageName)) throw new IllegalStateException("Primary display conflict");
        if (!userInput.tap(target.display.displayId,target.uid,x,y)) {
            throw new IllegalStateException("User tap injection was refused");
        }
        lastObservationId = null;
        lastObservationWindow = -1;
        lastEditableRefs = Collections.emptySet();
    }

    public synchronized void userSwipe(String token,long generation,int startX,int startY,
            int endX,int endY,int durationMs) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0
                || startX >= target.display.width || endX >= target.display.width
                || startY >= target.display.height || endY >= target.display.height) {
            throw new IllegalArgumentException("Swipe outside the owned display");
        }
        check(target);
        if (platform.foregroundTarget(target.packageName)) throw new IllegalStateException("Primary display conflict");
        if (!userInput.swipe(target.display.displayId,target.uid,startX,startY,endX,endY,durationMs)) {
            throw new IllegalStateException("User swipe injection was refused");
        }
        lastObservationId = null;
        lastObservationWindow = -1;
        lastEditableRefs = Collections.emptySet();
    }

    public synchronized int moveToDisplay(String token,long generation,int displayId) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        check(target);
        if (observer != null) {
            try { observer.close(); } catch (Exception failure) { observer = null; invalid = true; throw failure; }
            observer = null;
        }
        int root = TargetTaskMover.move(target,displayId);
        lastObservationId = null;
        lastObservationWindow = -1;
        lastEditableRefs = Collections.emptySet();
        return root;
    }
    public synchronized TargetTap tap(String token,long generation,String observationId,int x,int y) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        if (lastObservationId == null || !lastObservationId.equals(observationId)) {
            throw new IllegalStateException("Observation is no longer actionable");
        }
        TargetSession expected = target;
        if (x < 0 || y < 0 || x >= expected.display.width || y >= expected.display.height) {
            throw new IllegalArgumentException("Tap outside the owned display");
        }
        long started = platform.nowNanos(),deadline = started + 8000000000L;
        int observedWindow = lastObservationWindow;
        try {
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            if (observer == null) throw new IllegalStateException("Target observer is not open");
            TargetTap receipt = observer.tap(observationId,x,y,observedWindow,deadline);
            checkDeadline(deadline); check(expected);
            receipt.requireFor(expected.display,observationId);
            if (receipt.windowId != observedWindow) throw new IllegalStateException("Observed window changed before the tap");
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            // One observation authorizes at most one action; the client must re-observe.
            lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
            return receipt;
        } catch (Exception failure) { invalid = true; throw failure; }
    }
    public synchronized TargetText setText(String token,long generation,String observationId,String ref,String text) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        if (lastObservationId == null || !lastObservationId.equals(observationId)) {
            throw new IllegalStateException("Observation is no longer actionable");
        }
        if (ref == null || !lastEditableRefs.contains(ref)) {
            throw new IllegalStateException("Referenced field is not an actionable editable node");
        }
        if (text == null || text.isEmpty() || text.length() > 120
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid text for a single-line field");
        }
        TargetSession expected = target;
        long started = platform.nowNanos(),deadline = started + 8000000000L;
        int observedWindow = lastObservationWindow;
        try {
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            if (observer == null) throw new IllegalStateException("Target observer is not open");
            TargetText receipt = observer.setText(observationId,ref,text,observedWindow,deadline);
            checkDeadline(deadline); check(expected);
            receipt.requireFor(expected.display,observationId,ref);
            if (receipt.windowId != observedWindow) throw new IllegalStateException("Observed window changed before text input");
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
            return receipt;
        } catch (Exception failure) { invalid = true; throw failure; }
    }
    public synchronized TargetSwipe swipe(String token,long generation,String observationId,
            int startX,int startY,int endX,int endY,int durationMs) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        if (lastObservationId == null || !lastObservationId.equals(observationId)) {
            throw new IllegalStateException("Observation is no longer actionable");
        }
        TargetSession expected = target;
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0
                || startX >= expected.display.width || endX >= expected.display.width
                || startY >= expected.display.height || endY >= expected.display.height) {
            throw new IllegalArgumentException("Swipe outside the owned display");
        }
        if (durationMs < 100 || durationMs > 2000) throw new IllegalArgumentException("Swipe duration out of range");
        long started = platform.nowNanos(),deadline = started + 8000000000L;
        int observedWindow = lastObservationWindow;
        try {
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            if (observer == null) throw new IllegalStateException("Target observer is not open");
            TargetSwipe receipt = observer.swipe(observationId,startX,startY,endX,endY,durationMs,observedWindow,deadline);
            checkDeadline(deadline); check(expected);
            receipt.requireFor(expected.display,observationId);
            if (receipt.windowId != observedWindow) throw new IllegalStateException("Observed window changed before the swipe");
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
            return receipt;
        } catch (Exception failure) { invalid = true; throw failure; }
    }

    public synchronized TargetBack back(String token,long generation,String observationId) throws Exception {
        if (target == null || !target.token.equals(token) || target.generation != generation) {
            throw new SecurityException("Unknown target credentials");
        }
        if (invalid) throw new IllegalStateException("Observer requires confirmed cleanup");
        if (lastObservationId == null || !lastObservationId.equals(observationId)) {
            throw new IllegalStateException("Observation is no longer actionable");
        }
        TargetSession expected = target;
        long started = platform.nowNanos(),deadline = started + 8000000000L;
        int observedWindow = lastObservationWindow;
        try {
            check(expected);
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            if (observer == null) throw new IllegalStateException("Target observer is not open");
            TargetBack receipt = observer.back(observationId,observedWindow,deadline);
            checkDeadline(deadline); check(expected);
            receipt.requireFor(expected.display,observationId);
            if (receipt.windowId != observedWindow) throw new IllegalStateException("Observed window changed before back");
            if (platform.foregroundTarget(expected.packageName)) throw new IllegalStateException("Primary display conflict");
            lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
            return receipt;
        } catch (Exception failure) { invalid = true; throw failure; }
    }

    private DisplaySession current(String token,long generation) throws Exception {
        DisplayLifecycle.Session current = guard.current(token,generation);
        if (current == null || current.status != DisplayLifecycle.Status.ACTIVE || current.info == null) {
            throw new IllegalStateException("Owned display is no longer active");
        }
        DisplayLifecycle.Info i = current.info;
        return new DisplaySession(current.token,current.generation,i.displayId,i.uniqueId,i.ownerUid,
                i.width,i.height,i.densityDpi,i.flags,DisplaySession.Status.ACTIVE);
    }
    private void check(TargetSession expected) throws Exception {
        if (!platform.supportsSecondaryDisplays()) throw new IllegalStateException("Secondary activity displays are not supported");
        Identity actual = platform.resolve(expected.packageName);
        if (!expected.display.sameIdentity(current(expected.display.token,expected.display.generation))
                || actual.uid != expected.uid || !expected.launcherClass.equals(actual.launcherClass)) {
            throw new IllegalStateException("Owned display or target installation changed");
        }
    }
    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || platform.nowNanos() >= deadline) {
            throw new IllegalStateException("Target request deadline expired");
        }
    }
    public synchronized void close() throws Exception {
        invalid = true;
        if (observer != null) observer.close();
        observer = null; target = null; invalid = false;
        lastObservationId = null; lastObservationWindow = -1; lastEditableRefs = Collections.emptySet();
    }
}
