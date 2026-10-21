package agent.next.brain.reactive;

@FunctionalInterface
public interface ReactiveModel {
    ReactiveDecision next(ReactiveRequest request);
}
