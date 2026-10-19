package agent.phone.connection;

import android.content.pm.PackageManager;
import android.os.Build;
import agent.phone.executor.ClientPolicy;
import agent.phone.executor.ProtocolPolicy;
import rikka.shizuku.Shizuku;
import static agent.phone.connection.ConnectionState.Reason;
import static agent.phone.connection.ConnectionState.Status;

final class ConnectionChecks {
    private ConnectionChecks() { }

    static ConnectionState inspect() {
        if (!ClientPolicy.isOwnerAppUid(android.os.Process.myUid())
                || Build.VERSION.SDK_INT < 33 || Build.VERSION.SDK_INT > 36) {
            return state(Status.UNSUPPORTED, Reason.ENVIRONMENT);
        }
        if (!Shizuku.pingBinder()) return state(Status.NEEDS_SHIZUKU, Reason.NO_SHIZUKU);
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
            return state(Status.UNSUPPORTED, Reason.OLD_VERSION);
        }
        if (!ProtocolPolicy.isSupported(Build.VERSION.SDK_INT, Shizuku.getUid())) {
            return state(Status.UNSUPPORTED, Reason.ENVIRONMENT);
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return state(Status.PERMISSION_REQUIRED, Shizuku.shouldShowRequestPermissionRationale()
                    ? Reason.BLOCKED : Reason.DENIED);
        }
        return state(Status.DISCONNECTED, Reason.AVAILABLE);
    }

    private static ConnectionState state(Status status, Reason reason) {
        return new ConnectionState(status, reason, null, false);
    }
}
