package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.graphics.Paint;
import android.net.Uri;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.util.JsonReader;
import android.util.JsonToken;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.novagram.net.NovaDoh;
import org.telegram.messenger.novagram.net.NovaHttps;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;

/**
 * The prohibited-content check: a public list of people, organisations and
 * resources, an index built from it once, and three questions asked of the
 * index - who wrote this message, does this text carry a listed link, and what
 * should the dialog call the list.
 *
 * <p><b>What is in the index.</b> Only <em>keys</em>: a numeric peer id, or a
 * normalised address ({@code host + path}, lower case, no scheme, no query).
 * A {@code t.me/name} link is the same kind of key, so one map answers both
 * "is this username listed" and "is this link listed". Each key carries a
 * bit mask of the lists it was found in, because the same account is
 * sometimes on two.</p>
 *
 * <p><b>Global, not per account.</b> The list is public and the same for every
 * account, and per-account singletons outlive the accounts that created them
 * (G48), so nothing here is keyed by an account. The only per-account thing is
 * the lookup of a username from a peer, which goes through the account that
 * owns the message.</p>
 *
 * <p><b>Where it lives.</b> The index text is the bundled asset until a sync
 * writes {@code files/novagram_forbidden/list.txt}; both have the same format,
 * so there is one parser. It is public data, not a user list, which is why it
 * is not in a sealed container (I1 is about metadata the user made).</p>
 *
 * <p><b>The network.</b> Once in 24 hours, from the application start and from
 * a timer in the process - no service, no wakelock, no WorkManager job, so
 * nothing runs while the process is dead. The name is resolved over the
 * pinned DoH path (I5) like the update check; a proxy resolves it itself.
 * {@code If-None-Match} with the stored ETag: 304 changes nothing; a 200 whose
 * SHA-256 equals the stored one rewrites nothing. A failure is silent and the
 * next attempt comes at least an hour later.</p>
 *
 * <p>The decoy never reaches any of this: {@link #isEnabled()} is false there,
 * so no request, no file read, no mark on a message.</p>
 */
public final class NovaForbiddenList {
    public static final int LIST_AGENT = 0;
    public static final int LIST_TERRORIST = 1;
    public static final int LIST_EXTREMIST = 2;
    public static final int LIST_UNDESIRABLE = 3;
    public static final int LIST_MATERIALS = 4;
    /** The lists that name a person or an organisation, as opposed to a resource. */
    public static final int PEOPLE_MASK = 0x0F;

    public static final String URL_LIST =
            "https://raw.githubusercontent.com/confeden/nova_updates/refs/heads/main/forbidden_list/forbidden_list.json";

    private static final String ASSET = "novagram_forbidden.txt";
    private static final String FOLDER = "novagram_forbidden";
    private static final String LIST_FILE = "list.txt";
    private static final String META_FILE = "meta.txt";
    private static final String HEADER = "NFL1";
    private static final long PERIOD_MS = 24L * 60 * 60 * 1000;
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int SCAN_LIMIT = 8192;
    /** Index lines of a material's full title carry this prefix after the list digit. */
    private static final String NAME_PREFIX = "name:";
    /** Shorter titles match too often in ordinary text, so they are not checked. */
    private static final int MIN_NAME = 12;

    private static final class Index {
        final HashMap<Long, Integer> ids = new HashMap<>();
        final HashMap<String, Integer> keys = new HashMap<>();
        /** Normalised full title -> list mask. */
        final HashMap<String, Integer> names = new HashMap<>();
        String sha = "";
    }

    private static volatile Index index;
    /** Bumped on every swap of {@link #index}; a message flag is valid for one generation. */
    private static volatile int generation = 1;
    private static volatile Boolean cachedEnabled;
    private static volatile boolean busy;
    private static volatile boolean started;
    private static Paint overlayPaint;
    private static boolean overlayDark;

    private static final Pattern LINK = Pattern.compile(
            "(?i)tg://[^\\s<>\"]+|(?:[a-z][a-z0-9+.-]*://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:[/?#][^\\s<>\"]*)?");

    private NovaForbiddenList() {
    }

    // region switch

    public static boolean isEnabled() {
        if (NovaDecoyState.isActive()) {
            return false;
        }
        final Boolean known = cachedEnabled;
        if (known != null) {
            return known;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        final boolean enabled = NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.FORBIDDEN_CONTENT_CHECK);
        cachedEnabled = enabled;
        return enabled;
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        NovaPrivacySettings.global(context)
                .setFeatureEnabled(NovaPrivacyFeature.FORBIDDEN_CONTENT_CHECK, enabled);
        cachedEnabled = enabled;
        generation++;
        if (enabled) {
            start();
        }
    }

    // endregion

    // region start and sync

    /**
     * Called from {@code ApplicationLoader.onCreate} on the non-decoy side.
     * Loads the index off the main thread, then syncs if the day has passed.
     */
    public static void start() {
        if (!isEnabled()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (index == null) {
                    load();
                }
                syncIfDue();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            scheduleNext();
        });
    }

    private static void scheduleNext() {
        if (started) {
            return;
        }
        started = true;
        // A timer inside the live process, not a job: it fires only if the
        // process is still there, and a process that was killed checks at its
        // next start instead.
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        if (isEnabled()) {
                            syncIfDue();
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
                AndroidUtilities.runOnUIThread(this, PERIOD_MS);
            }
        }, PERIOD_MS);
    }

    private static File folder(Context context) {
        return new File(context.getFilesDir(), FOLDER);
    }

    private static void load() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        Index built = null;
        File file = new File(folder(context), LIST_FILE);
        if (file.isFile()) {
            try (InputStream in = new java.io.FileInputStream(file)) {
                built = parse(in);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (built == null) {
            try (InputStream in = context.getAssets().open(ASSET)) {
                built = parse(in);
            } catch (Throwable e) {
                // No asset: a build made without the generating script. The
                // first sync fills the index in.
                built = new Index();
            }
        }
        index = built;
        generation++;
    }

    private static String[] readMeta(Context context) {
        String[] meta = new String[] { "", "0", "0" };
        File file = new File(folder(context), META_FILE);
        if (!file.isFile()) {
            return meta;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            for (int i = 0; i < 3; i++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                meta[i] = line.trim();
            }
        } catch (Throwable ignored) {
        }
        return meta;
    }

    private static void writeMeta(Context context, String etag, long lastOk, long lastTry) {
        File dir = folder(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        String text = (etag == null ? "" : etag.replace('\n', ' ')) + "\n" + lastOk + "\n" + lastTry + "\n";
        writeAtomic(new File(dir, META_FILE), text.getBytes(StandardCharsets.UTF_8));
    }

    /** False when the bytes did not reach the file: the caller must not record the sync as done. */
    private static boolean writeAtomic(File target, byte[] data) {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        } catch (Throwable e) {
            FileLog.e(e);
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
        }
        return true;
    }

    private static void syncIfDue() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || busy || !isEnabled()) {
            return;
        }
        String[] meta = readMeta(context);
        long lastOk = parseLong(meta[1]);
        long lastTry = parseLong(meta[2]);
        long now = System.currentTimeMillis();
        // A clock set backwards must not postpone the check by as much.
        boolean due = lastOk > now || now - lastOk >= PERIOD_MS;
        boolean allowed = lastTry > now || now - lastTry >= RETRY_MS;
        if (!due || !allowed) {
            return;
        }
        busy = true;
        try {
            sync(context, meta[0], now);
        } catch (Throwable e) {
            // Silent: the next cycle tries again.
            FileLog.e(e);
            writeMeta(context, meta[0], lastOk, now);
        } finally {
            busy = false;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static void sync(Context context, String etag, long now) throws Exception {
        final String[] newEtag = new String[1];
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int code = fetch(URL_LIST, etag, newEtag, body);
        if (code == 304) {
            writeMeta(context, etag, now, now);
            return;
        }
        if (code != 200) {
            throw new IllegalStateException("http " + code);
        }
        final byte[] bytes = body.toByteArray();
        final String sha = sha256(bytes);
        final Index current = index;
        if (current != null && sha.equals(current.sha)) {
            // Same bytes under a new or missing ETag: nothing to rewrite.
            writeMeta(context, newEtag[0] != null ? newEtag[0] : etag, now, now);
            return;
        }
        final byte[] lines = toLines(bytes, sha);
        // Parsed before it is written: a body that is not the list must not
        // replace the one that is.
        final Index built = parse(new ByteArrayInputStream(lines));
        if (built.ids.isEmpty() && built.keys.isEmpty()) {
            throw new IllegalStateException("empty list");
        }
        File dir = folder(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("no folder");
        }
        // The list must be on disk before the sync is recorded as done; a
        // silent failure here left the metadata saying "synced" with no list.
        if (!writeAtomic(new File(dir, LIST_FILE), lines)) {
            throw new IOException("list not written");
        }
        writeMeta(context, newEtag[0], now, now);
        index = built;
        generation++;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }

    private static int listCode(String key) {
        switch (key) {
            case "иноагенты":
                return LIST_AGENT;
            case "террористы":
                return LIST_TERRORIST;
            case "экстремисты":
                return LIST_EXTREMIST;
            case "нежелательные":
                return LIST_UNDESIRABLE;
            case "материалы":
                return LIST_MATERIALS;
            default:
                return -1;
        }
    }

    /**
     * The JSON reduced to what the index reads: one line per link, the list
     * digit, a tab, the link as the list wrote it. This is also the format of
     * the bundled asset, which the build script writes the same way, so the
     * normalisation lives in one place only - {@link #normalise}.
     */
    private static byte[] toLines(byte[] json, String sha) throws Exception {
        StringBuilder out = new StringBuilder(256 * 1024);
        out.append(HEADER).append(' ').append(sha).append('\n');
        int links = 0;
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"lists".equals(reader.nextName())) {
                    reader.skipValue();
                    continue;
                }
                reader.beginObject();
                while (reader.hasNext()) {
                    int code = listCode(reader.nextName());
                    if (code < 0) {
                        reader.skipValue();
                        continue;
                    }
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if (!"items".equals(reader.nextName())) {
                            reader.skipValue();
                            continue;
                        }
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                final String field = reader.nextName();
                                if ("name".equals(field) && code == LIST_MATERIALS) {
                                    if (reader.peek() == JsonToken.STRING) {
                                        String title = reader.nextString();
                                        if (title != null && title.trim().length() > 0) {
                                            out.append((char) ('0' + code)).append('\t').append(NAME_PREFIX)
                                                    .append(title.replace('\n', ' ').replace('\r', ' '))
                                                    .append('\n');
                                            links++;
                                        }
                                    } else {
                                        reader.skipValue();
                                    }
                                    continue;
                                }
                                if (!"links".equals(field)) {
                                    reader.skipValue();
                                    continue;
                                }
                                reader.beginArray();
                                while (reader.hasNext()) {
                                    String link = reader.nextString();
                                    if (link != null && link.length() > 0) {
                                        out.append((char) ('0' + code)).append('\t')
                                                .append(link.replace('\n', ' ').replace('\r', ' '))
                                                .append('\n');
                                        links++;
                                    }
                                }
                                reader.endArray();
                            }
                            reader.endObject();
                        }
                        reader.endArray();
                    }
                    reader.endObject();
                }
                reader.endObject();
            }
            reader.endObject();
        }
        if (links == 0) {
            throw new IllegalStateException("no links in the answer");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Index parse(InputStream stream) throws IOException {
        Index built = new Index();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith(HEADER)) {
                throw new IOException("not a list");
            }
            built.sha = header.length() > HEADER.length() + 1 ? header.substring(HEADER.length() + 1).trim() : "";
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 3 || line.charAt(1) != '\t') {
                    continue;
                }
                int code = line.charAt(0) - '0';
                if (code < 0 || code > 9) {
                    continue;
                }
                add(built, 1 << code, line.substring(2));
            }
        }
        return built;
    }

    private static void add(Index built, int bit, String link) {
        if (link.startsWith(NAME_PREFIX)) {
            String key = normaliseName(link.substring(NAME_PREFIX.length()), MIN_NAME);
            if (key != null) {
                Integer old = built.names.get(key);
                built.names.put(key, (old == null ? 0 : old) | bit);
            }
            return;
        }
        String url = link;
        int marker = link.indexOf("ID:");
        if (marker >= 0) {
            String digits = link.substring(marker + 3).trim();
            try {
                long id = Long.parseLong(digits);
                // A channel is "-100" and its id; the client keeps the id
                // alone, negated. A small negative number is a basic group.
                if (id < 0) {
                    String text = digits.substring(1);
                    if (text.startsWith("100") && text.length() > 3) {
                        id = -Long.parseLong(text.substring(3));
                    }
                }
                if (id != 0) {
                    Integer old = built.ids.get(id);
                    built.ids.put(id, (old == null ? 0 : old) | bit);
                }
            } catch (NumberFormatException ignored) {
            }
            url = link.substring(0, marker).trim();
            if (url.endsWith(",")) {
                url = url.substring(0, url.length() - 1).trim();
            }
        }
        String key = normalise(url);
        if (key != null) {
            Integer old = built.keys.get(key);
            built.keys.put(key, (old == null ? 0 : old) | bit);
        }
    }

    // endregion

    // region network

    private static int fetch(String url, String etag, String[] etagOut, ByteArrayOutputStream out) throws Exception {
        final Proxy proxy = NovaDoh.proxyFor();
        if (proxy != Proxy.NO_PROXY) {
            // With a proxy the proxy resolves the name; nothing is looked up
            // here, which is the stricter of the two.
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(proxy);
            try {
                if (!(connection instanceof HttpsURLConnection)) {
                    throw new IllegalArgumentException("only https is accepted");
                }
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "NovaGram");
                if (etag != null && etag.length() > 0) {
                    connection.setRequestProperty("If-None-Match", etag);
                }
                int code = connection.getResponseCode();
                etagOut[0] = connection.getHeaderField("ETag");
                if (code == 200) {
                    try (InputStream in = connection.getInputStream()) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            if (out.size() + read > MAX_BYTES) {
                                throw new IllegalStateException("answer is too large");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                return code;
            } finally {
                connection.disconnect();
            }
        }
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("only https is accepted");
        }
        final String host = uri.getHost();
        NovaDoh.Answer answer = NovaDoh.resolve(host, NovaDoh.TYPE_A);
        if (answer.isEmpty()) {
            // No fall-back to the system resolver, by design.
            throw new IllegalStateException("could not resolve " + host + " over secure DNS");
        }
        String path = uri.getRawPath();
        if (path == null || path.length() == 0) {
            path = "/";
        }
        SSLSocket socket = null;
        try {
            socket = NovaHttps.connect(host, answer.first(), Proxy.NO_PROXY, TIMEOUT_MS);
            java.util.Map<String, String> headers = NovaHttps.headers();
            headers.put("User-Agent", "NovaGram");
            if (etag != null && etag.length() > 0) {
                headers.put("If-None-Match", etag);
            }
            OutputStream request = socket.getOutputStream();
            request.write(NovaHttps.head("GET", host, path, headers));
            request.flush();
            NovaHttps.Reader reader = NovaHttps.open(socket.getInputStream());
            etagOut[0] = reader.response.etag;
            if (reader.response.code == 200) {
                reader.drain(out, MAX_BYTES, 0, null, null);
            }
            return reader.response.code;
        } finally {
            NovaHttps.close(socket);
        }
    }

    // endregion

    // region normalisation

    private static String alias(String host) {
        switch (host) {
            case "twitter.com":
                return "x.com";
            case "threads.net":
                return "threads.com";
            case "vk.ru":
                return "vk.com";
            case "telegram.me":
            case "telegram.dog":
                return "t.me";
            default:
                return host;
        }
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static boolean isName(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static String tmeKey(String name) {
        return name.length() > 0 && isName(name) ? "t.me/" + name : null;
    }

    private static String tmeFromPath(String path) {
        String[] parts = path.split("/");
        java.util.ArrayList<String> segments = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.length() > 0) {
                segments.add(part);
            }
        }
        if (segments.isEmpty()) {
            return null;
        }
        String first = segments.get(0);
        if (first.startsWith("+")) {
            return "t.me/" + first;
        }
        if (first.equals("joinchat") && segments.size() > 1) {
            return "t.me/joinchat/" + segments.get(1);
        }
        if (first.equals("c") && segments.size() > 1) {
            return "t.me/c/" + segments.get(1);
        }
        if (first.equals("s") && segments.size() > 1) {
            return tmeKey(segments.get(1));
        }
        if (first.equals("addstickers") || first.equals("addemoji") || first.equals("share")
                || first.equals("proxy") || first.equals("socks") || first.equals("login")) {
            return null;
        }
        return tmeKey(first);
    }

    /**
     * One address, one key, for the list and for the text alike. Lower case,
     * no scheme, no {@code www.}, no query or fragment (a YouTube video keeps
     * its {@code v}), no trailing slash; {@code t.me/<name>/<post>} is the
     * channel, and {@code tg://resolve?domain=} is the same channel.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        while (s.length() > 0 && ".,;:!?)]}>\"'»".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() == 0) {
            return null;
        }
        try {
            s = Uri.decode(s);
        } catch (Throwable ignored) {
        }
        if (s == null) {
            return null;
        }
        s = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("tg:")) {
            int q = s.indexOf('?');
            if (q < 0) {
                return null;
            }
            String name = queryParam(s.substring(q + 1), "domain");
            return name == null ? null : tmeKey(name);
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0 && scheme <= 12) {
            s = s.substring(scheme + 3);
        }
        String query = null;
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        if (cut >= 0) {
            if (s.charAt(cut) == '?') {
                int hash = s.indexOf('#', cut);
                query = s.substring(cut + 1, hash < 0 ? s.length() : hash);
            }
            s = s.substring(0, cut);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int slash = s.indexOf('/');
        String host = slash < 0 ? s : s.substring(0, slash);
        String path = slash < 0 ? "" : s.substring(slash);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.indexOf('.') <= 0) {
            return null;
        }
        for (String prefix : new String[] { "www.", "m.", "mobile." }) {
            if (host.startsWith(prefix) && host.indexOf('.', prefix.length()) > 0) {
                host = host.substring(prefix.length());
                break;
            }
        }
        host = alias(host);
        if (host.equals("t.me")) {
            return tmeFromPath(path);
        }
        if (host.equals("youtube.com") && path.equals("/watch")) {
            String v = queryParam(query, "v");
            if (v != null && v.length() > 0) {
                path = "/watch?v=" + v;
            }
        }
        return host + path;
    }

    // endregion

    // region questions

    /**
     * Which of the people lists a message belongs to, as a bit mask; 0 when
     * none. Cached on the message for one generation of the index, so the
     * chat cell pays two integer compares per frame.
     */
    public static int maskFor(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return 0;
        }
        final Index current = index;
        if (current == null || !isEnabled()) {
            return 0;
        }
        final int gen = generation;
        if (message.novaForbiddenGen == gen) {
            return message.novaForbidden;
        }
        boolean[] complete = new boolean[] { true };
        int mask = 0;
        TLRPC.Message owner = message.messageOwner;
        long sender = MessageObject.getPeerId(owner.from_id);
        if (sender == 0 && owner.peer_id != null) {
            // A channel post has no author of its own; the channel is it.
            sender = MessageObject.getPeerId(owner.peer_id);
        }
        mask |= peerMask(current, message.currentAccount, sender, complete);
        if (owner.fwd_from != null && owner.fwd_from.from_id != null) {
            mask |= peerMask(current, message.currentAccount, MessageObject.getPeerId(owner.fwd_from.from_id), complete);
        }
        mask &= PEOPLE_MASK;
        if (complete[0]) {
            message.novaForbidden = mask;
            message.novaForbiddenGen = gen;
        }
        return mask;
    }

    private static int peerMask(Index current, int account, long peer, boolean[] complete) {
        if (peer == 0) {
            return 0;
        }
        int mask = 0;
        Integer byId = current.ids.get(peer);
        if (byId != null) {
            mask |= byId;
        }
        String username = null;
        MessagesController controller = MessagesController.getInstance(account);
        if (peer > 0) {
            TLRPC.User user = controller.getUser(peer);
            if (user == null) {
                complete[0] = false;
            } else {
                username = UserObject.getPublicUsername(user);
            }
        } else {
            TLRPC.Chat chat = controller.getChat(-peer);
            if (chat == null) {
                complete[0] = false;
            } else {
                username = ChatObject.getPublicUsername(chat);
            }
        }
        if (username != null && username.length() > 0) {
            Integer byName = current.keys.get("t.me/" + username.toLowerCase(Locale.ROOT));
            if (byName != null) {
                mask |= byName;
            }
        }
        return mask;
    }

    /** The mask of every list that has a link in the text, or 0. Send time only. */
    public static int scanText(CharSequence text) {
        final Index current = index;
        if (text == null || text.length() == 0 || current == null || !isEnabled()) {
            return 0;
        }
        int mask = 0;
        CharSequence head = text.length() > SCAN_LIMIT ? text.subSequence(0, SCAN_LIMIT) : text;
        Matcher matcher = LINK.matcher(head);
        while (matcher.find()) {
            mask |= lookupLink(current, matcher.group());
        }
        mask |= scanNames(current, head);
        if (text instanceof Spanned) {
            URLSpan[] spans = ((Spanned) text).getSpans(0, head.length(), URLSpan.class);
            if (spans != null) {
                for (URLSpan span : spans) {
                    mask |= lookupLink(current, span.getURL());
                }
            }
        }
        return mask;
    }

    /**
     * Full titles of listed materials in the text, as a mask. A title counts
     * only when it appears whole: the register titles carry the author and the
     * court, so a shorter fragment of one is ordinary text and is not flagged.
     */
    private static int scanNames(Index current, CharSequence text) {
        if (current.names.isEmpty()) {
            return 0;
        }
        final String haystack = normaliseName(text.toString(), 0);
        if (haystack == null) {
            return 0;
        }
        int mask = 0;
        for (java.util.Map.Entry<String, Integer> entry : current.names.entrySet()) {
            if (haystack.contains(entry.getKey())) {
                mask |= entry.getValue();
            }
        }
        return mask;
    }

    /**
     * Lower case, ё as е, quotes and guillemets as spaces, runs of spaces
     * folded. The same rule on both sides of the comparison, so a title that
     * the register wrote with «» matches the same title typed with "".
     * Returns null when the result is shorter than minLength.
     */
    static String normaliseName(String raw, int minLength) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = s.replaceAll("[«»\"“”„'`‘’]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() < Math.max(1, minLength) ? null : s;
    }

    private static int lookupLink(Index current, String link) {
        String key = normalise(link);
        if (key == null) {
            return 0;
        }
        Integer found = current.keys.get(key);
        return found == null ? 0 : found;
    }

    /** The lists named in a mask, as the dialog says them: "иноагент, террорист". */
    public static String names(int mask) {
        StringBuilder out = new StringBuilder();
        int[] strings = {
                R.string.NovaForbiddenKindAgent,
                R.string.NovaForbiddenKindTerrorist,
                R.string.NovaForbiddenKindExtremist,
                R.string.NovaForbiddenKindUndesirable,
                R.string.NovaForbiddenKindMaterials,
        };
        for (int i = 0; i < strings.length; i++) {
            if ((mask & (1 << i)) != 0) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(LocaleController.getString(strings[i]));
            }
        }
        return out.toString();
    }

    /**
     * The brick-red wash drawn over a marked bubble. A translucent colour over
     * the theme's own bubble rather than a fixed fill, so the text keeps the
     * contrast the theme gave it: computed for the common bubbles, the worst
     * pair is 6.4:1 on dark (white on the purple outgoing bubble) and 10:1 on
     * light, where WCAG asks for 4.5.
     */
    public static Paint overlayPaint() {
        final boolean dark = Theme.isCurrentThemeDark();
        if (overlayPaint == null || overlayDark != dark) {
            overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            overlayDark = dark;
            overlayPaint.setColor(dark ? 0x99962822 : 0x61962822);
        }
        return overlayPaint;
    }

    // endregion
}
