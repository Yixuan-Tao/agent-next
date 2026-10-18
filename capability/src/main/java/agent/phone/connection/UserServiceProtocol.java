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
    private UserServiceProtocol() { }
    static Bundle add() {
        Bundle packet = base();
        packet.putInt(USER_SERVICE_ARG_VERSION_CODE, 11);
        packet.putString(USER_SERVICE_ARG_PROCESS_NAME, "agent_shell");
        packet.putBoolean(USER_SERVICE_ARG_DEBUGGABLE, false);
        packet.putBoolean(USER_SERVICE_ARG_DAEMON, false);
        packet.putBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false);
        return packet;
    }

    static Bundle remove(boolean remove) {
        Bundle packet = base();
        packet.putBoolean(USER_SERVICE_ARG_REMOVE, remove);
        return packet;
    }

    private static Bundle base() {
        Bundle packet = new Bundle();
        packet.putParcelable(USER_SERVICE_ARG_COMPONENT,
                new ComponentName("agent.phone", "agent.phone.executor.ShellService"));
        packet.putString(USER_SERVICE_ARG_TAG, "agent.phone.connection.v9");
        return packet;
    }
}
