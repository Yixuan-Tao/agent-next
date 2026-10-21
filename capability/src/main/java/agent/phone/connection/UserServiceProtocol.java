package agent.phone.connection;

import android.os.Bundle;
import android.content.ComponentName;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TAG;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DAEMON;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_REMOVE;

final class UserServiceProtocol {
    static final String DEFAULT_PACKAGE = "agent.next.app";
    private static final String SERVICE_CLASS = "agent.phone.executor.ShellService";
    private static final String TAG = "agent.next.connection.v15";
    private static final int VERSION = 17;
    private static final String PROCESS_SUFFIX = "agent_next_shell";

    private UserServiceProtocol() { }

    static Bundle add() {
        return add(DEFAULT_PACKAGE);
    }

    static Bundle add(String ownerPackage) {
        Bundle packet = base(ownerPackage);
        packet.putInt(USER_SERVICE_ARG_VERSION_CODE, VERSION);
        packet.putString(USER_SERVICE_ARG_PROCESS_NAME, PROCESS_SUFFIX);
        packet.putBoolean(USER_SERVICE_ARG_DEBUGGABLE, false);
        packet.putBoolean(USER_SERVICE_ARG_DAEMON, false);
        packet.putBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false);
        return packet;
    }

    static Bundle remove(boolean remove) {
        return remove(remove, DEFAULT_PACKAGE);
    }

    static Bundle remove(boolean remove, String ownerPackage) {
        Bundle packet = base(ownerPackage);
        packet.putBoolean(USER_SERVICE_ARG_REMOVE, remove);
        return packet;
    }

    private static Bundle base(String ownerPackage) {
        if (ownerPackage == null || ownerPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("Owner package is required");
        }
        Bundle packet = new Bundle();
        packet.putParcelable(USER_SERVICE_ARG_COMPONENT,
                new ComponentName(ownerPackage, SERVICE_CLASS));
        packet.putString(USER_SERVICE_ARG_TAG, TAG);
        return packet;
    }
}
