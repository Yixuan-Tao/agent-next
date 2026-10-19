package agent.next.app.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal CalendarContract domain adapter; no calendar UI automation. */
public final class CalendarStore {

    public record EventSpec(String title, long startMillis, long endMillis) {
    }

    private final Context context;

    public CalendarStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public static EventSpec specFor(String goal, String intent) {
        String text = (goal == null ? "" : goal) + " " + (intent == null ? "" : intent);
        String title = text.contains("面试") ? "准备 Agent 开发面试"
                : text.contains("带伞") ? "带伞" : "Agent 日程";
        LocalDate day = LocalDate.now();
        DayOfWeek target = null;
        if (text.contains("周五") || text.contains("星期五")) {
            target = DayOfWeek.FRIDAY;
        } else if (text.contains("周六") || text.contains("星期六")) {
            target = DayOfWeek.SATURDAY;
        } else if (text.contains("周日") || text.contains("星期日")
                || text.contains("星期天")) {
            target = DayOfWeek.SUNDAY;
        } else if (text.contains("周一") || text.contains("星期一")) {
            target = DayOfWeek.MONDAY;
        } else if (text.contains("明天")) {
            day = day.plusDays(1);
        }
        if (target != null) {
            int delta = (target.getValue() - day.getDayOfWeek().getValue() + 7) % 7;
            day = day.plusDays(delta == 0 ? 7 : delta);
        }
        LocalTime time = parseTime(text);
        if (time == null) {
            time = LocalTime.of(9, 0);
        }
        ZoneId zone = ZoneId.systemDefault();
        long start = day.atTime(time).atZone(zone).toInstant().toEpochMilli();
        long end = day.atTime(time.plusMinutes(60)).atZone(zone).toInstant().toEpochMilli();
        return new EventSpec(title, start, end);
    }

    private static LocalTime parseTime(String text) {
        Matcher matcher = Pattern.compile(
                "(?:(上午|下午|晚上|中午)\\s*)?(\\d{1,2})\\s*[:：.]\\s*(\\d{2})")
                .matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String period = matcher.group(1);
        int hour = Integer.parseInt(matcher.group(2));
        int minute = Integer.parseInt(matcher.group(3));
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) {
            hour += 12;
        } else if ("中午".equals(period) && hour < 11) {
            hour += 12;
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    public boolean exists(EventSpec spec) {
        try (android.database.Cursor cursor = context.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI,
                new String[] {CalendarContract.Events._ID},
                CalendarContract.Events.TITLE + "=? AND "
                        + CalendarContract.Events.DTSTART + "=?",
                new String[] {spec.title(), Long.toString(spec.startMillis())},
                null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public boolean insert(EventSpec spec) {
        if (exists(spec)) {
            return true;
        }
        long calendarId;
        ContentResolver resolver = context.getContentResolver();
        try (android.database.Cursor cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[] {CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL},
                CalendarContract.Calendars.VISIBLE + "=1",
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            calendarId = -1;
            do {
                long candidate = cursor.getLong(0);
                int access = cursor.getInt(1);
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    calendarId = candidate;
                    break;
                }
            } while (cursor.moveToNext());
            if (calendarId < 0) {
                return false;
            }
        } catch (RuntimeException failure) {
            return false;
        }
        ContentValues event = new ContentValues();
        event.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        event.put(CalendarContract.Events.TITLE, spec.title());
        event.put(CalendarContract.Events.DTSTART, spec.startMillis());
        event.put(CalendarContract.Events.DTEND, spec.endMillis());
        event.put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().getId());
        event.put(CalendarContract.Events.HAS_ALARM, 1);
        try {
            Uri uri = resolver.insert(CalendarContract.Events.CONTENT_URI, event);
            if (uri == null) {
                return false;
            }
            long eventId = ContentUris.parseId(uri);
            ContentValues reminder = new ContentValues();
            reminder.put(CalendarContract.Reminders.EVENT_ID, eventId);
            reminder.put(CalendarContract.Reminders.MINUTES, 0);
            reminder.put(CalendarContract.Reminders.METHOD,
                    CalendarContract.Reminders.METHOD_ALERT);
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
