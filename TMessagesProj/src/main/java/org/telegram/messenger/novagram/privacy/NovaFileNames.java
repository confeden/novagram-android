package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Names of files the application writes to disk by itself.
 *
 * <p>Almost everything Telegram caches is already opaque: the name is built
 * from the data centre and the document identifier, and it says nothing about
 * what the file is. One branch is different — a document that arrives with a
 * file name of its own is stored under that name in the Telegram Documents
 * folder, so a directory listing becomes a readable list of what was sent to
 * this phone.</p>
 *
 * <p>Only that branch is masked here. Saving a file on purpose, through the
 * chat menu, keeps its real name: that is the user asking for a file they mean
 * to find later, and answering with a token would be a different feature, and a
 * worse one. The desktop half of the fork draws the same line.</p>
 */
public final class NovaFileNames {

    private static final String PREFERENCES = "novagram_filenames";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SALT = "salt";

    private static final int SALT_BYTES = 32;

    /**
     * Eight bytes, sixteen characters. Long enough that two names cannot
     * realistically collide — and a collision would be data loss, because the
     * loader treats a file of the wrong size under the final name as a stale
     * one and removes it — short enough to still read as a file name.
     */
    private static final int TOKEN_BYTES = 8;

    private static volatile byte[] salt;

    private NovaFileNames() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        return preferences(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Made once and kept. A salt drawn again on every start would give the same
     * file a new name every time it was fetched, and the folder would fill up
     * with copies of one document.
     */
    private static byte[] salt(Context context) {
        byte[] cached = salt;
        if (cached != null) {
            return cached;
        }
        synchronized (NovaFileNames.class) {
            if (salt != null) {
                return salt;
            }
            SharedPreferences preferences = preferences(context);
            String stored = preferences.getString(KEY_SALT, null);
            byte[] value = null;
            if (stored != null) {
                try {
                    value = Base64.decode(stored, Base64.NO_WRAP);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (value == null || value.length != SALT_BYTES) {
                value = new byte[SALT_BYTES];
                new SecureRandom().nextBytes(value);
                preferences.edit()
                        .putString(KEY_SALT, Base64.encodeToString(value, Base64.NO_WRAP))
                        .apply();
            }
            salt = value;
            return value;
        }
    }

    /**
     * Returns {@code name} unchanged while the setting is off, and otherwise
     * keeps the extension — it decides which application opens the file, and a
     * file that cannot be opened has not been saved — and replaces the rest
     * with a token derived from the name and the salt. Deriving rather than
     * drawing is what makes the answer stable across restarts, which the
     * loader needs: it looks for a partly fetched file under its final name.
     */
    public static String mask(String name) {
        Context context = ApplicationLoader.applicationContext;
        if (name == null || name.length() == 0 || context == null || !isEnabled()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        if (base.length() == 0) {
            // Nothing but an extension: no sender's name here to hide.
            return name;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt(context));
            digest.update(name.getBytes("UTF-8"));
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(TOKEN_BYTES * 2 + extension.length());
            for (int i = 0; i < TOKEN_BYTES; i++) {
                builder.append(Character.forDigit((hash[i] >> 4) & 0xf, 16));
                builder.append(Character.forDigit(hash[i] & 0xf, 16));
            }
            builder.append(extension);
            return builder.toString();
        } catch (Throwable e) {
            // A masked name is a privacy promise, so failing to produce one
            // must not quietly fall back to the sender's name. It must not fall
            // back to one shared name either: the loader removes a file of the
            // wrong size found under the final name, so two documents sharing a
            // name would delete each other.
            FileLog.e(e);
            return Long.toHexString(name.hashCode() & 0xffffffffL)
                    + "_" + name.length() + extension;
        }
    }
}
