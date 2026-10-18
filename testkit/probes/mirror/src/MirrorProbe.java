import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class MirrorProbe {
    public static void main(String[] args) {
        try {
            Class<?> builder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            for (Constructor<?> c : builder.getDeclaredConstructors()) {
                StringBuilder out = new StringBuilder("ctor(");
                Class<?>[] t = c.getParameterTypes();
                for (int i = 0; i < t.length; i++) { if (i > 0) out.append(','); out.append(t[i].getSimpleName()); }
                System.out.println(out.append(')'));
            }
            for (Method m : builder.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains("mirror") && !m.getName().toLowerCase().contains("surface")
                        && !m.getName().toLowerCase().contains("flag") && !m.getName().equals("build")) continue;
                StringBuilder out = new StringBuilder(m.getName()).append('(');
                Class<?>[] t = m.getParameterTypes();
                for (int i = 0; i < t.length; i++) { if (i > 0) out.append(','); out.append(t[i].getSimpleName()); }
                System.out.println(out.append(')'));
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
        }
    }
}
