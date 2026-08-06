package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class NovaEncryptedBlobStoreInstrumentedTest {
    private NovaEncryptedBlobStore store;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new NovaEncryptedBlobStore(context);
        store.clearAllAndDestroyKek();
    }

    @After
    public void tearDown() throws Exception {
        store.clearAllAndDestroyKek();
    }

    @Test
    public void hardwareBackedRoundTripTamperAndDelete() throws Exception {
        NovaCacheKeyStore.ProtectionLevel level = store.initialize(false);
        assertTrue(level.isHardwareBacked());

        byte[] marker = "NOVAGRAM_PLAINTEXT_SENTINEL".getBytes(StandardCharsets.US_ASCII);
        byte[] plaintext = new byte[256 * 1024];
        for (int offset = 0; offset < plaintext.length; offset += marker.length) {
            System.arraycopy(marker, 0, plaintext, offset, Math.min(marker.length, plaintext.length - offset));
        }

        NovaEncryptedBlobStore.BlobRecord record = store.put(
                new ByteArrayInputStream(plaintext),
                NovaPrivacyContract.DEFAULT_RETENTION_HOURS,
                false
        );
        assertTrue(record.getProtectionLevel().isHardwareBacked());

        byte[] recovered;
        try (NovaEncryptedBlobStore.BlobInputStream input = store.open(record.getBlobId())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(plaintext.length);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            recovered = output.toByteArray();
        }
        assertArrayEquals(plaintext, recovered);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File blob = new File(
                context.getNoBackupFilesDir(),
                "novagram/encrypted_cache/v1/" + record.getBlobId() + ".nvb"
        );
        byte[] ciphertext = new byte[(int) blob.length()];
        try (RandomAccessFile file = new RandomAccessFile(blob, "r")) {
            file.readFully(ciphertext);
        }
        assertFalse(contains(ciphertext, marker));

        try (RandomAccessFile file = new RandomAccessFile(blob, "rw")) {
            long last = file.length() - 1L;
            file.seek(last);
            int original = file.read();
            file.seek(last);
            file.write(original ^ 0x01);
        }
        try (NovaEncryptedBlobStore.BlobInputStream input = store.open(record.getBlobId())) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
                // A complete read must authenticate the final streaming segment.
            }
            fail("Tampered encrypted cache blob was accepted");
        } catch (GeneralSecurityException | java.io.IOException expected) {
            // Expected fail-closed behavior.
        }

        assertTrue(store.delete(record.getBlobId()));
        try {
            store.open(record.getBlobId());
            fail("Deleted encrypted cache blob was reopened");
        } catch (java.io.IOException expected) {
            // The wrapped per-blob key and ciphertext are no longer available.
        }

        Arrays.fill(plaintext, (byte) 0);
        Arrays.fill(recovered, (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
