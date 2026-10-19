package agent.next.brain.contract;

import java.time.LocalDate;
import java.time.ZoneId;

import static agent.next.brain.TestSupport.check;

/** M2 clarification gate tests. */
public final class TaskClarificationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final ZoneId ZONE = ZoneId.of("Australia/Adelaide");

    private TaskClarificationTest() {
    }

    public static void run() {
        check(TaskClarification.detect("明天提醒我面试", TODAY, ZONE).isPresent(),
                "missing time asks for clarification");
        check(TaskClarification.detect("周五下午7点提醒我面试", TODAY, ZONE).isEmpty(),
                "complete time needs no clarification");
        check(TaskClarification.detect("25:00 提醒我面试", TODAY, ZONE).isPresent(),
                "invalid time asks for clarification");
        check(TaskClarification.detect("查上海天气", TODAY, ZONE).isEmpty(),
                "non-time task needs no clarification");
        check(TaskClarification.detect("", TODAY, ZONE).isPresent(),
                "empty goal asks for clarification");
        System.out.println("TaskClarificationTest: PASS (missing/invalid time, non-time task)");
    }
}
