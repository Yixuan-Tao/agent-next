package agent.phone.connection;

public final class FrameEvidenceTest {
    public static void main(String[] args) {
        TargetSession target = target("target",1);
        FrameEvidence frame = frame(target,1,45);
        frame.requireFresh(target,0);
        fails(() -> frame.requireFresh(target,1));
        fails(() -> frame.requireFresh(target("replacement",2),0));
        fails(() -> frame(target,0,45));
        fails(() -> frame(target,1,FrameEvidence.MAX_PNG_BYTES + 1));
        fails(() -> new FrameEvidence(target,"frame",1,4,3,"observation",6,1080,1920,45));
        fails(() -> new FrameEvidence(target,"frame",1,3,4,"observation",6,1081,1920,45));
        fails(() -> new FrameEvidence(target,"frame",1,3,4,"observation",6,1080,1919,45));
        fails(() -> new FrameEvidence(target,"frame",1,3,4,"",6,1080,1920,45));
        byte[] png = png();
        FrameEvidence.Capture capture = new FrameEvidence.Capture(frame,png);
        png[0] = 0;
        check(capture.png()[0] == (byte) 137,"input is copied");
        byte[] output = capture.png(); output[0] = 0;
        check(capture.png()[0] == (byte) 137,"output is copied");
        byte[] wrong = png(); wrong[19]++;
        fails(() -> new FrameEvidence.Capture(frame,wrong));
        fails(() -> new FrameEvidence.Capture(frame,new byte[45]));
        fails(() -> new FrameEvidence.Capture(frame,new byte[44]));
        System.out.println("FrameEvidenceTest: PASS (identity, sequence, time, dimensions, bounded immutable PNG)");
    }
    public static TargetSession target(String token,long generation) {
        DisplaySession display = new DisplaySession("display",1,4,"owned",2000,1080,1920,360,1,DisplaySession.Status.ACTIVE);
        return new TargetSession(display,"task",token,generation,"com.example.first",10123,"com.example.first.Main");
    }
    public static FrameEvidence frame(TargetSession target,long sequence,int bytes) {
        return new FrameEvidence(target,"frame",sequence,3,4,"observation",6,1080,1920,bytes);
    }
    public static byte[] png() {
        byte[] bytes = new byte[45];
        byte[] signature = {(byte) 137,80,78,71,13,10,26,10};
        System.arraycopy(signature,0,bytes,0,8);
        bytes[11] = 13; bytes[12] = 73; bytes[13] = 72; bytes[14] = 68; bytes[15] = 82;
        putInt(bytes,16,1080); putInt(bytes,20,1920);
        bytes[24] = 8; bytes[25] = 6;
        return bytes;
    }
    private static void putInt(byte[] bytes,int at,int value) {
        bytes[at] = (byte) (value >>> 24); bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8); bytes[at + 3] = (byte) value;
    }
    static void fails(Runnable action) { try { action.run(); throw new AssertionError("expected rejection"); } catch (IllegalArgumentException expected) { } }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
