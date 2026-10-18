package agent.next.brain.model;

import java.util.Map;

/** Short stage summary and at most eight key/value entities. */
public record StageData(String summary, Map<String, String> entities) {

    public StageData {
        entities = Map.copyOf(entities);
        if (summary == null || summary.length() > 300) {
            throw new IllegalArgumentException("summary is blank or too long");
        }
        if (entities.size() > 8) {
            throw new IllegalArgumentException("too many entities");
        }
    }
}
