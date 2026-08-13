package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
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
 * <p>The server side logout is best effort by nature, because it needs a
 * working network. The local destruction is not conditional on it.</p>
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
        deleteStorage(target);
        // Re-armed last, because the sweep above removes the marker together
        // with the PIN state: the decoy must not inherit a PIN prompt, and the
        // two live in the same protected directory.
        NovaDecoyState.arm(target, identity[0], identity[1], identity[2]);
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
