package agent.next.brain.contract;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static agent.next.brain.TestSupport.check;

/** M2 structured time parsing; no silent defaults. */
public final class TaskTimeTest {

    private static final ZoneId ZONE = ZoneId.of("Australia/Adelaide");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private TaskTimeTest() {
    }

    public static void run() {
        tomorrowEvening();
        crossMidnight();
        weekday();
        missingTime();
        System.out.println("TaskTimeTest: PASS (tomorrow, weekday, cross-midnight, missing)");
    }

    private static void tomorrowEvening() {
        TaskTime time = parse("明天下午7点提醒我面试");
        check(time.start().equals(TODAY.plusDays(1).atTime(LocalTime.of(19, 0))),
                "tomorrow 19:00");
        check(time.end().equals(time.start().plusHours(1)), "one hour duration");
    }

    private static void crossMidnight() {
        TaskTime time = parse("23:30 夜间会议");
        check(time.start().toLocalDate().equals(TODAY), "today start");
        check(time.end().toLocalDate().equals(TODAY.plusDays(1)), "next-day end");
        check(time.endMillis() - time.startMillis() == 3_600_000L, "positive duration");
    }

    private static void weekday() {
        TaskTime time = parse("周五19:00复盘");
        check(time.start().toLocalDate().equals(LocalDate.of(2026, 10, 23)), "next Friday");
    }

    private static void missingTime() {
        check(TaskTimeParser.parse("明天提醒我面试", TODAY, ZONE).isEmpty(),
                "missing time must not default");
        check(TaskTimeParser.mentionsTime("明天提醒我面试") == false, "no time mentioned");
    }

    private static TaskTime parse(String goal) {
        return TaskTimeParser.parse(goal, TODAY, ZONE).orElseThrow(
                () -> new AssertionError("expected time: " + goal));
    }
}
