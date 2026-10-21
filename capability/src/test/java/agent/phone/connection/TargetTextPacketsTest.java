package agent.phone.connection;

import android.os.Bundle;

public final class TargetTextPacketsTest {
    public static void main(String[] args) {
        DisplaySession display = new DisplaySession("display",1,4,"owned",2000,1080,1920,360,1,
                DisplaySession.Status.ACTIVE);
        TargetText valid = new TargetText(4,6,"observation:2",5,3000,"observation");
        TargetText decoded = TargetTextPackets.decode(TargetTextPackets.encode(valid),display,"observation","observation:2");
        check(decoded.displayId == 4 && decoded.windowId == 6 && decoded.textLength == 5
                && "observation:2".equals(decoded.ref) && "observation".equals(decoded.observationId),"round trip");
        Bundle extra = TargetTextPackets.encode(valid); extra.putString("unexpected","value");
        fails(() -> TargetTextPackets.decode(extra,display,"observation","observation:2"));
        Bundle wrongType = TargetTextPackets.encode(valid); wrongType.putString("text_length","5");
        fails(() -> TargetTextPackets.decode(wrongType,display,"observation","observation:2"));
        fails(() -> TargetTextPackets.decode(TargetTextPackets.encode(valid),display,"other","observation:2"));
        fails(() -> TargetTextPackets.decode(TargetTextPackets.encode(valid),display,"observation","observation:3"));
        fails(() -> new TargetText(4,6,"observation:2",0,2,"observation"));
        fails(() -> new TargetText(4,6,"observation:2",121,2,"observation"));
        fails(() -> new TargetText(4,6,"",2,2,"observation"));
        fails(() -> TargetTextPackets.decode(TargetTextPackets.encode(new TargetText(5,6,"observation:2",2,2,"observation")),
                display,"observation","observation:2"));
        System.out.println("TargetTextPacketsTest: PASS (strict receipt, field, observation, length)");
    }
    static void fails(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
