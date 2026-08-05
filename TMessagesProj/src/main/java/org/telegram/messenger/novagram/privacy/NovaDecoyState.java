package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Marker of the permanent decoy mode entered after the emergency PIN.
 *
 * <p>The marker lives in the no-backup directory, so it never reaches a cloud
 * or device-to-device backup and cannot be restored from one. It is written
 * before anything is destroyed: if the process dies mid-wipe, the next start
 * must still come up as the decoy rather than as a half-erased client that
 * asks for a PIN.</p>
 *
 * <p>Honest limit, spelled out in the roadmap as well: a normal APK cannot
 * survive the system <b>Clear storage</b> action, which removes every file the
 * package owns, this marker included. Enforcing the mode across that requires
 * Device Owner, MDM or a custom system image.</p>
 */
public final class NovaDecoyState {
    private static final String DIRECTORY = "novagram/security";
    private static final String FILE_NAME = "decoy.marker";

    private static volatile Boolean cached;

    private NovaDecoyState() {
    }

    private static File markerFile(Context context) {
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        return new File(new File(target.getNoBackupFilesDir(), DIRECTORY), FILE_NAME);
    }

    public static boolean isActive(Context context) {
        Boolean known = cached;
        if (known != null) {
            return known;
        }
        boolean active = markerFile(context).isFile();
        cached = active;
        return active;
    }

    /**
     * Turns the decoy mode on. Called before the destruction starts, never
     * after it, so an interrupted wipe still lands in the decoy.
     */
    public static boolean arm(Context context) {
        File file = markerFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        try {
            if (!file.isFile() && !file.createNewFile()) {
                return false;
            }
            cached = Boolean.TRUE;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
