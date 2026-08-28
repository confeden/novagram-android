package org.telegram.messenger.novagram.privacy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.NovaPinGateActivity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
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

    /**
     * Set when the Keystore refused rather than answered, as opposed to "there
     * is nothing stored yet". A refusal is never cached as an answer: caching
     * it once left the process running unbound for good, with the settings row
     * still saying the binding was on.
     */
    private static boolean secretUnavailable;

    /** Set when the network library would not take the key. */
    private static boolean nativeKeyFailed;

    /**
     * Set once the network library has met a sealed config it could not open.
     * Independent of whether that was a verdict or a bad minute: either way it
     * read nothing and is holding an empty config over a sealed file, and
     * nothing may run on top of that. A Keystore that recovers a moment later
     * does not repair it - only starting again does - so this outranks every
     * other answer here.
     */
    private static boolean nativeConfigUnusable;

    /**
     * One way, set when a wipe starts. After it no binding is ever created
     * again in this process. Without it a {@link ConnectionsManager} built
     * between the two sweeps - a push account waking up is enough - would find
     * the binding file already deleted, decide there is none, and write a fresh
     * {@code binding.v1} together with a fresh Keystore entry. The second sweep
     * removes the file; the Keystore entry, deleted a moment earlier, would
     * survive the wipe and be inherited by the decoy.
     */
    private static boolean wiping;

    private NovaDeviceLock() {
    }

    /**
     * What the binding is actually doing right now, as opposed to what the
     * setting says. The two differ whenever the Keystore refuses, and the
     * settings screen has to show this one.
     */
    public enum State {
        /** The owner switched it off. The data directory is portable on purpose (D14). */
        OFF,
        /** On, and what is on disk is sealed to this device. */
        BOUND,
        /** On and working, but the file has not been rewritten through it yet. */
        PENDING,
        /** On in the setting and not in effect: the Keystore would not give a key. */
        UNAVAILABLE,
        /** What is on disk was sealed somewhere else. Nothing may be started over it. */
        FOREIGN
    }

    /**
     * True when the authorization data on this disk was sealed somewhere else.
     * Nothing may be started in that state: there is no key here that opens it,
     * and letting the client run would only overwrite data that the device it
     * belongs to can still read.
     */
    public static boolean isBlocked() {
        synchronized (LOCK) {
            if (nativeConfigUnusable) {
                // Outranks the cached answer and the Keystore alike. See the
                // field: the library is holding an empty config over a sealed
                // file, and a key that turns up afterwards does not undo that.
                return true;
            }
            if (blocked != null) {
                return blocked;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                // Asked too early to answer. Not cached, so that the real
                // answer is still computed once there is a context.
                return false;
            }
            if (!isAnyConfigSealed()) {
                blocked = false;
                return false;
            }
            boolean haveSecret = existingSecret() != null;
            if (!haveSecret && secretUnavailable) {
                // Sealed, and the Keystore would not say whether the key that
                // opens it is here. Blocked, because running would be running
                // over data that may well be ours - but not remembered as a
                // verdict, and not certain enough to offer the owner the one
                // irreversible start over. See isForeignCertain().
                FileLog.e("NovaGram device lock: cannot tell whose this data is, the Keystore did not answer");
                return true;
            }
            blocked = !haveSecret;
            if (blocked) {
                FileLog.e("NovaGram device lock: the stored authorization belongs to another device");
            }
            return blocked;
        }
    }

    /**
     * Whether "this belongs to another device" is an answer or only the safe
     * reply to a question that could not be asked. A Keystore that refused, and
     * a key the network library would not take, both look exactly like a
     * foreign data directory from the outside - and one of them is a phone
     * having a bad minute, which is no reason to offer to destroy an account.
     */
    public static boolean isForeignCertain() {
        synchronized (LOCK) {
            // Only when the verdict was actually reached and latched. Blocking
            // for want of an answer never gets here, so the screen that offers
            // the irreversible start over is never shown on that basis.
            return Boolean.TRUE.equals(blocked) && !secretUnavailable && !nativeKeyFailed;
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
        boolean wantedSeal;
        synchronized (LOCK) {
            wantedSeal = !isBlocked() && isEnabled();
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
        boolean installed;
        try {
            ConnectionsManager.native_novaSetDeviceKey(secret, seal);
            installed = true;
        } catch (Throwable e) {
            FileLog.e(e);
            installed = false;
            try {
                // Whatever the library was holding must not stay in place: a
                // stale key would keep sealing writes to a secret this class no
                // longer believes in.
                ConnectionsManager.native_novaSetDeviceKey(null, false);
            } catch (Throwable ignored) {
            }
        }
        synchronized (LOCK) {
            // Recorded rather than swallowed. Binding that was asked for and did
            // not happen is what the settings screen has to say out loud, and
            // what the next start has to try again.
            nativeKeyFailed = !installed || (wantedSeal && !seal);
            if (nativeKeyFailed) {
                FileLog.e("NovaGram device lock: binding is on but sealing is not in effect");
            }
        }
    }

    /**
     * True when what is on disk does not match what the owner asked for: an
     * unsealed file from an older build, or a binding switched on or off since
     * the last write.
     *
     * <p>Compared against the setting, deliberately, and not against what this
     * process managed to do. Comparing against the outcome meant that a
     * Keystore that refused a key answered "nothing to rewrite" - the file
     * stayed in the clear and nothing ever came back to it.</p>
     */
    public static boolean needsConfigRewrite(int account) {
        synchronized (LOCK) {
            if (isBlocked()) {
                return false;
            }
            // This account's own file. Asking "is any of them sealed" answered
            // for the wrong file: with two accounts upgrading from a build that
            // wrote in the clear, the first one sealing itself made the answer
            // true for the second as well, so the second never rewrote and
            // stayed in the clear for good.
            return isConfigSealed(configFile(account)) != isEnabled();
        }
    }

    /**
     * The network library met a sealed config it could not open. That is a
     * conclusion this class cannot always reach on its own: a binding file that
     * opens, holding a secret that is simply not the one the config was sealed
     * with, looks healthy from here and only fails inside the AEAD.
     *
     * <p>Nothing is deleted (D13). The verdict is latched so every later
     * question answers "another device", and the blocked screen is brought up
     * now rather than at the next cold start where it can be.</p>
     */
    public static void onNativeForeignConfig() {
        synchronized (LOCK) {
            // Set in either case: whatever the reason, this process read
            // nothing and cannot be repaired without starting again.
            nativeConfigUnusable = true;
            if (secretUnavailable || nativeKeyFailed) {
                // The library could not open it because this process never got
                // a key to try, not because the bytes are someone else's. Still
                // blocked - isBlocked() says so, and nothing is written either
                // way - but not latched, and not grounds for a start over.
                FileLog.e("NovaGram device lock: config unopened while no key was in effect, not calling it foreign");
                return;
            }
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            blocked = true;
        }
        FileLog.e("NovaGram device lock: the network library could not open the stored authorization");
        try {
            AndroidUtilities.runOnUIThread(NovaDeviceLock::showBlockedScreen);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Best effort only. The latched state above is what actually blocks: every
     * entry point asks {@link #isBlocked()} and the gate lands on the "another
     * device" screen anyway, at the latest when the application is next brought
     * to the front. This only saves the user from looking at a client that
     * cannot do anything until then.
     */
    private static void showBlockedScreen() {
        try {
            Activity activity = LaunchActivity.instance;
            Context context = activity != null ? activity : ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            Intent gate = new Intent(context, NovaPinGateActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(gate);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** What the binding is doing, for the settings screen. Reads, never creates. */
    public static State state() {
        synchronized (LOCK) {
            if (isBlocked()) {
                // Only call it foreign when it is known to be. isBlocked() also
                // answers true for "the Keystore would not say", and the gate
                // is careful not to name that as theft; this row must not name
                // it either.
                return isForeignCertain() ? State.FOREIGN : State.UNAVAILABLE;
            }
            if (!isEnabled()) {
                // Switched off, but the file on disk is still sealed: the
                // unsealing rewrite has not landed, or could not. Saying "off,
                // portable" there would be a plain untruth.
                return isAnyConfigSealed() ? State.PENDING : State.OFF;
            }
            if (nativeKeyFailed) {
                return State.UNAVAILABLE;
            }
            if (existingSecret() != null) {
                return isAnyConfigSealed() ? State.BOUND : State.PENDING;
            }
            if (secretUnavailable) {
                return State.UNAVAILABLE;
            }
            // No secret and nothing refused: nothing has needed one yet.
            return State.PENDING;
        }
    }

    /**
     * Drops what was remembered about the files on disk, so the next answer
     * looks at them again. The rewrite is queued on the network thread, so a
     * screen that wants to show the result has to ask afresh.
     */
    public static void refreshDiskState() {
        synchronized (LOCK) {
            configWasSealed = null;
        }
    }

    /**
     * Lets a refusal be tried again. A Keystore that failed once is not a
     * permanent answer - it fails while the device is still booting, while the
     * user is changing a screen lock, and on some devices for no lasting reason
     * at all - so nothing here is allowed to be final.
     *
     * <p>Must not run on the UI thread: it can reach the Keystore, and creating
     * a key there costs hundreds of milliseconds, StrongBox more.</p>
     */
    public static void retryBinding() {
        forgetRefusals();
        applyToRunningAccounts();
    }

    /**
     * Asks the Keystore once more and answers whether it now gives up the
     * secret. Deliberately does <em>not</em> touch the network library: doing
     * that would construct a {@link ConnectionsManager} for every account,
     * which is itself a config read, and the retry would become the thing that
     * causes the failure it is trying to clear.
     *
     * <p>A true answer does not repair this process. The library read the
     * config while no key was in effect and is holding an empty one; nothing
     * may be written over the sealed file from here, and the caller has to
     * restart rather than carry on. Must not run on the UI thread.</p>
     */
    public static boolean retryKeyAccess() {
        synchronized (LOCK) {
            forgetRefusals();
            blocked = null;
            return existingSecret() != null;
        }
    }

    private static void forgetRefusals() {
        synchronized (LOCK) {
            if (cachedSecret == null) {
                secretResolved = false;
            }
            secretUnavailable = false;
            nativeKeyFailed = false;
            configWasSealed = null;
        }
    }

    /**
     * Stops every further {@code tgnet.dat} write in this process. Called by the
     * wipe before it removes anything, so that the device key can be destroyed
     * without a {@code saveConfig()} landing in between and writing the real
     * authorization keys unsealed (N15). One way: both wipes end the process.
     */
    public static void forbidConfigWrites() {
        synchronized (LOCK) {
            // The Java half of the same barrier: no binding is created from
            // here on either. See the field.
            wiping = true;
        }
        try {
            ConnectionsManager.native_novaForbidConfigWrites();
        } catch (Throwable e) {
            // Only reachable when nothing native was ever started, which is
            // also the case where nothing over there can write.
            FileLog.e(e);
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
        // Switching it on is also the user's way of saying "try again" after a
        // refusal, so the refusal is dropped before the attempt.
        retryBinding();
    }

    /**
     * Gives up on data this device cannot read and turns the installation back
     * into a first start. Must not run on the UI thread.
     */
    public static void resetForNewDevice(Context context) {
        // Barrier first, in both halves. forget() drops the "belongs to another
        // device" verdict, and between that and the sweep nothing must be able
        // to decide it should build a fresh binding or write a config.
        forbidConfigWrites();
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
            secretUnavailable = false;
            nativeKeyFailed = false;
            nativeConfigUnusable = false;
            configWasSealed = null;
            blocked = null;
        }
        try {
            ConnectionsManager.native_novaSetDeviceKey(null, false);
            // The "belongs to another device" verdict was about files the
            // caller has just deleted or is about to. Left standing it would go
            // on refusing writes for a directory that no longer exists.
            ConnectionsManager.native_novaClearForeignConfig();
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
            secretUnavailable = false;
            byte[] secret = readSecret();
            if (secret == null && secretUnavailable) {
                // The Keystore refused, which is not the same as "there is
                // nothing stored". Not cached, for the same reason as above:
                // the next attempt has to ask again instead of taking one bad
                // moment as the answer for the rest of the process.
                return null;
            }
            secretResolved = true;
            cachedSecret = secret;
            return cachedSecret;
        }
    }

    /** The stored secret, creating and persisting one when there is none. */
    private static byte[] secretForSealing() {
        synchronized (LOCK) {
            if (wiping) {
                // A wipe is in progress. Nothing is created, nothing is sealed:
                // the files these would protect are being destroyed.
                return null;
            }
            byte[] existing = existingSecret();
            if (existing != null) {
                return existing;
            }
            if (secretUnavailable) {
                // The Keystore refused to answer. Nothing may be replaced on
                // the strength of that: the binding may well be perfectly good
                // and this the wrong minute to ask. Come back later.
                return null;
            }
            File file = bindingFile();
            if (file != null && file.exists()) {
                if (isAnyConfigSealed()) {
                    // The file is there and did not open, and something on disk
                    // is sealed. Replacing it would throw away the only thing
                    // that could still decrypt that data.
                    return null;
                }
                // The Keystore answered, and what it answered does not open this
                // binding. Nothing on disk is sealed either, so the binding
                // protects nothing and there is nothing to lose by replacing it.
                // Without this a binding file left unreadable - a Keystore entry
                // lost to a restore, a half-finished wipe - meant sealing never
                // came back on, and every later write went out in the clear.
                FileLog.e("NovaGram device lock: replacing an unreadable binding, nothing on disk is sealed");
                new AtomicFile(file).delete();
                if (file.exists()) {
                    return null;
                }
                deleteKeystoreKeyQuietly();
            }
            byte[] created = createSecret();
            if (created == null) {
                // Not cached. A failed creation is a refusal, not an answer.
                return null;
            }
            cachedSecret = created;
            secretResolved = true;
            secretUnavailable = false;
            return created;
        }
    }

    /**
     * The Keystore entry that wrapped a binding file being replaced. It opens
     * nothing any more; removing it is what lets {@code getOrCreate} make a new
     * one rather than hand back the entry that just failed.
     */
    private static void deleteKeystoreKeyQuietly() {
        try {
            NovaDeviceKeyStore.delete();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static byte[] readSecret() {
        File file = bindingFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            DataInputStream stream = new DataInputStream(input);
            // A file we cannot parse is not a file sealed elsewhere. It is a
            // version this build does not know - the same downgrade trap the
            // desktop half hit twice, G39 and G40 - or plain corruption. Both
            // are reported as "could not ask", because the alternative is
            // offering to wipe an installation whose data may be perfectly
            // good, and neither of them is evidence of another device.
            if (stream.readInt() != FILE_MAGIC || stream.readInt() != FILE_VERSION) {
                secretUnavailable = true;
                FileLog.e("NovaGram device lock: unreadable binding file");
                return null;
            }
            int ivLength = stream.readInt();
            if (ivLength != GCM_IV_BYTES) {
                secretUnavailable = true;
                return null;
            }
            byte[] iv = new byte[ivLength];
            stream.readFully(iv);
            int sealedLength = stream.readInt();
            if (sealedLength <= 16 || sealedLength > MAX_SEALED_BYTES) {
                secretUnavailable = true;
                return null;
            }
            byte[] sealed = new byte[sealedLength];
            stream.readFully(sealed);

            SecretKey key;
            try {
                key = NovaDeviceKeyStore.getExisting();
            } catch (GeneralSecurityException | RuntimeException e) {
                // The Keystore would not answer. That is not the same as "the
                // entry is gone" - it happens while the device is still booting
                // and while a screen lock is being changed - so it is reported
                // as a refusal, which nothing caches and everything retries.
                secretUnavailable = true;
                FileLog.e(e);
                return null;
            }
            if (key == null) {
                FileLog.e("NovaGram device lock: no Keystore key for the stored binding");
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] secret = cipher.doFinal(sealed);
            if (secret.length != SECRET_BYTES) {
                // Opened, and what came out is not a secret of ours. Nothing
                // here is going to make it one, so this is an answer.
                NovaSecretWiper.wipe(secret);
                return null;
            }
            return secret;
        } catch (AEADBadTagException e) {
            // The only failure that actually means "sealed somewhere else":
            // the file is well formed, the key on this device was applied to
            // it, and the tag did not authenticate. This is the one that may
            // reach the owner as a foreign directory.
            FileLog.e("NovaGram device lock: the sealed binding does not open with this device's key");
            return null;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // Everything else is the question failing, not being answered.
            // `cipher.init` and `doFinal` on a Keystore key are IPC to
            // keystore2, and it throws `ProviderException` wrapping a
            // `KeyStoreException` when it is busy - notably right after the
            // system unfreezes a cached process. Falling through here without
            // the flag latched a **certain** foreign verdict on a phone having
            // a bad minute, and the screen that verdict opens offers one
            // button, which wipes.
            secretUnavailable = true;
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
            // Reported, not swallowed. Binding was asked for and did not
            // happen; the caller must be able to say so and to come back later.
            secretUnavailable = true;
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
