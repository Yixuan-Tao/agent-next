package agent.next.brain.loop;

import agent.next.brain.action.ActionProposal;

/** A loop action that delegates execution to the governed ActionExecutor. */
public interface ProposalCarrier {

    ActionProposal proposal();
}
