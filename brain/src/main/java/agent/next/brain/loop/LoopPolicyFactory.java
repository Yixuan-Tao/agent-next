package agent.next.brain.loop;

import agent.next.brain.TaskGraph;

/** Creates a fast-loop policy for each attempt; replanning may need a different approach. */
public interface LoopPolicyFactory {

    LoopPolicy create(int attempt, TaskGraph graph);
}
