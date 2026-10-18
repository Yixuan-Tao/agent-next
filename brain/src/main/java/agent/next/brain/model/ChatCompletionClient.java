package agent.next.brain.model;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chat-completions client matching the previous project's request shape:
 * model, thinking, reasoning_effort, json_object response, finish_reason=stop,
 * then choices[0].message.content. Supports text-only and one PNG image.
 */
public final class ChatCompletionClient {

    private static final int MAX_PROMPT_CHARS = 100_000;
    private static final int MAX_CONTENT_CHARS = 1_048_576;
    private static final int MAX_IMAGE_BYTES = 4_194_304;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ModelConfig config;
    private final ChatCompletionTransport transport;
    private final Sleeper sleeper;
    private final int maxRetries;
    private final ModelCallBudget budget;

    public ChatCompletionClient(ModelConfig config) {
        this(config, new HttpChatCompletionTransport());
    }

    public ChatCompletionClient(ModelConfig config, ModelCallBudget budget) {
        this(config, new HttpChatCompletionTransport(), millis -> Thread.sleep(millis), 2,
                budget);
    }

    public ChatCompletionClient(ModelConfig config, ChatCompletionTransport transport) {
        this(config, transport, millis -> Thread.sleep(millis), 2,
                ModelCallBudget.unlimited());
    }

    public ChatCompletionClient(ModelConfig config, ChatCompletionTransport transport,
            Sleeper sleeper, int maxRetries) {
        this(config, transport, sleeper, maxRetries, ModelCallBudget.unlimited());
    }

    public ChatCompletionClient(ModelConfig config, ChatCompletionTransport transport,
            Sleeper sleeper, int maxRetries, ModelCallBudget budget) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (maxRetries < 0 || maxRetries > 3) {
            throw new IllegalArgumentException("maxRetries must be 0..3");
        }
        this.maxRetries = maxRetries;
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public String complete(String prompt, int maxTokens) throws IOException {
        validatePrompt(prompt);
        validateTokens(maxTokens);
        String messages = "[{\"role\":\"user\",\"content\":" + Json.quote(prompt) + "}]";
        return post(messages, maxTokens);
    }

    public String completeWithImage(String prompt, byte[] png, int maxTokens)
            throws IOException {
        validatePrompt(prompt);
        validateTokens(maxTokens);
        if (png == null || png.length == 0 || png.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("invalid image size");
        }
        String image = Base64.getEncoder().encodeToString(png);
        String messages = "[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":" + Json.quote(prompt) + "},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":"
                + Json.quote("data:image/png;base64," + image)
                + ",\"detail\":\"original\"}}]}]";
        return post(messages, maxTokens);
    }

    private String post(String messages, int maxTokens) throws IOException {
        String body = "{"
                + "\"model\":" + Json.quote(config.model())
                + ",\"thinking\":{\"type\":\"enabled\"}"
                + ",\"reasoning_effort\":\"low\""
                + ",\"response_format\":{\"type\":\"json_object\"}"
                + ",\"max_tokens\":" + maxTokens
                + ",\"messages\":" + messages
                + "}";
        String raw = postWithRetry(body);
        if (raw == null || raw.length() > MAX_CONTENT_CHARS) {
            throw new IOException("invalid model response size");
        }
        Map<String, Object> response;
        try {
            response = Json.object(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid model response JSON", invalid);
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)) {
            throw new IOException("model response has no choice");
        }
        if (!"stop".equals(choice.get("finish_reason"))) {
            throw new IOException("model did not finish normally: "
                    + choice.get("finish_reason"));
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)
                || content.isBlank()) {
            throw new IOException("model response has no content");
        }
        return content;
    }

    private String postWithRetry(String body) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                budget.acquire();
                return transport.post(config.endpoint(), "Bearer " + config.apiKey(), body);
            } catch (ModelHttpException failure) {
                last = failure;
                if (!failure.retryable() || attempt == maxRetries) {
                    throw failure;
                }
                long wait = failure.retryAfterMillis() > 0
                        ? Math.min(5000, failure.retryAfterMillis())
                        : Math.min(4000, 500L << attempt);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("model retry interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IOException("model call failed") : last;
    }

    private static void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("invalid model prompt");
        }
    }

    private static void validateTokens(int maxTokens) {
        if (maxTokens <= 0 || maxTokens > 8192) {
            throw new IllegalArgumentException("maxTokens out of range");
        }
    }
}
