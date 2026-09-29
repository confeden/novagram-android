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

    /**
     * Whether the key can be fetched, not merely listed.
     *
     * <p>`containsAlias` answers false for every failure to read the entry and
     * not only for a key that is not there (G68), and "the file is here, the
     * key is not" is reported to the owner as a corrupt vault whose only offer
     * is starting over. Right after the package is replaced the Keystore has
     * been seen to answer exactly that for a moment. Asking for the key itself
     * is the cheaper and the truer question (P39).</p>
     */
    public static boolean hasUsableKey() {
        try {
            return loadKeyStore().getKey(KEY_ALIAS, null) != null;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    public static KeyMaterial getExisting() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("NovaGram PIN key is missing");
        }
        return new KeyMaterial(key, inspectSecurityLevel(key));
    }

    public static KeyMaterial create() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            throw new GeneralSecurityException("NovaGram PIN key already exists");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                SecretKey key = generate(true);
                return new KeyMaterial(key, inspectSecurityLevel(key));
            } catch (StrongBoxUnavailableException ignored) {
                deleteQuietly();
            } catch (GeneralSecurityException | RuntimeException ignored) {
                deleteQuietly();
            }
        }

        SecretKey key = generate(false);
        return new KeyMaterial(key, inspectSecurityLevel(key));
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

    private static ProtectionLevel inspectSecurityLevel(SecretKey key)
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
        // Below API 31 there is nothing here that tells a StrongBox key
        // from a TEE one: `KeyInfo` answers `isInsideSecureHardware()` and
        // says no more. Recording what was *asked for* instead of what was
        // observed is what made `create()` write STRONGBOX and the next
        // `getExisting()` read TRUSTED_ENVIRONMENT for the same key, which
        // the vault reports as a changed protection level - a corrupt
        // state the owner can only answer by wiping, and starting over
        // writes the same mismatch again (G74).
        if (info.isInsideSecureHardware()) {
            return ProtectionLevel.TRUSTED_ENVIRONMENT;
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

        /**
         * Whether a level read out of a sealed state may still be believed of
         * the key as it reports itself now.
         *
         * <p>What this check is for is a key that has lost its hardware: a
         * state sealed by a key in secure hardware must not be opened by one
         * the platform now keeps in software. That question is answered here.
         * Which secure element holds it is not: below API 31 the platform will
         * not say, so the fork used to record what it had *asked* for, and the
         * same key then read back as a different level - which was reported as
         * a changed protection level, i.e. a corrupt vault offering a wipe, on
         * every API 28 device (G74). Keeping the two apart above 31 would only
         * move the same brick to the first OS upgrade across that line, and a
         * key does not travel between secure elements on its own, so the
         * distinction buys no detection and costs the whole feature.</p>
         */
        public static boolean matchesStored(ProtectionLevel stored, ProtectionLevel actual) {
            if (stored == UNKNOWN || actual == UNKNOWN) {
                return false;
            }
            return stored == actual || (stored.hardwareBacked && actual.hardwareBacked);
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
