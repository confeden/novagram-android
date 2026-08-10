package org.telegram.messenger.novagram.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.novagram.privacy.NovaDecoyState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONObject;

/**
 * Checks a small static manifest for a newer NovaGram and installs it.
 *
 * <p>This is the only network request NovaGram makes outside Telegram, so it
 * is a setting rather than a fact of life, and it never runs while the decoy
 * is on: the decoy promises the process opens no sockets, and a request to
 * GitHub would break that promise in the most traceable way there is.</p>
 *
 * <p>One NovaGram release covers both platforms, and its tag carries both base
 * versions: {@code v<desktop>/<android>}. Each client compares only its own
 * half, which is what makes an Android-only release invisible to the desktop
 * and the other way round: if the number for this platform did not move, there
 * is nothing to install here even though the release is new.</p>
 *
 * <p>The downloaded package is checked against the digest from the manifest
 * before it is offered for installation. Installing over the existing package
 * keeps application data: Android replaces the code and leaves the data
 * directory alone as long as the signing key is the same one.</p>
 */
public final class NovaUpdateChecker {
    public static final String PROJECT_URL = "https://github.com/confeden/Novagram";

    /**
     * Both halves of the joint release tag. Only the Android half can be read
     * from the package, so the whole tag has to be written down here and bumped
     * together with the bases.
     */
    public static final String RELEASE_TAG = "v7.0.9/12.9.2";

    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/confeden/nova_updates/main/Novagram_android.json";
    private static final String DOWNLOAD_PREFIX = PROJECT_URL + "/releases/download/";

    private static final String PREFERENCES = "novagram_update";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_CHECK = "lastCheck";

    private static final long PERIOD_MS = 8L * 60L * 60L * 1000L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_PACKAGE_BYTES = 512 * 1024 * 1024;
    private static final int TIMEOUT_MS = 20 * 1000;

    public enum State {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        FOUND,
        DOWNLOADING,
        READY,
        FAILED,
    }

    /** One entry of the manifest published next to the releases. */
    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final String releaseUrl;

        Release(String version, String url, String sha256, String releaseUrl) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.releaseUrl = releaseUrl;
        }
    }

    public interface Listener {
        void onNovaUpdateStateChanged();
    }

    private static final Object LOCK = new Object();
    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private static volatile State state = State.IDLE;
    private static volatile Release release;
    private static volatile int progress;
    private static volatile File downloaded;
    private static volatile boolean busy;

    private NovaUpdateChecker() {
    }

    /**
     * The Telegram Android this build is based on, taken from the package
     * itself rather than from a constant: a constant would be one more thing
     * that can silently disagree with what was actually shipped.
     */
    public static String currentVersion() {
        try {
            Context context = ApplicationLoader.applicationContext;
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    /** Page of the release this build came from, with the list of changes. */
    public static String releaseUrl() {
        // The separator is escaped: a tag with a slash in it is a valid tag,
        // but a slash left raw in the path would be read as another segment.
        return PROJECT_URL + "/releases/tag/" + RELEASE_TAG.replace("/", "%2F");
    }

    // region state

    public static State getState() {
        return state;
    }

    public static Release getRelease() {
        return release;
    }

    public static int getProgress() {
        return progress;
    }

    public static void addListener(Listener listener) {
        synchronized (LOCK) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (LOCK) {
            listeners.remove(listener);
        }
    }

    private static void setState(State value) {
        state = value;
        ArrayList<Listener> copy;
        synchronized (LOCK) {
            copy = new ArrayList<>(listeners);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener listener : copy) {
                listener.onNovaUpdateStateChanged();
            }
        });
    }

    // endregion

    public static boolean isEnabled(Context context) {
        if (context == null || NovaDecoyState.isActive(context)) {
            return false;
        }
        return preferences(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            checkNow();
        }
    }

    /**
     * Called once from {@code ApplicationLoader.onCreate}. Checks only when the
     * period has really elapsed, so restarting the application ten times in a
     * row does not produce ten requests.
     */
    public static void start() {
        Context context = ApplicationLoader.applicationContext;
        if (!isEnabled(context)) {
            return;
        }
        long last = preferences(context).getLong(KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        // A clock moved backwards would otherwise postpone the check for as
        // long as the user set the clock back by.
        if (last > now || now - last >= PERIOD_MS) {
            checkNow();
        }
    }

    public static void checkNow() {
        Context context = ApplicationLoader.applicationContext;
        if (!isEnabled(context) || busy) {
            return;
        }
        busy = true;
        setState(State.CHECKING);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                byte[] body = read(MANIFEST_URL, MAX_MANIFEST_BYTES, null);
                applyManifest(context, new String(body, "UTF-8"));
            } catch (Throwable e) {
                FileLog.e(e);
                setState(State.FAILED);
            } finally {
                busy = false;
            }
        });
    }

    private static void applyManifest(Context context, String body) throws Exception {
        JSONObject object = new JSONObject(body);
        String version = object.optString("version", "");
        String url = object.optString("url", "");
        String sha256 = object.optString("sha256", "").toLowerCase();
        String releaseUrl = object.optString("release_url", "");
        if (version.length() == 0) {
            setState(State.FAILED);
            return;
        }
        // The manifest may only ever point back into the project it belongs to.
        // It is a file in a repository, and a repository can be edited by more
        // people than the one who signs the releases.
        if (url.length() > 0 && !url.startsWith(DOWNLOAD_PREFIX)) {
            setState(State.FAILED);
            return;
        }
        if (releaseUrl.length() > 0 && !releaseUrl.startsWith(PROJECT_URL)) {
            setState(State.FAILED);
            return;
        }

        preferences(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply();

        String current = currentVersion();
        if (current.length() == 0 || compareVersions(version, current) <= 0) {
            release = null;
            setState(State.UP_TO_DATE);
        } else {
            release = new Release(version, url, sha256, releaseUrl);
            setState(State.FOUND);
        }
    }

    public static void download() {
        Release target = release;
        if (busy || target == null || target.url.length() == 0) {
            return;
        }
        if (target.sha256.length() != 64) {
            // A package that cannot be checked is not installed. Publishing a
            // release without the digest is a mistake worth failing on.
            setState(State.FAILED);
            return;
        }
        busy = true;
        progress = 0;
        setState(State.DOWNLOADING);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                byte[] body = read(target.url, MAX_PACKAGE_BYTES, value -> {
                    progress = value;
                    setState(State.DOWNLOADING);
                });
                if (!hexDigest(body).equals(target.sha256)) {
                    setState(State.FAILED);
                    return;
                }
                File file = store(body, target.version);
                if (file == null) {
                    setState(State.FAILED);
                    return;
                }
                downloaded = file;
                setState(State.READY);
            } catch (Throwable e) {
                FileLog.e(e);
                setState(State.FAILED);
            } finally {
                busy = false;
            }
        });
    }

    /**
     * Hands the package to the system installer. Nothing is installed behind
     * the user's back: this opens the ordinary Android install screen, and
     * {@code openForView} already asks for the install permission when the
     * system has not granted it yet.
     */
    public static void install(Activity activity) {
        File file = downloaded;
        if (activity == null || file == null || !file.exists()) {
            return;
        }
        AndroidUtilities.openForView(
                file,
                file.getName(),
                "application/vnd.android.package-archive",
                activity,
                null,
                false);
    }

    // region plumbing

    private interface Progress {
        void report(int percent);
    }

    private static byte[] read(String url, int limit, Progress progress) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (!(connection instanceof HttpsURLConnection)) {
                // Plain HTTP would let anyone on the path choose what NovaGram
                // installs. The manifest is checked the same way.
                throw new IllegalArgumentException("only https is accepted");
            }
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NovaGram");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("http " + connection.getResponseCode());
            }
            int total = connection.getContentLength();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            InputStream stream = connection.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            int done = 0;
            int reported = -1;
            while ((read = stream.read(buffer)) > 0) {
                done += read;
                if (done > limit) {
                    throw new IllegalStateException("answer is too large");
                }
                out.write(buffer, 0, read);
                if (progress != null && total > 0) {
                    int percent = (int) ((done * 100L) / total);
                    if (percent != reported) {
                        reported = percent;
                        progress.report(percent);
                    }
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static File store(byte[] body, String version) {
        // Under files/cache, which is the subtree the FileProvider of this
        // application already exposes; anywhere else the install intent would
        // be handed a URI the system refuses to open.
        File folder = new File(
                new File(ApplicationLoader.applicationContext.getFilesDir(), "cache"),
                "novagram_update");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return null;
        }
        for (File old : folder.listFiles() != null ? folder.listFiles() : new File[0]) {
            old.delete();
        }
        File file = new File(folder, "NovaGram-" + version + ".apk");
        OutputStream stream = null;
        try {
            stream = new FileOutputStream(file);
            stream.write(body);
            stream.flush();
            return file;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String hexDigest(byte[] body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(body);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(Character.forDigit((value >> 4) & 0xf, 16));
            builder.append(Character.forDigit(value & 0xf, 16));
        }
        return builder.toString();
    }

    /**
     * Negative when {@code a} is older than {@code b}, zero when they are the
     * same release, positive when {@code a} is newer. Missing components count
     * as zero, so "1.1" and "1.1.0" are one version.
     */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int one = part(left, i);
            int two = part(right, i);
            if (one != two) {
                return one < two ? -1 : 1;
            }
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    // endregion
}
