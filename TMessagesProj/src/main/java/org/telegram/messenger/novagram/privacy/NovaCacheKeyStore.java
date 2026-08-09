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

/** Separate non-exportable KEK used only to wrap encrypted-cache blob keys. */
public final class NovaCacheKeyStore {
    public static final String KEY_ALIAS = "novagram.cache.kek.v1";

    private static final String PROVIDER = "AndroidKeyStore";

    private NovaCacheKeyStore() {
    }

    public static synchronized KeyMaterial getOrCreate(boolean acceptSoftwareProtection)
            throws GeneralSecurityException, SoftwareProtectionRequiredException {
        KeyStore keyStore = loadKeyStore();
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            KeyMaterial material = inspect(existing, false);
            requireAcceptable(material, acceptSoftwareProtection, false);
            return material;
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

        KeyMaterial material = inspect(generated, requestedStrongBox);
        try {
            requireAcceptable(material, acceptSoftwareProtection, true);
            return material;
        } catch (GeneralSecurityException | SoftwareProtectionRequiredException e) {
            deleteQuietly();
            throw e;
        }
    }

    public static synchronized KeyMaterial getExisting()
            throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("NovaGram cache KEK is missing");
        }
        KeyMaterial material = inspect(key, false);
        if (material.protectionLevel == ProtectionLevel.UNKNOWN) {
            throw new GeneralSecurityException("Unknown NovaGram cache KEK protection level");
        }
        return material;
    }

    public static synchronized void delete() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
    }

    private static void requireAcceptable(
            KeyMaterial material,
            boolean acceptSoftwareProtection,
            boolean newlyCreated
    ) throws GeneralSecurityException, SoftwareProtectionRequiredException {
        if (material.protectionLevel == ProtectionLevel.UNKNOWN) {
            throw new GeneralSecurityException("Unknown NovaGram cache KEK protection level");
        }
        if (!material.protectionLevel.hardwareBacked && !acceptSoftwareProtection) {
            throw new SoftwareProtectionRequiredException(material.protectionLevel, newlyCreated);
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
        ProtectionLevel level;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            switch (info.getSecurityLevel()) {
                case KeyProperties.SECURITY_LEVEL_STRONGBOX:
                    level = ProtectionLevel.STRONGBOX;
                    break;
                case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                    level = ProtectionLevel.TRUSTED_ENVIRONMENT;
                    break;
                case KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE:
                    level = ProtectionLevel.UNKNOWN_SECURE_HARDWARE;
                    break;
                case KeyProperties.SECURITY_LEVEL_SOFTWARE:
                    level = ProtectionLevel.SOFTWARE;
                    break;
                default:
                    level = ProtectionLevel.UNKNOWN;
                    break;
            }
        } else if (info.isInsideSecureHardware()) {
            level = requestedStrongBox
                    ? ProtectionLevel.STRONGBOX
                    : ProtectionLevel.TRUSTED_ENVIRONMENT;
        } else {
            level = ProtectionLevel.SOFTWARE;
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

    public enum ProtectionLevel {
        STRONGBOX(true),
        TRUSTED_ENVIRONMENT(true),
        UNKNOWN_SECURE_HARDWARE(true),
        SOFTWARE(false),
        UNKNOWN(false);

        private final boolean hardwareBacked;

        ProtectionLevel(boolean hardwareBacked) {
            this.hardwareBacked = hardwareBacked;
        }

        public boolean isHardwareBacked() {
            return hardwareBacked;
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

    public static final class SoftwareProtectionRequiredException extends Exception {
        private final ProtectionLevel protectionLevel;
        private final boolean newlyCreated;

        private SoftwareProtectionRequiredException(
                ProtectionLevel protectionLevel,
                boolean newlyCreated
        ) {
            super("Software-backed cache protection requires explicit consent");
            this.protectionLevel = protectionLevel;
            this.newlyCreated = newlyCreated;
        }

        public ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }

        public boolean wasNewlyCreated() {
            return newlyCreated;
        }
    }
}
