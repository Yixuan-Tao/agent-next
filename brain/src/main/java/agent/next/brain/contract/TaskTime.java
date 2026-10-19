package agent.next.brain.contract;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/** Structured task time, fixed at task start. No silent 09:00/07:00 defaults. */
public record TaskTime(LocalDateTime start, Duration duration, ZoneId zone) {

    public TaskTime {
        start = Objects.requireNonNull(start, "start");
        duration = Objects.requireNonNull(duration, "duration");
        zone = Objects.requireNonNull(zone, "zone");
        if (duration.isZero() || duration.isNegative() || duration.toHours() > 24) {
            throw new IllegalArgumentException("duration must be 1 minute..24 hours");
        }
    }

    public LocalDateTime end() {
        return start.plus(duration);
    }

    public long startMillis() {
        return start.atZone(zone).toInstant().toEpochMilli();
    }

    public long endMillis() {
        return end().atZone(zone).toInstant().toEpochMilli();
    }
}
