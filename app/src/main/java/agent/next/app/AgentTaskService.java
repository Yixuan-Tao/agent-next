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
import android.util.Log;

/** Keeps the process alive while an autonomous task runs; Stop action is deliberate. */
public final class AgentTaskService extends Service {

    private static final String CHANNEL = "agent-task";
    private static final int NOTIFICATION_ID = 41;
    private static final String ACTION_STOP = "agent.next.app.STOP_TASK";
    private static final String ACTION_RESUME = "agent.next.app.RESUME_TASK";
    private static final String ACTION_HANDOFF = "agent.next.app.HANDOFF_TASK";
    private static final String ACTION_SOAK = "agent.next.app.SOAK";
    private static final String EXTRA_REASON = "reason";
    private static final String EXTRA_MINUTES = "minutes";

    /** Non-null while a handoff is waiting for the user; keeps the action notification alive. */
    private static volatile String handoffReason;

    public static void start(Context context) {
        Intent intent = new Intent(context, AgentTaskService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Debug/soak only: keep a foreground service alive for a bounded interval. */
    public static void startSoak(Context context, int minutes) {
        Intent intent = new Intent(context, AgentTaskService.class)
                .setAction(ACTION_SOAK)
                .putExtra(EXTRA_MINUTES, minutes);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        cancelNotification(context);
        context.stopService(new Intent(context, AgentTaskService.class));
    }

    private static void cancelNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    public static void update(Context context, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        String reason = handoffReason;
        manager.notify(NOTIFICATION_ID, reason == null
                ? notification(context, text)
                // A normal state update must not replace the handoff actions the user still needs.
                : handoffNotification(context, reason));
    }

    public static void handoff(Context context, String reason) {
        handoffReason = reason;
        Intent intent = new Intent(context, AgentTaskService.class)
                .setAction(ACTION_HANDOFF)
                .putExtra(EXTRA_REASON, reason);
        context.startService(intent);
    }

    /** Called when the handoff ends; the next state update may restore the normal notification. */
    public static void finishHandoff(Context context) {
        handoffReason = null;
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
    public void onDestroy() {
        handoffReason = null;
        cancelNotification(this);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("AgentNextService", "action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            handoffReason = null;
            cancelNotification(this);
            AgentRuntime.get(this).stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            handoffReason = null;
            AgentRuntime.get(this).resumeHandoff();
            AgentRuntime runtime = AgentRuntime.get(this);
            update(this, runtime.detail());
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_HANDOFF.equals(intent.getAction())) {
            String reason = intent.getStringExtra(EXTRA_REASON);
            handoffReason = reason == null ? "需要人工接管" : reason;
            startForeground(NOTIFICATION_ID, handoffNotification(this, handoffReason));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SOAK.equals(intent.getAction())) {
            int minutes = Math.max(1, Math.min(20,
                    intent.getIntExtra(EXTRA_MINUTES, 1)));
            startForeground(NOTIFICATION_ID,
                    notification(this, "Foreground soak " + minutes + "min"));
            new android.os.Handler(getMainLooper()).postDelayed(this::stopSelf,
                    minutes * 60_000L);
            return START_NOT_STICKY;
        }
        AgentRuntime runtime = AgentRuntime.get(this);
        String reason = handoffReason;
        startForeground(NOTIFICATION_ID, reason == null
                ? notification(this, runtime.detail())
                : handoffNotification(this, reason));
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

    private static Notification handoffNotification(Context context, String reason) {
        Intent open = new Intent(context, PreviewActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent resume = new Intent(context, AgentTaskService.class).setAction(ACTION_RESUME);
        PendingIntent resumeIntent = PendingIntent.getService(context, 2, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(context, AgentTaskService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(context, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("AgentNext 需要人工接管")
                .setContentText(reason + " · 点通知查看并主动接管，完成后点“继续”")
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "完成，继续", resumeIntent).build())
                .addAction(new Notification.Action.Builder(null, "停止", stopIntent).build())
                .build();
    }
}
