package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;

/**
 * Destruction of every local trace after the emergency PIN.
 *
 * <p>Order matters and follows the roadmap: arm the decoy marker first, then
 * ask the server to log out while the network is still configured, then remove
 * the data. An interrupted run therefore never leaves a client that would ask
 * for a PIN again and hand the data back.</p>
 *
 * <p>Within the removal the order is: stop config writes, sweep, destroy the
 * keys, sweep again. Destroying the device key before the files meant that any
 * {@code saveConfig()} landing in between wrote the datacenter authorization
 * keys in the clear - a plaintext window in the middle of the one operation
 * that exists to leave no plaintext.</p>
 *
 * <p>The server side logout is best effort by nature, because it needs a
 * working network. The local destruction is not conditional on it.</p>
 *
 * <p><b>What deletion means here.</b> {@link File#delete()} is an unlink. On
 * flash storage with wear levelling, overwriting a file first would not erase
 * the physical pages either - the controller writes the new bytes elsewhere -
 * so it would buy nothing but time, and this runs while someone is standing
 * over the phone. What makes the wipe worth something is the key destruction:
 * the Keystore entries behind {@code tgnet.dat}, the PIN vault, the auto-delete
 * queue, the read-status rules and the muted-member list are removed, so every
 * copy of those files taken beforehand stays shut. The files that were never
 * sealed - {@code cache4.db} and the saved media (N16) - are only unlinked, and
 * that is the honest limit.</p>
 */
public final class NovaEmergencyWipe {
    private NovaEmergencyWipe() {
    }

    /**
     * Runs the whole sequence. Must not run on the UI thread: it performs disk
     * work over the entire application storage.
     */
    public static void run(Context context) {
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;

        // Read before anything is cleared. The decoy keeps showing this name
        // and number, because they are the part of the account whoever holds
        // the phone can already check elsewhere.
        String[] identity = captureIdentity();

        // Before anything is deleted: a network callback of the auto-delete
        // engine returning mid-wipe would otherwise seal the queue again, and
        // recreate both the file and its Keystore key behind the sweep.
        NovaAutoDelete.shutdown();
        NovaReadStatus.shutdown();
        NovaMutedMembers.shutdown();

        NovaDecoyState.arm(target, identity[0], identity[1], identity[2]);
        requestServerLogout();
        clearLocalAccounts();
        destroyPinState();
        // Before a single file goes and before the device key does. Everything
        // below can be running alongside the network thread, and a saveConfig()
        // landing between "the key is gone" and "the file is gone" wrote the
        // real datacenter authorization keys unsealed. N15 states the rule the
        // other way round - the key stays until what depends on it has been
        // rewritten - and here nothing is being rewritten at all, so the
        // honest form of it is: stop the writes, then destroy both.
        NovaDeviceLock.forbidConfigWrites();
        deleteStorage(target);
        destroyDeviceKey();
        // A second pass. The first one raced whatever was still running; this
        // one runs after the writes have been stopped and the keys destroyed,
        // so anything it finds was written unsealed and has to go.
        deleteStorage(target);
        // Re-armed last, because the sweep above removes the marker together
        // with the PIN state: the decoy must not inherit a PIN prompt, and the
        // two live in the same protected directory.
        NovaDecoyState.arm(target, identity[0], identity[1], identity[2]);
    }

    /**
     * The same sweep without the disguise: the owner told the device lock to
     * give up on data it cannot read.
     *
     * <p>No decoy is armed - nothing was destroyed to hide - and no logout is
     * requested, because there is no authorization key here to sign one with.
     * The session on the servers stays alive; it is ended from the device that
     * owns it, or from the account's active-sessions list.</p>
     *
     * <p>Must not run on the UI thread.</p>
     */
    public static void runForNewDevice(Context context) {
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;

        NovaAutoDelete.shutdown();
        NovaReadStatus.shutdown();
        NovaMutedMembers.shutdown();

        destroyPinState();
        NovaDeviceLock.forbidConfigWrites();
        deleteStorage(target);
        destroyDeviceKey();
        deleteStorage(target);
    }

    private static void destroyDeviceKey() {
        // Runs after the files are gone and after config writes have been
        // stopped, never before either. The secret goes out of the network
        // library first so that nothing over there holds it, and only then is
        // the Keystore entry destroyed - that destruction is the part that
        // makes this wipe worth anything, because it is what turns every copy
        // of the sealed files taken beforehand into bytes with no key.
        NovaDeviceLock.forget();
        try {
            NovaDeviceKeyStore.delete();
        } catch (Throwable ignored) {
        }
    }

    private static String[] captureIdentity() {
        String[] identity = { "", "", "" };
        try {
            TLRPC.User user = findSelfUser();
            if (user != null) {
                identity[0] = user.first_name != null ? user.first_name : "";
                identity[1] = user.last_name != null ? user.last_name : "";
                identity[2] = user.phone != null ? user.phone : "";
            }
        } catch (Throwable ignored) {
        }
        return identity;
    }

    /**
     * Finds the destroyed account's own user record for the decoy.
     *
     * <p>The emergency PIN is entered on the cold-start gate, which runs before
     * the Telegram client loads {@link UserConfig} into memory. On that path
     * {@code getCurrentUser()} is null, so relying on it captured nothing and
     * the decoy showed a blank name and number. The persisted account blob is
     * on disk the whole time, so it is read and deserialized directly here,
     * mirroring how {@code NovaPinSession} already reads the same preference to
     * decide the gate is needed. The in-memory config is still tried first for
     * the warm path of a wipe triggered from an unlocked session.</p>
     */
    private static TLRPC.User findSelfUser() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
                if (user != null) {
                    return user;
                }
            } catch (Throwable ignored) {
            }
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                String preferencesName = account == 0 ? "userconfing" : "userconfig" + account;
                SharedPreferences preferences =
                        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
                String encoded = preferences.getString("user", null);
                if (TextUtils.isEmpty(encoded)) {
                    continue;
                }
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                SerializedData data = new SerializedData(bytes);
                TLRPC.User user = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
                data.cleanup();
                if (user != null) {
                    return user;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void destroyPinState() {
        // Without the Keystore key the protected state is undecryptable even
        // if a copy of the file was taken before the wipe.
        try {
            NovaPinKeyStore.delete();
        } catch (Throwable ignored) {
        }
        // The auto-delete queue is sealed by its own key, and it holds dialog
        // and message identifiers: a copy of that file taken beforehand must
        // stay unreadable too.
        try {
            NovaAutoDeleteKeyStore.delete();
        } catch (Throwable ignored) {
        }
        // Same for the read status rules: they name the people whose messages
        // were read quietly.
        try {
            NovaReadStatusStore.deleteKey();
        } catch (Throwable ignored) {
        }
        // And for the muted members: that file names the people the user did
        // not want to hear from, which is the same kind of answer.
        try {
            NovaMutedMembersStore.deleteKey();
        } catch (Throwable ignored) {
        }
    }

    private static void requestServerLogout() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (!UserConfig.getInstance(account).isClientActivated()) {
                    continue;
                }
                // Type 1 sends auth.logOut. The promise is explicit that every
                // account goes, not only the one currently selected.
                MessagesController.getInstance(account).performLogout(1);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clearLocalAccounts() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                UserConfig config = UserConfig.getInstance(account);
                config.clearConfig();
                config.saveConfig(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void deleteStorage(Context context) {
        deleteChildren(context.getFilesDir());
        deleteChildren(context.getCacheDir());
        deleteChildren(context.getNoBackupFilesDir());
        deleteChildren(ApplicationLoader.getFilesDirFixed());
        // Covers the debug log directory too: it is getExternalFilesDir(null)/logs,
        // and these are walked recursively.
        File[] external = context.getExternalCacheDirs();
        if (external != null) {
            for (File dir : external) {
                deleteChildren(dir);
            }
        }
        File[] externalFiles = context.getExternalFilesDirs(null);
        if (externalFiles != null) {
            for (File dir : externalFiles) {
                deleteChildren(dir);
            }
        }
        File parent = context.getFilesDir() != null
                ? context.getFilesDir().getParentFile()
                : null;
        if (parent != null) {
            deleteChildren(new File(parent, "shared_prefs"));
            deleteChildren(new File(parent, "databases"));
        }
        deleteSavedMedia(context);
    }

    /**
     * Everything the app saved outside its own directories: the "Telegram
     * Images", "Telegram Video", "Telegram Documents", "Telegram Audio",
     * "Telegram Files" and "Telegram Stories" folders.
     *
     * <p>Where those sit is not a constant. Before scoped storage they are
     * under {@code /sdcard/Telegram}; from API 30 under the app's own external
     * files directory and, for the two the gallery shows, under the app's
     * external media directory; and either of those moves to an SD card when
     * one is chosen for storage. So the answer is asked of the app itself -
     * {@link FileLoader#checkDirectory} returns exactly the directories
     * {@code ImageLoader} resolved - and the well-known locations are swept as
     * well, because the wipe can run from the cold-start gate, before
     * {@code ImageLoader} has ever built that map.</p>
     *
     * <p>What this cannot reach, and the fork must not claim it does: anything
     * copied into the system gallery through MediaStore ({@code Pictures/Telegram}
     * and the like). Those rows belong to MediaStore, and deleting them on
     * Android 11 and later needs a consent dialog - which is precisely what a
     * wipe entered under duress cannot stop to show.</p>
     */
    private static void deleteSavedMedia(Context context) {
        int[] mediaTypes = {
                FileLoader.MEDIA_DIR_IMAGE,
                FileLoader.MEDIA_DIR_AUDIO,
                FileLoader.MEDIA_DIR_VIDEO,
                FileLoader.MEDIA_DIR_DOCUMENT,
                FileLoader.MEDIA_DIR_CACHE,
                FileLoader.MEDIA_DIR_FILES,
                FileLoader.MEDIA_DIR_STORIES,
                FileLoader.MEDIA_DIR_IMAGE_PUBLIC,
                FileLoader.MEDIA_DIR_VIDEO_PUBLIC,
        };
        for (int type : mediaTypes) {
            File dir;
            try {
                dir = FileLoader.checkDirectory(type);
            } catch (Throwable ignored) {
                continue;
            }
            if (dir == null) {
                continue;
            }
            deleteChildren(dir);
            // One level up is the "Telegram" container these all live in. Its
            // other folders - the ones this installation never happened to
            // register, because the writability probe failed for them - hold
            // the same kind of file and are swept with it. Only ever one level,
            // and only when the name matches, so nothing can climb out into the
            // rest of the storage volume.
            File container = dir.getParentFile();
            if (container != null && "Telegram".equals(container.getName())) {
                deleteChildren(container);
            }
        }
        // The pre-scoped-storage location, asked directly. The map above is
        // empty when ImageLoader has not run, and on Android 9 - the version
        // this matters most on - this is where the saved media is.
        try {
            deleteChildren(new File(Environment.getExternalStorageDirectory(), "Telegram"));
        } catch (Throwable ignored) {
        }
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs != null) {
                for (File dir : mediaDirs) {
                    if (dir != null) {
                        deleteChildren(new File(dir, "Telegram"));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteChildren(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }
}
