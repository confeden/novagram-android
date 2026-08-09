package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;

/**
 * Marker of the permanent decoy mode entered after the emergency PIN.
 *
 * <p>The marker lives in the no-backup directory, so it never reaches a cloud
 * or device-to-device backup and cannot be restored from one. It is written
 * before anything is destroyed: if the process dies mid-wipe, the next start
 * must still come up as the decoy rather than as a half-erased client that
 * asks for a PIN.</p>
 *
 * <p>Besides the on/off fact the marker carries the little state the decoy
 * needs to stay the same on every launch: a random seed and a time anchor for
 * the generated conversations, and the real first name, last name and phone
 * number of the destroyed account. Those three are kept on purpose. Whoever
 * holds the phone can usually read the number off the SIM or another
 * application, so a decoy showing a different one is the fastest way to be
 * caught. Nothing about the conversations is kept: they are regenerated from
 * the seed.</p>
 *
 * <p>Honest limit, spelled out in the roadmap as well: a normal APK cannot
 * survive the system <b>Clear storage</b> action, which removes every file the
 * package owns, this marker included. Enforcing the mode across that requires
 * Device Owner, MDM or a custom system image.</p>
 */
public final class NovaDecoyState {
    private static final String DIRECTORY = "novagram/security";
    private static final String FILE_NAME = "decoy.marker";
    private static final String HEADER = "novagram-decoy-1";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_MARKER_SIZE = 4096;

    private static final Object LOCK = new Object();

    private static volatile Boolean cached;
    private static boolean profileLoaded;
    private static long seed;
    private static int anchor;
    private static String firstName = "";
    private static String lastName = "";
    private static String phone = "";

    private NovaDecoyState() {
    }

    private static Context resolve(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    private static File markerFile(Context context) {
        return new File(new File(resolve(context).getNoBackupFilesDir(), DIRECTORY), FILE_NAME);
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
     * Context free variant for the deep parts of the client that have no
     * activity at hand. Safe before the application context exists: it can only
     * answer from the cache then, and the cache is primed in
     * {@code ApplicationLoader.onCreate} before anything else runs.
     */
    public static boolean isActive() {
        Boolean known = cached;
        if (known != null) {
            return known;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && isActive(context);
    }

    /**
     * Turns the decoy mode on. Called before the destruction starts, never
     * after it, so an interrupted wipe still lands in the decoy.
     *
     * <p>The identity arguments come from the account that is about to be
     * destroyed and may be empty, in which case the decoy invents its own.</p>
     */
    public static boolean arm(Context context, String selfFirstName, String selfLastName, String selfPhone) {
        Context target = resolve(context);
        File file = markerFile(target);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        synchronized (LOCK) {
            if (!profileLoaded || seed == 0) {
                seed = newSeed();
                anchor = (int) (System.currentTimeMillis() / 1000L);
            }
            if (!TextUtils.isEmpty(selfFirstName)) {
                firstName = selfFirstName;
            }
            if (selfLastName != null) {
                lastName = selfLastName;
            }
            if (!TextUtils.isEmpty(selfPhone)) {
                phone = selfPhone;
            }
            profileLoaded = true;
            if (!write(file)) {
                return false;
            }
        }
        cached = Boolean.TRUE;
        return true;
    }

    /** Re-arms without touching the identity already captured. */
    public static boolean arm(Context context) {
        return arm(context, null, null, null);
    }

    public static long seed() {
        loadProfile();
        return seed;
    }

    /**
     * Moment the decoy was armed, in epoch seconds. Every generated
     * conversation is dated relative to it, so the fake history is as old as
     * the wipe and never moves under an examiner who looks twice.
     */
    public static int anchor() {
        loadProfile();
        return anchor;
    }

    public static String selfFirstName() {
        loadProfile();
        return firstName;
    }

    public static String selfLastName() {
        loadProfile();
        return lastName;
    }

    public static String selfPhone() {
        loadProfile();
        return phone;
    }

    private static void loadProfile() {
        if (profileLoaded) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        synchronized (LOCK) {
            if (profileLoaded) {
                return;
            }
            File file = context != null ? markerFile(context) : null;
            boolean parsed = file != null && read(file);
            if (!parsed || seed == 0) {
                // Either an older build armed the marker as an empty file, or
                // it was damaged. Generating now and writing back keeps the
                // decoy identical on every later launch, which is the whole
                // point of storing the seed at all.
                seed = newSeed();
                anchor = (int) (System.currentTimeMillis() / 1000L);
                if (file != null) {
                    write(file);
                }
            }
            profileLoaded = true;
        }
    }

    private static long newSeed() {
        long value = new SecureRandom().nextLong();
        return value != 0 ? value : 1L;
    }

    private static boolean write(File file) {
        StringBuilder builder = new StringBuilder(HEADER).append('\n');
        builder.append("seed=").append(Long.toString(seed)).append('\n');
        builder.append("anchor=").append(anchor).append('\n');
        builder.append("first=").append(encode(firstName)).append('\n');
        builder.append("last=").append(encode(lastName)).append('\n');
        builder.append("phone=").append(encode(phone)).append('\n');
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(file);
            stream.write(builder.toString().getBytes(UTF8));
            stream.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            close(stream);
        }
    }

    private static boolean read(File file) {
        if (!file.isFile()) {
            return false;
        }
        String content = readText(file);
        if (content == null || !content.startsWith(HEADER)) {
            return false;
        }
        long parsedSeed = 0;
        int parsedAnchor = 0;
        String parsedFirst = "";
        String parsedLast = "";
        String parsedPhone = "";
        for (String line : content.split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            try {
                if ("seed".equals(key)) {
                    parsedSeed = Long.parseLong(value);
                } else if ("anchor".equals(key)) {
                    parsedAnchor = Integer.parseInt(value);
                } else if ("first".equals(key)) {
                    parsedFirst = decode(value);
                } else if ("last".equals(key)) {
                    parsedLast = decode(value);
                } else if ("phone".equals(key)) {
                    parsedPhone = decode(value);
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (parsedSeed == 0 || parsedAnchor == 0) {
            return false;
        }
        seed = parsedSeed;
        anchor = parsedAnchor;
        firstName = parsedFirst;
        lastName = parsedLast;
        phone = parsedPhone;
        return true;
    }

    private static String readText(File file) {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = stream.read(buffer)) > 0 && out.size() < MAX_MARKER_SIZE) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF8);
        } catch (IOException e) {
            return null;
        } finally {
            close(stream);
        }
    }

    private static String encode(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return Base64.encodeToString(value.getBytes(UTF8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        try {
            return new String(Base64.decode(value, Base64.NO_WRAP), UTF8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static void close(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static void close(FileOutputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
