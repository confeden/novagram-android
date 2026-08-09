package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-sealed storage of the auto-delete queue and its per-chat rules.
 *
 * <p>The queue is a list of dialog and message identifiers, that is, exactly the
 * metadata the fork promises to protect, so it may not live in
 * {@link NovaPrivacySettings}: shared preferences are plain XML readable by
 * anyone holding the device storage. It is sealed with its own Keystore key
 * instead, which the emergency wipe destroys separately.</p>
 *
 * <p>All methods do disk and cryptographic work and must run away from the UI
 * thread. Each account keeps its own file, because queues of different accounts
 * must not become each other's evidence.</p>
 */
public final class NovaAutoDeleteStore {
    /** Auto-delete replaces the text first; {@link #STAGE_DELETE} follows. */
    public static final int STAGE_REPLACE = 0;
    public static final int STAGE_DELETE = 1;

    /** The message was a plain text one when queued, so replacing it is possible. */
    public static final int FLAG_EDITABLE = 1;
    /** Queued by Erase evidence, so its outcome belongs in that chat's report. */
    public static final int FLAG_ERASE = 2;

    public static final int RULE_DEFAULT = 0;
    public static final int RULE_ALWAYS = 1;
    public static final int RULE_NEVER = 2;

    private static final Object PROCESS_LOCK = new Object();
    private static final int OUTER_MAGIC = 0x4e564151; // NVAQ
    private static final int INNER_MAGIC = 0x4e564131; // NVA1
    /** Version 2 added the Erase evidence reports after the queue. */
    private static final int STORAGE_VERSION = 2;
    private static final int MIN_STORAGE_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_STATE_BYTES = 4 * 1024 * 1024;
    /**
     * A queue this long already covers a week of several thousand messages a
     * day. The cap exists so a runaway producer cannot grow the sealed file
     * without bound; reaching it is reported, never silently ignored.
     */
    public static final int MAX_QUEUE_ENTRIES = 20000;
    public static final int MAX_RULES = 4096;
    /** Reports of the last few Erase evidence runs, oldest dropped first. */
    public static final int MAX_REPORTS = 8;

    private static final byte[] STATE_AAD =
            "NovaGram auto-delete queue v1".getBytes(StandardCharsets.US_ASCII);

    private final File stateFile;
    private final AtomicFile atomicStateFile;
    private final File lockFile;

    public NovaAutoDeleteStore(Context context, int account) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (account < 0) {
            throw new IllegalArgumentException("account must not be negative");
        }
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        File directory = new File(target.getNoBackupFilesDir(), "novagram/security");
        // A neutral name: the directory already says NovaGram, but the file
        // itself should not name the feature to whoever lists it.
        stateFile = new File(directory, "queue_state_" + account + ".bin");
        atomicStateFile = new AtomicFile(stateFile);
        lockFile = new File(directory, "queue_state_" + account + ".lock");
    }

    /**
     * Reads the sealed state. A missing file is an empty queue, not an error.
     * Corruption is terminal for the contents: the queue is a cache of intent,
     * and a half-decrypted list of identifiers is worse than none.
     *
     * <p>{@code ownerId} is the user the queue must belong to. Account slots are
     * reused: signing out of one account and into another gives the new one the
     * same slot number, and message identifiers live in the account's own
     * namespace. A queue whose owner does not match is therefore discarded, not
     * applied to somebody else's messages.</p>
     */
    public LoadResult load(long ownerId) {
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                if (!stateFile.isFile()) {
                    return new LoadResult(LoadStatus.EMPTY, new State(ownerId));
                }
                NovaAutoDeleteKeyStore.KeyMaterial keyMaterial =
                        NovaAutoDeleteKeyStore.getOrCreate();
                State state = readState(keyMaterial);
                if (state.ownerId != ownerId) {
                    deleteQuietly();
                    return new LoadResult(LoadStatus.FOREIGN, new State(ownerId));
                }
                return new LoadResult(LoadStatus.LOADED, state);
            } catch (CorruptStateException | AEADBadTagException | EOFException e) {
                // A truncated file reaches this as EOFException. Without it the
                // read would be retried as a temporary failure forever.
                deleteQuietly();
                return new LoadResult(LoadStatus.CORRUPT, new State(ownerId));
            } catch (GeneralSecurityException | IOException e) {
                return new LoadResult(LoadStatus.UNAVAILABLE, new State(ownerId));
            }
        }
    }

    /** Returns false when the state could not be persisted. */
    public boolean save(State state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        synchronized (PROCESS_LOCK) {
            try (LockedFile ignored = lock()) {
                NovaAutoDeleteKeyStore.KeyMaterial keyMaterial =
                        NovaAutoDeleteKeyStore.getOrCreate();
                writeState(keyMaterial, state);
                return true;
            } catch (GeneralSecurityException | IOException e) {
                return false;
            }
        }
    }

    /** Removes the sealed file. The Keystore key is destroyed separately. */
    public void destroy() {
        synchronized (PROCESS_LOCK) {
            deleteQuietly();
        }
    }

    private State readState(NovaAutoDeleteKeyStore.KeyMaterial keyMaterial)
            throws IOException, GeneralSecurityException, CorruptStateException {
        byte[] encoded = atomicStateFile.readFully();
        if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
            throw new CorruptStateException("Invalid encrypted queue size");
        }

        byte[] plaintext = null;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != OUTER_MAGIC) {
                throw new CorruptStateException("Invalid encrypted queue header");
            }
            int outerVersion = input.readInt();
            if (outerVersion < MIN_STORAGE_VERSION || outerVersion > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported encrypted queue version");
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
                throw new CorruptStateException("Trailing encrypted queue data");
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

    private void writeState(NovaAutoDeleteKeyStore.KeyMaterial keyMaterial, State state)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = encodePlaintext(state, keyMaterial.getProtectionLevel());
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
            if (encoded.length > MAX_STATE_BYTES) {
                throw new IOException("Encrypted queue exceeds the maximum size");
            }

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

    private static byte[] encodePlaintext(
            State state,
            NovaPinKeyStore.ProtectionLevel protectionLevel
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(INNER_MAGIC);
            output.writeInt(STORAGE_VERSION);
            output.writeInt(protectionLevel.getStorageId());
            output.writeLong(state.ownerId);

            Map<Long, Integer> rules = state.getRules();
            int ruleCount = Math.min(rules.size(), MAX_RULES);
            output.writeInt(ruleCount);
            int written = 0;
            for (Map.Entry<Long, Integer> rule : rules.entrySet()) {
                if (written >= ruleCount) {
                    break;
                }
                output.writeLong(rule.getKey());
                output.writeInt(rule.getValue());
                written++;
            }

            List<Entry> queue = state.getQueue();
            int entryCount = Math.min(queue.size(), MAX_QUEUE_ENTRIES);
            output.writeInt(entryCount);
            for (int i = 0; i < entryCount; i++) {
                Entry entry = queue.get(i);
                output.writeLong(entry.dialogId);
                output.writeInt(entry.messageId);
                output.writeInt(entry.date);
                output.writeInt(entry.dueAt);
                output.writeInt(entry.stage);
                output.writeInt(entry.flags);
            }

            List<Report> reports = state.getReports();
            int reportCount = Math.min(reports.size(), MAX_REPORTS);
            output.writeInt(reportCount);
            for (int i = 0; i < reportCount; i++) {
                Report report = reports.get(i);
                output.writeLong(report.dialogId);
                output.writeInt(report.queued);
                output.writeInt(report.replaced);
                output.writeInt(report.deleted);
                output.writeInt(report.skipped);
                output.writeBoolean(report.finished);
                output.writeBoolean(report.shown);
            }
        }
        return bytes.toByteArray();
    }

    private static State decodePlaintext(
            byte[] plaintext,
            NovaPinKeyStore.ProtectionLevel actualProtectionLevel
    ) throws IOException, CorruptStateException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readInt() != INNER_MAGIC) {
                throw new CorruptStateException("Invalid queue header");
            }
            int version = input.readInt();
            if (version < MIN_STORAGE_VERSION || version > STORAGE_VERSION) {
                throw new CorruptStateException("Unsupported queue version");
            }
            NovaPinKeyStore.ProtectionLevel storedProtectionLevel =
                    NovaPinKeyStore.ProtectionLevel.fromStorageId(input.readInt());
            if (storedProtectionLevel == NovaPinKeyStore.ProtectionLevel.UNKNOWN
                    || storedProtectionLevel != actualProtectionLevel) {
                // A key that changed protection level is not the key that sealed
                // this file, whatever the Keystore says about the alias.
                throw new CorruptStateException("Queue key protection level changed");
            }
            long ownerId = input.readLong();

            int ruleCount = input.readInt();
            if (ruleCount < 0 || ruleCount > MAX_RULES) {
                throw new CorruptStateException("Invalid queue rule count");
            }
            State state = new State(ownerId);
            for (int i = 0; i < ruleCount; i++) {
                long dialogId = input.readLong();
                int rule = input.readInt();
                if (rule != RULE_ALWAYS && rule != RULE_NEVER) {
                    throw new CorruptStateException("Invalid per-chat rule");
                }
                state.rules.put(dialogId, rule);
            }

            int entryCount = input.readInt();
            if (entryCount < 0 || entryCount > MAX_QUEUE_ENTRIES) {
                throw new CorruptStateException("Invalid queue length");
            }
            for (int i = 0; i < entryCount; i++) {
                long dialogId = input.readLong();
                int messageId = input.readInt();
                int date = input.readInt();
                int dueAt = input.readInt();
                int stage = input.readInt();
                int flags = input.readInt();
                if (stage != STAGE_REPLACE && stage != STAGE_DELETE) {
                    throw new CorruptStateException("Invalid queue stage");
                }
                if (messageId <= 0 || date < 0 || dueAt < 0) {
                    throw new CorruptStateException("Invalid queue entry");
                }
                state.queue.add(new Entry(dialogId, messageId, date, dueAt, stage, flags));
            }

            if (version >= 2) {
                int reportCount = input.readInt();
                if (reportCount < 0 || reportCount > MAX_REPORTS) {
                    throw new CorruptStateException("Invalid report count");
                }
                for (int i = 0; i < reportCount; i++) {
                    Report report = new Report(input.readLong());
                    report.queued = input.readInt();
                    report.replaced = input.readInt();
                    report.deleted = input.readInt();
                    report.skipped = input.readInt();
                    report.finished = input.readBoolean();
                    report.shown = input.readBoolean();
                    if (report.queued < 0 || report.replaced < 0
                            || report.deleted < 0 || report.skipped < 0) {
                        throw new CorruptStateException("Invalid report counters");
                    }
                    state.reports.add(report);
                }
            }
            if (input.available() != 0) {
                throw new CorruptStateException("Trailing queue data");
            }
            return state;
        }
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

    private void deleteQuietly() {
        if (stateFile.exists()) {
            stateFile.delete();
        }
        File backup = new File(stateFile.getPath() + ".bak");
        if (backup.exists()) {
            backup.delete();
        }
    }

    public enum LoadStatus {
        /** State read and decrypted. */
        LOADED,
        /** No state yet: a fresh install or a wiped device. */
        EMPTY,
        /** The sealed queue belongs to another account and was discarded. */
        FOREIGN,
        /** Sealed state failed authentication and was discarded. */
        CORRUPT,
        /** Keystore or storage is temporarily unavailable; try again later. */
        UNAVAILABLE
    }

    public static final class LoadResult {
        private final LoadStatus status;
        private final State state;

        private LoadResult(LoadStatus status, State state) {
            this.status = status;
            this.state = state;
        }

        public LoadStatus getStatus() {
            return status;
        }

        public State getState() {
            return state;
        }
    }

    /** One queued message on its way through replace and delete. */
    public static final class Entry {
        public final long dialogId;
        public final int messageId;
        /** Server date of the message: the countdown runs from sending. */
        public final int date;
        public int dueAt;
        public int stage;
        public final int flags;

        public Entry(long dialogId, int messageId, int date, int dueAt, int stage, int flags) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.date = date;
            this.dueAt = dueAt;
            this.stage = stage;
            this.flags = flags;
        }

        public boolean isEditable() {
            return (flags & FLAG_EDITABLE) != 0;
        }
    }

    /**
     * What one Erase evidence run did in one chat. Kept until it has been shown,
     * because the run outlives the screen that started it: the queue keeps
     * working after the chat is closed and after a restart.
     */
    public static final class Report {
        public final long dialogId;
        /** Messages handed to the queue when the run finished collecting. */
        public int queued;
        /** How many were replaced with a dot before being deleted. */
        public int replaced;
        /** How many deletion requests were sent. */
        public int deleted;
        /** How many were dropped without reaching the server. */
        public int skipped;
        public boolean finished;
        public boolean shown;

        public Report(long dialogId) {
            this.dialogId = dialogId;
        }
    }

    /** Mutable in-memory image of the sealed file, owned by a single engine. */
    public static final class State {
        private final ArrayList<Entry> queue = new ArrayList<>();
        private final LinkedHashMap<Long, Integer> rules = new LinkedHashMap<>();
        private final ArrayList<Report> reports = new ArrayList<>();
        /** Telegram user the identifiers below belong to. */
        private long ownerId;

        public State(long ownerId) {
            this.ownerId = ownerId;
        }

        public long getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(long value) {
            ownerId = value;
        }

        public List<Entry> getQueue() {
            return queue;
        }

        public Map<Long, Integer> getRules() {
            return rules;
        }

        public List<Report> getReports() {
            return reports;
        }

        public Report findReport(long dialogId) {
            for (int i = 0, count = reports.size(); i < count; i++) {
                if (reports.get(i).dialogId == dialogId) {
                    return reports.get(i);
                }
            }
            return null;
        }

        /** Starts a fresh report, replacing an earlier one for the same chat. */
        public Report startReport(long dialogId) {
            for (int i = reports.size() - 1; i >= 0; i--) {
                if (reports.get(i).dialogId == dialogId) {
                    reports.remove(i);
                }
            }
            while (reports.size() >= MAX_REPORTS) {
                reports.remove(0);
            }
            Report report = new Report(dialogId);
            reports.add(report);
            return report;
        }

        public void removeReport(long dialogId) {
            for (int i = reports.size() - 1; i >= 0; i--) {
                if (reports.get(i).dialogId == dialogId) {
                    reports.remove(i);
                }
            }
        }

        public Entry find(long dialogId, int messageId) {
            for (int i = 0, count = queue.size(); i < count; i++) {
                Entry entry = queue.get(i);
                if (entry.dialogId == dialogId && entry.messageId == messageId) {
                    return entry;
                }
            }
            return null;
        }

        /** Returns false when the queue is full and the entry was not stored. */
        public boolean add(Entry entry) {
            if (queue.size() >= MAX_QUEUE_ENTRIES) {
                return false;
            }
            queue.add(entry);
            return true;
        }

        public void remove(long dialogId, int messageId) {
            for (int i = queue.size() - 1; i >= 0; i--) {
                Entry entry = queue.get(i);
                if (entry.dialogId == dialogId && entry.messageId == messageId) {
                    queue.remove(i);
                }
            }
        }

        public int getRule(long dialogId) {
            Integer rule = rules.get(dialogId);
            return rule == null ? RULE_DEFAULT : rule;
        }

        /** Storing {@link #RULE_DEFAULT} removes the explicit choice. */
        public void setRule(long dialogId, int rule) {
            if (rule == RULE_DEFAULT) {
                rules.remove(dialogId);
            } else if (rule == RULE_ALWAYS || rule == RULE_NEVER) {
                if (rules.containsKey(dialogId) || rules.size() < MAX_RULES) {
                    rules.put(dialogId, rule);
                }
            } else {
                throw new IllegalArgumentException("Unknown per-chat rule " + rule);
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
    }
}
