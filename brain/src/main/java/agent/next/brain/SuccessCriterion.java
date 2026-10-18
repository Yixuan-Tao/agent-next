package agent.next.brain;

/** A verifiable, evidence-backed completion criterion. */
public record SuccessCriterion(String id, String description, boolean required) {

    public SuccessCriterion {
        id = Preconditions.requireText(id, "id");
        description = Preconditions.requireText(description, "description");
    }
}
