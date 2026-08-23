package org.telegram.messenger.novagram.privacy;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The non-exportable key that binds this installation's authorization data to
 * this device.
 *
 * <p>Software protection is accepted here, unlike {@link NovaCacheKeyStore}.
 * The question this key answers is not "can the key be pulled out of the
 * hardware" but "does it travel with a copy of the application data directory",
 * and it does not: even a software Keystore key lives in the system keystore,
 * outside {@code /data/data/<package>}. Hardware backing, when the device has
 * it, additionally means the key cannot be extracted from the device at all.</p>
 */
public final class NovaDeviceKeyStore {
    public static final String KEY_ALIAS = "novagram.device.kek.v1";

    private static final String PROVIDER = "AndroidKeyStore";

    private NovaDeviceKeyStore() {
    }

    public static synchronized SecretKey getOrCreate() throws GeneralSecurityException {
        SecretKey existing = getExisting();
        if (existing != null) {
            return existing;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(true);
            } catch (StrongBoxUnavailableException ignored) {
                deleteQuietly();
            } catch (GeneralSecurityException | RuntimeException ignored) {
                deleteQuietly();
            }
        }
        return generate(false);
    }

    /** Null when nothing was ever created here, or when it was created elsewhere. */
    public static synchronized SecretKey getExisting() throws GeneralSecurityException {
        return (SecretKey) loadKeyStore().getKey(KEY_ALIAS, null);
    }

    public static synchronized void delete() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
    }

    private static SecretKey generate(boolean strongBox) throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                // Not tied to the screen lock: this key has to be usable before
                // anything is on screen, and on some devices adding or removing
                // a lock invalidates keys that require authentication.
                .setUserAuthenticationRequired(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox);
        }
        generator.init(builder.build());
        return generator.generateKey();
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(PROVIDER);
            keyStore.load(null);
            return keyStore;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Unable to load Android Keystore", e);
        }
    }

    private static void deleteQuietly() {
        try {
            delete();
        } catch (GeneralSecurityException ignored) {
        }
    }
}
