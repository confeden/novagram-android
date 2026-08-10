package org.telegram.messenger.novagram.privacy;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/**
 * Separate non-exportable key sealing the auto-delete queue.
 *
 * <p>The queue is a list of dialog and message identifiers, which is exactly the
 * metadata NovaGram promises to protect, so it never shares a key with anything
 * else. A separate alias also means the emergency wipe can destroy this key on
 * its own.</p>
 *
 * <p>Unlike the PIN key, software protection is accepted without asking again.
 * The application PIN is mandatory before Telegram is ever used, and a device
 * without secure hardware already made the user accept software protection
 * there; refusing here would only mean the sent messages are never deleted at
 * all, which is the worse outcome of the two. The level is recorded inside the
 * sealed state and a later change is treated as corruption.</p>
 */
public final class NovaAutoDeleteKeyStore {
    public static final String KEY_ALIAS = "novagram.autodelete.state.v1";

    private static final String PROVIDER = "AndroidKeyStore";

    private NovaAutoDeleteKeyStore() {
    }

    public static synchronized boolean containsKey() throws GeneralSecurityException {
        return loadKeyStore().containsAlias(KEY_ALIAS);
    }

    public static synchronized KeyMaterial getOrCreate() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return inspect(existing, false);
        }

        SecretKey generated = null;
        boolean requestedStrongBox = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generated = generate(true);
                requestedStrongBox = true;
            } catch (StrongBoxUnavailableException ignored) {
                deleteQuietly();
            } catch (GeneralSecurityException | RuntimeException ignored) {
                deleteQuietly();
            }
        }
        if (generated == null) {
            generated = generate(false);
        }
        return inspect(generated, requestedStrongBox);
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
                .setUserAuthenticationRequired(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox);
        }
        generator.init(builder.build());
        return generator.generateKey();
    }

    private static KeyMaterial inspect(SecretKey key, boolean requestedStrongBox)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), PROVIDER);
        KeyInfo info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
        NovaPinKeyStore.ProtectionLevel level;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            switch (info.getSecurityLevel()) {
                case KeyProperties.SECURITY_LEVEL_STRONGBOX:
                    level = NovaPinKeyStore.ProtectionLevel.STRONGBOX;
                    break;
                case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                    level = NovaPinKeyStore.ProtectionLevel.TRUSTED_ENVIRONMENT;
                    break;
                case KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE:
                    level = NovaPinKeyStore.ProtectionLevel.UNKNOWN_SECURE_HARDWARE;
                    break;
                case KeyProperties.SECURITY_LEVEL_SOFTWARE:
                    level = NovaPinKeyStore.ProtectionLevel.SOFTWARE;
                    break;
                default:
                    level = NovaPinKeyStore.ProtectionLevel.UNKNOWN;
                    break;
            }
        } else if (info.isInsideSecureHardware()) {
            level = requestedStrongBox
                    ? NovaPinKeyStore.ProtectionLevel.STRONGBOX
                    : NovaPinKeyStore.ProtectionLevel.TRUSTED_ENVIRONMENT;
        } else {
            level = NovaPinKeyStore.ProtectionLevel.SOFTWARE;
        }
        return new KeyMaterial(key, level);
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

    public static final class KeyMaterial {
        private final SecretKey key;
        private final NovaPinKeyStore.ProtectionLevel protectionLevel;

        private KeyMaterial(SecretKey key, NovaPinKeyStore.ProtectionLevel protectionLevel) {
            this.key = key;
            this.protectionLevel = protectionLevel;
        }

        SecretKey getKey() {
            return key;
        }

        public NovaPinKeyStore.ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }
    }
}
