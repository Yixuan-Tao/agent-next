package agent.next.brain.contract;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal deterministic date/time extraction. It returns empty when no time is
 * present; callers must ask for clarification instead of defaulting.
 */
public final class TaskTimeParser {

    private static final Pattern CLOCK = Pattern.compile(
            "(?:(上午|下午|晚上|中午|凌晨)\\s*)?(\\d{1,2})\\s*[:：.]\\s*(\\d{1,2})");
    private static final Pattern SPOKEN = Pattern.compile(
            "(?:(上午|下午|晚上|中午|凌晨)\\s*)?(\\d{1,2})\\s*[点時时]\\s*(半|\\d{1,2}\\s*分?)?");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");

    private TaskTimeParser() {
    }

    public static Optional<TaskTime> parse(String goal, LocalDate today, ZoneId zone) {
        if (goal == null || goal.isBlank()) {
            return Optional.empty();
        }
        LocalTime time = parseTime(goal);
        if (time == null) {
            return Optional.empty();
        }
        LocalDate date = parseDate(goal, today);
        if (date == null) {
            date = today;
        }
        return Optional.of(new TaskTime(date.atTime(time), Duration.ofHours(1), zone));
    }

    public static boolean mentionsTime(String goal) {
        return goal != null && (CLOCK.matcher(goal).find() || SPOKEN.matcher(goal).find());
    }

    private static LocalDate parseDate(String text, LocalDate today) {
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.find()) {
            int month = Integer.parseInt(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                LocalDate candidate = LocalDate.of(today.getYear(), month, day);
                return candidate.isBefore(today) ? candidate.plusYears(1) : candidate;
            }
        }
        if (text.contains("明天")) {
            return today.plusDays(1);
        }
        if (text.contains("今天") || text.contains("今日")) {
            return today;
        }
        DayOfWeek weekday = weekday(text);
        if (weekday != null) {
            int delta = (weekday.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
            return today.plusDays(delta);
        }
        return null;
    }

    private static DayOfWeek weekday(String text) {
        if (text.contains("周一") || text.contains("星期一")) {
            return DayOfWeek.MONDAY;
        }
        if (text.contains("周二") || text.contains("星期二")) {
            return DayOfWeek.TUESDAY;
        }
        if (text.contains("周三") || text.contains("星期三")) {
            return DayOfWeek.WEDNESDAY;
        }
        if (text.contains("周四") || text.contains("星期四")) {
            return DayOfWeek.THURSDAY;
        }
        if (text.contains("周五") || text.contains("星期五")) {
            return DayOfWeek.FRIDAY;
        }
        if (text.contains("周六") || text.contains("星期六")) {
            return DayOfWeek.SATURDAY;
        }
        if (text.contains("周日") || text.contains("星期日") || text.contains("星期天")) {
            return DayOfWeek.SUNDAY;
        }
        return null;
    }

    private static LocalTime parseTime(String text) {
        Matcher matcher = CLOCK.matcher(text);
        if (matcher.find()) {
            return toTime(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = SPOKEN.matcher(text);
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
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) {
            hour += 12;
        } else if ("中午".equals(period) && hour < 11) {
            hour += 12;
        } else if ("凌晨".equals(period) && hour == 12) {
            hour = 0;
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }
}
