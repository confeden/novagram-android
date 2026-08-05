package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

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

        NovaDecoyState.arm(target);
        requestServerLogout();
        clearLocalAccounts();
        destroyPinState();
        deleteStorage(target);
        // Re-armed last, because the sweep above removes the marker together
        // with the PIN state: the decoy must not inherit a PIN prompt, and the
        // two live in the same protected directory.
        NovaDecoyState.arm(target);
    }

    private static void destroyPinState() {
        // Without the Keystore key the protected state is undecryptable even
        // if a copy of the file was taken before the wipe.
        try {
            NovaPinKeyStore.delete();
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
