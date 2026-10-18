package agent.next.brain.loop;

/** Decision port for slow-loop replanning. */
public interface SlowLoopPolicy {

    SlowLoopDecision decide(SlowLoopContext context);
}
