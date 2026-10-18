package agent.next.brain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class MemoryTest {

    private MemoryTest() {
    }

    static void run() {
        policyDefaults();
        workingMemory();
        episodicMemory();
        eventValidation();
        System.out.println("MemoryTest: PASS (D4 defaults, working/episodic stores, event immutability)");
    }

    private static void policyDefaults() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        check(policy.workingCheckpointTtl().equals(Duration.ofHours(24)), "checkpoint ttl");
        check(policy.episodicRetention().equals(Duration.ofDays(30)), "episodic retention");
        check(policy.semanticRevalidation().equals(Duration.ofDays(90)), "semantic revalidation");
        check(policy.userPreferenceRevalidation().equals(Duration.ofDays(180)), "preference revalidation");
        check(policy.evidenceRetention().equals(Duration.ofDays(7)), "evidence retention");
        check(!policy.evidenceOptIn(), "evidence off by default");

        expect(IllegalArgumentException.class,
                () -> new MemoryPolicy(Duration.ZERO, Duration.ofDays(30), Duration.ofDays(90),
                        Duration.ofDays(180), Duration.ofDays(7), false),
                "zero checkpoint ttl");
        expect(IllegalArgumentException.class,
                () -> new MemoryPolicy(Duration.ofHours(24), Duration.ofDays(-1), Duration.ofDays(90),
                        Duration.ofDays(180), Duration.ofDays(7), false),
                "negative retention");
    }

    private static void workingMemory() {
        InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
        check(memory.get("missing").isEmpty(), "missing value");
        memory.put("page", "cart");
        check(memory.get("page").orElseThrow().equals("cart"), "stored value");

        Map<String, String> snapshot = memory.snapshot();
        expect(UnsupportedOperationException.class,
                () -> snapshot.put("new", "value"), "snapshot immutable");
        memory.clear();
        check(memory.snapshot().isEmpty(), "cleared");
        expect(IllegalArgumentException.class, () -> memory.put(" ", "value"), "blank key");
        expect(NullPointerException.class, () -> memory.put("key", null), "null value");
    }

    private static void episodicMemory() {
        InMemoryEpisodicMemory memory = new InMemoryEpisodicMemory();
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        TaskEvent first = event("t1", now, TaskEvent.EventType.ACTION, "tap");
        TaskEvent second = event("t1", now.plusSeconds(1), TaskEvent.EventType.EFFECT, "changed");
        TaskEvent other = event("t2", now, TaskEvent.EventType.FAILURE, "stuck");

        memory.append(first);
        memory.append(second);
        memory.append(other);
        check(memory.events("t1").equals(List.of(first, second)), "ordered task events");
        check(memory.events("missing").isEmpty(), "no events for unknown task");

        memory.deleteTask("t1");
        check(memory.events("t1").isEmpty(), "deleted task events");
        check(memory.events("t2").equals(List.of(other)), "other task retained");
    }

    private static void eventValidation() {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        Map<String, String> source = new HashMap<>();
        source.put("target", "search");
        TaskEvent event = new TaskEvent("t1", now, TaskEvent.EventType.ACTION, "tap", source);
        source.put("late", "value");
        check(!event.data().containsKey("late"), "event copies data");
        expect(UnsupportedOperationException.class,
                () -> event.data().put("new", "value"), "event data immutable");
        expect(IllegalArgumentException.class,
                () -> new TaskEvent(" ", now, TaskEvent.EventType.ACTION, "tap", Map.of()), "blank task id");
        expect(IllegalArgumentException.class,
                () -> new TaskEvent("t1", now, TaskEvent.EventType.ACTION, " ", Map.of()), "blank summary");
        expect(NullPointerException.class,
                () -> new TaskEvent("t1", now, null, "tap", Map.of()), "null event type");
    }

    private static TaskEvent event(
            String taskId, Instant at, TaskEvent.EventType type, String summary) {
        return new TaskEvent(taskId, at, type, summary, Map.of("step", summary));
    }
}
