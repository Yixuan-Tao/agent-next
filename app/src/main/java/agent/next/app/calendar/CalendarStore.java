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
        String goalText = goal == null ? "" : goal;
        String intentText = intent == null ? "" : intent;
        String text = goalText + " " + intentText;
        String title = extractTitle(goalText);
        if (title == null) {
            title = extractTitle(intentText);
        }
        if (title == null) {
            title = text.contains("面试") ? "面试"
                    : text.contains("带伞") ? "带伞" : "Agent 日程";
        }
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
        } else if (text.contains("周二") || text.contains("星期二")) {
            target = DayOfWeek.TUESDAY;
        } else if (text.contains("周三") || text.contains("星期三")) {
            target = DayOfWeek.WEDNESDAY;
        } else if (text.contains("周四") || text.contains("星期四")) {
            target = DayOfWeek.THURSDAY;
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

    private static String extractTitle(String text) {
        Matcher matcher = Pattern.compile(
                "(?:提醒我|(?:日程|事件)?标题\\s*[为是:：]?)\\s*[「“\"']?"
                        + "([^，。；;\\s」”\"']{1,40})")
                .matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String title = matcher.group(1).trim().replaceAll("[」”\"']$", "").trim();
        return title.isEmpty() ? null : title;
    }

    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?:(上午|下午|晚上|中午|凌晨)\\s*)?(\\d{1,2})\\s*[:：.]\\s*(\\d{1,2})");

    private static final Pattern SPOKEN_TIME = Pattern.compile(
            "(?:(上午|下午|晚上|中午|凌晨)\\s*)?(\\d{1,2})\\s*[点時时]\\s*(半|\\d{1,2}\\s*分?)?");

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
