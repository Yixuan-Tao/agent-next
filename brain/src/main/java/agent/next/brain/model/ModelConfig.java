package agent.next.brain.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Local model configuration copied from the previous project's model-config.json
 * shape. The API key is never written to logs or committed; loader failures are
 * silent to avoid leaking path/content details.
 */
public final class ModelConfig {

    private static final int MAX_CONFIG_BYTES = 65_536;

    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public ModelConfig(String baseUrl, String model, String apiKey) {
        this.baseUrl = require(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.model = require(model, "model");
        this.apiKey = require(apiKey, "apiKey");
        if (!this.baseUrl.startsWith("https://") && !this.baseUrl.startsWith("http://")) {
            throw new IllegalArgumentException("baseUrl must be HTTP(S)");
        }
    }

    public static Optional<ModelConfig> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_CONFIG_BYTES) {
                return Optional.empty();
            }
            return fromJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** Parse an already-decrypted config without writing the secret to disk. */
    public static Optional<ModelConfig> fromJson(String json) {
        if (json == null || json.length() > MAX_CONFIG_BYTES) {
            return Optional.empty();
        }
        try {
            Map<String, Object> value = Json.object(json);
            String base = Json.string(value, "base_url").trim();
            String model = Json.string(value, "model").trim();
            String key = Json.string(value, "api_key").trim();
            if (base.isEmpty() || model.isEmpty() || key.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ModelConfig(base, model, key));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey;
    }

    public String endpoint() {
        return baseUrl + "/chat/completions";
    }

    /** DeepSeek's strict function-call mode is currently exposed on the beta endpoint. */
    public String strictToolEndpoint() {
        String apiRoot = baseUrl.replaceFirst("/v1$", "").replaceFirst("/beta$", "");
        if (apiRoot.matches("https://api\\.deepseek\\.com(?::[0-9]+)?")) {
            return apiRoot + "/beta/chat/completions";
        }
        return endpoint();
    }

    @Override
    public String toString() {
        return "ModelConfig{baseUrl='" + baseUrl + "', model='" + model
                + "', apiKey=<redacted>}";
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
