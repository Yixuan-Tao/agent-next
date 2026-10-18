package agent.next.brain;

/**
 * Human-in-the-loop boundary. Confirmation and handoff may fail when no human
 * is available; the loop must then stop as NEED_USER, never auto-approve.
 */
public interface HumanGateway {

    ConfirmationToken confirm(ConfirmationRequest request) throws HumanUnavailableException;

    HandoffSession handoff(HandoffRequest request) throws HumanUnavailableException;

    void endHandoff(HandoffSession session);

    void pause(String reason);

    void resume();

    void stop(String reason);
}
