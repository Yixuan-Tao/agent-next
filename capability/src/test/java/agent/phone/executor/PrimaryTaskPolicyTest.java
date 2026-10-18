package agent.phone.executor;

public final class PrimaryTaskPolicyTest {
    public static void main(String[] args) {
        check(PrimaryTaskPolicy.isTarget(0,true,new int[]{7},"com.example.target","com.example.target"),"single visible target conflicts");
        check(!PrimaryTaskPolicy.isTarget(0,true,new int[]{7},"com.example.other","com.example.target"),"single unrelated app may coexist");
        check(!PrimaryTaskPolicy.isTarget(0,false,null,null,"com.example.target"),"invisible root ignored");
        // A root's topActivity describes only one child, so a different top cannot clear a split root.
        denied(() -> PrimaryTaskPolicy.isTarget(0,true,new int[]{7,8},"com.example.other","com.example.target"));
        denied(() -> PrimaryTaskPolicy.isTarget(0,true,null,"com.example.other","com.example.target"));
        denied(() -> PrimaryTaskPolicy.isTarget(0,true,new int[0],"com.example.other","com.example.target"));
        denied(() -> PrimaryTaskPolicy.isTarget(0,true,new int[]{7},null,"com.example.target"));
        denied(() -> PrimaryTaskPolicy.isTarget(4,true,new int[]{7},"com.example.other","com.example.target"));
        System.out.println("PrimaryTaskPolicyTest: PASS (single task, split root, missing identity, wrong display)");
    }
    private static void denied(Runnable action) {
        try { action.run(); throw new AssertionError("expected fail closed"); } catch (IllegalStateException expected) { }
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
