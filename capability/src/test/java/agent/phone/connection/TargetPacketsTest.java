package agent.phone.connection;

import android.os.Bundle;
import java.util.Collections;

public final class TargetPacketsTest {
    public static void main(String[] args) {
        DisplaySession display = new DisplaySession("display",1,4,"owned",2000,1080,1920,360,1,DisplaySession.Status.ACTIVE);
        TargetSession first = target(display,"first",1,"com.example.first",10123);
        TargetSession second = target(display,"second",2,"com.example.second",10124);
        Bundle bound = TargetPackets.encode(first);
        check(TargetPackets.decode(bound,display,"task","com.example.first").sameIdentity(first),"binding round trip");
        denied(() -> TargetPackets.decode(bound,display,"other-task","com.example.first"));
        denied(() -> TargetPackets.decode(bound,display,"task","com.example.second"));
        Bundle invalid = TargetPackets.encode(first); invalid.putString("target_uid","10123");
        denied(() -> TargetPackets.decode(invalid,display,"task","com.example.first"));
        Bundle extra = TargetPackets.encode(first); extra.putString("command","hidden");
        denied(() -> TargetPackets.decode(extra,display,"task","com.example.first"));
        TargetObservation observation = new TargetObservation(first,"observation",1,100,200,
                TargetObservation.Status.READY,6,new TargetObservation.Bounds(0,0,1080,1920),2,false,Collections.emptyList());
        Bundle packet = ObservationPackets.encode(observation);
        check(ObservationPackets.decode(packet,first,0).target.sameIdentity(first),"observation round trip");
        denied(() -> ObservationPackets.decode(packet,second,0));
        denied(() -> ObservationPackets.decode(packet,target(display,"first",2,"com.example.first",10123),0));
        denied(() -> ObservationPackets.decode(packet,target(display,"first",1,"com.example.first",10125),0));
        denied(() -> ObservationPackets.decode(packet,new TargetSession(display,"other-task","first",1,
                "com.example.first",10123,"com.example.first.Main"),0));
        DisplaySession newer = new DisplaySession("new-display",2,4,"owned",2000,1080,1920,360,1,DisplaySession.Status.ACTIVE);
        denied(() -> ObservationPackets.decode(packet,target(newer,"first",1,"com.example.first",10123),0));
        denied(() -> ObservationPackets.decode(packet,first,1));
        Bundle forged = ObservationPackets.encode(observation);
        ((Bundle) forged.get("target")).putInt("target_uid",10124);
        denied(() -> ObservationPackets.decode(forged,first,0));
        System.out.println("TargetPacketsTest: PASS (strict binding, target/task/display/UID, replay)");
    }
    private static TargetSession target(DisplaySession d,String token,long generation,String pkg,int uid) {
        return new TargetSession(d,"task",token,generation,pkg,uid,pkg + ".Main");
    }
    private static void denied(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); } catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
