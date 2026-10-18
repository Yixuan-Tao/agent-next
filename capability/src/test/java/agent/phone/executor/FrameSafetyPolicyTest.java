package agent.phone.executor;

import agent.phone.connection.FrameEvidenceTest;
import agent.phone.connection.TargetObservation;
import agent.phone.connection.TargetSession;
import java.util.Arrays;
import java.util.Collections;

public final class FrameSafetyPolicyTest {
    public static void main(String[] args) {
        TargetSession target = FrameEvidenceTest.target("target",1);
        FrameSafetyPolicy.Window focused = window(6,true,"com.example.first",true,true,0,0,720,1280);
        FrameSafetyPolicy.Snapshot first = FrameSafetyPolicy.validate(target,Collections.singletonList(focused));
        FrameSafetyPolicy.requireSame(first,FrameSafetyPolicy.validate(target,Collections.singletonList(focused)));
        FrameSafetyPolicy.Window dots = unrooted(9,true,340,26,380,34);
        FrameSafetyPolicy.Snapshot withDots = FrameSafetyPolicy.validate(target,Arrays.asList(focused,dots));
        FrameSafetyPolicy.requireSame(withDots,FrameSafetyPolicy.validate(target,Arrays.asList(focused,unrooted(9,true,340,26,380,34))));
        fails(() -> FrameSafetyPolicy.validate(target,Collections.emptyList()));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,window(7,false,"com.foreign.overlay",true,true,0,0,10,10))));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,window(7,false,null,true,true,0,0,10,10))));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,window(7,false,"com.example.first",false,true,0,0,10,10))));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,window(7,false,"com.example.first",true,false,0,0,10,10))));
        fails(() -> FrameSafetyPolicy.validate(target,Collections.singletonList(unrooted(9,false,340,26,380,34))));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,unrooted(9,false,340,26,380,34))));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,unrooted(9,true,0,0,720,1280))));
        fails(() -> FrameSafetyPolicy.validate(target,Arrays.asList(focused,unrooted(9,true,10,10,10,20))));
        fails(() -> FrameSafetyPolicy.validate(target,Collections.singletonList(window(6,true,"com.example.first",true,true,0,0,720,1200))));
        fails(() -> FrameSafetyPolicy.requireSame(first,withDots));
        fails(() -> FrameSafetyPolicy.requireSame(withDots,FrameSafetyPolicy.validate(target,
                Arrays.asList(focused,window(9,false,"com.example.first",true,true,340,26,380,34)))));
        fails(() -> FrameSafetyPolicy.requireSame(first,FrameSafetyPolicy.validate(target,
                Collections.singletonList(window(8,true,"com.example.first",true,true,0,0,720,1280)))));
        fails(() -> FrameSafetyPolicy.requireSame(first,FrameSafetyPolicy.validate(target,
                Arrays.asList(focused,window(7,false,"com.example.first",true,true,0,0,10,10)))));
        System.out.println("FrameSafetyPolicyTest: PASS (owned windows, unrooted target decoration, focus and overlay change)");
    }
    static FrameSafetyPolicy.Window window(int id,boolean focus,String pkg,boolean visible,boolean application,
            int left,int top,int right,int bottom) {
        return new FrameSafetyPolicy.Window(4,id,id,focus,application,visible,pkg,0,new TargetObservation.Bounds(left,top,right,bottom));
    }
    static FrameSafetyPolicy.Window unrooted(int id,boolean application,int left,int top,int right,int bottom) {
        return new FrameSafetyPolicy.Window(4,id,-1,false,application,false,null,0,
                new TargetObservation.Bounds(left,top,right,bottom),true);
    }
    static void fails(Runnable action) { try { action.run(); throw new AssertionError("expected rejection"); } catch (IllegalStateException expected) { } }
}
