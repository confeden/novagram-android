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

/** Creates and inspects the non-exportable key that seals the PIN state. */
public final class NovaPinKeyStore {
    public static final String KEY_ALIAS = "novagram.pin.state.v1";

    private static final String PROVIDER = "AndroidKeyStore";

    private NovaPinKeyStore() {
    }

    public static boolean containsKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        return keyStore.containsAlias(KEY_ALIAS);
    }

    public static KeyMaterial getExisting() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("NovaGram PIN key is missing");
        }
        return new KeyMaterial(key, inspectSecurityLevel(key, false));
    }

    public static KeyMaterial create() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            throw new GeneralSecurityException("NovaGram PIN key already exists");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                SecretKey key = generate(true);
                return new KeyMaterial(key, inspectSecurityLevel(key, true));
            } catch (StrongBoxUnavailableException ignored) {
                deleteQuietly();
            } catch (GeneralSecurityException | RuntimeException ignored) {
                deleteQuietly();
            }
        }

        SecretKey key = generate(false);
        return new KeyMaterial(key, inspectSecurityLevel(key, false));
    }

    public static void delete() throws GeneralSecurityException {
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

    private static ProtectionLevel inspectSecurityLevel(SecretKey key, boolean requestedStrongBox)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), PROVIDER);
        KeyInfo info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            switch (info.getSecurityLevel()) {
                case KeyProperties.SECURITY_LEVEL_STRONGBOX:
                    return ProtectionLevel.STRONGBOX;
                case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                    return ProtectionLevel.TRUSTED_ENVIRONMENT;
                case KeyProperties.SECURITY_LEVEL_SOFTWARE:
                    return ProtectionLevel.SOFTWARE;
                case KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE:
                    return ProtectionLevel.UNKNOWN_SECURE_HARDWARE;
                default:
                    return ProtectionLevel.UNKNOWN;
            }
        }
        if (info.isInsideSecureHardware()) {
            return requestedStrongBox
                    ? ProtectionLevel.STRONGBOX
                    : ProtectionLevel.TRUSTED_ENVIRONMENT;
        }
        return ProtectionLevel.SOFTWARE;
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

    public enum ProtectionLevel {
        STRONGBOX(4, true),
        TRUSTED_ENVIRONMENT(3, true),
        UNKNOWN_SECURE_HARDWARE(2, true),
        SOFTWARE(1, false),
        UNKNOWN(0, false);

        private final int storageId;
        private final boolean hardwareBacked;

        ProtectionLevel(int storageId, boolean hardwareBacked) {
            this.storageId = storageId;
            this.hardwareBacked = hardwareBacked;
        }

        public int getStorageId() {
            return storageId;
        }

        public boolean isHardwareBacked() {
            return hardwareBacked;
        }

        public static ProtectionLevel fromStorageId(int id) {
            for (ProtectionLevel value : values()) {
                if (value.storageId == id) {
                    return value;
                }
            }
            return UNKNOWN;
        }
    }

    public static final class KeyMaterial {
        private final SecretKey key;
        private final ProtectionLevel protectionLevel;

        private KeyMaterial(SecretKey key, ProtectionLevel protectionLevel) {
            this.key = key;
            this.protectionLevel = protectionLevel;
        }

        SecretKey getKey() {
            return key;
        }

        public ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }
    }
}
