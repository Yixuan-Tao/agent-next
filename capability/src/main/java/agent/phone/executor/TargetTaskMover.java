package agent.phone.executor;

import agent.phone.connection.TargetSession;
import java.lang.reflect.Method;
import java.util.List;

/** Moves the target root task between its private display and the main display. */
final class TargetTaskMover {
    private TargetTaskMover() { }
    static int move(TargetSession target, int toDisplay) throws Exception {
        if (target == null || target.display == null) throw new IllegalArgumentException("Missing target");
        int fromDisplay = target.display.displayId;
        if (toDisplay != 0 && toDisplay != fromDisplay) throw new IllegalArgumentException("Display not allowed");
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class)
                .invoke(null, "activity_task");
        Class<?> ibinder = Class.forName("android.os.IBinder");
        Object atm = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", ibinder).invoke(null, binder);
        Method list = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
        int rootTaskId = -1;
        int actualDisplay = -1;
        int[] candidates = fromDisplay == 0 ? new int[] {0,toDisplay} : new int[] {fromDisplay,0};
        for (int display : candidates) {
            List<?> roots = (List<?>) list.invoke(atm, display);
            if (roots == null) continue;
            for (Object root : roots) {
                Object top = root.getClass().getField("topActivity").get(root);
                if (top != null && top.toString().contains(target.packageName + "/")) {
                    rootTaskId = root.getClass().getField("taskId").getInt(root);
                    actualDisplay = display;
                    break;
                }
            }
            if (rootTaskId >= 0) break;
        }
        if (rootTaskId < 0) throw new IllegalStateException("Target root task is not present");
        if (actualDisplay == toDisplay) return rootTaskId;
        Method move = atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
        move.invoke(atm, rootTaskId, toDisplay);
        return rootTaskId;
    }
}
