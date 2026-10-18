package agent.next.brain.model;

import java.io.IOException;

/** HTTP seam for the chat-completions client; tests use a deterministic fake. */
public interface ChatCompletionTransport {

    String post(String url, String authorization, String body) throws IOException;
}
