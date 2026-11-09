package org.telegram.messenger.novagram.net;

import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebResourceResponse;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Telegram's mini-app SDK when telegram.org cannot be reached.
 *
 * <p>Almost every mini app starts with
 * {@code <script src="https://telegram.org/js/telegram-web-app.js">} in its head.
 * On a line that drops Telegram's addresses (149.154.160.0/20, 2001:67c:4e8::/48)
 * that synchronous script never arrives: the page stays blank until the TCP
 * connect gives up, and then {@code Telegram.WebApp} is missing, so the app is
 * dead even when its own host is reachable. The messenger itself survives the
 * same line through the WSS ladder, the mini apps did not.</p>
 *
 * <p>So the script is fetched live first, as upstream would; a live copy is kept
 * on disk. When telegram.org does not answer, the request is served from that
 * copy, or from the one bundled in the APK ({@code assets/telegram-web-app.js},
 * refreshed at release time), and telegram.org is not tried again for ten
 * minutes so the next launch does not wait on the same timeout.</p>
 */
public final class NovaWebAppSdk {
    private static final String HOST = "telegram.org";
    private static final String PATH = "/js/telegram-web-app.js";
    private static final String LIVE_URL = "https://telegram.org/js/telegram-web-app.js";
    private static final String ASSET = "telegram-web-app.js";
    private static final String CACHE = "nova_telegram-web-app.js";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 6000;
    private static final long BLOCKED_FOR_MS = 10 * 60 * 1000L;
    private static final int MAX_BYTES = 1 << 20;

    private static final Object LOCK = new Object();
    private static long blockedUntil;

    private NovaWebAppSdk() {
    }

    public static boolean matches(Uri url) {
        if (url == null || !"https".equalsIgnoreCase(url.getScheme())) {
            return false;
        }
        String host = url.getHost();
        return host != null
            && (HOST.equalsIgnoreCase(host) || ("www." + HOST).equalsIgnoreCase(host))
            && PATH.equals(url.getPath());
    }

    public static boolean matches(String url) {
        try {
            return url != null && matches(Uri.parse(url));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called off the UI thread (WebView's IO thread), so blocking here is allowed. */
    public static WebResourceResponse serve(Uri url) {
        boolean tryLive;
        synchronized (LOCK) {
            tryLive = SystemClock.elapsedRealtime() >= blockedUntil;
        }
        if (tryLive) {
            byte[] live = fetchLive(url);
            if (live != null) {
                store(live);
                return respond(live);
            }
            synchronized (LOCK) {
                blockedUntil = SystemClock.elapsedRealtime() + BLOCKED_FOR_MS;
            }
        }
        byte[] local = cached();
        if (local == null) {
            local = bundled();
        }
        return local == null ? null : respond(local);
    }

    private static byte[] fetchLive(Uri url) {
        HttpURLConnection connection = null;
        try {
            String query = url.getEncodedQuery();
            URL target = new URL(LIVE_URL + (query == null ? "" : "?" + query));
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            byte[] body = readAll(connection.getInputStream());
            return looksLikeSdk(body) ? body : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean looksLikeSdk(byte[] body) {
        if (body == null || body.length < 10_000) {
            return false;
        }
        return new String(body, StandardCharsets.UTF_8).contains("TelegramWebviewProxy");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    return null;
                }
            }
            return out.toByteArray();
        }
    }

    private static File cacheFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), CACHE);
    }

    private static void store(byte[] body) {
        File target = cacheFile();
        // Unique per writer: two webviews may finish a live fetch at once.
        File temp = new File(target.getPath() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(body);
        } catch (Exception e) {
            FileLog.e(e);
            temp.delete();
            return;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
        }
    }

    private static byte[] cached() {
        File file = cacheFile();
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] body = readAll(new java.io.FileInputStream(file));
            return looksLikeSdk(body) ? body : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] bundled() {
        try {
            return readAll(ApplicationLoader.applicationContext.getAssets().open(ASSET));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static WebResourceResponse respond(byte[] body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(
            "application/javascript", "utf-8", 200, "OK", headers, new ByteArrayInputStream(body));
    }
}
