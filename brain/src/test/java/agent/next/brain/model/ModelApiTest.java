package agent.next.brain.model;

import agent.next.brain.skill.SkillCapability;
import agent.next.brain.skill.SkillModelRequest;
import agent.next.brain.skill.SkillModelStep;
import agent.next.brain.skill.SkillSelector;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Tests for the copied chat-completions API and semantic JSON parser. */
public final class ModelApiTest {

    private ModelApiTest() {
    }

    public static void run() {
        configLoad();
        clientParsesCompletion();
        clientParsesImageCompletion();
        clientRejectsNonStop();
        skillModelParsesSemanticStep();
        skillModelUsesFrame();
        skillModelRejectsCoordinates();
        System.out.println("ModelApiTest: PASS (config, transport, semantic JSON)");
    }

    private static void configLoad() {
        try {
            Path file = Files.createTempFile("agent-next-model", ".json");
            try {
                Files.writeString(file, "{\"base_url\":\"https://example.test/\","
                        + "\"model\":\"deepseek-flash\",\"api_key\":\"secret-key\"}",
                        StandardCharsets.UTF_8);
                Optional<ModelConfig> loaded = ModelConfig.load(file);
                check(loaded.isPresent(), "config loads");
                ModelConfig config = loaded.orElseThrow();
                check(config.baseUrl().equals("https://example.test"), "base URL normalized");
                check(config.endpoint().equals("https://example.test/chat/completions"),
                        "endpoint");
                check(!config.toString().contains("secret-key"), "key redacted");
                check(ModelConfig.load(file.resolveSibling("missing.json")).isEmpty(),
                        "missing config is empty");
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException failure) {
            throw new AssertionError("config test I/O", failure);
        }
    }

    private static void clientParsesCompletion() {
        AtomicReference<String> request = new AtomicReference<>();
        ChatCompletionTransport transport = (url, auth, body) -> {
            check(url.endsWith("/chat/completions"), "completion URL");
            check(auth.equals("Bearer test-key"), "bearer auth");
            request.set(body);
            return completion("{\"ok\":true}");
        };
        ModelConfig config = new ModelConfig("https://example.test", "deepseek-flash",
                "test-key");
        try {
            String content = new ChatCompletionClient(config, transport)
                    .complete("hello", 128);
            check(content.equals("{\"ok\":true}"), "content parsed");
            check(request.get().contains("\"model\":\"deepseek-flash\""), "model field");
            check(request.get().contains("\"response_format\":{\"type\":\"json_object\"}"),
                    "JSON response format");
            check(request.get().contains("\"content\":\"hello\""), "prompt encoded");
        } catch (IOException failure) {
            throw new AssertionError("completion test I/O", failure);
        }
    }

    private static void clientParsesImageCompletion() {
        AtomicReference<String> request = new AtomicReference<>();
        ChatCompletionTransport transport = (url, auth, body) -> {
            request.set(body);
            return completion("{\"ok\":true}");
        };
        ModelConfig config = new ModelConfig("https://example.test", "deepseek-flash",
                "test-key");
        try {
            new ChatCompletionClient(config, transport)
                    .completeWithImage("describe", new byte[] {1, 2, 3}, 1024);
            check(request.get().contains("\"type\":\"image_url\""), "image message");
            check(request.get().contains("data:image/png;base64,AQID"), "image base64");
        } catch (IOException failure) {
            throw new AssertionError("image completion test I/O", failure);
        }
    }

    private static void clientRejectsNonStop() {
        ChatCompletionTransport transport = (url, auth, body) ->
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":"
                        + "{\"content\":\"{}\"}}]}";
        ModelConfig config = new ModelConfig("https://example.test", "deepseek-flash",
                "test-key");
        try {
            new ChatCompletionClient(config, transport).complete("hello", 128);
            throw new AssertionError("non-stop response must fail");
        } catch (IOException expected) {
            // expected
        }
    }

    private static void skillModelParsesSemanticStep() {
        String content = "{\"kind\":\"SetText\",\"selector\":{\"type\":\"Editable\"},"
                + "\"text\":\"短袖T恤\",\"risk\":\"REVERSIBLE_SIDE_EFFECT\"}";
        DeepSeekSkillModel model = modelReturning(content);
        SkillModelStep step = model.next(request());
        check(step instanceof SkillModelStep.SetText, "set-text step parsed");
        SkillModelStep.SetText setText = (SkillModelStep.SetText) step;
        check(setText.text().equals("短袖T恤"), "text preserved");
        check(setText.selector() instanceof SkillSelector.Editable, "editable selector");
    }

    private static void skillModelUsesFrame() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        ChatCompletionTransport transport = (url, auth, body) -> {
            requestBody.set(body);
            return completion("{\"kind\":\"SetText\",\"selector\":{\"type\":\"Editable\"},"
                    + "\"text\":\"短袖T恤\",\"risk\":\"REVERSIBLE_SIDE_EFFECT\"}");
        };
        DeepSeekSkillModel model = new DeepSeekSkillModel(new ChatCompletionClient(
                new ModelConfig("https://example.test", "deepseek-flash", "test-key"),
                transport));
        SkillModelRequest base = request();
        SkillModelRequest withFrame = new SkillModelRequest(base.skillId(), base.prompt(),
                base.observation(), base.targetPackage(), base.parameters(),
                base.capabilities(), 0,
                new Frame(720, 1280, new byte[] {1, 2, 3}, Instant.now(), 1));
        model.next(withFrame);
        check(requestBody.get().contains("\"type\":\"image_url\""), "skill model sends frame");
    }

    private static void skillModelRejectsCoordinates() {
        DeepSeekSkillModel model = modelReturning(
                "{\"kind\":\"Tap\",\"selector\":{\"type\":\"Point\",\"x\":1,\"y\":2},"
                        + "\"risk\":\"LOW\"}");
        expect(IllegalArgumentException.class, () -> model.next(request()),
                "coordinate selector rejected");

        DeepSeekSkillModel extra = modelReturning(
                "{\"kind\":\"Tap\",\"selector\":{\"type\":\"Editable\"},"
                        + "\"risk\":\"LOW\",\"x\":1}");
        expect(IllegalArgumentException.class, () -> extra.next(request()),
                "coordinate field rejected");
    }

    private static DeepSeekSkillModel modelReturning(String content) {
        ChatCompletionTransport transport = (url, auth, body) -> completion(content);
        return new DeepSeekSkillModel(new ChatCompletionClient(
                new ModelConfig("https://example.test", "deepseek-flash", "test-key"),
                transport));
    }

    private static SkillModelRequest request() {
        try {
            Observation observation = new LoopFakeDevice().observations().observe(
                    new TargetHandle("model-target", 1, "model-task", "com.example.shop"),
                    ObservationOptions.defaults());
            return new SkillModelRequest("shop.search", "Find the query.", observation,
                    "com.example.shop", Map.of("query", "短袖T恤"),
                    Set.of(SkillCapability.TAP, SkillCapability.SET_TEXT), 0);
        } catch (DeviceException failure) {
            throw new AssertionError("observation failed", failure);
        }
    }

    private static String completion(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + Json.quote(content) + "}}]}";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action,
            String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(message + " (got " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }
}
