package org.telegram.messenger.novagram.net;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.novagram.privacy.NovaPrivacyContract;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;

/**
 * Every name this client resolves for itself, resolved over the fork's own list
 * of encrypted endpoints and over nothing else.
 *
 * <p>The counterpart of {@code novagram/nova_doh.cpp} on the desktop half, down
 * to the endpoint list, the wire format, the cache bounds and the log prefix,
 * so that one paragraph of the roadmap describes both.</p>
 *
 * <p><b>What this is not.</b> There is no fall-back to {@code InetAddress}, to
 * the {@code hosts} file, to Private DNS or to whatever the network handed out
 * over DHCP. An empty answer is an answer, and a caller that cannot work
 * without an address has to fail visibly: a name that quietly resolved through
 * the system is exactly the leak this replaces.</p>
 *
 * <p>Everything here blocks. It is meant to be called from the background
 * threads that already exist for this work, not wrapped in another layer of
 * scheduling.</p>
 */
public final class NovaDoh {
    public static final String LOG_PREFIX = "NovaGram DoH: ";

    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_TXT = 16;

    /**
     * A name that does not answer in this long is treated as not answering at
     * all and the next endpoint is tried. Deliberately short: four endpoints in
     * a row have to fit inside the patience of whoever is waiting for a
     * connection.
     */
    private static final int TIMEOUT_MS = 5000;

    private static final long MIN_TTL_MS = 30L * 1000L;
    private static final long MAX_TTL_MS = 6L * 60L * 60L * 1000L;

    /**
     * The cache is not an optimisation, it is what keeps a working client from
     * asking four strangers about the same name over and over. Bounded because
     * a hostile answer could otherwise name a million records.
     */
    private static final int MAX_CACHE_ENTRIES = 256;

    /**
     * How often the same name may actually be asked over the wire, however
     * loudly a caller insists on a live reply.
     *
     * <p>Found by running the desktop half: a client that cannot connect asks
     * for the time on every connection attempt, and a cache-skipping resolve
     * turned that into thirty-nine queries to Cloudflare in one minute for one
     * name. The caller was right to want a fresh reply and wrong about how
     * often — so the floor lives here, where the next caller that needs one
     * cannot forget it.</p>
     */
    private static final long MIN_LIVE_INTERVAL_MS = 60L * 1000L;

    private static final int MAX_ANSWER_BYTES = 64 * 1024;

    private static final String BLOB_VERSION = "1";

    /** The reply that carried an answer, and what came with it. */
    public static final class Answer {
        public final List<String> values;
        /** How long the answer stays good for, already clamped. */
        public final long ttlMs;
        /**
         * The {@code Date} header of the reply, in seconds since the epoch, or
         * zero when the answer came out of the cache. The clock is a by-product
         * of asking: an endpoint that answered has just said, over a checked
         * certificate, what time it thinks it is.
         */
        public final long httpUnixtime;

        Answer(List<String> values, long ttlMs, long httpUnixtime) {
            this.values = values;
            this.ttlMs = ttlMs;
            this.httpUnixtime = httpUnixtime;
        }

        public boolean isEmpty() {
            return values == null || values.isEmpty();
        }

        public String first() {
            return isEmpty() ? null : values.get(0);
        }
    }

    /**
     * One place a name may be asked. The four built-in ones carry the addresses
     * they answer on, so they can be reached without asking anybody first —
     * that is the whole point of a fixed list. An endpoint the owner added
     * carries a name and, optionally, addresses of its own.
     */
    public static final class Endpoint {
        public final String host;
        public final String path;
        public final List<String> addresses;
        public final boolean builtin;
        public final boolean enabled;

        public Endpoint(String host, String path, List<String> addresses, boolean builtin, boolean enabled) {
            this.host = host;
            this.path = path;
            this.addresses = addresses == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(addresses));
            this.builtin = builtin;
            this.enabled = enabled;
        }

        public Endpoint withEnabled(boolean value) {
            return new Endpoint(host, path, addresses, builtin, value);
        }
    }

    private static final class CacheEntry {
        final List<String> values;
        final long until;

        CacheEntry(List<String> values, long until) {
            this.values = values;
            this.until = until;
        }
    }

    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, CacheEntry> CACHE = new HashMap<>();

    /**
     * When each name was last actually asked over the wire, as opposed to
     * answered from the cache. See {@link #MIN_LIVE_INTERVAL_MS}.
     */
    private static final Map<String, Long> LIVE = new HashMap<>();

    private static volatile List<Endpoint> builtin;

    private NovaDoh() {
    }

    /**
     * Not the A-records of the endpoint names. {@code cloudflare-dns.com}
     * resolves to Cloudflare's CDN range, which the provider promises only as a
     * range, while 1.1.1.1 is the address of the service itself and is covered
     * by the certificate. Every address below was checked against the
     * provider's own documentation and by a live request, and every one of them
     * is also compiled into the desktop half; if one side changes, both change.
     */
    public static List<Endpoint> builtin() {
        List<Endpoint> result = builtin;
        if (result != null) {
            return result;
        }
        List<Endpoint> list = new ArrayList<>(4);
        for (NovaPrivacyContract.DohProvider provider : NovaPrivacyContract.DOH_ORDER) {
            String endpoint = provider.getEndpoint();
            int slash = endpoint.indexOf('/', "https://".length());
            String host = endpoint.substring("https://".length(), slash);
            String path = endpoint.substring(slash);
            list.add(new Endpoint(host, path, bootstrapFor(provider), true, true));
        }
        result = Collections.unmodifiableList(list);
        builtin = result;
        return result;
    }

    private static List<String> bootstrapFor(NovaPrivacyContract.DohProvider provider) {
        switch (provider) {
            case CLOUDFLARE:
                return addresses("1.1.1.1", "1.0.0.1",
                        "2606:4700:4700::1111", "2606:4700:4700::1001");
            case GOOGLE:
                return addresses("8.8.8.8", "8.8.4.4",
                        "2001:4860:4860::8888", "2001:4860:4860::8844");
            case ADGUARD:
                return addresses("94.140.14.14", "94.140.15.15",
                        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff");
            case QUAD9:
                return addresses("9.9.9.9", "149.112.112.112",
                        "2620:fe::fe", "2620:fe::9");
        }
        return Collections.emptyList();
    }

    private static List<String> addresses(String... values) {
        List<String> result = new ArrayList<>(values.length);
        Collections.addAll(result, values);
        return result;
    }

    /**
     * The list as configured: the built-in four with their on/off state,
     * followed by whatever the owner added.
     *
     * <p>The built-in four are always present, whatever a stored blob says: a
     * release that adds or renames one must not be overruled by a file written
     * by an older build. What the blob decides about them is only whether they
     * are on.</p>
     */
    public static List<Endpoint> endpoints() {
        List<Endpoint> stored = deserialize(storedBlob());
        if (stored.isEmpty()) {
            return builtin();
        }
        List<Endpoint> result = new ArrayList<>();
        for (Endpoint endpoint : builtin()) {
            Endpoint value = endpoint;
            for (Endpoint saved : stored) {
                if (saved.builtin && saved.host.equals(endpoint.host)) {
                    value = endpoint.withEnabled(saved.enabled);
                }
            }
            result.add(value);
        }
        for (Endpoint saved : stored) {
            if (!saved.builtin && saved.host.length() > 0) {
                result.add(saved);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static void setEndpoints(List<Endpoint> list) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        NovaPrivacySettings.global(context).setDohEndpoints(serialize(list));
        clearCache();
    }

    private static String storedBlob() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        try {
            return NovaPrivacySettings.global(context).getDohEndpoints();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Whether a list may be saved, as a message to show, or {@code null} when
     * it may. An empty list, or one whose only usable members are named
     * endpoints with nowhere to resolve them, would leave the client without a
     * resolver at all — and it must say so rather than quietly fall back to the
     * system, which is the one thing this class exists to prevent.
     */
    public static String validate(List<Endpoint> list, boolean russian) {
        int enabledBuiltin = 0;
        int enabledAny = 0;
        int namedWithoutAddress = 0;
        if (list != null) {
            for (Endpoint endpoint : list) {
                if (!endpoint.enabled) {
                    continue;
                }
                enabledAny++;
                if (endpoint.builtin) {
                    enabledBuiltin++;
                    continue;
                }
                boolean hasAddress = false;
                for (String address : endpoint.addresses) {
                    if (looksLikeAddress(address)) {
                        hasAddress = true;
                        break;
                    }
                }
                if (!hasAddress) {
                    namedWithoutAddress++;
                }
            }
        }
        if (enabledAny == 0) {
            return russian
                    ? "Нужен хотя бы один включённый сервер: иначе имена не будет кому "
                            + "разрешать, а к системному DNS NovaGram не обращается."
                    : "At least one server has to stay on: otherwise there is nobody to "
                            + "resolve names, and NovaGram never asks the system.";
        }
        if (namedWithoutAddress > 0 && enabledBuiltin == 0) {
            return russian
                    ? "Ваш сервер задан именем, а разрешить это имя нечем: оставьте "
                            + "включённым хотя бы один встроенный сервер или укажите "
                            + "IP-адрес своего."
                    : "Your server is given by name and there is nothing to resolve that "
                            + "name with: keep at least one built-in server on, or give "
                            + "your server an IP address.";
        }
        return null;
    }

    // --- storage --------------------------------------------------------

    private static String serialize(List<Endpoint> list) {
        StringBuilder builder = new StringBuilder(BLOB_VERSION);
        if (list != null) {
            for (Endpoint endpoint : list) {
                builder.append('\n')
                        .append(endpoint.host).append('|')
                        .append(endpoint.path).append('|')
                        .append(endpoint.builtin ? '1' : '0').append('|')
                        .append(endpoint.enabled ? '1' : '0').append('|');
                for (int i = 0; i < endpoint.addresses.size(); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(endpoint.addresses.get(i));
                }
            }
        }
        return builder.toString();
    }

    private static List<Endpoint> deserialize(String blob) {
        if (blob == null || blob.length() == 0) {
            return Collections.emptyList();
        }
        String[] lines = blob.split("\n");
        if (lines.length == 0 || !BLOB_VERSION.equals(lines[0])) {
            return Collections.emptyList();
        }
        List<Endpoint> result = new ArrayList<>();
        for (int i = 1; i < lines.length && result.size() < 64; i++) {
            String[] parts = lines[i].split("\\|", -1);
            if (parts.length != 5 || parts[0].length() == 0) {
                continue;
            }
            List<String> addresses = new ArrayList<>();
            if (parts[4].length() > 0) {
                for (String address : parts[4].split(",")) {
                    if (looksLikeAddress(address) && addresses.size() < 16) {
                        addresses.add(address);
                    }
                }
            }
            result.add(new Endpoint(
                    parts[0],
                    parts[1].length() > 0 ? parts[1] : "/dns-query",
                    addresses,
                    "1".equals(parts[2]),
                    "1".equals(parts[3])));
        }
        return result;
    }

    // --- resolving ------------------------------------------------------

    /** Cached answers only, no network. Never carries a {@code Date} header. */
    public static Answer cached(String host, int type) {
        if (host == null) {
            return new Answer(Collections.<String>emptyList(), 0, 0);
        }
        String key = cacheKey(host.toLowerCase(Locale.US), type);
        synchronized (CACHE_LOCK) {
            CacheEntry entry = CACHE.get(key);
            long now = SystemClock.elapsedRealtime();
            if (entry == null || entry.until < now) {
                return new Answer(Collections.<String>emptyList(), 0, 0);
            }
            return new Answer(entry.values, entry.until - now, 0);
        }
    }

    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            CACHE.clear();
            LIVE.clear();
        }
    }

    /** Resolves over the configured endpoints and nowhere else. Blocks. */
    public static Answer resolve(String host, int type) {
        return resolve(host, type, true);
    }

    /**
     * @param useCache pass {@code false} when the reply itself is the point
     *                 rather than what it says — a cached answer carries no
     *                 {@code Date} header, and the clock is read from that
     *                 header.
     */
    public static Answer resolve(String host, int type, boolean useCache) {
        if (host == null || host.length() == 0) {
            return new Answer(Collections.<String>emptyList(), 0, 0);
        }
        String lowered = host.toLowerCase(Locale.US);
        if (looksLikeAddress(lowered)) {
            // Already an address. Asking about it would be a question with a
            // known answer and a needless request to a stranger.
            return new Answer(Collections.singletonList(lowered), MAX_TTL_MS, 0);
        }
        final String key = cacheKey(lowered, type);
        boolean tooSoon = false;
        synchronized (CACHE_LOCK) {
            Long asked = LIVE.get(key);
            tooSoon = !useCache
                    && asked != null
                    && (SystemClock.elapsedRealtime() - asked) < MIN_LIVE_INTERVAL_MS;
        }
        if (useCache || tooSoon) {
            Answer ready = cached(lowered, type);
            if (!ready.isEmpty()) {
                return ready;
            }
        }
        synchronized (CACHE_LOCK) {
            if (LIVE.size() >= MAX_CACHE_ENTRIES) {
                LIVE.clear();
            }
            LIVE.put(key, SystemClock.elapsedRealtime());
        }

        long stamp = 0;
        for (Target target : plan(lowered)) {
            Answer answer = ask(target.endpoint, target.address, lowered, type);
            if (answer == null) {
                continue;
            }
            if (answer.httpUnixtime > 0) {
                // Kept across the walk so that a caller who asked for a live
                // reply still learns the time when every endpoint answers "no
                // such name": it wanted the reply, not the records.
                stamp = answer.httpUnixtime;
            }
            if (answer.isEmpty()) {
                continue;
            }
            store(lowered, type, answer);
            FileLog.d(LOG_PREFIX + "answer " + lowered + " -> " + answer.first()
                    + " ttl " + (answer.ttlMs / 1000));
            return new Answer(answer.values, answer.ttlMs, stamp);
        }
        FileLog.e(LOG_PREFIX + "all endpoints failed for " + lowered);
        return new Answer(Collections.<String>emptyList(), 0, stamp);
    }

    private static final class Target {
        final Endpoint endpoint;
        final String address;

        Target(Endpoint endpoint, String address) {
            this.endpoint = endpoint;
            this.address = address;
        }
    }

    /**
     * Every enabled endpoint paired with every address it answers on, in the
     * configured order — but every IPv4 address of every endpoint first, then
     * IPv6.
     *
     * <p>Interleaving them per endpoint, which is how they are written down,
     * means a device without IPv6 spends two timeouts inside each endpoint
     * before reaching the next one, and the list runs out before anything is
     * asked.</p>
     */
    private static List<Target> plan(String forHost) {
        List<Endpoint> configured = endpoints();
        List<Target> result = new ArrayList<>();
        boolean ipv6 = hasIpv6();
        for (int pass = 0; pass < (ipv6 ? 2 : 1); pass++) {
            boolean wantIpv6 = (pass == 1);
            for (Endpoint endpoint : configured) {
                if (!endpoint.enabled || endpoint.host.equalsIgnoreCase(forHost)) {
                    // Never ask an endpoint to resolve itself.
                    continue;
                }
                for (String address : addressesOf(endpoint, forHost)) {
                    if (looksLikeAddress(address) && (address.indexOf(':') >= 0) == wantIpv6) {
                        result.add(new Target(endpoint, address));
                    }
                }
            }
        }
        return result;
    }

    /**
     * The addresses an endpoint can be reached on. A built-in one carries its
     * own; one the owner added by name only is resolved through the enabled
     * built-in ones and only through them — which keeps the invariant intact,
     * because nothing here ever reaches the system resolver.
     */
    private static List<String> addressesOf(Endpoint endpoint, String forHost) {
        if (!endpoint.addresses.isEmpty() || endpoint.builtin) {
            return endpoint.addresses;
        }
        String host = endpoint.host.toLowerCase(Locale.US);
        // Guard against the obvious loop: an added endpoint is bootstrapped
        // through the built-ins, never through another added one, and never
        // while resolving its own name.
        if (host.equals(forHost)) {
            return Collections.emptyList();
        }
        Answer known = cached(host, TYPE_A);
        if (!known.isEmpty()) {
            return known.values;
        }
        for (Endpoint builtinEndpoint : builtin()) {
            if (!isEnabled(builtinEndpoint.host)) {
                continue;
            }
            for (String address : builtinEndpoint.addresses) {
                Answer answer = ask(builtinEndpoint, address, host, TYPE_A);
                if (answer != null && !answer.isEmpty()) {
                    store(host, TYPE_A, answer);
                    return answer.values;
                }
            }
        }
        return Collections.emptyList();
    }

    private static boolean isEnabled(String host) {
        for (Endpoint endpoint : endpoints()) {
            if (endpoint.host.equals(host)) {
                return endpoint.enabled;
            }
        }
        return false;
    }

    /**
     * Whether this device has a route to the IPv6 internet at all. Without the
     * check every IPv6 address in the list costs a full timeout before the next
     * address is tried.
     */
    private static boolean hasIpv6() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface item = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = item.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet6Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && !address.isSiteLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e, false);
        }
        return false;
    }

    private static void store(String host, int type, Answer answer) {
        if (answer.isEmpty() || answer.ttlMs <= 0) {
            return;
        }
        synchronized (CACHE_LOCK) {
            if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(cacheKey(host, type),
                    new CacheEntry(answer.values, SystemClock.elapsedRealtime() + answer.ttlMs));
        }
    }

    private static String cacheKey(String host, int type) {
        return type + ":" + host;
    }

    /**
     * One question to one address of one endpoint. Returns {@code null} when
     * the endpoint could not be reached at all, and an empty answer when it was
     * reached and said nothing useful — the caller tells them apart only to
     * decide whether it is worth carrying the clock forward.
     *
     * <p>The connection is opened on a literal address and the name travels in
     * SNI, in the certificate check and in the {@code Host} header. That is the
     * whole trick, and it is also the whole protection: what stops a network
     * from answering in the endpoint's place is the certificate checked against
     * the name, not the address it was reached on.</p>
     */
    private static Answer ask(Endpoint endpoint, String address, String host, int type) {
        byte[] query = buildQuery(host, type);
        if (query == null) {
            return null;
        }
        String where = endpoint.host + " [" + address + "]";
        SSLSocket ssl = null;
        try {
            FileLog.d(LOG_PREFIX + "query " + host + "/" + typeName(type) + " via " + where);
            ssl = NovaHttps.connect(endpoint.host, address, proxyFor(), TIMEOUT_MS);
            Map<String, String> headers = NovaHttps.headers();
            headers.put("Accept", "application/dns-message");
            headers.put("Content-Type", "application/dns-message");
            headers.put("Content-Length", String.valueOf(query.length));
            OutputStream out = ssl.getOutputStream();
            out.write(NovaHttps.head("POST", endpoint.host, endpoint.path, headers));
            out.write(query);
            out.flush();

            NovaHttps.Reader reader = NovaHttps.open(ssl.getInputStream());
            long stamp = parseHttpDate(reader.response.date);
            if (reader.response.code != 200) {
                FileLog.e(LOG_PREFIX + "endpoint " + where
                        + " failed: http " + reader.response.code);
                return stamp > 0
                        ? new Answer(Collections.<String>emptyList(), 0, stamp)
                        : null;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            reader.drain(body, MAX_ANSWER_BYTES, 0, null, null);
            Answer parsed = parseResponse(body.toByteArray(), type);
            return new Answer(parsed.values, parsed.ttlMs, stamp);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "endpoint " + where + " failed: " + e);
            return null;
        } finally {
            NovaHttps.close(ssl);
        }
    }

    /**
     * The proxy the user chose for Telegram, whenever it is one a plain HTTPS
     * request can use — the same policy the update check follows, because an
     * endpoint is as unreachable as anything else in a network that needs a
     * proxy.
     */
    public static Proxy proxyFor() {
        try {
            if (!SharedConfig.isProxyEnabled()) {
                return Proxy.NO_PROXY;
            }
            SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            if (info == null
                    || info.address == null
                    || info.address.length() == 0
                    || (info.secret != null && info.secret.length() > 0)) {
                // A secret means MTProto, which speaks Telegram's protocol and
                // nothing else.
                return Proxy.NO_PROXY;
            }
            if (!looksLikeAddress(info.address)) {
                // The proxy is named and its name is not known yet. Handing it
                // over like this would have the system resolve it, which is the
                // one thing this class exists to prevent — so this request goes
                // without it. Not a fall-back: it breaks the circle in which
                // the resolver needs the proxy and the proxy needs the
                // resolver. Where the only way out really is that proxy, the
                // request fails and the client says so; the answer to that is
                // to give the proxy by address, and then nothing needs
                // resolving at all.
                String known = cached(info.address.toLowerCase(Locale.US), TYPE_A).first();
                if (known == null) {
                    return Proxy.NO_PROXY;
                }
                return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(known, info.port));
            }
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(info.address, info.port));
        } catch (Throwable e) {
            FileLog.e(e);
            return Proxy.NO_PROXY;
        }
    }

    // --- RFC 8484 wire format -------------------------------------------
    //
    // A minimal codec on purpose. The JSON APIs are not an option: only two of
    // the four endpoints have one, and Google's lives on a different path — the
    // single thing all four accept is a binary POST of a DNS message.

    static byte[] buildQuery(String host, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Id zero on purpose: over HTTPS it buys nothing, and a constant one
        // keeps identical queries byte-identical, which is what lets a proxy
        // cache them.
        push16(out, 0);
        push16(out, 0x0100); // Recursion desired.
        push16(out, 1); // One question.
        push16(out, 0);
        push16(out, 0);
        push16(out, 0);
        for (String label : host.split("\\.")) {
            if (label.length() == 0) {
                continue;
            }
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (bytes.length > 63) {
                return null;
            }
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        push16(out, type);
        push16(out, 1); // IN.
        return out.toByteArray();
    }

    private static void push16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Walks a name at {@code offset[0]}, following compression pointers, and
     * leaves {@code offset[0]} after the name as it appears here. Returns false
     * on anything malformed — a hostile answer must end the parse, not steer
     * it.
     */
    static boolean skipName(byte[] data, int[] offset) {
        // A pointer sends the reader backwards, so the position to continue
        // from is the one right after the FIRST pointer, not wherever the chain
        // ends. The guard is what keeps a name that points at itself from
        // hanging the parse — the data here comes from a stranger.
        int guard = 0;
        int position = offset[0];
        int after = -1;
        while (position >= 0 && position < data.length) {
            int length = data[position] & 0xFF;
            if (length == 0) {
                offset[0] = (after >= 0) ? after : (position + 1);
                return true;
            } else if ((length & 0xC0) == 0xC0) {
                if (position + 1 >= data.length || ++guard > 32) {
                    return false;
                }
                if (after < 0) {
                    after = position + 2;
                }
                position = ((length & 0x3F) << 8) | (data[position + 1] & 0xFF);
            } else if (length > 63) {
                return false;
            } else {
                position += 1 + length;
            }
        }
        return false;
    }

    static Answer parseResponse(byte[] data, int type) {
        List<String> values = new ArrayList<>();
        if (data == null || data.length < 12) {
            return new Answer(values, 0, 0);
        }
        int flags = read16(data, 2);
        if ((flags & 0x000F) != 0) {
            // Anything but NOERROR: an answer that says "no" is still an
            // answer, and the caller must not read a failure as "try the
            // system instead".
            return new Answer(values, 0, 0);
        }
        int questions = read16(data, 4);
        int answers = read16(data, 6);
        int[] offset = new int[] { 12 };
        for (int i = 0; i < questions; i++) {
            if (!skipName(data, offset) || offset[0] + 4 > data.length) {
                return new Answer(values, 0, 0);
            }
            offset[0] += 4;
        }
        long ttl = 0;
        for (int i = 0; i < answers; i++) {
            if (!skipName(data, offset) || offset[0] + 10 > data.length) {
                break;
            }
            int at = offset[0];
            int recordType = read16(data, at);
            long recordTtl = ((long) (data[at + 4] & 0xFF) << 24)
                    | ((long) (data[at + 5] & 0xFF) << 16)
                    | ((long) (data[at + 6] & 0xFF) << 8)
                    | (long) (data[at + 7] & 0xFF);
            int length = read16(data, at + 8);
            at += 10;
            if (at + length > data.length) {
                break;
            }
            if (recordType == type) {
                if (type == TYPE_TXT) {
                    ByteArrayOutputStream text = new ByteArrayOutputStream();
                    int piece = at;
                    while (piece < at + length) {
                        int size = data[piece] & 0xFF;
                        if (piece + 1 + size > at + length) {
                            break;
                        }
                        text.write(data, piece + 1, size);
                        piece += 1 + size;
                    }
                    if (text.size() > 0) {
                        values.add(new String(text.toByteArray(),
                                java.nio.charset.StandardCharsets.ISO_8859_1));
                    }
                } else {
                    String address = addressFromRecord(data, at, length, recordType);
                    if (address != null) {
                        values.add(address);
                    }
                }
                long asMillis = recordTtl * 1000L;
                if (ttl == 0 || asMillis < ttl) {
                    ttl = asMillis;
                }
            }
            offset[0] = at + length;
        }
        if (ttl == 0) {
            ttl = MIN_TTL_MS;
        }
        return new Answer(values, Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, ttl)), 0);
    }

    private static int read16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static String addressFromRecord(byte[] data, int offset, int length, int type) {
        if (type == TYPE_A && length == 4) {
            return (data[offset] & 0xFF) + "." + (data[offset + 1] & 0xFF) + "."
                    + (data[offset + 2] & 0xFF) + "." + (data[offset + 3] & 0xFF);
        } else if (type == TYPE_AAAA && length == 16) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) {
                    builder.append(':');
                }
                builder.append(Integer.toHexString(read16(data, offset + i * 2)));
            }
            return builder.toString();
        }
        return null;
    }

    static String typeName(int type) {
        switch (type) {
            case TYPE_A: return "A";
            case TYPE_AAAA: return "AAAA";
            case TYPE_TXT: return "TXT";
            default: return String.valueOf(type);
        }
    }

    /**
     * The {@code Date} header of an endpoint reply, in seconds since the epoch,
     * or zero if there is nothing believable there.
     *
     * <p>The sanity window matters more than the parse: this value goes on to
     * correct the clock of the whole client, and a header that is decades off —
     * from a broken cache, a captive portal or a deliberately lying middlebox —
     * would take message ordering and key expiry with it.</p>
     */
    static long parseHttpDate(String value) {
        if (value == null || value.length() == 0) {
            return 0;
        }
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            java.util.Date parsed = format.parse(value);
            if (parsed == null) {
                return 0;
            }
            long seconds = parsed.getTime() / 1000L;
            return (seconds >= 1735689600L && seconds <= 2524608000L) ? seconds : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Same rules as the rest of the call stack uses, and for the same reason:
     * what must be impossible is a name reaching a resolver by accident.
     */
    public static boolean looksLikeAddress(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            int colons = 0;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == ':') {
                    colons++;
                } else if (ch != '.'
                        && !(ch >= '0' && ch <= '9')
                        && !(ch >= 'a' && ch <= 'f')
                        && !(ch >= 'A' && ch <= 'F')) {
                    return false;
                }
            }
            return colons >= 2 && colons <= 7;
        }
        int groups = 0;
        int digits = 0;
        int number = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '.') {
                if (digits == 0 || ++groups > 3) {
                    return false;
                }
                digits = 0;
                number = 0;
            } else if (ch >= '0' && ch <= '9') {
                if (++digits > 3) {
                    return false;
                }
                number = number * 10 + (ch - '0');
                if (number > 255) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return groups == 3 && digits > 0;
    }

}
