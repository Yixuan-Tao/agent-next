package agent.next.app.security;

import android.content.Context;

import agent.next.brain.model.ModelConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/** Decrypts model config in memory; migrates a legacy plaintext file once. */
public final class EncryptedModelConfigStore {

    private static final String ENCRYPTED = "model-config.enc";
    private static final String LEGACY = "model-config.json";
    private static final int MAX_BYTES = 65_536;

    private EncryptedModelConfigStore() {
    }

    public static boolean hasConfig(Context context) {
        return new File(context.getFilesDir(), ENCRYPTED).isFile();
    }

    public static Optional<ModelConfig> load(Context context) {
        File encrypted = new File(context.getFilesDir(), ENCRYPTED);
        if (encrypted.isFile()) {
            try {
                return ModelConfig.fromJson(EncryptedFileStore.read(context, encrypted));
            } catch (IOException failure) {
                return Optional.empty();
            }
        }
        File legacy = new File(context.getFilesDir(), LEGACY);
        if (!legacy.isFile()) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(legacy.toPath());
            if (bytes.length > MAX_BYTES) {
                return Optional.empty();
            }
            String plain = new String(bytes, StandardCharsets.UTF_8);
            Optional<ModelConfig> config = ModelConfig.fromJson(plain);
            if (config.isPresent()) {
                EncryptedFileStore.write(context, encrypted, plain);
                Files.deleteIfExists(legacy.toPath());
            }
            return config;
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    public static void save(Context context, String json) throws IOException {
        EncryptedFileStore.write(context,
                new File(context.getFilesDir(), ENCRYPTED), json);
        Files.deleteIfExists(new File(context.getFilesDir(), LEGACY).toPath());
    }
}
