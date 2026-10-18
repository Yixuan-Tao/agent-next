package agent.next.app.security;

import android.content.Context;
import android.util.Log;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM file encryption with an app-scoped Android Keystore key. */
public final class EncryptedFileStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "agent-next-local-store-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] MAGIC = {'A', 'G', 'N', 'X', '1'};

    private EncryptedFileStore() {
    }

    public static String read(Context context, File file) throws IOException {
        if (!file.isFile() || file.length() > 4 * 1024 * 1024) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length < MAGIC.length + IV_BYTES) {
                return "";
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (bytes[i] != MAGIC[i]) {
                    return "";
                }
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(bytes, MAGIC.length, iv, 0, IV_BYTES);
            byte[] cipherText = new byte[bytes.length - MAGIC.length - IV_BYTES];
            System.arraycopy(bytes, MAGIC.length + IV_BYTES, cipherText, 0,
                    cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            Log.e("AgentNextCrypto", "encrypted read failed", failure);
            throw new IOException("encrypted store read failed", failure);
        }
    }

    public static void write(Context context, File file, String value) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new IOException("invalid generated IV");
            }
            byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create store directory");
            }
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(MAGIC);
                output.write(iv);
                output.write(cipherText);
                output.getFD().sync();
            }
            if (!temp.renameTo(file)) {
                Files.deleteIfExists(file.toPath());
                if (!temp.renameTo(file)) {
                    throw new IOException("cannot replace encrypted store");
                }
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            Log.e("AgentNextCrypto", "encrypted write failed", failure);
            throw new IOException("encrypted store write failed", failure);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null))
                    .getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
