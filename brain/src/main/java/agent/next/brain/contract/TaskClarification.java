package agent.next.brain.contract;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Deterministic clarification gate for missing or invalid task parameters.
 * It never invents a default time or location.
 */
public final class TaskClarification {

    private static final String[] TIME_TASKS = {
            "提醒", "日程", "日历", "闹钟", "会议", "预约", "面试"
    };

    private TaskClarification() {
    }

    public static Optional<String> detect(String goal, LocalDate today, ZoneId zone) {
        if (goal == null || goal.isBlank()) {
            return Optional.of("请用一句话描述要完成的任务");
        }
        boolean needsTime = false;
        for (String marker : TIME_TASKS) {
            if (goal.contains(marker)) {
                needsTime = true;
                break;
            }
        }
        boolean mentionsTime = TaskTimeParser.mentionsTime(goal);
        if (needsTime && !mentionsTime) {
            return Optional.of("请提供具体日期和时间，例如“明天下午7点”。");
        }
        if (mentionsTime && TaskTimeParser.parse(goal, today, zone).isEmpty()) {
            return Optional.of("时间无法解析，请提供具体日期和时间。");
        }
        return Optional.empty();
    }
}
