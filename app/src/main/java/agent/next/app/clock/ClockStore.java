package agent.next.app.clock;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import java.time.LocalTime;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sets a Clock alarm through AlarmClock; no time-picker UI automation. */
public final class ClockStore {

    public record AlarmSpec(int hour, int minute, String label) {
    }

    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?:(上午|下午|晚上|中午|凌晨)\\s*)?(\\d{1,2})\\s*[:：.]\\s*(\\d{1,2})");

    private static final Pattern SPOKEN_TIME = Pattern.compile(
            "(?:(上午|下午|晚上|中午|凌晨)\\s*)?(\\d{1,2})\\s*[点時时]\\s*(半|\\d{1,2}\\s*分?)?");

    private final Context context;

    public ClockStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public static AlarmSpec specFor(String goal, String intent) {
        String text = (goal == null ? "" : goal) + " " + (intent == null ? "" : intent);
        LocalTime time = parseTime(text);
        if (time == null) {
            time = LocalTime.of(7, 0);
        }
        String label = text.contains("面试") ? "面试"
                : text.contains("起床") ? "起床" : "Agent 闹钟";
        return new AlarmSpec(time.getHour(), time.getMinute(), label);
    }

    public boolean exists(AlarmSpec spec) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) {
            return false;
        }
        AlarmManager.AlarmClockInfo next = manager.getNextAlarmClock();
        if (next == null) {
            return false;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(next.getTriggerTime());
        return calendar.get(Calendar.HOUR_OF_DAY) == spec.hour()
                && calendar.get(Calendar.MINUTE) == spec.minute();
    }

    public boolean insert(AlarmSpec spec) {
        try {
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, spec.hour())
                    .putExtra(AlarmClock.EXTRA_MINUTES, spec.minute())
                    .putExtra(AlarmClock.EXTRA_MESSAGE, spec.label())
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static LocalTime parseTime(String text) {
        Matcher matcher = CLOCK_TIME.matcher(text);
        if (matcher.find()) {
            return toTime(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = SPOKEN_TIME.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String minutes = matcher.group(3);
        if (minutes == null || minutes.isBlank()) {
            minutes = "0";
        } else if (minutes.startsWith("半")) {
            minutes = "30";
        } else {
            minutes = minutes.replace("分", "").trim();
        }
        return toTime(matcher.group(1), matcher.group(2), minutes);
    }

    private static LocalTime toTime(String period, String hourText, String minuteText) {
        int hour = Integer.parseInt(hourText);
        int minute = Integer.parseInt(minuteText);
        if ("凌晨".equals(period) && hour == 12) {
            hour = 0;
        } else if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) {
            hour += 12;
        } else if ("中午".equals(period) && hour < 11) {
            hour += 12;
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }
}
