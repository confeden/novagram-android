package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.util.AtomicFile;

import com.google.crypto.tink.BinaryKeysetReader;
import com.google.crypto.tink.BinaryKeysetWriter;
import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Versioned encrypted blob foundation for private application cache data.
 *
 * <p>Each blob receives an independent Tink Streaming AEAD keyset. That keyset
 * is wrapped with a separate, non-exportable Android Keystore KEK. No plaintext
 * temporary file is created. This class is not yet a transparent replacement
 * for Telegram's random-access media cache.</p>
 */
public final class NovaEncryptedBlobStore {
    private static final Object PROCESS_LOCK = new Object();
    private static final Object TINK_LOCK = new Object();

    private static final int OUTER_MAGIC = 0x4e564231; // NVB1
    private static final int INNER_MAGIC = 0x4e564d31; // NVM1
    private static final int FORMAT_VERSION = 1;
    private static final int BLOB_ID_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_WRAPPED_KEY_BYTES = 32 * 1024;
    private static final int COPY_BUFFER_BYTES = 32 * 1024;
    private static final String FILE_SUFFIX = ".nvb";

    private static final byte[] KEY_AAD_PREFIX =
            "NovaGram cache wrapped key v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STREAM_AAD_PREFIX =
            "NovaGram encrypted cache stream v1".getBytes(StandardCharsets.US_ASCII);

    private static volatile boolean tinkRegistered;

    private final File rootDirectory;
    private final SecureRandom random = new SecureRandom();

    public NovaEncryptedBlobStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context app = context.getApplicationContext();
        Context resolved = app != null ? app : context;
        rootDirectory = new File(resolved.getNoBackupFilesDir(), "novagram/encrypted_cache/v1");
    }

    public NovaCacheKeyStore.ProtectionLevel initialize(boolean acceptSoftwareProtection)
            throws GeneralSecurityException,
            NovaCacheKeyStore.SoftwareProtectionRequiredException,
            IOException {
        synchronized (PROCESS_LOCK) {
            ensureRootDirectory();
            registerTink();
            return NovaCacheKeyStore.getOrCreate(acceptSoftwareProtection).getProtectionLevel();
        }
    }

    public BlobRecord put(
            InputStream plaintext,
            int retentionHours,
            boolean acceptSoftwareProtection
    ) throws IOException, GeneralSecurityException,
            NovaCacheKeyStore.SoftwareProtectionRequiredException {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        NovaPrivacyContract.requireRetentionHours(retentionHours);

        synchronized (PROCESS_LOCK) {
            ensureRootDirectory();
            registerTink();
            NovaCacheKeyStore.KeyMaterial kek =
                    NovaCacheKeyStore.getOrCreate(acceptSoftwareProtection);

            byte[] blobIdBytes = new byte[BLOB_ID_BYTES];
            String blobId;
            File target;
            do {
                random.nextBytes(blobIdBytes);
                blobId = encodeHex(blobIdBytes);
                target = fileForValidatedId(blobId);
            } while (target.exists());

            long createdAt = System.currentTimeMillis();
            long expiresAt = NovaCachePolicy.expirationFor(createdAt, retentionHours);
            byte[] keyAad = associatedData(KEY_AAD_PREFIX, blobIdBytes);
            byte[] streamAad = associatedData(STREAM_AAD_PREFIX, blobIdBytes);
            byte[] serializedKeyset = null;
            byte[] wrappedKeyset = null;
            byte[] wrappingIv = null;
            AtomicFile atomicFile = new AtomicFile(target);
            FileOutputStream rawOutput = null;
            try {
                KeysetHandle handle = KeysetHandle.generateNew(
                        StreamingAeadKeyTemplates.AES256_GCM_HKDF_4KB
                );
                serializedKeyset = serializeKeyset(handle);

                Cipher wrappingCipher = Cipher.getInstance("AES/GCM/NoPadding");
                wrappingCipher.init(Cipher.ENCRYPT_MODE, kek.getKey());
                wrappingCipher.updateAAD(keyAad);
                wrappingIv = wrappingCipher.getIV();
                if (wrappingIv == null || wrappingIv.length != GCM_IV_BYTES) {
                    throw new GeneralSecurityException("Invalid Android Keystore GCM IV");
                }
                wrappedKeyset = wrappingCipher.doFinal(serializedKeyset);
                if (wrappedKeyset.length > MAX_WRAPPED_KEY_BYTES) {
                    throw new GeneralSecurityException("Wrapped cache keyset is too large");
                }

                rawOutput = atomicFile.startWrite();
                DataOutputStream header = new DataOutputStream(rawOutput);
                header.writeInt(OUTER_MAGIC);
                header.writeInt(FORMAT_VERSION);
                header.writeInt(blobIdBytes.length);
                header.write(blobIdBytes);
                header.writeInt(wrappingIv.length);
                header.write(wrappingIv);
                header.writeInt(wrappedKeyset.length);
                header.write(wrappedKeyset);
                header.flush();

                StreamingAead streamingAead = handle.getPrimitive(StreamingAead.class);
                OutputStream encrypted = streamingAead.newEncryptingStream(
                        new NonClosingOutputStream(rawOutput),
                        streamAad
                );
                try (DataOutputStream protectedOutput = new DataOutputStream(encrypted)) {
                    protectedOutput.writeInt(INNER_MAGIC);
                    protectedOutput.writeInt(FORMAT_VERSION);
                    protectedOutput.writeLong(createdAt);
                    protectedOutput.writeLong(expiresAt);
                    copy(plaintext, protectedOutput);
                }

                atomicFile.finishWrite(rawOutput);
                rawOutput = null;
                return new BlobRecord(
                        blobId,
                        createdAt,
                        expiresAt,
                        kek.getProtectionLevel()
                );
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                if (rawOutput != null) {
                    atomicFile.failWrite(rawOutput);
                    rawOutput = null;
                }
                atomicFile.delete();
                throw e;
            } finally {
                NovaSecretWiper.wipe(blobIdBytes);
                NovaSecretWiper.wipe(keyAad);
                NovaSecretWiper.wipe(streamAad);
                NovaSecretWiper.wipe(serializedKeyset);
                NovaSecretWiper.wipe(wrappedKeyset);
                NovaSecretWiper.wipe(wrappingIv);
            }
        }
    }

    public BlobInputStream open(String blobId)
            throws IOException, GeneralSecurityException, ExpiredBlobException {
        synchronized (PROCESS_LOCK) {
            ensureRootDirectory();
            registerTink();
            File target = fileForValidatedId(blobId);
            if (!target.isFile()) {
                throw new IOException("Encrypted cache blob does not exist");
            }

            byte[] expectedBlobId = decodeHex(blobId);
            byte[] storedBlobId = null;
            byte[] wrappingIv = null;
            byte[] wrappedKeyset = null;
            byte[] serializedKeyset = null;
            byte[] keyAad = null;
            byte[] streamAad = null;
            FileInputStream rawInput = null;
            boolean transferred = false;
            try {
                rawInput = new AtomicFile(target).openRead();
                DataInputStream header = new DataInputStream(rawInput);
                if (header.readInt() != OUTER_MAGIC || header.readInt() != FORMAT_VERSION) {
                    throw new GeneralSecurityException("Invalid encrypted cache header");
                }
                int blobIdLength = header.readInt();
                if (blobIdLength != BLOB_ID_BYTES) {
                    throw new GeneralSecurityException("Invalid encrypted cache blob ID length");
                }
                storedBlobId = new byte[blobIdLength];
                header.readFully(storedBlobId);
                if (!MessageDigest.isEqual(expectedBlobId, storedBlobId)) {
                    throw new GeneralSecurityException("Encrypted cache blob ID mismatch");
                }
                int ivLength = header.readInt();
                if (ivLength != GCM_IV_BYTES) {
                    throw new GeneralSecurityException("Invalid wrapped key IV length");
                }
                wrappingIv = new byte[ivLength];
                header.readFully(wrappingIv);
                int wrappedLength = header.readInt();
                if (wrappedLength <= 16 || wrappedLength > MAX_WRAPPED_KEY_BYTES) {
                    throw new GeneralSecurityException("Invalid wrapped cache key length");
                }
                wrappedKeyset = new byte[wrappedLength];
                header.readFully(wrappedKeyset);

                keyAad = associatedData(KEY_AAD_PREFIX, storedBlobId);
                streamAad = associatedData(STREAM_AAD_PREFIX, storedBlobId);
                Cipher wrappingCipher = Cipher.getInstance("AES/GCM/NoPadding");
                wrappingCipher.init(
                        Cipher.DECRYPT_MODE,
                        NovaCacheKeyStore.getExisting().getKey(),
                        new GCMParameterSpec(128, wrappingIv)
                );
                wrappingCipher.updateAAD(keyAad);
                serializedKeyset = wrappingCipher.doFinal(wrappedKeyset);

                KeysetHandle handle = CleartextKeysetHandle.read(
                        BinaryKeysetReader.withBytes(serializedKeyset)
                );
                StreamingAead streamingAead = handle.getPrimitive(StreamingAead.class);
                InputStream decrypted = streamingAead.newDecryptingStream(rawInput, streamAad);
                DataInputStream protectedInput = new DataInputStream(decrypted);
                if (protectedInput.readInt() != INNER_MAGIC
                        || protectedInput.readInt() != FORMAT_VERSION) {
                    throw new GeneralSecurityException("Invalid protected cache metadata");
                }
                long createdAt = protectedInput.readLong();
                long expiresAt = protectedInput.readLong();
                long now = System.currentTimeMillis();
                if (NovaCachePolicy.isExpired(createdAt, expiresAt, now)) {
                    protectedInput.close();
                    rawInput = null;
                    delete(blobId);
                    throw new ExpiredBlobException(blobId, createdAt, expiresAt);
                }

                BlobRecord record = new BlobRecord(
                        blobId,
                        createdAt,
                        expiresAt,
                        NovaCacheKeyStore.getExisting().getProtectionLevel()
                );
                BlobInputStream result = new BlobInputStream(protectedInput, record);
                transferred = true;
                rawInput = null;
                return result;
            } finally {
                if (!transferred && rawInput != null) {
                    try {
                        rawInput.close();
                    } catch (IOException ignored) {
                    }
                }
                NovaSecretWiper.wipe(expectedBlobId);
                NovaSecretWiper.wipe(storedBlobId);
                NovaSecretWiper.wipe(wrappingIv);
                NovaSecretWiper.wipe(wrappedKeyset);
                NovaSecretWiper.wipe(serializedKeyset);
                NovaSecretWiper.wipe(keyAad);
                NovaSecretWiper.wipe(streamAad);
            }
        }
    }

    public boolean delete(String blobId) {
        synchronized (PROCESS_LOCK) {
            File target = fileForValidatedId(blobId);
            boolean existed = target.exists()
                    || new File(target.getPath() + ".new").exists()
                    || new File(target.getPath() + ".bak").exists();
            new AtomicFile(target).delete();
            return existed;
        }
    }

    public int purgeExpired(int maximumFiles) {
        if (maximumFiles <= 0) {
            throw new IllegalArgumentException("maximumFiles must be positive");
        }
        synchronized (PROCESS_LOCK) {
            File[] files = rootDirectory.listFiles((directory, name) -> name.endsWith(FILE_SUFFIX));
            if (files == null) {
                return 0;
            }
            int removed = 0;
            int inspected = 0;
            for (File file : files) {
                if (inspected >= maximumFiles) {
                    break;
                }
                inspected++;
                String name = file.getName();
                String blobId = name.substring(0, name.length() - FILE_SUFFIX.length());
                if (!NovaCachePolicy.isValidBlobId(blobId)) {
                    continue;
                }
                try (BlobInputStream ignored = open(blobId)) {
                    // Reading authenticated metadata is sufficient for the TTL decision.
                } catch (ExpiredBlobException e) {
                    removed++;
                } catch (IOException | GeneralSecurityException ignored) {
                    // Corruption is reported by open(); callers may decide whether to evict it.
                }
            }
            return removed;
        }
    }

    /** Removes wrapped per-blob keys before deleting the cache KEK. Flash overwrite is not claimed. */
    public void clearAllAndDestroyKek() throws GeneralSecurityException {
        synchronized (PROCESS_LOCK) {
            File[] files = rootDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        new AtomicFile(file).delete();
                    }
                }
            }
            NovaCacheKeyStore.delete();
        }
    }

    private File fileForValidatedId(String blobId) {
        if (!NovaCachePolicy.isValidBlobId(blobId)) {
            throw new IllegalArgumentException("Invalid encrypted cache blob ID");
        }
        return new File(rootDirectory, blobId + FILE_SUFFIX);
    }

    private void ensureRootDirectory() throws IOException {
        if (!rootDirectory.isDirectory()
                && !rootDirectory.mkdirs()
                && !rootDirectory.isDirectory()) {
            throw new IOException("Unable to create NovaGram encrypted cache directory");
        }
    }

    private static void registerTink() throws GeneralSecurityException {
        if (tinkRegistered) {
            return;
        }
        synchronized (TINK_LOCK) {
            if (!tinkRegistered) {
                StreamingAeadConfig.register();
                tinkRegistered = true;
            }
        }
    }

    private static byte[] serializeKeyset(KeysetHandle handle)
            throws IOException, GeneralSecurityException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(output));
        return output.toByteArray();
    }

    private static byte[] associatedData(byte[] prefix, byte[] blobId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(prefix.length + blobId.length + 8);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_VERSION);
            output.writeInt(prefix.length);
            output.write(prefix);
            output.write(blobId);
        }
        return bytes.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } finally {
            NovaSecretWiper.wipe(buffer);
        }
    }

    private static String encodeHex(byte[] value) {
        char[] encoded = new char[value.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int unsigned = value[i] & 0xff;
            encoded[i * 2] = alphabet[unsigned >>> 4];
            encoded[i * 2 + 1] = alphabet[unsigned & 0x0f];
        }
        return new String(encoded);
    }

    private static byte[] decodeHex(String encoded) {
        if (!NovaCachePolicy.isValidBlobId(encoded)) {
            throw new IllegalArgumentException("Invalid encrypted cache blob ID");
        }
        byte[] decoded = new byte[encoded.length() / 2];
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = (byte) ((Character.digit(encoded.charAt(i * 2), 16) << 4)
                    | Character.digit(encoded.charAt(i * 2 + 1), 16));
        }
        return decoded;
    }

    public static final class BlobRecord {
        private final String blobId;
        private final long createdEpochMillis;
        private final long expiresEpochMillis;
        private final NovaCacheKeyStore.ProtectionLevel protectionLevel;

        private BlobRecord(
                String blobId,
                long createdEpochMillis,
                long expiresEpochMillis,
                NovaCacheKeyStore.ProtectionLevel protectionLevel
        ) {
            this.blobId = blobId;
            this.createdEpochMillis = createdEpochMillis;
            this.expiresEpochMillis = expiresEpochMillis;
            this.protectionLevel = protectionLevel;
        }

        public String getBlobId() {
            return blobId;
        }

        public long getCreatedEpochMillis() {
            return createdEpochMillis;
        }

        public long getExpiresEpochMillis() {
            return expiresEpochMillis;
        }

        public NovaCacheKeyStore.ProtectionLevel getProtectionLevel() {
            return protectionLevel;
        }
    }

    public static final class BlobInputStream extends FilterInputStream {
        private final BlobRecord record;

        private BlobInputStream(InputStream input, BlobRecord record) {
            super(input);
            this.record = record;
        }

        public BlobRecord getRecord() {
            return record;
        }
    }

    public static final class ExpiredBlobException extends IOException {
        private final String blobId;
        private final long createdEpochMillis;
        private final long expiresEpochMillis;

        private ExpiredBlobException(String blobId, long createdEpochMillis, long expiresEpochMillis) {
            super("Encrypted cache blob expired");
            this.blobId = blobId;
            this.createdEpochMillis = createdEpochMillis;
            this.expiresEpochMillis = expiresEpochMillis;
        }

        public String getBlobId() {
            return blobId;
        }

        public long getCreatedEpochMillis() {
            return createdEpochMillis;
        }

        public long getExpiresEpochMillis() {
            return expiresEpochMillis;
        }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        private NonClosingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
