package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-sealed list of chat members whose messages are muted.
 *
 * <p>A rule is a pair of identifiers — the chat and the person in it — and "the
 * people I do not want to see" is exactly the metadata the fork promises to
 * protect. It gets the same treatment as the auto-delete queue and the read
 * status rules: its own Keystore key and an authenticated container, never
 * plain shared preferences, which are XML readable by anyone holding the
 * device storage.</p>
 *
 * <p>All methods do disk and cryptographic work and must run away from the UI
 * thread.</p>
 */
public final class NovaMutedMembersStore {

    public static final String KEY_ALIAS = "novagram.mutedmembers.state.v1";

    /**
     * A person muted in one chat is one entry. The cap is generous because a
     * rule costs sixteen bytes and the whole sealed blob stays far below
     * {@link #MAX_STATE_BYTES}; it exists so a corrupt count cannot make the
     * reader allocate without bound.
     */
    public static final int MAX_RULES = 8192;

    private static final String PROVIDER = "AndroidKeyStore";
    private static final Object PROCESS_LOCK = new Object();
    private static final int OUTER_MAGIC = 0x4e564d53; // NVMS
    private static final int INNER_MAGIC = 0x4e564d31; // NVM1
    private static final int STORAGE_VERSION = 1;
    private static final int MIN_STORAGE_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_STATE_BYTES = 256 * 1024;
    private static final byte[] STATE_AAD =
            "NovaGram muted members v1".getBytes(StandardCharsets.US_ASCII);

    private final File stateFile;
    private final AtomicFile atomicStateFile;

    public NovaMutedMembersStore(Context context, int account) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (account < 0) {
            throw new IllegalArgumentException("account must not be negative");
        }
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        File directory = new File(target.getNoBackupFilesDir(), "novagram/security");
        // The directory already says NovaGram; the file itself does not have to
        // name the feature to anyone reading a directory listing.
        stateFile = new File(directory, "member_rules_" + account + ".bin");
        atomicStateFile = new AtomicFile(stateFile);
    }

    /**
     * Reads the rules. {@code ownerId} is the account they must belong to:
     * account slots are reused, and the rules of a previous owner would hide
     * the messages of whoever happens to share an identifier.
     */
    public State load(long ownerId) {
        synchronized (PROCESS_LOCK) {
            try {
                if (!stateFile.isFile()) {
                    return new State(ownerId);
                }
                State state = readState(getOrCreateKey());
                if (state.ownerId != ownerId) {
                    deleteQuietly();
                    return new State(ownerId);
                }
                return state;
            } catch (CorruptStateException | AEADBadTagException | EOFException e) {
                deleteQuietly();
                return new State(ownerId);
            } catch (GeneralSecurityException | IOException e) {
                // Unavailable is not empty. Here the safe answer is the opposite
                // of the read status store's: showing a message that should have
                // been muted is a nuisance, hiding one that should not have been
                // is a lost message. The engine shows everything while the rules
                // cannot be read, and says so by this flag.
                State state = new State(ownerId);
                state.unavailable = true;
                return state;
            }
        }
    }

    public boolean save(State state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        synchronized (PROCESS_LOCK) {
            FileOutputStream output = null;
            try {
                byte[] plaintext = encodePlaintext(state);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                cipher.updateAAD(STATE_AAD);
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != GCM_IV_BYTES) {
                    throw new GeneralSecurityException("Android Keystore returned an invalid GCM IV");
                }
                byte[] ciphertext = cipher.doFinal(plaintext);

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream data = new DataOutputStream(bytes)) {
                    data.writeInt(OUTER_MAGIC);
                    data.writeInt(STORAGE_VERSION);
                    data.writeInt(iv.length);
                    data.write(iv);
                    data.writeInt(ciphertext.length);
                    data.write(ciphertext);
                }
                byte[] encoded = bytes.toByteArray();

                ensureParentDirectory();
                output = atomicStateFile.startWrite();
                output.write(encoded);
                atomicStateFile.finishWrite(output);
                output = null;

                NovaSecretWiper.wipe(plaintext);
                NovaSecretWiper.wipe(ciphertext);
                NovaSecretWiper.wipe(encoded);
                return true;
            } catch (GeneralSecurityException | IOException e) {
                if (output != null) {
                    atomicStateFile.failWrite(output);
                }
                return false;
            }
        }
    }

    public void destroy() {
        synchronized (PROCESS_LOCK) {
            deleteQuietly();
        }
    }

    /** Removes the Keystore key, making any surviving copy of the file useless. */
    public static synchronized void deleteKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
    }

    private State readState(SecretKey key)
            throws IOException, GeneralSecurityException, CorruptStateException {
        byte[] encoded = atomicStateFile.readFully();
        if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
            throw new CorruptStateException("Invalid sealed member rules size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != OUTER_MAGIC) {
                throw new CorruptStateException("Invalid sealed member rules header");
            }
            int version = input.readInt();
            if (version < MIN_STORAGE_VERSION || version > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported sealed member rules version");
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
                throw new CorruptStateException("Trailing sealed member rules data");
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(STATE_AAD);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return decodePlaintext(plaintext);
            } finally {
                NovaSecretWiper.wipe(plaintext);
            }
        } finally {
            NovaSecretWiper.wipe(encoded);
        }
    }

    private static byte[] encodePlaintext(State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(INNER_MAGIC);
            output.writeInt(STORAGE_VERSION);
            output.writeLong(state.ownerId);
            output.writeInt(state.countRules());
            int written = 0;
            for (Map.Entry<Long, LinkedHashSet<Long>> chat : state.rules.entrySet()) {
                for (Long userId : chat.getValue()) {
                    if (written >= MAX_RULES) {
                        break;
                    }
                    output.writeLong(chat.getKey());
                    output.writeLong(userId);
                    written++;
                }
            }
        }
        return bytes.toByteArray();
    }

    private static State decodePlaintext(byte[] plaintext)
            throws IOException, CorruptStateException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readInt() != INNER_MAGIC) {
                throw new CorruptStateException("Invalid member rules header");
            }
            int version = input.readInt();
            if (version < MIN_STORAGE_VERSION || version > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported member rules version");
            }
            State state = new State(input.readLong());
            int count = input.readInt();
            if (count < 0 || count > MAX_RULES) {
                throw new CorruptStateException("Invalid member rule count");
            }
            for (int i = 0; i < count; i++) {
                long dialogId = input.readLong();
                long userId = input.readLong();
                if (dialogId == 0 || userId == 0) {
                    throw new CorruptStateException("Invalid member rule");
                }
                state.setMuted(dialogId, userId, true);
            }
            if (input.available() != 0) {
                throw new CorruptStateException("Trailing member rules data");
            }
            return state;
        }
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(true);
            } catch (StrongBoxUnavailableException ignored) {
                deleteKeyQuietly();
            } catch (GeneralSecurityException | RuntimeException ignored) {
                deleteKeyQuietly();
            }
        }
        return generate(false);
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
                // Not tied to the PIN: the rules have to be readable while the
                // chat list is being drawn, which happens before any unlock.
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
        } catch (IOException e) {
            throw new GeneralSecurityException("Unable to load Android Keystore", e);
        }
    }

    private static void deleteKeyQuietly() {
        try {
            deleteKey();
        } catch (GeneralSecurityException ignored) {
        }
    }

    private void ensureParentDirectory() throws IOException {
        File parent = stateFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("Unable to create NovaGram protected state directory");
        }
    }

    private void deleteQuietly() {
        if (stateFile.exists()) {
            stateFile.delete();
        }
        File backup = new File(stateFile.getPath() + ".bak");
        if (backup.exists()) {
            backup.delete();
        }
    }

    /** In-memory image of the sealed rules, owned by a single engine. */
    public static final class State {
        /** Chat to the members muted in it. A chat with none is not kept. */
        private final LinkedHashMap<Long, LinkedHashSet<Long>> rules = new LinkedHashMap<>();
        private final long ownerId;
        private boolean unavailable;

        public State(long ownerId) {
            this.ownerId = ownerId;
        }

        public long getOwnerId() {
            return ownerId;
        }

        /** True when the rules could not be read and must not be assumed empty. */
        public boolean isUnavailable() {
            return unavailable;
        }

        public boolean isEmpty() {
            return rules.isEmpty();
        }

        public boolean isMuted(long dialogId, long userId) {
            Set<Long> members = rules.get(dialogId);
            return members != null && members.contains(userId);
        }

        public boolean hasChat(long dialogId) {
            Set<Long> members = rules.get(dialogId);
            return members != null && !members.isEmpty();
        }

        /** Every rule as a pair, for merging one state into another. */
        public void forEachRule(RuleVisitor visitor) {
            for (Map.Entry<Long, LinkedHashSet<Long>> chat : rules.entrySet()) {
                for (Long userId : chat.getValue()) {
                    visitor.visit(chat.getKey(), userId);
                }
            }
        }

        public void setMutedTrue(long dialogId, long userId) {
            setMuted(dialogId, userId, true);
        }

        public void setMuted(long dialogId, long userId, boolean muted) {
            if (dialogId == 0 || userId == 0) {
                return;
            }
            if (!muted) {
                Set<Long> members = rules.get(dialogId);
                if (members != null && members.remove(userId) && members.isEmpty()) {
                    rules.remove(dialogId);
                }
                return;
            }
            LinkedHashSet<Long> members = rules.get(dialogId);
            if (members == null) {
                if (countRules() >= MAX_RULES) {
                    return;
                }
                members = new LinkedHashSet<>();
                rules.put(dialogId, members);
            }
            if (members.contains(userId) || countRules() < MAX_RULES) {
                members.add(userId);
            }
        }

        /**
         * Forgets everything decided about a chat. Used when the conversation is
         * deleted: a rule that outlives it would mute a stranger who happens to
         * appear in whatever chat takes that identifier next.
         */
        public void forgetChat(long dialogId) {
            rules.remove(dialogId);
        }

        public int countRules() {
            int total = 0;
            for (LinkedHashSet<Long> members : rules.values()) {
                total += members.size();
            }
            return total;
        }

        public State copy() {
            State result = new State(ownerId);
            for (Map.Entry<Long, LinkedHashSet<Long>> chat : rules.entrySet()) {
                result.rules.put(chat.getKey(), new LinkedHashSet<>(chat.getValue()));
            }
            result.unavailable = unavailable;
            return result;
        }
    }

    /** Callback of {@link State#forEachRule}. */
    public interface RuleVisitor {
        void visit(long dialogId, long userId);
    }

    private static final class CorruptStateException extends Exception {
        private CorruptStateException(String message) {
            super(message);
        }
    }
}
