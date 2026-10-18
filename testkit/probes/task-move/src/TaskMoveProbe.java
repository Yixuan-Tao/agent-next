import android.os.IBinder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class TaskMoveProbe {
    static Object atm() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity_task");
        return Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    static void printRoots(Object atm, int display) throws Exception {
        Method list = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
        List<?> roots = (List<?>) list.invoke(atm, display);
        System.out.println("display=" + display + " roots=" + roots.size());
        for (Object root : roots) {
            Class<?> c = root.getClass();
            int task = c.getField("taskId").getInt(root);
            int disp = c.getField("displayId").getInt(root);
            Object top = c.getField("topActivity").get(root);
            boolean visible = c.getField("visible").getBoolean(root);
            System.out.println("rootTask=" + task + " display=" + disp + " visible=" + visible + " top=" + top);
        }
    }
    public static void main(String[] args) {
        try {
            Object atm = atm();
            if (args.length >= 1 && args[0].equals("list")) {
                printRoots(atm, Integer.parseInt(args[1]));
                return;
            }
            if (args.length >= 4 && args[0].equals("move")) {
                int from = Integer.parseInt(args[1]), to = Integer.parseInt(args[2]);
                String pkg = args[3];
                printRoots(atm, from);
                Method list = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
                List<?> roots = (List<?>) list.invoke(atm, from);
                int rootId = -1;
                for (Object root : roots) {
                    Object top = root.getClass().getField("topActivity").get(root);
                    if (top != null && top.toString().contains(pkg + "/")) {
                        rootId = root.getClass().getField("taskId").getInt(root);
                        break;
                    }
                }
                if (rootId < 0) throw new IllegalStateException("no root for " + pkg);
                Method move = atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
                move.invoke(atm, rootId, to);
                System.out.println("moved root=" + rootId + " to display=" + to);
                return;
            }
            for (Method method : atm.getClass().getMethods()) {
                String name = method.getName();
                if (name.startsWith("move") || name.startsWith("getAllRoot")) {
                    StringBuilder out = new StringBuilder(name).append('(');
                    Class<?>[] types = method.getParameterTypes();
                    for (int i = 0; i < types.length; i++) {
                        if (i > 0) out.append(',');
                        out.append(types[i].getSimpleName());
                    }
                    System.out.println(out.append(')'));
                }
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
