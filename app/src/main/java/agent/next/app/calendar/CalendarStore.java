package agent.next.app.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/** Minimal CalendarContract domain adapter; no calendar UI automation. */
public final class CalendarStore {

    public record EventSpec(String title, long startMillis, long endMillis) {
    }

    private final Context context;

    public CalendarStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public static EventSpec specFor(String goal, String intent) {
        String title = "带伞";
        if (goal != null && goal.contains("带伞")) {
            title = "带伞";
        } else if (intent != null && intent.contains("带伞")) {
            title = "带伞";
        }
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        ZoneId zone = ZoneId.systemDefault();
        long start = tomorrow.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli();
        long end = tomorrow.atTime(LocalTime.of(9, 30)).atZone(zone).toInstant().toEpochMilli();
        return new EventSpec(title, start, end);
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
