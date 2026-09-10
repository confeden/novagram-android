package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-sealed per-chat rules for hiding the read status.
 *
 * <p>The rules are a list of dialog identifiers, and a list of "these are the
 * people whose messages I read quietly" is exactly the metadata the fork
 * promises to protect, so it gets the same treatment as the auto-delete queue:
 * its own Keystore key and an authenticated container, never plain shared
 * preferences.</p>
 *
 * <p>All methods do disk and cryptographic work and must run away from the UI
 * thread.</p>
 */
public final class NovaReadStatusStore {
    /** No message of this dialog is confirmed as read to the server. */
    public static final int RULE_HIDDEN = 1;
    /** The user turned hiding off here; the choice is permanent. */
    public static final int RULE_REVEALED = 2;
    /**
     * Written down by the sweep over the dialogs the account already had,
     * while the one thing that could have told them apart - the bar the server
     * offers over a stranger who wrote first - had not been asked for. It
     * behaves like a {@link #RULE_REVEALED} that is not final: receipts are
     * withheld, and the first real answer about that peer turns it into one of
     * the two above.
     *
     * <p>Its whole reason: the sweep runs when the chat list arrives, and at
     * that moment the peer settings are unknown for every dialog, because they
     * are only asked for when a conversation is opened. So the sweep used to
     * write REVEALED over every dialog the account already had - including the
     * ones a stranger had started and the user never answered, which are
     * exactly the ones the feature is for.</p>
     */
    public static final int RULE_ASSUMED = 3;

    public static final String KEY_ALIAS = "novagram.readstatus.state.v1";
    /**
     * The baseline writes one rule per dialog the account already had, so the
     * cap has to hold a whole chat list rather than only the decided dialogs.
     * At twelve bytes per rule this still leaves the sealed blob far below
     * {@link #MAX_STATE_BYTES}.
     */
    public static final int MAX_RULES = 16384;

    /**
     * Spells of dropping remembered per dialog. Four is already generous — the
     * switch goes off by itself as soon as the user answers — and when there
     * are more the two oldest are merged into one span rather than forgotten,
     * because forgetting a range hands back what was thrown away.
     */
    public static final int MAX_DROP_RANGES = 4;

    private static final String PROVIDER = "AndroidKeyStore";
    private static final Object PROCESS_LOCK = new Object();
    private static final int OUTER_MAGIC = 0x4e565253; // NVRS
    private static final int INNER_MAGIC = 0x4e565231; // NVR1
    /**
     * Version 2 added the baseline date; version 3 the per-dialog watermark of
     * "drop what arrives from here on"; version 4 tells a REVEALED the client
     * was told from one it only assumed. Version 1 blobs are read as "no
     * baseline", version 2 blobs as "no dialog drops anything" - both are the
     * state those builds actually meant - and in a blob older than 4 every
     * REVEALED becomes an assumption, because the sweep wrote them without an
     * answer and a real reveal wrote the same value. Asking again settles both
     * correctly: a dialog the user has written in shows no stranger bar. A
     * HIDDEN is never re-asked - taking one back would send the receipts it
     * held.
     */
    private static final int STORAGE_VERSION = 4;
    private static final int MIN_STORAGE_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_STATE_BYTES = 256 * 1024;
    private static final byte[] STATE_AAD =
            "NovaGram read status rules v1".getBytes(StandardCharsets.US_ASCII);

    private final File stateFile;
    private final AtomicFile atomicStateFile;

    public NovaReadStatusStore(Context context, int account) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (account < 0) {
            throw new IllegalArgumentException("account must not be negative");
        }
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        File directory = new File(target.getNoBackupFilesDir(), "novagram/security");
        stateFile = new File(directory, "chat_rules_" + account + ".bin");
        atomicStateFile = new AtomicFile(stateFile);
    }

    /**
     * Reads the rules. {@code ownerId} is the account they must belong to:
     * account slots are reused, and rules of a previous owner would silence
     * read receipts for whoever signs in next.
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
                // Unavailable is not empty: returning no rules here would send
                // read receipts the user asked to withhold. The engine treats a
                // failed load as "stay quiet" instead.
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
            throw new CorruptStateException("Invalid sealed rules size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != OUTER_MAGIC) {
                throw new CorruptStateException("Invalid sealed rules header");
            }
            int version = input.readInt();
            if (version < MIN_STORAGE_VERSION || version > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported sealed rules version");
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
                throw new CorruptStateException("Trailing sealed rules data");
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
            output.writeInt(state.baselineDate);
            Map<Long, Integer> rules = state.getRules();
            int count = Math.min(rules.size(), MAX_RULES);
            output.writeInt(count);
            int written = 0;
            for (Map.Entry<Long, Integer> rule : rules.entrySet()) {
                if (written >= count) {
                    break;
                }
                output.writeLong(rule.getKey());
                output.writeInt(rule.getValue());
                written++;
            }
            Map<Long, int[]> drops = state.getDropRanges();
            int dropCount = Math.min(drops.size(), MAX_RULES);
            output.writeInt(dropCount);
            written = 0;
            for (Map.Entry<Long, int[]> drop : drops.entrySet()) {
                if (written >= dropCount) {
                    break;
                }
                int[] ranges = drop.getValue();
                output.writeLong(drop.getKey());
                output.writeInt(ranges.length / 3);
                for (int i = 0; i + 2 < ranges.length; i += 3) {
                    output.writeInt(ranges[i]);
                    output.writeInt(ranges[i + 1]);
                    output.writeInt(ranges[i + 2]);
                }
                written++;
            }
        }
        return bytes.toByteArray();
    }

    private static State decodePlaintext(byte[] plaintext)
            throws IOException, CorruptStateException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readInt() != INNER_MAGIC) {
                throw new CorruptStateException("Invalid rules header");
            }
            int version = input.readInt();
            if (version < MIN_STORAGE_VERSION || version > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported rules version");
            }
            State state = new State(input.readLong());
            if (version >= 2) {
                state.baselineDate = input.readInt();
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_RULES) {
                throw new CorruptStateException("Invalid rule count");
            }
            for (int i = 0; i < count; i++) {
                long dialogId = input.readLong();
                int rule = input.readInt();
                if (rule != RULE_HIDDEN && rule != RULE_REVEALED && rule != RULE_ASSUMED) {
                    throw new CorruptStateException("Invalid rule");
                }
                if (version < 4 && rule == RULE_REVEALED) {
                    rule = RULE_ASSUMED;
                }
                state.rules.put(dialogId, rule);
            }
            if (version >= 3) {
                int dropCount = input.readInt();
                if (dropCount < 0 || dropCount > MAX_RULES) {
                    throw new CorruptStateException("Invalid drop count");
                }
                for (int i = 0; i < dropCount; i++) {
                    long dialogId = input.readLong();
                    int rangeCount = input.readInt();
                    if (rangeCount <= 0 || rangeCount > MAX_DROP_RANGES) {
                        throw new CorruptStateException("Invalid drop range count");
                    }
                    int[] ranges = new int[rangeCount * 3];
                    for (int j = 0; j < rangeCount; j++) {
                        int from = input.readInt();
                        int till = input.readInt();
                        int open = input.readInt();
                        if (from <= 0 || till < 0) {
                            throw new CorruptStateException("Invalid drop range");
                        }
                        ranges[j * 3] = from;
                        ranges[j * 3 + 1] = till;
                        ranges[j * 3 + 2] = open != 0 ? 1 : 0;
                    }
                    state.dropRanges.put(dialogId, ranges);
                }
            }
            if (input.available() != 0) {
                throw new CorruptStateException("Trailing rules data");
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
                .setUserAuthenticationRequired(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox);
        }
        generator.init(builder.build());
        return generator.generateKey();
    }

    /**
     * Whether the sealed rules sit in secure hardware. Only reported, never
     * enforced: refusing to run would mean sending the very read receipts the
     * user asked to withhold.
     */
    public static NovaPinKeyStore.ProtectionLevel protectionLevel() {
        try {
            SecretKey key = getOrCreateKey();
            SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), PROVIDER);
            KeyInfo info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                switch (info.getSecurityLevel()) {
                    case KeyProperties.SECURITY_LEVEL_STRONGBOX:
                        return NovaPinKeyStore.ProtectionLevel.STRONGBOX;
                    case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                        return NovaPinKeyStore.ProtectionLevel.TRUSTED_ENVIRONMENT;
                    case KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE:
                        return NovaPinKeyStore.ProtectionLevel.UNKNOWN_SECURE_HARDWARE;
                    case KeyProperties.SECURITY_LEVEL_SOFTWARE:
                        return NovaPinKeyStore.ProtectionLevel.SOFTWARE;
                    default:
                        return NovaPinKeyStore.ProtectionLevel.UNKNOWN;
                }
            }
            return info.isInsideSecureHardware()
                    ? NovaPinKeyStore.ProtectionLevel.TRUSTED_ENVIRONMENT
                    : NovaPinKeyStore.ProtectionLevel.SOFTWARE;
        } catch (GeneralSecurityException e) {
            return NovaPinKeyStore.ProtectionLevel.UNKNOWN;
        }
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
        private final LinkedHashMap<Long, Integer> rules = new LinkedHashMap<>();
        /**
         * The spells of dropping everything the other side sends, per dialog,
         * flattened into triples of {@code from, till, open}. Lives beside the
         * rules and not in a preference of its own: it is the same kind of
         * secret — a list of the people being ignored — and I1 says such a list
         * belongs in the sealed container, never in SharedPreferences.
         *
         * <p>A closed range is kept, and that is the whole point: an answer in
         * the dialog switches the dropping off, and without the range
         * everything it dropped would come back from the server the next time
         * the history is opened.</p>
         */
        private final LinkedHashMap<Long, int[]> dropRanges = new LinkedHashMap<>();
        private long ownerId;
        private int baselineDate;
        private boolean unavailable;

        public State(long ownerId) {
            this.ownerId = ownerId;
        }

        public long getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(long value) {
            ownerId = value;
        }

        /**
         * When the chat list this account already had was written down as
         * "was not started by the other side", in server seconds. Zero means it
         * never was, and then an absent rule says nothing about a dialog.
         */
        public int getBaselineDate() {
            return baselineDate;
        }

        public void setBaselineDate(int value) {
            baselineDate = value;
        }

        public boolean isBaselineTaken() {
            return baselineDate != 0;
        }

        /** True when the rules could not be read and must not be assumed empty. */
        public boolean isUnavailable() {
            return unavailable;
        }

        public Map<Long, Integer> getRules() {
            return rules;
        }

        public int getRule(long dialogId) {
            Integer rule = rules.get(dialogId);
            return rule == null ? 0 : rule;
        }

        public void setRule(long dialogId, int rule) {
            if (rule != RULE_HIDDEN && rule != RULE_REVEALED && rule != RULE_ASSUMED) {
                throw new IllegalArgumentException("Unknown read status rule " + rule);
            }
            if (rules.containsKey(dialogId) || rules.size() < MAX_RULES) {
                rules.put(dialogId, rule);
            }
        }

        /**
         * Forgets what was decided about a dialog. Used when the conversation
         * itself is deleted: a rule that outlives it would answer about the old
         * conversation when the next one begins.
         */
        public void removeRule(long dialogId) {
            rules.remove(dialogId);
            dropRanges.remove(dialogId);
        }

        public Map<Long, int[]> getDropRanges() {
            return dropRanges;
        }

        /** The triples of this dialog, or null when it drops nothing. */
        public int[] getDropRanges(long dialogId) {
            return dropRanges.get(dialogId);
        }

        public void setDropRanges(long dialogId, int[] ranges) {
            if (ranges == null || ranges.length == 0) {
                dropRanges.remove(dialogId);
            } else if (dropRanges.containsKey(dialogId)
                    || dropRanges.size() < MAX_RULES) {
                dropRanges.put(dialogId, ranges);
            }
        }
    }

    private static final class CorruptStateException extends Exception {
        private CorruptStateException(String message) {
            super(message);
        }
    }
}
