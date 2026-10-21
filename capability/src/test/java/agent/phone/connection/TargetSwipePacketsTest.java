package agent.phone.connection;

import android.os.Bundle;

public final class TargetSwipePacketsTest {
    public static void main(String[] args) {
        DisplaySession display = new DisplaySession("display",1,4,"owned",2000,1080,1920,360,1,
                DisplaySession.Status.ACTIVE);
        TargetSwipe valid = new TargetSwipe(4,6,100,200,300,500,400,3000,"observation");
        TargetSwipe decoded = TargetSwipePackets.decode(TargetSwipePackets.encode(valid),display,"observation");
        check(decoded.displayId == 4 && decoded.windowId == 6 && decoded.startX == 100 && decoded.endX == 300
                && decoded.durationMs == 400 && "observation".equals(decoded.observationId),"round trip");
        Bundle extra = TargetSwipePackets.encode(valid); extra.putString("unexpected","value");
        fails(() -> TargetSwipePackets.decode(extra,display,"observation"));
        Bundle wrongType = TargetSwipePackets.encode(valid); wrongType.putString("duration_ms","400");
        fails(() -> TargetSwipePackets.decode(wrongType,display,"observation"));
        fails(() -> TargetSwipePackets.decode(TargetSwipePackets.encode(valid),display,"other"));
        fails(() -> TargetSwipePackets.decode(TargetSwipePackets.encode(
                new TargetSwipe(4,6,0,0,1080,500,400,2,"observation")),display,"observation"));
        fails(() -> new TargetSwipe(4,6,0,0,300,500,99,2,"observation"));
        fails(() -> new TargetSwipe(4,6,0,0,300,500,2001,2,"observation"));
        System.out.println("TargetSwipePacketsTest: PASS (strict receipt, display, duration, observation)");
    }
    static void fails(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
