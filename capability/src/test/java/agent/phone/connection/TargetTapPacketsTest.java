package agent.phone.connection;

import android.os.Bundle;

public final class TargetTapPacketsTest {
    public static void main(String[] args) {
        DisplaySession display = new DisplaySession("display",1,4,"owned",2000,1080,1920,360,1,
                DisplaySession.Status.ACTIVE);
        TargetTap valid = new TargetTap(4,6,100,200,3000,"observation");
        Bundle stale = TargetTapPackets.stale(new TargetTap.StaleException(4,"observation",null));
        try { TargetTapPackets.decode(stale,display,"observation"); throw new AssertionError("expected stale input"); }
        catch (TargetTap.StaleException expected) { check(expected.displayId == 4,"bound stale input response"); }
        fails(() -> TargetTapPackets.decode(stale,display,"other"));
        stale.putInt("display_id",5);
        fails(() -> TargetTapPackets.decode(stale,display,"observation"));
        Bundle packet = TargetTapPackets.encode(valid);
        TargetTap decoded = TargetTapPackets.decode(packet,display,"observation");
        check(decoded.displayId == 4 && decoded.windowId == 6 && decoded.x == 100 && decoded.y == 200
                && decoded.atNanos == 3000 && "observation".equals(decoded.observationId),"round trip");
        Bundle extra = TargetTapPackets.encode(valid); extra.putString("unexpected","value");
        fails(() -> TargetTapPackets.decode(extra,display,"observation"));
        Bundle wrongType = TargetTapPackets.encode(valid); wrongType.putString("x","100");
        fails(() -> TargetTapPackets.decode(wrongType,display,"observation"));
        fails(() -> TargetTapPackets.decode(TargetTapPackets.encode(valid),display,"other"));
        fails(() -> TargetTapPackets.decode(TargetTapPackets.encode(new TargetTap(5,6,1,1,2,"observation")),
                display,"observation"));
        fails(() -> TargetTapPackets.decode(TargetTapPackets.encode(new TargetTap(4,6,1080,1,2,"observation")),
                display,"observation"));
        fails(() -> new TargetTap(4,-1,1,1,2,"observation"));
        fails(() -> new TargetTap(4,6,-1,1,2,"observation"));
        System.out.println("TargetTapPacketsTest: PASS (strict receipt, display, bounds, observation)");
    }
    static void fails(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
