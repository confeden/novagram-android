package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.util.AtomicFile;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Binds the datacenter authorization keys to this device.
 *
 * <p>Upstream writes {@code tgnet.dat} in the clear. Whoever can read the
 * application data directory - through root, a recovery image or a service
 * shop - can drop it into another installation and be signed in, with no cloud
 * password and no new-device notice, because from the servers' point of view
 * nothing new ever logged in.</p>
 *
 * <p>Here the file is sealed with a random key that is itself wrapped by a
 * non-exportable Android Keystore key. The wrapped copy travels with the data;
 * the Keystore key does not. On any other device the seal cannot be opened, so
 * the copy holds no authorization at all.</p>
 *
 * <p>What this does not do: protect against code running as this application on
 * this device. That is what the PIN is for.</p>
 */
public final class NovaDeviceLock {
    private static final int FILE_MAGIC = 0x4E56444C; // NVDL
    private static final int FILE_VERSION = 1;
    private static final int SECRET_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_SEALED_BYTES = 4096;

    /** First bytes of a sealed tgnet.dat payload; see jni/tgnet/NovaConfigSeal.cpp. */
    private static final byte[] CONFIG_SEAL_MAGIC = { 'N', 'V', 'C', '1' };
    private static final String CONFIG_FILE = "tgnet.dat";

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] cachedSecret;
    private static boolean secretResolved;
    private static Boolean configWasSealed;
    private static Boolean blocked;

    private NovaDeviceLock() {
    }

    /**
     * True when the authorization data on this disk was sealed somewhere else.
     * Nothing may be started in that state: there is no key here that opens it,
     * and letting the client run would only overwrite data that the device it
     * belongs to can still read.
     */
    public static boolean isBlocked() {
        synchronized (LOCK) {
            if (blocked != null) {
                return blocked;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                // Asked too early to answer. Not cached, so that the real
                // answer is still computed once there is a context.
                return false;
            }
            boolean sealed = isAnyConfigSealed();
            blocked = sealed && existingSecret() == null;
            if (blocked) {
                FileLog.e("NovaGram device lock: the stored authorization belongs to another device");
            }
            return blocked;
        }
    }

    public static boolean isEnabled() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return true;
        }
        try {
            return NovaPrivacySettings.global(context)
                    .isFeatureEnabled(NovaPrivacyFeature.DEVICE_BINDING);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Hands the sealing key to the network library. Called from the
     * {@link ConnectionsManager} constructor, before {@code native_init} builds
     * the first {@code Config}: the very first read of {@code tgnet.dat} has to
     * go through the key, not after it.
     */
    public static void installNativeKey() {
        byte[] secret;
        boolean seal;
        synchronized (LOCK) {
            if (isBlocked()) {
                secret = null;
                seal = false;
            } else if (isEnabled()) {
                secret = secretForSealing();
                seal = (secret != null);
            } else {
                // A file sealed before the owner switched binding off still has
                // to be readable - that is what lets the next write put it back
                // in the clear.
                secret = existingSecret();
                seal = false;
            }
        }
        try {
            ConnectionsManager.native_novaSetDeviceKey(secret, seal);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * True when what is on disk does not match what would be written now: an
     * unsealed file from an older build, or a binding switched on or off since
     * the last write. Answered once per process, before anything writes.
     */
    public static boolean needsConfigRewrite() {
        synchronized (LOCK) {
            if (isBlocked()) {
                return false;
            }
            boolean sealed = isAnyConfigSealed();
            boolean wouldSeal = isEnabled() && secretForSealing() != null;
            return sealed != wouldSeal;
        }
    }

    /** Applies a change of the setting to every account that is already running. */
    public static void applyToRunningAccounts() {
        installNativeKey();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    ConnectionsManager.getInstance(account).novaRewriteConfig();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        NovaPrivacySettings.global(context)
                .setFeatureEnabled(NovaPrivacyFeature.DEVICE_BINDING, enabled);
        synchronized (LOCK) {
            // The Keystore key is deliberately not deleted when binding is
            // switched off. The rewrite below is asynchronous, and a key
            // removed before it lands would leave a sealed file nothing can
            // open. Switching back on then reuses the same key.
            configWasSealed = null;
        }
        applyToRunningAccounts();
    }

    /**
     * Gives up on data this device cannot read and turns the installation back
     * into a first start. Must not run on the UI thread.
     */
    public static void resetForNewDevice(Context context) {
        forget();
        NovaEmergencyWipe.runForNewDevice(context);
    }

    /**
     * Forgets the secret, in this process and in the network library, without
     * touching the Keystore. Called by whoever deletes the binding file behind
     * this class's back - the emergency wipe does - because a write that lands
     * after the file is gone would seal {@code tgnet.dat} to a secret the next
     * start cannot recover, and the wipe would end at the blocked screen.
     */
    public static void forget() {
        synchronized (LOCK) {
            NovaSecretWiper.wipe(cachedSecret);
            cachedSecret = null;
            secretResolved = false;
            configWasSealed = null;
            blocked = null;
        }
        try {
            ConnectionsManager.native_novaSetDeviceKey(null, false);
        } catch (Throwable ignored) {
            // The native library is not loaded on the blocked path: nothing
            // was started, so there is no key over there to forget either.
        }
    }

    // -- the secret ------------------------------------------------------

    /** The stored secret, or null. Never creates one. */
    private static byte[] existingSecret() {
        synchronized (LOCK) {
            if (secretResolved) {
                return cachedSecret;
            }
            if (ApplicationLoader.applicationContext == null) {
                // Deliberately not cached: "asked too early" is not an answer,
                // and remembering it as one would leave the process unbound.
                return null;
            }
            secretResolved = true;
            cachedSecret = readSecret();
            return cachedSecret;
        }
    }

    /** The stored secret, creating and persisting one when there is none. */
    private static byte[] secretForSealing() {
        synchronized (LOCK) {
            byte[] existing = existingSecret();
            if (existing != null) {
                return existing;
            }
            if (bindingFile() != null && bindingFile().exists()) {
                // The file is there and did not open. Replacing it would throw
                // away the only thing that could still decrypt this data.
                return null;
            }
            byte[] created = createSecret();
            cachedSecret = created;
            secretResolved = true;
            return created;
        }
    }

    private static byte[] readSecret() {
        File file = bindingFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            DataInputStream stream = new DataInputStream(input);
            if (stream.readInt() != FILE_MAGIC || stream.readInt() != FILE_VERSION) {
                FileLog.e("NovaGram device lock: unreadable binding file");
                return null;
            }
            int ivLength = stream.readInt();
            if (ivLength != GCM_IV_BYTES) {
                return null;
            }
            byte[] iv = new byte[ivLength];
            stream.readFully(iv);
            int sealedLength = stream.readInt();
            if (sealedLength <= 16 || sealedLength > MAX_SEALED_BYTES) {
                return null;
            }
            byte[] sealed = new byte[sealedLength];
            stream.readFully(sealed);

            SecretKey key = NovaDeviceKeyStore.getExisting();
            if (key == null) {
                FileLog.e("NovaGram device lock: no Keystore key for the stored binding");
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] secret = cipher.doFinal(sealed);
            if (secret.length != SECRET_BYTES) {
                NovaSecretWiper.wipe(secret);
                return null;
            }
            return secret;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] createSecret() {
        File file = bindingFile();
        if (file == null) {
            return null;
        }
        byte[] secret = new byte[SECRET_BYTES];
        byte[] sealed = null;
        byte[] iv = null;
        RANDOM.nextBytes(secret);
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                    && !parent.isDirectory()) {
                throw new IOException("Unable to create the NovaGram device directory");
            }
            SecretKey key = NovaDeviceKeyStore.getOrCreate();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            iv = cipher.getIV();
            if (iv == null || iv.length != GCM_IV_BYTES) {
                throw new GeneralSecurityException("Invalid Android Keystore GCM IV");
            }
            sealed = cipher.doFinal(secret);

            output = atomicFile.startWrite();
            DataOutputStream stream = new DataOutputStream(output);
            stream.writeInt(FILE_MAGIC);
            stream.writeInt(FILE_VERSION);
            stream.writeInt(iv.length);
            stream.write(iv);
            stream.writeInt(sealed.length);
            stream.write(sealed);
            stream.flush();
            atomicFile.finishWrite(output);
            output = null;
            return secret;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            atomicFile.delete();
            NovaSecretWiper.wipe(secret);
            FileLog.e(e);
            return null;
        } finally {
            NovaSecretWiper.wipe(sealed);
            NovaSecretWiper.wipe(iv);
        }
    }

    private static File bindingFile() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return new File(context.getNoBackupFilesDir(), "novagram/device/binding.v1");
    }

    // -- what is on disk -------------------------------------------------

    private static boolean isAnyConfigSealed() {
        synchronized (LOCK) {
            if (configWasSealed != null) {
                return configWasSealed;
            }
            boolean sealed = false;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (isConfigSealed(configFile(account))) {
                    sealed = true;
                    break;
                }
            }
            configWasSealed = sealed;
            return sealed;
        }
    }

    /**
     * The path {@code ConnectionsManager} hands to the native library: the
     * fixed files directory for account 0, plus one directory per extra slot.
     */
    private static File configFile(int account) {
        File base = ApplicationLoader.getFilesDirFixed();
        if (base == null) {
            return null;
        }
        if (account != 0) {
            base = new File(base, "account" + account);
        }
        return new File(base, CONFIG_FILE);
    }

    private static boolean isConfigSealed(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        // Four bytes of length written by Config::writeConfig, then the payload.
        byte[] head = new byte[4 + CONFIG_SEAL_MAGIC.length];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = 0;
            while (read < head.length) {
                int count = input.read(head, read, head.length - read);
                if (count < 0) {
                    return false;
                }
                read += count;
            }
        } catch (IOException e) {
            FileLog.e(e);
            return false;
        }
        for (int i = 0; i < CONFIG_SEAL_MAGIC.length; i++) {
            if (head[4 + i] != CONFIG_SEAL_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
