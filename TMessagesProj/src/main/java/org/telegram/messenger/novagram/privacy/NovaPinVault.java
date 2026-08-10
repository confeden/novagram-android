package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-sealed PIN verifier and retry state.
 *
 * <p>All methods perform disk and cryptographic work and must run away from the
 * UI thread. The supplied PIN array remains owned by the caller and should be
 * cleared immediately after the operation.</p>
 */
public final class NovaPinVault {
    private static final Object PROCESS_LOCK = new Object();
    private static final int OUTER_MAGIC = 0x4e565345; // NVSE
    private static final int INNER_MAGIC = 0x4e565031; // NVP1
    private static final int STORAGE_VERSION = 2;
    /**
     * Version 1 carried the primary verifier only. It is still read, because a
     * user who already enrolled a PIN must not lose access when the emergency
     * PIN support arrives.
     */
    private static final int MIN_STORAGE_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_STATE_BYTES = 16 * 1024;
    private static final int MAX_KDF_ITERATIONS = 5_000_000;
    private static final byte[] STATE_AAD =
            "NovaGram protected PIN state v1".getBytes(StandardCharsets.US_ASCII);

    private final Context applicationContext;
    private final File stateFile;
    private final AtomicFile atomicStateFile;
    private final File lockFile;
    private final SecureRandom random = new SecureRandom();

    public NovaPinVault(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context app = context.getApplicationContext();
        applicationContext = app != null ? app : context;
        File directory = new File(applicationContext.getNoBackupFilesDir(), "novagram/security");
        stateFile = new File(directory, "pin_state.bin");
        atomicStateFile = new AtomicFile(stateFile);
        lockFile = new File(directory, "pin_state.lock");
    }

    public VaultInspection inspect() {
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                boolean fileExists = stateFile.isFile();
                boolean keyExists = NovaPinKeyStore.containsKey();
                if (!fileExists && !keyExists) {
                    return new VaultInspection(VaultState.NOT_ENROLLED, 0L, null);
                }
                if (!fileExists || !keyExists) {
                    return new VaultInspection(VaultState.CORRUPT, 0L, null);
                }
                NovaPinKeyStore.KeyMaterial keyMaterial = NovaPinKeyStore.getExisting();
                StoredState state = readState(keyMaterial);
                try {
                    NovaPinLockout.Evaluation evaluation = NovaPinLockout.evaluate(
                            state.lockout,
                            SystemClock.elapsedRealtime(),
                            readBootCount()
                    );
                    if (evaluation.isStateChanged()) {
                        state = state.withLockout(evaluation.getState());
                        writeState(keyMaterial, state);
                    }
                    return new VaultInspection(
                            VaultState.ENROLLED,
                            evaluation.getRemainingMillis(),
                            state.protectionLevel
                    );
                } finally {
                    state.clearSensitive();
                }
            } catch (CorruptStateException | AEADBadTagException e) {
                return new VaultInspection(VaultState.CORRUPT, 0L, null);
            } catch (GeneralSecurityException | IOException e) {
                return new VaultInspection(VaultState.UNAVAILABLE, 0L, null);
            }
        }
    }

    public EnrollmentResult enroll(char[] pin, boolean acceptSoftwareProtection) {
        if (!NovaPrivacyContract.isValidPin(pin)) {
            return new EnrollmentResult(EnrollmentStatus.INVALID_PIN, null);
        }
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                boolean fileExists = stateFile.isFile();
                boolean keyExists = NovaPinKeyStore.containsKey();
                if (fileExists || keyExists) {
                    return new EnrollmentResult(
                            fileExists && keyExists
                                    ? EnrollmentStatus.ALREADY_ENROLLED
                                    : EnrollmentStatus.CORRUPT_STATE,
                            null
                    );
                }

                NovaPinKeyStore.KeyMaterial keyMaterial = null;
                byte[] salt = null;
                byte[] verifier = null;
                try {
                    keyMaterial = NovaPinKeyStore.create();
                    NovaPinKeyStore.ProtectionLevel level = keyMaterial.getProtectionLevel();
                    if (level == NovaPinKeyStore.ProtectionLevel.UNKNOWN) {
                        NovaPinKeyStore.delete();
                        return new EnrollmentResult(EnrollmentStatus.UNAVAILABLE, null);
                    }
                    if (!level.isHardwareBacked() && !acceptSoftwareProtection) {
                        NovaPinKeyStore.delete();
                        return new EnrollmentResult(
                                EnrollmentStatus.SOFTWARE_CONSENT_REQUIRED,
                                level
                        );
                    }

                    salt = NovaPinKdf.newSalt(random);
                    verifier = NovaPinKdf.createVerifier(pin, salt, NovaPinKdf.ITERATIONS);
                    StoredState state = new StoredState(
                            NovaPinKdf.ITERATIONS,
                            level,
                            salt,
                            verifier,
                            null,
                            null,
                            NovaPinLockout.State.initial()
                    );
                    writeState(keyMaterial, state);
                    return new EnrollmentResult(EnrollmentStatus.ENROLLED, level);
                } catch (GeneralSecurityException | IOException e) {
                    deleteStateFileQuietly();
                    try {
                        NovaPinKeyStore.delete();
                    } catch (GeneralSecurityException ignoredDelete) {
                    }
                    return new EnrollmentResult(EnrollmentStatus.UNAVAILABLE, null);
                } finally {
                    if (salt != null) {
                        NovaSecretWiper.wipe(salt);
                    }
                    if (verifier != null) {
                        NovaSecretWiper.wipe(verifier);
                    }
                }
            } catch (GeneralSecurityException | IOException e) {
                return new EnrollmentResult(EnrollmentStatus.UNAVAILABLE, null);
            }
        }
    }

    /**
     * Stores an emergency PIN next to the primary one. Passing an empty array
     * removes it. The emergency PIN must differ from the primary one, or
     * entering it under coercion would simply unlock the application.
     */
    public EmergencyStatus enrollEmergency(char[] pin) {
        boolean removing = (pin == null || pin.length == 0);
        if (!removing && !NovaPrivacyContract.isValidPin(pin)) {
            return EmergencyStatus.INVALID_PIN;
        }
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                if (!stateFile.isFile() || !NovaPinKeyStore.containsKey()) {
                    return EmergencyStatus.NOT_ENROLLED;
                }
                NovaPinKeyStore.KeyMaterial keyMaterial = NovaPinKeyStore.getExisting();
                StoredState stored = readState(keyMaterial);
                byte[] salt = null;
                byte[] verifier = null;
                try {
                    if (removing) {
                        writeState(keyMaterial, stored.withEmergency(null, null));
                        return EmergencyStatus.ENROLLED;
                    }
                    if (NovaPinKdf.verify(pin, stored.salt, stored.kdfIterations, stored.verifier)) {
                        return EmergencyStatus.SAME_AS_PRIMARY;
                    }
                    salt = NovaPinKdf.newSalt(random);
                    verifier = NovaPinKdf.createVerifier(pin, salt, stored.kdfIterations);
                    writeState(keyMaterial, stored.withEmergency(salt, verifier));
                    return EmergencyStatus.ENROLLED;
                } finally {
                    stored.clearSensitive();
                    if (salt != null) {
                        NovaSecretWiper.wipe(salt);
                    }
                    if (verifier != null) {
                        NovaSecretWiper.wipe(verifier);
                    }
                }
            } catch (CorruptStateException | AEADBadTagException e) {
                return EmergencyStatus.CORRUPT_STATE;
            } catch (GeneralSecurityException | IOException e) {
                return EmergencyStatus.UNAVAILABLE;
            }
        }
    }

    public VerificationResult verify(char[] pin) {
        if (!NovaPrivacyContract.isValidPin(pin)) {
            return new VerificationResult(VerificationStatus.INVALID_PIN, 0L, 0, null);
        }
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                boolean fileExists = stateFile.isFile();
                boolean keyExists = NovaPinKeyStore.containsKey();
                if (!fileExists && !keyExists) {
                    return new VerificationResult(VerificationStatus.NOT_ENROLLED, 0L, 0, null);
                }
                if (!fileExists || !keyExists) {
                    return new VerificationResult(VerificationStatus.CORRUPT_STATE, 0L, 0, null);
                }

                NovaPinKeyStore.KeyMaterial keyMaterial = NovaPinKeyStore.getExisting();
                StoredState stored = readState(keyMaterial);
                try {
                    long now = SystemClock.elapsedRealtime();
                    int bootCount = readBootCount();
                    NovaPinLockout.Evaluation evaluation = NovaPinLockout.evaluate(
                            stored.lockout,
                            now,
                            bootCount
                    );
                    if (evaluation.isStateChanged()) {
                        stored = stored.withLockout(evaluation.getState());
                        writeState(keyMaterial, stored);
                    }
                    // The emergency PIN is checked before the lockout gate on
                    // purpose. Under coercion an attacker can burn attempts
                    // until the delay is long, and if the emergency PIN were
                    // blocked by that delay the whole scenario it exists for
                    // would be the one case where it does not work.
                    if (stored.hasEmergency() && NovaPinKdf.verify(
                            pin,
                            stored.emergencySalt,
                            stored.kdfIterations,
                            stored.emergencyVerifier
                    )) {
                        return new VerificationResult(
                                VerificationStatus.EMERGENCY,
                                0L,
                                0,
                                stored.protectionLevel
                        );
                    }

                    if (evaluation.isLocked()) {
                        return new VerificationResult(
                                VerificationStatus.LOCKED,
                                evaluation.getRemainingMillis(),
                                stored.lockout.getFailedAttempts(),
                                stored.protectionLevel
                        );
                    }

                    boolean accepted = NovaPinKdf.verify(
                            pin,
                            stored.salt,
                            stored.kdfIterations,
                            stored.verifier
                    );
                    if (accepted) {
                        StoredState reset = stored.withLockout(NovaPinLockout.afterSuccess());
                        writeState(keyMaterial, reset);
                        return new VerificationResult(
                                VerificationStatus.ACCEPTED,
                                0L,
                                0,
                                stored.protectionLevel
                        );
                    }

                    NovaPinLockout.State failed = NovaPinLockout.afterFailure(
                            stored.lockout,
                            now,
                            bootCount
                    );
                    writeState(keyMaterial, stored.withLockout(failed));
                    long delay = NovaPrivacyContract.getPinRetryDelayMillis(failed.getFailedAttempts());
                    return new VerificationResult(
                            VerificationStatus.REJECTED,
                            delay,
                            failed.getFailedAttempts(),
                            stored.protectionLevel
                    );
                } finally {
                    stored.clearSensitive();
                }
            } catch (CorruptStateException | AEADBadTagException e) {
                return new VerificationResult(VerificationStatus.CORRUPT_STATE, 0L, 0, null);
            } catch (GeneralSecurityException | IOException e) {
                return new VerificationResult(VerificationStatus.UNAVAILABLE, 0L, 0, null);
            }
        }
    }

    /**
     * Cheap "is a PIN set" answer for the settings screen, which has to draw a
     * row before it can afford to decrypt anything. It asks the same two
     * questions {@link #inspect()} asks first and stops there, so it can say
     * "enrolled" for a state that later turns out to be corrupt - the gate is
     * what finds that out, and it already reports it.
     */
    public boolean isEnrolled() {
        synchronized (PROCESS_LOCK) {
            try {
                return stateFile.isFile() && NovaPinKeyStore.containsKey();
            } catch (GeneralSecurityException e) {
                return false;
            }
        }
    }

    /**
     * Removes the application PIN after the user has proved they know it.
     *
     * <p>The check is {@link #verify}, unchanged and whole, so everything it
     * guarantees still holds here: the lockout delay after wrong attempts
     * applies, and the emergency PIN is answered with {@code EMERGENCY} rather
     * than with a removal. That last part is the reason this does not do its
     * own comparison. Someone standing over the owner and demanding the PIN be
     * switched off is exactly the situation the emergency PIN exists for, and
     * a "remove" screen that quietly refused it - or worse, accepted it and
     * removed the protection - would be a hole in the one path that must not
     * have one.</p>
     *
     * <p>The emergency PIN goes with the primary one, because the gate is the
     * only place it can ever be typed and there is no gate without a primary
     * PIN. Leaving it stored would keep a secret on disk that nothing can
     * reach, and would let it come back to life unannounced the next time a
     * PIN is set. The setting screen says this in as many words before asking.
     * </p>
     */
    public VerificationResult disable(char[] pin) {
        VerificationResult result = verify(pin);
        if (result.getStatus() != VerificationStatus.ACCEPTED) {
            return result;
        }
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                // Both halves, or neither: a state file without its key and a
                // key without its state file are what inspect() reports as
                // CORRUPT, and leaving the user there would be worse than
                // leaving the PIN on.
                atomicStateFile.delete();
                NovaPinKeyStore.delete();
                return result;
            } catch (GeneralSecurityException | IOException e) {
                return new VerificationResult(
                        VerificationStatus.UNAVAILABLE,
                        0L,
                        0,
                        null
                );
            }
        }
    }

    private int readBootCount() {
        try {
            return Settings.Global.getInt(
                    applicationContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT
            );
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            return NovaPinLockout.UNKNOWN_BOOT_COUNT;
        }
    }

    private StoredState readState(NovaPinKeyStore.KeyMaterial keyMaterial)
            throws IOException, GeneralSecurityException, CorruptStateException {
        byte[] encoded = atomicStateFile.readFully();
        if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
            throw new CorruptStateException("Invalid encrypted state size");
        }

        byte[] plaintext = null;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != OUTER_MAGIC) {
                throw new CorruptStateException("Invalid encrypted state header");
            }
            int outerVersion = input.readInt();
            if (outerVersion < MIN_STORAGE_VERSION || outerVersion > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported encrypted state version");
            }
            int ivLength = input.readInt();
            if (ivLength != GCM_IV_BYTES) {
                throw new CorruptStateException("Invalid GCM IV length");
            }
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            int ciphertextLength = input.readInt();
            if (ciphertextLength <= 16 || ciphertextLength > MAX_STATE_BYTES) {
                throw new CorruptStateException("Invalid ciphertext length");
            }
            byte[] ciphertext = new byte[ciphertextLength];
            input.readFully(ciphertext);
            if (input.available() != 0) {
                throw new CorruptStateException("Trailing encrypted state data");
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyMaterial.getKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(STATE_AAD);
            plaintext = cipher.doFinal(ciphertext);
            return decodePlaintext(plaintext, keyMaterial.getProtectionLevel());
        } finally {
            NovaSecretWiper.wipe(encoded);
            if (plaintext != null) {
                NovaSecretWiper.wipe(plaintext);
            }
        }
    }

    private void writeState(NovaPinKeyStore.KeyMaterial keyMaterial, StoredState state)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = encodePlaintext(state);
        byte[] ciphertext = null;
        byte[] encoded = null;
        FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyMaterial.getKey());
            cipher.updateAAD(STATE_AAD);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != GCM_IV_BYTES) {
                throw new GeneralSecurityException("Android Keystore returned an invalid GCM IV");
            }
            ciphertext = cipher.doFinal(plaintext);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeInt(OUTER_MAGIC);
                data.writeInt(STORAGE_VERSION);
                data.writeInt(iv.length);
                data.write(iv);
                data.writeInt(ciphertext.length);
                data.write(ciphertext);
            }
            encoded = bytes.toByteArray();

            ensureParentDirectory();
            output = atomicStateFile.startWrite();
            output.write(encoded);
            atomicStateFile.finishWrite(output);
            output = null;
        } catch (IOException | GeneralSecurityException e) {
            if (output != null) {
                atomicStateFile.failWrite(output);
            }
            throw e;
        } finally {
            NovaSecretWiper.wipe(plaintext);
            if (ciphertext != null) {
                NovaSecretWiper.wipe(ciphertext);
            }
            if (encoded != null) {
                NovaSecretWiper.wipe(encoded);
            }
        }
    }

    private static byte[] encodePlaintext(StoredState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(INNER_MAGIC);
            output.writeInt(STORAGE_VERSION);
            output.writeInt(state.kdfIterations);
            output.writeInt(state.protectionLevel.getStorageId());
            output.writeInt(state.lockout.getFailedAttempts());
            output.writeLong(state.lockout.getLockoutStartedElapsedRealtime());
            output.writeLong(state.lockout.getLockoutDeadlineElapsedRealtime());
            output.writeInt(state.lockout.getBootCount());
            output.writeInt(state.salt.length);
            output.write(state.salt);
            output.writeInt(state.verifier.length);
            output.write(state.verifier);
            output.writeBoolean(state.hasEmergency());
            if (state.hasEmergency()) {
                output.writeInt(state.emergencySalt.length);
                output.write(state.emergencySalt);
                output.writeInt(state.emergencyVerifier.length);
                output.write(state.emergencyVerifier);
            }
        }
        return bytes.toByteArray();
    }

    private static StoredState decodePlaintext(
            byte[] plaintext,
            NovaPinKeyStore.ProtectionLevel actualProtectionLevel
    ) throws IOException, CorruptStateException {
        byte[] salt = null;
        byte[] verifier = null;
        byte[] emergencySalt = null;
        byte[] emergencyVerifier = null;
        boolean transferred = false;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readInt() != INNER_MAGIC) {
                throw new CorruptStateException("Invalid protected state header");
            }
            int version = input.readInt();
            if (version < MIN_STORAGE_VERSION || version > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported protected state version");
            }
            int iterations = input.readInt();
            if (iterations < NovaPinKdf.ITERATIONS || iterations > MAX_KDF_ITERATIONS) {
                throw new CorruptStateException("Invalid KDF iteration count");
            }
            NovaPinKeyStore.ProtectionLevel storedProtectionLevel =
                    NovaPinKeyStore.ProtectionLevel.fromStorageId(input.readInt());
            if (storedProtectionLevel == NovaPinKeyStore.ProtectionLevel.UNKNOWN
                    || storedProtectionLevel != actualProtectionLevel) {
                throw new CorruptStateException("PIN key protection level changed");
            }
            int failedAttempts = input.readInt();
            long lockoutStart = input.readLong();
            long lockoutDeadline = input.readLong();
            int bootCount = input.readInt();
            salt = readFixed(input, NovaPinKdf.SALT_BYTES);
            verifier = readFixed(input, NovaPinKdf.VERIFIER_BYTES);
            if (version >= 2 && input.readBoolean()) {
                emergencySalt = readFixed(input, NovaPinKdf.SALT_BYTES);
                emergencyVerifier = readFixed(input, NovaPinKdf.VERIFIER_BYTES);
            }
            if (input.available() != 0) {
                throw new CorruptStateException("Trailing protected state data");
            }
            NovaPinLockout.State lockout;
            try {
                lockout = new NovaPinLockout.State(
                        failedAttempts,
                        lockoutStart,
                        lockoutDeadline,
                        bootCount
                );
            } catch (IllegalArgumentException e) {
                throw new CorruptStateException("Invalid lockout state", e);
            }
            StoredState result = new StoredState(
                    iterations,
                    storedProtectionLevel,
                    salt,
                    verifier,
                    emergencySalt,
                    emergencyVerifier,
                    lockout
            );
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                if (salt != null) {
                    NovaSecretWiper.wipe(salt);
                }
                if (verifier != null) {
                    NovaSecretWiper.wipe(verifier);
                }
                if (emergencySalt != null) {
                    NovaSecretWiper.wipe(emergencySalt);
                }
                if (emergencyVerifier != null) {
                    NovaSecretWiper.wipe(emergencyVerifier);
                }
            }
        }
    }

    private static byte[] readFixed(DataInputStream input, int expectedLength)
            throws IOException, CorruptStateException {
        int length = input.readInt();
        if (length != expectedLength) {
            throw new CorruptStateException("Invalid protected field length");
        }
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private LockedFile lock() throws IOException {
        ensureParentDirectory();
        RandomAccessFile randomAccessFile = new RandomAccessFile(lockFile, "rw");
        FileChannel channel = randomAccessFile.getChannel();
        try {
            FileLock fileLock = channel.lock();
            return new LockedFile(randomAccessFile, channel, fileLock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            randomAccessFile.close();
            throw e;
        }
    }

    private void ensureParentDirectory() throws IOException {
        File parent = stateFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("Unable to create NovaGram protected state directory");
        }
    }

    private void deleteStateFileQuietly() {
        if (stateFile.exists()) {
            stateFile.delete();
        }
        File backup = new File(stateFile.getPath() + ".bak");
        if (backup.exists()) {
            backup.delete();
        }
    }

    public enum VaultState {
        NOT_ENROLLED,
        ENROLLED,
        CORRUPT,
        UNAVAILABLE
    }

    public static final class VaultInspection {
        private final VaultState state;
        private final long retryAfterMillis;
        private final NovaPinKeyStore.ProtectionLevel protectionLevel;

        private VaultInspection(
                VaultState state,
                long retryAfterMillis,
                NovaPinKeyStore.ProtectionLevel protectionLevel
        ) {
            this.state = state;
            this.retryAfterMillis = retryAfterMillis;
            this.protectionLevel = protectionLevel;
        }

        public VaultState getState() {
            return state;
        }

        public long getRetryAfterMillis() {
            return retryAfterMillis;
        }

        public NovaPinKeyStore.ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }
    }

    public enum EnrollmentStatus {
        ENROLLED,
        INVALID_PIN,
        SOFTWARE_CONSENT_REQUIRED,
        ALREADY_ENROLLED,
        CORRUPT_STATE,
        UNAVAILABLE
    }

    public enum EmergencyStatus {
        ENROLLED,
        INVALID_PIN,
        SAME_AS_PRIMARY,
        NOT_ENROLLED,
        CORRUPT_STATE,
        UNAVAILABLE,
    }

    public enum VerificationStatus {
        ACCEPTED,
        /** The emergency PIN was entered: destroy the data, never unlock. */
        EMERGENCY,
        REJECTED,
        LOCKED,
        INVALID_PIN,
        NOT_ENROLLED,
        CORRUPT_STATE,
        UNAVAILABLE
    }

    public static final class EnrollmentResult {
        private final EnrollmentStatus status;
        private final NovaPinKeyStore.ProtectionLevel protectionLevel;

        private EnrollmentResult(
                EnrollmentStatus status,
                NovaPinKeyStore.ProtectionLevel protectionLevel
        ) {
            this.status = status;
            this.protectionLevel = protectionLevel;
        }

        public EnrollmentStatus getStatus() {
            return status;
        }

        public NovaPinKeyStore.ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }
    }

    public static final class VerificationResult {
        private final VerificationStatus status;
        private final long retryAfterMillis;
        private final int failedAttempts;
        private final NovaPinKeyStore.ProtectionLevel protectionLevel;

        private VerificationResult(
                VerificationStatus status,
                long retryAfterMillis,
                int failedAttempts,
                NovaPinKeyStore.ProtectionLevel protectionLevel
        ) {
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
            this.failedAttempts = failedAttempts;
            this.protectionLevel = protectionLevel;
        }

        public VerificationStatus getStatus() {
            return status;
        }

        public long getRetryAfterMillis() {
            return retryAfterMillis;
        }

        public int getFailedAttempts() {
            return failedAttempts;
        }

        public NovaPinKeyStore.ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }
    }

    private static final class StoredState {
        private final int kdfIterations;
        private final NovaPinKeyStore.ProtectionLevel protectionLevel;
        private final byte[] salt;
        private final byte[] verifier;
        private final byte[] emergencySalt;
        private final byte[] emergencyVerifier;
        private final NovaPinLockout.State lockout;

        private StoredState(
                int kdfIterations,
                NovaPinKeyStore.ProtectionLevel protectionLevel,
                byte[] salt,
                byte[] verifier,
                byte[] emergencySalt,
                byte[] emergencyVerifier,
                NovaPinLockout.State lockout
        ) {
            this.kdfIterations = kdfIterations;
            this.protectionLevel = protectionLevel;
            this.salt = salt;
            this.verifier = verifier;
            this.emergencySalt = emergencySalt;
            this.emergencyVerifier = emergencyVerifier;
            this.lockout = lockout;
        }

        private boolean hasEmergency() {
            return emergencySalt != null && emergencyVerifier != null;
        }

        private StoredState withLockout(NovaPinLockout.State value) {
            return new StoredState(
                    kdfIterations,
                    protectionLevel,
                    salt,
                    verifier,
                    emergencySalt,
                    emergencyVerifier,
                    value
            );
        }

        private StoredState withEmergency(byte[] newSalt, byte[] newVerifier) {
            return new StoredState(
                    kdfIterations,
                    protectionLevel,
                    salt,
                    verifier,
                    newSalt,
                    newVerifier,
                    lockout
            );
        }

        private void clearSensitive() {
            NovaSecretWiper.wipe(salt);
            NovaSecretWiper.wipe(verifier);
            if (emergencySalt != null) {
                NovaSecretWiper.wipe(emergencySalt);
            }
            if (emergencyVerifier != null) {
                NovaSecretWiper.wipe(emergencyVerifier);
            }
        }
    }

    private static final class LockedFile implements AutoCloseable {
        private final RandomAccessFile randomAccessFile;
        private final FileChannel channel;
        private final FileLock lock;

        private LockedFile(RandomAccessFile randomAccessFile, FileChannel channel, FileLock lock) {
            this.randomAccessFile = randomAccessFile;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
            } finally {
                try {
                    channel.close();
                } catch (IOException ignored) {
                } finally {
                    try {
                        randomAccessFile.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static final class CorruptStateException extends Exception {
        private CorruptStateException(String message) {
            super(message);
        }

        private CorruptStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
