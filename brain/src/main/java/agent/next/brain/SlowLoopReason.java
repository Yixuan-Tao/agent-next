package agent.next.brain;

/** Why the fast loop should escalate to the slow loop. */
public enum SlowLoopReason {
    NO_EFFECT,
    NO_PROGRESS,
    GRAPH_INCONSISTENT,
    NEEDS_NEW_APP,
    RISK_GATE,
    AMBIGUOUS_SUCCESS,
    BUDGET_NEAR_LIMIT,
    NODE_COMPLETE
}
