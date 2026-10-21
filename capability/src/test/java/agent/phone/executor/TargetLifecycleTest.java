package agent.phone.executor;

import agent.phone.connection.DisplaySession;
import agent.phone.connection.TargetSession;
import agent.phone.connection.TargetObservation;
import agent.phone.connection.FrameEvidence;
import agent.phone.connection.FrameEvidenceTest;
import agent.phone.connection.TargetTap;
import agent.phone.connection.TargetText;
import agent.phone.connection.TargetSwipe;
import agent.phone.connection.TargetBack;
import java.util.Collections;

public final class TargetLifecycleTest {
    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture();
        TargetSession a = f.bind("com.example.first");
        TargetObservation first = f.lifecycle.run(a.token,a.generation,true);
        check(first.status == TargetObservation.Status.READY && f.launches == 1,"authorized launch");
        fails(() -> f.lifecycle.bind("task","display",1,new String[]{"com.example.first"},"com.other.app"));
        fails(() -> f.lifecycle.bind("task","display",1,new String[]{"bad;command"},"bad;command"));
        fails(() -> f.lifecycle.run("stale",a.generation,false));
        check(f.lifecycle.run(a.token,a.generation,false).sequence > first.sequence,"bad requests preserve session");
        f.foreground = true;
        check(f.lifecycle.run(a.token,a.generation,true).status == TargetObservation.Status.FOREGROUND_CONFLICT
                && f.launches == 1,"primary target conflict refuses launch");
        f.foreground = false;
        TargetSession b = f.bind("com.example.second");
        check(f.closes == 1 && !a.token.equals(b.token) && b.generation > a.generation,"rebind closes old observer");
        fails(() -> f.lifecycle.run(a.token,a.generation,false));
        check(f.lifecycle.run(b.token,b.generation,false).target.packageName.equals("com.example.second"),"second app observed");
        fails(() -> first.requireFresh(b,0));
        f.failClose = true; fails(() -> f.bind("com.example.first"));
        fails(() -> f.lifecycle.run(b.token,b.generation,false));
        check(f.allocations == 2,"failed close forbids another allocation");
        f.failClose = false; f.lifecycle.close();
        TargetSession c = f.bind("com.example.first"); f.lifecycle.run(c.token,c.generation,false);
        f.uid++; fails(() -> f.lifecycle.run(c.token,c.generation,false)); f.uid--;
        fails(() -> f.lifecycle.run(c.token,c.generation,false));
        Fixture launcher = new Fixture(); TargetSession l = launcher.bind("com.example.first");
        launcher.launcher = "Other"; fails(() -> launcher.lifecycle.run(l.token,l.generation,true));
        Fixture changed = new Fixture(); TargetSession d = changed.bind("com.example.first");
        changed.changeDuringRead = true; fails(() -> changed.lifecycle.run(d.token,d.generation,false));
        Fixture primary = new Fixture(); TargetSession p = primary.bind("com.example.first");
        primary.foregroundDuringRead = true;
        check(primary.lifecycle.run(p.token,p.generation,false).status == TargetObservation.Status.FOREGROUND_CONFLICT,
                "post read primary contention discards content");
        Fixture partial = new Fixture(); TargetSession q = partial.bind("com.example.first"); partial.failOpen = true;
        fails(() -> partial.lifecycle.run(q.token,q.generation,false));
        fails(() -> partial.lifecycle.run(q.token,q.generation,false));
        partial.failClose = true; fails(() -> partial.bind("com.example.second"));
        check(partial.allocations == 1,"partial connection retained");
        Fixture timeout = new Fixture(); TargetSession t = timeout.bind("com.example.first"); timeout.ready = false;
        check(timeout.lifecycle.run(t.token,t.generation,true).status == TargetObservation.Status.TARGET_NOT_READY
                && timeout.now <= 8000000100L,"one bounded launch deadline");
        Fixture changedUid = new Fixture(); TargetSession u = changedUid.bind("com.example.first");
        changedUid.uidDuringRead = true; fails(() -> changedUid.lifecycle.run(u.token,u.generation,false));
        Fixture unsupported = new Fixture(); unsupported.secondarySupported = false;
        fails(() -> unsupported.bind("com.example.first"));
        check(unsupported.allocations == 0 && unsupported.launches == 0,"unsupported secondary displays never launch");
        Fixture featureLost = new Fixture(); TargetSession feature = featureLost.bind("com.example.first");
        featureLost.secondarySupported = false;
        fails(() -> featureLost.lifecycle.run(feature.token,feature.generation,true));
        check(featureLost.allocations == 0 && featureLost.launches == 0,"lost secondary support blocks before allocation");
        capturesUseTheCurrentObserverAndRejectChangedOwnership();
        tapsRequireTheLatestObservationAndWindow();
        textsRequireTheLatestObservationAndField();
        swipesAndBacksRequireTheLatestObservation();
        audioMustBeIsolatedBeforeLaunch();
        slowObservationsCanRecoverWithoutReusingOldInputEvidence();
        staleTapBeforeInjectionCanRecover();
        System.out.println("TargetLifecycleTest: PASS");
    }
    private static void staleTapBeforeInjectionCanRecover() throws Exception {
        Fixture f = new Fixture(); TargetSession target = f.bind("com.example.first");
        TargetObservation observed = f.lifecycle.run(target.token,target.generation,false);
        f.staleBeforeTap = true;
        fails(() -> f.lifecycle.tap(target.token,target.generation,observed.observationId,100,100));
        f.staleBeforeTap = false;
        fails(() -> f.lifecycle.tap(target.token,target.generation,observed.observationId,100,100));
        TargetObservation fresh = f.lifecycle.run(target.token,target.generation,false);
        f.lifecycle.tap(target.token,target.generation,fresh.observationId,100,100);
        check(f.allocations == 1,"a pre-injection window change requires a fresh observation, not a new session");
    }
    private static void slowObservationsCanRecoverWithoutReusingOldInputEvidence() throws Exception {
        Fixture f = new Fixture();
        TargetSession target = f.bind("com.example.first");
        TargetObservation first = f.lifecycle.run(target.token,target.generation,false);
        f.expireDuringRead = true;
        TargetObservation slow = f.lifecycle.run(target.token,target.generation,false);
        check(slow.status == TargetObservation.Status.TARGET_NOT_READY && slow.nodes.isEmpty(),
                "observation deadline returns no actionable content without breaking the connection");
        fails(() -> f.lifecycle.tap(target.token,target.generation,first.observationId,100,100));
        f.expireDuringRead = false;
        TargetObservation recovered = f.lifecycle.run(target.token,target.generation,false);
        check(recovered.status == TargetObservation.Status.READY && recovered.sequence > slow.sequence
                && f.allocations == 1 && f.closes == 0,
                "a later observation recovers on the same authorized observer");
        f.lifecycle.tap(target.token,target.generation,recovered.observationId,100,100);
        f.expireDuringRead = true; f.throwTimeoutDuringRead = true;
        check(f.lifecycle.run(target.token,target.generation,false).status == TargetObservation.Status.TARGET_NOT_READY,
                "a deadline thrown inside the observer is also retryable");
        f.expireDuringRead = false; f.throwTimeoutDuringRead = false;
        check(f.lifecycle.run(target.token,target.generation,false).status == TargetObservation.Status.READY,
                "observer timeout does not invalidate the authorized target");
        Fixture changed = new Fixture(); TargetSession other = changed.bind("com.example.first");
        changed.expireDuringRead = true; changed.throwTimeoutDuringRead = true; changed.changeDuringRead = true;
        fails(() -> changed.lifecycle.run(other.token,other.generation,false));
        changed.expireDuringRead = false; changed.changeDuringRead = false; changed.invalid = false;
        fails(() -> changed.lifecycle.run(other.token,other.generation,false));
    }
    private static void audioMustBeIsolatedBeforeLaunch() throws Exception {
        Fixture f = new Fixture();
        TargetSession target = f.bind("com.example.first");
        f.failAudio = true;
        fails(() -> f.lifecycle.run(target.token,target.generation,true));
        check(f.launches == 0 && f.allocations == 0,"unavailable audio isolation blocks target launch");
        Fixture foreground = new Fixture();
        TargetSession other = foreground.bind("com.example.first");
        foreground.foreground = true;
        foreground.lifecycle.run(other.token,other.generation,true);
        check(foreground.audioMutes == 0,"a user's foreground app must not be muted on refusal");
        Fixture lateForeground = new Fixture();
        TargetSession late = lateForeground.bind("com.example.first");
        lateForeground.lifecycle.run(late.token,late.generation,false);
        check(lateForeground.audioMutes == 1 && lateForeground.audioReleases == 0,
                "agent-bound target starts muted");
        lateForeground.foreground = true;
        check(lateForeground.lifecycle.run(late.token,late.generation,false).status
                        == TargetObservation.Status.FOREGROUND_CONFLICT
                        && lateForeground.audioReleases == 1,
                "post-bind foreground refusal hands sound back to the user");
        lateForeground.foreground = false;
        lateForeground.lifecycle.close();
        check(lateForeground.audioReleases == 1,"close after user handback does not double release");
    }
    static void capturesUseTheCurrentObserverAndRejectChangedOwnership() throws Exception {
        Fixture f = new Fixture(); TargetSession target = f.bind("com.example.first");
        f.lifecycle.run(target.token,target.generation,true);
        FrameEvidence.Capture first = f.lifecycle.capture(target.token,target.generation);
        FrameEvidence.Capture second = f.lifecycle.capture(target.token,target.generation);
        check(first.evidence.target.sameIdentity(target) && second.evidence.sequence > first.evidence.sequence,
                "capture evidence matches target and increases");
        check(f.allocations == 1 && f.captures == 2,"capture reuses persistent observer");
        fails(() -> f.lifecycle.capture("stale",target.generation));
        check(f.lifecycle.capture(target.token,target.generation).evidence.sequence > second.evidence.sequence,
                "stale request preserves valid observer");
        f.foregroundDuringRead = true;
        fails(() -> f.lifecycle.capture(target.token,target.generation));
        fails(() -> f.lifecycle.capture(target.token,target.generation));
        Fixture changed = new Fixture(); TargetSession other = changed.bind("com.example.first");
        changed.uidDuringRead = true; fails(() -> changed.lifecycle.capture(other.token,other.generation));
        Fixture deadline = new Fixture(); TargetSession timed = deadline.bind("com.example.first");
        deadline.expireDuringCapture = true; fails(() -> deadline.lifecycle.capture(timed.token,timed.generation));
        Fixture wrongId = new Fixture(); TargetSession mismatch = wrongId.bind("com.example.first");
        wrongId.wrongFrameId = true; fails(() -> wrongId.lifecycle.capture(mismatch.token,mismatch.generation));
    }
    static void tapsRequireTheLatestObservationAndWindow() throws Exception {
        Fixture f = new Fixture(); TargetSession target = f.bind("com.example.first");
        TargetObservation first = f.lifecycle.run(target.token,target.generation,true);
        f.lifecycle.tap(target.token,target.generation,first.observationId,100,200);
        fails(() -> f.lifecycle.tap(target.token,target.generation,first.observationId,100,200));
        TargetObservation second = f.lifecycle.run(target.token,target.generation,false);
        fails(() -> f.lifecycle.tap(target.token,target.generation,first.observationId,100,200));
        fails(() -> f.lifecycle.tap(target.token,target.generation,second.observationId,1080,200));
        Fixture edge = new Fixture(); TargetSession edgeTarget = edge.bind("com.example.first");
        TargetObservation edgeObservation = edge.lifecycle.run(edgeTarget.token,edgeTarget.generation,true);
        edge.lifecycle.tap(edgeTarget.token,edgeTarget.generation,edgeObservation.observationId,1079,1919);
        fails(() -> f.lifecycle.tap("stale",target.generation,second.observationId,100,200));
        f.foreground = true;
        fails(() -> f.lifecycle.tap(target.token,target.generation,second.observationId,100,200));
        Fixture wrong = new Fixture(); TargetSession wt = wrong.bind("com.example.first");
        TargetObservation wo = wrong.lifecycle.run(wt.token,wt.generation,true); wrong.wrongTapWindow = true;
        fails(() -> wrong.lifecycle.tap(wt.token,wt.generation,wo.observationId,10,10));
        Fixture captured = new Fixture(); TargetSession ct = captured.bind("com.example.first");
        TargetObservation co = captured.lifecycle.run(ct.token,ct.generation,true);
        captured.lifecycle.capture(ct.token,ct.generation);
        captured.lifecycle.tap(ct.token,ct.generation,co.observationId,10,10);
        fails(() -> captured.lifecycle.tap(ct.token,ct.generation,co.observationId,10,10));
    }
    static void textsRequireTheLatestObservationAndField() throws Exception {
        Fixture f = new Fixture(); TargetSession target = f.bind("com.example.first");
        TargetObservation first = f.lifecycle.run(target.token,target.generation,true);
        String ref = first.nodes.get(0).ref;
        f.lifecycle.setText(target.token,target.generation,first.observationId,ref,"hello");
        fails(() -> f.lifecycle.setText(target.token,target.generation,first.observationId,ref,"again"));
        Fixture unknown = new Fixture(); TargetSession ut = unknown.bind("com.example.first");
        TargetObservation uo = unknown.lifecycle.run(ut.token,ut.generation,true);
        fails(() -> unknown.lifecycle.setText(ut.token,ut.generation,uo.observationId,"not-a-field","hello"));
        Fixture newline = new Fixture(); TargetSession nt = newline.bind("com.example.first");
        TargetObservation no = newline.lifecycle.run(nt.token,nt.generation,true);
        fails(() -> newline.lifecycle.setText(nt.token,nt.generation,no.observationId,no.nodes.get(0).ref,"line\nbreak"));
        Fixture stale = new Fixture(); TargetSession st = stale.bind("com.example.first");
        TargetObservation so = stale.lifecycle.run(st.token,st.generation,true);
        fails(() -> stale.lifecycle.setText("stale",st.generation,so.observationId,so.nodes.get(0).ref,"hello"));
        Fixture primary = new Fixture(); TargetSession pt = primary.bind("com.example.first");
        TargetObservation po = primary.lifecycle.run(pt.token,pt.generation,true);
        primary.foreground = true;
        fails(() -> primary.lifecycle.setText(pt.token,pt.generation,po.observationId,po.nodes.get(0).ref,"hello"));
        Fixture wrong = new Fixture(); TargetSession wt = wrong.bind("com.example.first");
        TargetObservation wo = wrong.lifecycle.run(wt.token,wt.generation,true); wrong.wrongTapWindow = true;
        fails(() -> wrong.lifecycle.setText(wt.token,wt.generation,wo.observationId,wo.nodes.get(0).ref,"hello"));
        Fixture captured = new Fixture(); TargetSession ct = captured.bind("com.example.first");
        TargetObservation co = captured.lifecycle.run(ct.token,ct.generation,true);
        captured.lifecycle.capture(ct.token,ct.generation);
        captured.lifecycle.setText(ct.token,ct.generation,co.observationId,co.nodes.get(0).ref,"hello");
        fails(() -> captured.lifecycle.setText(ct.token,ct.generation,co.observationId,co.nodes.get(0).ref,"again"));
    }
    static void swipesAndBacksRequireTheLatestObservation() throws Exception {
        Fixture f = new Fixture(); TargetSession target = f.bind("com.example.first");
        TargetObservation first = f.lifecycle.run(target.token,target.generation,true);
        f.lifecycle.swipe(target.token,target.generation,first.observationId,100,200,100,900,400);
        fails(() -> f.lifecycle.swipe(target.token,target.generation,first.observationId,100,200,100,900,400));
        TargetObservation second = f.lifecycle.run(target.token,target.generation,false);
        f.lifecycle.back(target.token,target.generation,second.observationId);
        fails(() -> f.lifecycle.back(target.token,target.generation,second.observationId));
        Fixture bounds = new Fixture(); TargetSession bt = bounds.bind("com.example.first");
        TargetObservation bo = bounds.lifecycle.run(bt.token,bt.generation,true);
        fails(() -> bounds.lifecycle.swipe(bt.token,bt.generation,bo.observationId,0,0,1080,900,400));
        fails(() -> bounds.lifecycle.swipe(bt.token,bt.generation,bo.observationId,0,0,100,900,99));
        Fixture primary = new Fixture(); TargetSession pt = primary.bind("com.example.first");
        TargetObservation po = primary.lifecycle.run(pt.token,pt.generation,true);
        primary.foreground = true;
        fails(() -> primary.lifecycle.back(pt.token,pt.generation,po.observationId));
        Fixture wrong = new Fixture(); TargetSession wt = wrong.bind("com.example.first");
        TargetObservation wo = wrong.lifecycle.run(wt.token,wt.generation,true); wrong.wrongTapWindow = true;
        fails(() -> wrong.lifecycle.swipe(wt.token,wt.generation,wo.observationId,0,0,100,900,400));
    }
    interface Action { void run() throws Exception; }
    static void fails(Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected failure"); }
        catch (IllegalStateException | IllegalArgumentException | SecurityException expected) { }
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    static final class Fixture implements TargetLifecycle.Platform {
        long now = 100; int uid = 10123,allocations,launches,closes,captures;
        String launcher = "Main";
        boolean secondarySupported = true;
        boolean foreground,failOpen,failClose,ready = true,changeDuringRead,foregroundDuringRead,uidDuringRead,invalid;
        boolean expireDuringRead,throwTimeoutDuringRead,expireDuringCapture,wrongFrameId,wrongTapWindow,staleBeforeTap;
        boolean failAudio;
        int audioMutes,audioReleases;
        final TargetAudioIsolation audio = new TargetAudioIsolation(uid -> {
            if (failAudio) throw new IllegalStateException("audio unavailable");
            audioMutes++;
            return () -> audioReleases++;
        });
        final DisplayLifecycle.Info info = new DisplayLifecycle.Info(4,"owned",2000,1080,1920,360,1,"agent-phone");
        final TargetLifecycle lifecycle = new TargetLifecycle((token,generation) -> {
            if (invalid || !"display".equals(token) || generation != 1) throw new SecurityException("invalid display");
            return new DisplayLifecycle.Session(token,generation,info,DisplayLifecycle.Status.ACTIVE);
        },this,audio);
        TargetSession bind(String pkg) throws Exception {
            return lifecycle.bind("task","display",1,new String[]{"com.example.first","com.example.second"},pkg);
        }
        @Override public TargetLifecycle.Identity resolve(String pkg) { return new TargetLifecycle.Identity(uid,pkg + "." + launcher); }
        @Override public boolean foregroundTarget(String pkg) { return foreground; }
        @Override public boolean supportsSecondaryDisplays() { return secondarySupported; }
        @Override public void launch(TargetSession target) { launches++; }
        @Override public long nowNanos() { return now; }
        @Override public void pause() { now += 100000000L; }
        @Override public TargetLifecycle.Observer allocate(TargetSession target) {
            allocations++;
            return new TargetLifecycle.Observer() {
                @Override public void open() { if (failOpen) throw new IllegalStateException("partial connect"); }
                @Override public TargetObservation read(String id,long sequence,long started,long deadline,boolean nodes) {
                    invalid = changeDuringRead; foreground = foregroundDuringRead; if (uidDuringRead) uid++;
                    if (expireDuringRead) now = deadline;
                    if (throwTimeoutDuringRead) throw new TargetLifecycle.RequestTimeoutException();
                    TargetObservation.Node field = new TargetObservation.Node(id + ":0",0,6,-1,
                            new TargetObservation.Bounds(0,0,1080,400),"field","","com.example:id/field",
                            "android.widget.EditText",true,true,true,false,true,true,false,false);
                    return new TargetObservation(target,id,sequence,started,now,
                            ready ? TargetObservation.Status.READY : TargetObservation.Status.TARGET_NOT_READY,
                            ready ? 6 : -1,ready ? new TargetObservation.Bounds(0,0,1080,1920) : null,2,false,
                            ready ? Collections.singletonList(field) : Collections.emptyList());
                }
                @Override public void close() { closes++; if (failClose) throw new IllegalStateException("cleanup failure"); }
                @Override public FrameEvidence.Capture capture(String id,long sequence,long started,long deadline,String observationId) {
                    captures++; invalid = changeDuringRead; foreground = foregroundDuringRead; if (uidDuringRead) uid++;
                    if (expireDuringCapture) now = deadline;
                    return new FrameEvidence.Capture(new FrameEvidence(target,wrongFrameId ? "old-frame" : id,sequence,started,now,observationId,
                            6,1080,1920,45),FrameEvidenceTest.png());
                }
                @Override public TargetTap tap(String observationId,int x,int y,int expectedWindowId,long deadline) {
                    if (staleBeforeTap) throw new TargetTap.StaleException(4,observationId,null);
                    return new TargetTap(4,wrongTapWindow ? 7 : expectedWindowId,x,y,now,observationId);
                }
                @Override public TargetText setText(String observationId,String ref,String text,int expectedWindowId,long deadline) {
                    return new TargetText(4,wrongTapWindow ? 7 : expectedWindowId,ref,text.length(),now,observationId);
                }
                @Override public TargetSwipe swipe(String observationId,int startX,int startY,int endX,int endY,
                        int durationMs,int expectedWindowId,long deadline) {
                    return new TargetSwipe(4,wrongTapWindow ? 7 : expectedWindowId,startX,startY,endX,endY,
                            durationMs,now,observationId);
                }
                @Override public TargetBack back(String observationId,int expectedWindowId,long deadline) {
                    return new TargetBack(4,wrongTapWindow ? 7 : expectedWindowId,now,observationId);
                }
            };
        }
    }
}
