package agent.next.brain;

/** A named precondition or postcondition. Evidence binding is added later. */
public record Condition(String id, String description, boolean required) {

    public Condition {
        id = Preconditions.requireText(id, "id");
        description = Preconditions.requireText(description, "description");
    }
}
