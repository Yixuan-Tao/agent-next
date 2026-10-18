package agent.phone.capability;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Read-only probe for the hidden VirtualDisplayConfig preview API. */
public final class PreviewCapabilities {

    private PreviewCapabilities() {
    }

    public static Report probe() {
        try {
            Class<?> builder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Constructor<?> constructor = builder.getConstructor(
                    String.class, int.class, int.class, int.class);
            Method mirror = null;
            try {
                mirror = builder.getMethod("setDisplayIdToMirror", int.class);
            } catch (NoSuchMethodException ignored) {
                // API not present on this release.
            }
            boolean surface = has(builder, "setSurface", android.view.Surface.class);
            boolean flags = has(builder, "setFlags", int.class);
            boolean build = has(builder, "build");
            return new Report(true, mirror != null, surface, flags, build,
                    "constructor=" + constructor.getName());
        } catch (Throwable failure) {
            return new Report(false, false, false, false, false,
                    failure.getClass().getSimpleName());
        }
    }

    private static boolean has(Class<?> type, String name, Class<?>... parameters) {
        try {
            type.getMethod(name, parameters);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    public static final class Report {
        private final boolean configApi;
        private final boolean mirrorMethod;
        private final boolean surfaceMethod;
        private final boolean flagsMethod;
        private final boolean buildMethod;
        private final String detail;

        Report(boolean configApi, boolean mirrorMethod, boolean surfaceMethod,
                boolean flagsMethod, boolean buildMethod, String detail) {
            this.configApi = configApi;
            this.mirrorMethod = mirrorMethod;
            this.surfaceMethod = surfaceMethod;
            this.flagsMethod = flagsMethod;
            this.buildMethod = buildMethod;
            this.detail = detail;
        }

        public boolean mirrorUsable() {
            return configApi && mirrorMethod && surfaceMethod && flagsMethod && buildMethod;
        }

        @Override
        public String toString() {
            return "PreviewCapabilities{config=" + configApi
                    + ", mirror=" + mirrorMethod
                    + ", surface=" + surfaceMethod
                    + ", flags=" + flagsMethod
                    + ", build=" + buildMethod
                    + ", usable=" + mirrorUsable()
                    + ", detail=" + detail + '}';
        }
    }
}
