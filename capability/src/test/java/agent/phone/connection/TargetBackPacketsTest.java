package agent.phone.connection;

import android.os.Bundle;

public final class TargetBackPacketsTest {
    public static void main(String[] args) {
        DisplaySession display = new DisplaySession("display",1,4,"owned",2000,1080,1920,360,1,
                DisplaySession.Status.ACTIVE);
        TargetBack valid = new TargetBack(4,6,3000,"observation");
        TargetBack decoded = TargetBackPackets.decode(TargetBackPackets.encode(valid),display,"observation");
        check(decoded.displayId == 4 && decoded.windowId == 6 && decoded.atNanos == 3000
                && "observation".equals(decoded.observationId),"round trip");
        Bundle extra = TargetBackPackets.encode(valid); extra.putString("unexpected","value");
        fails(() -> TargetBackPackets.decode(extra,display,"observation"));
        Bundle wrongType = TargetBackPackets.encode(valid); wrongType.putString("window_id","6");
        fails(() -> TargetBackPackets.decode(wrongType,display,"observation"));
        fails(() -> TargetBackPackets.decode(TargetBackPackets.encode(valid),display,"other"));
        fails(() -> new TargetBack(4,-1,2,"observation"));
        fails(() -> new TargetBack(4,6,0,"observation"));
        System.out.println("TargetBackPacketsTest: PASS (strict receipt, display, observation)");
    }
    static void fails(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
