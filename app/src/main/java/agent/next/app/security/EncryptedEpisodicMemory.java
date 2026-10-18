package agent.next.app.security;

import android.content.Context;

import agent.next.brain.EpisodicMemory;
import agent.next.brain.TaskEvent;
import agent.next.brain.model.Json;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encrypted, best-effort episodic memory; no raw frames or full UI trees. */
public final class EncryptedEpisodicMemory implements EpisodicMemory {

    private static final int MAX_EVENTS = 500;
    private final Context context;
    private final File file;
    private final List<TaskEvent> events = new ArrayList<>();

    public EncryptedEpisodicMemory(Context context) {
        this.context = context.getApplicationContext();
        this.file = new File(this.context.getFilesDir(), "episodic.enc");
        load();
    }

    @Override
    public synchronized void append(TaskEvent event) {
        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        persist();
    }

    @Override
    public synchronized List<TaskEvent> events(String taskId) {
        List<TaskEvent> result = new ArrayList<>();
        for (TaskEvent event : events) {
            if (event.taskId().equals(taskId)) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void deleteTask(String taskId) {
        events.removeIf(event -> event.taskId().equals(taskId));
        persist();
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized void clear() {
        events.clear();
        persist();
    }

    private void load() {
        try {
            String json = EncryptedFileStore.read(context, file);
            if (json.isBlank()) {
                return;
            }
            Object root = Json.parse(json);
            if (!(root instanceof List<?> list)) {
                return;
            }
            for (Object value : list) {
                if (value instanceof Map<?, ?> map) {
                    TaskEvent event = decode(map);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            events.clear();
        }
    }

    private void persist() {
        try {
            EncryptedFileStore.write(context, file, encode(events));
        } catch (IOException ignored) {
            // best effort; callers still get the in-memory event for this run
        }
    }

    private static TaskEvent decode(Map<?, ?> value) {
        try {
            String taskId = String.valueOf(value.get("taskId"));
            Instant at = Instant.ofEpochMilli(((Number) value.get("at")).longValue());
            TaskEvent.EventType type = TaskEvent.EventType.valueOf(
                    String.valueOf(value.get("type")));
            String summary = String.valueOf(value.get("summary"));
            Map<String, String> data = new LinkedHashMap<>();
            Object dataValue = value.get("data");
            if (dataValue instanceof Map<?, ?> dataMap) {
                for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                    data.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return new TaskEvent(taskId, at, type, summary, data);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String encode(List<TaskEvent> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            TaskEvent event = values.get(index);
            json.append("{\"taskId\":").append(Json.quote(event.taskId()))
                    .append(",\"at\":").append(event.at().toEpochMilli())
                    .append(",\"type\":").append(Json.quote(event.type().name()))
                    .append(",\"summary\":").append(Json.quote(event.summary()))
                    .append(",\"data\":{");
            int entryIndex = 0;
            for (Map.Entry<String, String> entry : event.data().entrySet()) {
                if (entryIndex++ > 0) {
                    json.append(',');
                }
                json.append(Json.quote(entry.getKey())).append(':')
                        .append(Json.quote(entry.getValue()));
            }
            json.append("}}");
        }
        return json.append(']').toString();
    }
}
