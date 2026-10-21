package agent.phone.connection;

import java.util.ArrayList;
import java.util.Collections;

public final class TargetObservationTest {
    public static void main(String[] args) {
        hiddenInvertedNodeGeometryHasNoActionableArea();
        DisplaySession display = new DisplaySession("token", 1, 4, "owned", 2000, 1080, 1920, 360, 1, DisplaySession.Status.ACTIVE);
        TargetSession target = new TargetSession(display,"task","target",1,"com.example.app",10123,"com.example.app.Main");
        ArrayList<TargetObservation.Node> nodes = new ArrayList<>();
        TargetObservation observation = new TargetObservation(target, "observation", 2, 100, 200,
                TargetObservation.Status.READY, 6, new TargetObservation.Bounds(0, 0, 1080, 1920), 2, false, nodes);
        nodes.add(node(false, "example"));
        check(observation.nodes.isEmpty(), "copy collection at boundary");
        try { observation.nodes.add(node(false, "example")); throw new AssertionError("mutable nodes"); }
        catch (UnsupportedOperationException expected) { }
        observation.requireFresh(target, 1);
        denied(() -> observation.requireFresh(target, 2));
        denied(() -> observation.requireFresh(new TargetSession(display,"task","oldtarget",1,"com.example.app",10123,"com.example.app.Main"), 0));
        denied(() -> node(true, "secret"));
        denied(() -> new TargetSession(display,"task","target",1,"com.example.app",2000,"com.example.app.Main"));
        denied(() -> new TargetObservation(target, "id", 3, 10, 11,
                TargetObservation.Status.FOREIGN_WINDOW, 6, new TargetObservation.Bounds(0,0,1080,1920), 2, false,
                Collections.singletonList(node(false, "foreign"))));
        System.out.println("TargetObservationTest: PASS");
    }
    private static void hiddenInvertedNodeGeometryHasNoActionableArea() {
        TargetObservation.Bounds hidden = TargetObservation.nodeBounds(331,186,389,128,false);
        check(hidden.equals(new TargetObservation.Bounds(0,0,0,0)),"hidden inverted geometry becomes empty, never sorted");
        denied(() -> TargetObservation.nodeBounds(331,186,389,128,true));
        denied(() -> new TargetObservation.Bounds(331,186,389,128));
        check(TargetObservation.nodeBounds(0,0,1080,1920,true).equals(new TargetObservation.Bounds(0,0,1080,1920)),
                "visible valid geometry is preserved");
        check(TargetObservation.nodeBounds(-20,-10,10,5,false).equals(new TargetObservation.Bounds(-20,-10,10,5)),
                "valid hidden geometry is preserved");
    }
    private static TargetObservation.Node node(boolean password, String text) {
        return new TargetObservation.Node("observation:0",0,6,-1,new TargetObservation.Bounds(0,0,20,20),
                text,"","","",false,false,true,password,true,false,false,false);
    }
    private static void denied(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); } catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
