package agent.next.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Keeps the process alive while an autonomous task runs; Stop action is deliberate. */
public final class AgentTaskService extends Service {

    private static final String CHANNEL = "agent-task";
    private static final int NOTIFICATION_ID = 41;
    private static final String ACTION_STOP = "agent.next.app.STOP_TASK";

    public static void start(Context context) {
        Intent intent = new Intent(context, AgentTaskService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AgentTaskService.class));
    }

    public static void update(Context context, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(context, text));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Agent tasks", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AgentRuntime.get(this).stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification(this, "任务准备中"));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static Notification notification(Context context, String text) {
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(context, AgentTaskService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(context, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("AgentNext")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "停止", stopIntent).build());
        return builder.build();
    }
}
