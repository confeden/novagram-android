package org.telegram.messenger.novagram.net;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.novagram.privacy.NovaDecoyState;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;

/**
 * MTProto carried inside a WebSocket over TLS, for networks where the plain TCP
 * to Telegram's data centres is dropped but an ordinary HTTPS site is not.
 *
 * <p>This class is the policy half: the setting, the route ladder per data
 * centre and the published relay list. {@link NovaWssBridge} is the transport
 * half. The ladder, the 10-minute memory of a failed official relay and the
 * list format are the same as the desktop's {@code novagram/nova_wss.cpp}.</p>
 *
 * <p><b>How tgnet is reached.</b> tgnet is native and speaks plain TCP. When the
 * setting is on and the user has no proxy of their own, the native socket is
 * told (one process-wide port, {@link ConnectionsManager#native_setWssBridge})
 * to dial a loopback listener instead of the data centre, and to write the data
 * centre into the obfuscation header. The listener does the WebSocket. A proxy
 * of the user's own is checked natively before this redirect, so it always
 * wins; the decoy never starts any of it.</p>
 */
public final class NovaWss {
    private static final String PREFERENCES = "novagram_wss";
    private static final String KEY_ENABLED = "enabled";
    private static final String FOLDER = "novagram_wss";
    private static final String LIST_FILE = "relay.json";
    private static final String META_FILE = "relay.meta";

    private static final String LIST_HOST = "raw.githubusercontent.com";
    private static final String LIST_PATH = "/confeden/nova_updates/refs/heads/main/NovaGram/relay.json";
    private static final long PERIOD_MS = 24L * 60L * 60L * 1000L;
    private static final long FIRST_DELAY_MS = 45L * 1000L;
    private static final int TIMEOUT_MS = 30 * 1000;
    private static final int MAX_BODY = 128 * 1024;
    private static final int MAX_ENTRIES = 96;
    private static final long RELAY_BLOCKED_FOR_MS = 10L * 60L * 1000L;

    private static final Pattern GOOD_HOST = Pattern.compile("^[a-z0-9{}][a-z0-9.{}-]{0,120}$");
    private static final Pattern GOOD_PATH = Pattern.compile("^/[A-Za-z0-9/_.~?=&%{}-]{0,120}$");
    private static final Pattern GOOD_IP = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    /** One way in. {@code host} and {@code path} may carry {dc} and {media}. */
    private static final class Entry {
        String host;
        String path = "/apiws";
        final String[] ips = new String[6]; // by data centre 1..5; empty: resolved over DoH
    }

    private static final class RouteSet {
        int version;
        Entry official;
        final List<Entry> own = new ArrayList<>();
        final List<Entry> fronts = new ArrayList<>();
    }

    /** What the bridge dials. */
    public static final class Route {
        public final String host;
        public final String ip;
        public final String path;
        public final boolean front;
        /** Place in the ladder: 0 is the official relay. */
        public final int index;
        /** Our own Worker: the token offered as a subprotocol; null on every other route. */
        public final String token;

        Route(String host, String ip, String path, boolean front, int index, String token) {
            this.token = token;
            this.host = host;
            this.ip = ip;
            this.path = path;
            this.front = front;
            this.index = index;
        }
    }

    private static final String WORKER_HOST = "kws{dc}{media}.nova-app.eu";

    private static final Object LOCK = new Object();
    private static RouteSet current;
    private static String metaEtag = "";
    private static int metaVersion;
    private static boolean started;
    private static boolean fetching;

    private static final AtomicInteger[] WALK_START = new AtomicInteger[12];
    private static final AtomicLong[] RELAY_BLOCKED_UNTIL = new AtomicLong[3];
    private static final int FRONT_OFFSET_SEED = new SecureRandom().nextInt(1 << 20);

    static {
        for (int i = 0; i < WALK_START.length; i++) {
            WALK_START[i] = new AtomicInteger();
        }
        for (int i = 0; i < RELAY_BLOCKED_UNTIL.length; i++) {
            RELAY_BLOCKED_UNTIL[i] = new AtomicLong();
        }
    }

    private NovaWss() {
    }

    // --- the setting --------------------------------------------------------

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** On by default: it is what makes Telegram reachable on a line that drops plain TCP. */
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
        apply();
    }

    /**
     * Makes the native side agree with the setting. Called before the first
     * connection opens (from {@code ConnectionsManager.init}) and on every
     * toggle. The decoy never gets a listener, and a switched-off setting
     * leaves existing connections alone: only new ones go the other way.
     */
    public static void apply() {
        int port = 0;
        if (isEnabled() && !NovaDecoyState.isActive()) {
            port = NovaWssBridge.ensureStarted();
        }
        ConnectionsManager.native_setWssBridge(port, port != 0 ? NovaWssBridge.token() : new byte[16]);
        if (port != 0) {
            start();
        }
    }

    /**
     * Whether the traffic of this client really goes over WSS right now. Used to
     * leave {@code cdn_supported} off in {@code upload.getFile}: a CDN redirect
     * names a data centre that no relay carries, and the file then hangs.
     */
    public static boolean carriesTraffic() {
        try {
            return isEnabled()
                    && !NovaDecoyState.isActive()
                    && NovaWssBridge.port() != 0
                    && !SharedConfig.isProxyEnabled();
        } catch (Throwable e) {
            return false;
        }
    }

    // --- the ladder ---------------------------------------------------------

    private static int absDc(int dc) {
        return dc < 0 ? -dc : dc;
    }

    private static int slotFor(int dc) {
        return (absDc(dc) - 1) + (dc < 0 ? 6 : 0);
    }

    private static int relayGroup(int dc) {
        switch (absDc(dc)) {
            case 1:
            case 3:
                return 0;
            case 2:
            case 4:
                return 1;
            default:
                return 2;
        }
    }

    /** Whether data centre {@code dc} (negative for media) can be carried this way at all. */
    public static boolean supports(int dc) {
        int a = absDc(dc);
        return a >= 1 && a <= 5;
    }

    private static boolean relayAvailable(int dc) {
        return android.os.SystemClock.elapsedRealtime() >= RELAY_BLOCKED_UNTIL[relayGroup(dc)].get();
    }

    private static String fill(String text, int dc) {
        return text.replace("{dc}", String.valueOf(absDc(dc))).replace("{media}", dc < 0 ? "-1" : "");
    }

    private static Route makeRoute(Entry entry, int dc, int index, boolean front) {
        String ip = entry.ips[absDc(dc)];
        return new Route(fill(entry.host, dc), ip == null ? "" : ip, fill(entry.path, dc), front, index, null);
    }

    private static Route routeAt(RouteSet set, int dc, int index) {
        if (index <= 0) {
            return makeRoute(set.official, dc, 0, false);
        }
        int own = set.own.size();
        if (index <= own) {
            return makeRoute(set.own.get(index - 1), dc, index, false);
        }
        int count = set.fronts.size();
        int front = (index - 1 - own + FRONT_OFFSET_SEED % count) % count;
        return makeRoute(set.fronts.get(front), dc, index, true);
    }

    private static RouteSet currentSet() {
        synchronized (LOCK) {
            if (current == null) {
                current = builtIn();
            }
            return current;
        }
    }

    /**
     * Routes to try for one connection attempt, best first: the one that worked
     * last time, then the next. Exhausting them is a failed attempt, and the
     * next attempt starts further along.
     */
    public static List<Route> attempt(int dc, int count) {
        List<Route> result = new ArrayList<>();
        if (!supports(dc)) {
            return result;
        }
        RouteSet set = currentSet();
        // The official relay first, unless it was found blocked a moment ago.
        // Several data centres share one ingress address, so one failure is
        // known to all of them and a connection does not spend its first 3.5
        // seconds on a door that is known to be shut.
        if (relayAvailable(dc)) {
            result.add(routeAt(set, dc, 0));
        }
        // The Worker is the last rung, and only while a token is at hand. Asking
        // never waits: a missing token is fetched in the background and the rung
        // is left out of this attempt.
        String token = NovaRelayToken.cached();
        int others = set.own.size() + set.fronts.size() + (token != null ? 1 : 0);
        int start = Math.max(WALK_START[slotFor(dc)].get(), 1);
        for (int i = 0; result.size() < count && i != others; i++) {
            int index = 1 + (start - 1 + i) % others;
            if (token != null && index == others) {
                result.add(workerRoute(dc, index, token));
            } else {
                result.add(routeAt(set, dc, index));
            }
        }
        return result;
    }

    private static Route workerRoute(int dc, int index, String token) {
        return new Route(fill(WORKER_HOST, dc), "", "/apiws", false, index, token);
    }

    public static void noteWorked(int dc, Route route) {
        if (!supports(dc)) {
            return;
        }
        if (route.index == 0) {
            RELAY_BLOCKED_UNTIL[relayGroup(dc)].set(0);
        } else {
            WALK_START[slotFor(dc)].set(route.index);
        }
    }

    public static void noteFailed(int dc, Route route) {
        if (!supports(dc)) {
            return;
        }
        if (route.index == 0) {
            RELAY_BLOCKED_UNTIL[relayGroup(dc)].set(
                    android.os.SystemClock.elapsedRealtime() + RELAY_BLOCKED_FOR_MS);
            return;
        }
        RouteSet set = currentSet();
        int others = set.own.size() + set.fronts.size() + (NovaRelayToken.has() ? 1 : 0);
        if (others == 0) {
            return;
        }
        // The next one, wrapping and never onto the official relay.
        WALK_START[slotFor(dc)].compareAndSet(route.index, 1 + (route.index % others));
    }

    // --- the list: built in, then the published one -------------------------

    private static Entry front(String host) {
        Entry entry = new Entry();
        entry.host = "kws{dc}." + host;
        return entry;
    }

    /** The same as the first published NovaGram/relay.json; used until a newer list is read. */
    private static RouteSet builtIn() {
        RouteSet result = new RouteSet();
        result.version = 1;
        Entry official = new Entry();
        official.host = "kws{dc}{media}.web.telegram.org";
        // The name alone resolves to an address that is not served the same
        // way; a wrong one answers with a redirect or with silence.
        official.ips[1] = "149.154.174.100";
        official.ips[2] = "149.154.167.220";
        official.ips[3] = "149.154.174.100";
        official.ips[4] = "149.154.167.220";
        official.ips[5] = "149.154.170.100";
        result.official = official;
        String[] hosts = {
                "pclead.co.uk", "offshor.co.uk", "cakeisalie.co.uk", "noskomnadzor.co.uk",
                "lovetrue.co.uk", "sorokdva.co.uk", "pyatdesyatdva.co.uk", "kartoshka.co.uk",
                "sorokodin.co.uk", "pyatdesyatodin.co.uk", "notelega.co.uk", "ebally.co.uk",
                "nebally.co.uk", "havegreatday.co.uk", "pomogite.co.uk", "fixtelega.co.uk",
                "sadnews.co.uk", "onedaychamp.co.uk", "stopblocking.co.uk", "nothingthere.co.uk",
        };
        for (String host : hosts) {
            result.fronts.add(front(host));
        }
        return result;
    }

    private static Entry parseEntry(Object value) {
        if (!(value instanceof JSONObject)) {
            return null;
        }
        JSONObject object = (JSONObject) value;
        Entry result = new Entry();
        result.host = object.optString("host", "").toLowerCase(java.util.Locale.US);
        String path = object.optString("path", "");
        if (!path.isEmpty()) {
            result.path = path;
        }
        if (!GOOD_HOST.matcher(result.host).matches() || !GOOD_PATH.matcher(result.path).matches()) {
            return null;
        }
        JSONObject ips = object.optJSONObject("ips");
        for (int dc = 1; dc != 6; dc++) {
            String ip = ips == null ? "" : ips.optString(String.valueOf(dc), "");
            if (!ip.isEmpty() && !GOOD_IP.matcher(ip).matches()) {
                return null;
            }
            result.ips[dc] = ip;
        }
        return result;
    }

    private static RouteSet parse(byte[] bytes) {
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            RouteSet result = new RouteSet();
            result.version = root.optInt("version", 0);
            if (result.version <= 0) {
                return null;
            }
            Entry official = parseEntry(root.optJSONObject("official"));
            if (official == null) {
                return null;
            }
            result.official = official;
            JSONArray own = root.optJSONArray("own");
            for (int i = 0; own != null && i < own.length(); i++) {
                Entry entry = parseEntry(own.optJSONObject(i));
                if (entry != null) {
                    result.own.add(entry);
                }
            }
            JSONArray fronts = root.optJSONArray("fronts");
            for (int i = 0; fronts != null && i < fronts.length(); i++) {
                Entry entry = parseEntry(fronts.optJSONObject(i));
                if (entry != null) {
                    result.fronts.add(entry);
                }
            }
            while (result.own.size() + result.fronts.size() > MAX_ENTRIES && !result.fronts.isEmpty()) {
                result.fronts.remove(result.fronts.size() - 1);
            }
            if (result.own.size() + result.fronts.size() == 0) {
                // Nothing to fall back on is a worse list than the built-in one.
                return null;
            }
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    private static File folder() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), FOLDER);
    }

    private static byte[] readAll(File file) {
        if (!file.isFile() || file.length() > MAX_BODY) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeAtomic(File target, byte[] data) throws Exception {
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("cannot create " + dir);
        }
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            throw new java.io.IOException("cannot replace " + target);
        }
    }

    private static void loadCached() {
        try {
            byte[] meta = readAll(new File(folder(), META_FILE));
            if (meta != null) {
                JSONObject object = new JSONObject(new String(meta, StandardCharsets.UTF_8));
                synchronized (LOCK) {
                    metaEtag = object.optString("etag", "");
                    metaVersion = object.optInt("version", 0);
                }
            }
            byte[] list = readAll(new File(folder(), LIST_FILE));
            RouteSet set = list == null ? null : parse(list);
            if (set != null) {
                synchronized (LOCK) {
                    current = set;
                }
                log("relay list v" + set.version + " read from the disk");
            }
        } catch (Throwable e) {
            FileLog.e(e, false);
        }
    }

    /** Reads the list kept on the disk and starts the daily check. Not for the decoy. */
    public static void start() {
        synchronized (LOCK) {
            if (started || NovaDecoyState.isActive()) {
                return;
            }
            started = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            loadCached();
            Utilities.globalQueue.postRunnable(NovaWss::sync, FIRST_DELAY_MS);
            // Have a token ready before the ladder ever reaches the last rung.
            Utilities.globalQueue.postRunnable(NovaRelayToken::prefetch, FIRST_DELAY_MS / 3);
        });
    }

    private static void sync() {
        Utilities.globalQueue.postRunnable(NovaWss::sync, PERIOD_MS);
        if (NovaDecoyState.isActive() || !isEnabled()) {
            return;
        }
        synchronized (LOCK) {
            if (fetching) {
                return;
            }
            fetching = true;
        }
        Utilities.stageQueue.postRunnable(() -> {
            try {
                fetchAndApply();
            } catch (Throwable e) {
                // Silent by design; the next attempt is tomorrow.
                log("relay list sync failed: " + e);
            } finally {
                synchronized (LOCK) {
                    fetching = false;
                }
            }
        });
    }

    private static void fetchAndApply() throws Exception {
        NovaDoh.Answer answer = NovaDoh.resolve(LIST_HOST, NovaDoh.TYPE_A);
        if (answer.isEmpty()) {
            throw new IllegalStateException("could not resolve " + LIST_HOST + " over secure DNS");
        }
        String etag;
        synchronized (LOCK) {
            etag = metaEtag;
        }
        SSLSocket socket = null;
        try {
            socket = NovaHttps.connect(LIST_HOST, answer.first(), Proxy.NO_PROXY, TIMEOUT_MS);
            Map<String, String> headers = NovaHttps.headers();
            headers.put("User-Agent", "NovaGram");
            if (!etag.isEmpty()) {
                headers.put("If-None-Match", etag);
            }
            OutputStream request = socket.getOutputStream();
            request.write(NovaHttps.head("GET", LIST_HOST, LIST_PATH, headers));
            request.flush();
            NovaHttps.Reader reader = NovaHttps.open(socket.getInputStream());
            int code = reader.response.code;
            if (code == 304) {
                return;
            }
            if (code != 200) {
                throw new IllegalStateException("http " + code);
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            reader.drain(body, MAX_BODY, 0, null, null);
            byte[] bytes = body.toByteArray();
            RouteSet set = parse(bytes);
            if (set == null) {
                FileLog.e("NovaGram WSS: the published relay list did not parse, kept the old");
                return;
            }
            int knownVersion;
            synchronized (LOCK) {
                knownVersion = metaVersion;
            }
            // A list that went back in version is somebody's mistake or a
            // replay of an old file; the one in use stays.
            if (set.version < knownVersion) {
                return;
            }
            writeAtomic(new File(folder(), LIST_FILE), bytes);
            JSONObject meta = new JSONObject();
            meta.put("etag", reader.response.etag == null ? "" : reader.response.etag);
            meta.put("version", set.version);
            writeAtomic(new File(folder(), META_FILE), meta.toString().getBytes(StandardCharsets.UTF_8));
            synchronized (LOCK) {
                metaEtag = reader.response.etag == null ? "" : reader.response.etag;
                metaVersion = set.version;
                current = set;
            }
            log("relay list v" + set.version + ": " + set.own.size() + " own, " + set.fronts.size() + " mirrors");
        } finally {
            NovaHttps.close(socket);
        }
    }

    static void log(String text) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("NovaGram WSS: " + text);
        } else if (debuggable()) {
            // A debuggable build prints the route taken even with the client's own
            // logging off, so a test on a device can read it from logcat.
            android.util.Log.i("NovaWss", text);
        }
    }

    private static Boolean debuggableCache;

    private static boolean debuggable() {
        Boolean known = debuggableCache;
        if (known == null) {
            Context context = ApplicationLoader.applicationContext;
            known = context != null
                    && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            debuggableCache = known;
        }
        return known;
    }
}
