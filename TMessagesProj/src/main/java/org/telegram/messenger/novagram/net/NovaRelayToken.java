package org.telegram.messenger.novagram.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.novagram.privacy.NovaDecoyState;
import org.telegram.messenger.novagram.privacy.NovaDeviceKeyStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/**
 * The per-install token for our own Cloudflare Worker (the last rung of
 * {@link NovaWss}). The issuer on nova-app.eu hands the install a credential
 * once, after a small proof of work, and then short-lived tokens against an
 * HMAC of that credential.
 *
 * <p>Nothing here ever blocks a caller: {@link #cached()} answers from memory
 * and, when a token is missing or near its end, starts one background worker.
 * Every failure backs off (6 h for enrolment, 10 min for a token) and is
 * logged once; nothing loops.</p>
 *
 * <p>The credential is device-level, not account-level. It is sealed with
 * AES/GCM under the Keystore key of {@link NovaDeviceKeyStore}, tagged with the
 * purpose as associated data, and lives in {@code files/novagram_wss/relay.cred}.</p>
 */
final class NovaRelayToken {
    /** The issuer, in the order tried: the new name first, the old one as a fallback. */
    private static final String[] ISSUERS = {
            "https://relay-tg.nova-app.eu/relay",
            "https://nova-app.eu/relay",
    };
    private static final String PREFERENCES = "novagram_wss";
    private static final String KEY_ENROLL_AFTER = "relay_enroll_after";
    private static final String KEY_ENROLLED_AT = "relay_enrolled_at";
    private static final String CRED_FILE = "relay.cred";
    private static final byte[] AAD = "novagram.relay.cred.v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte MAGIC = 1;

    private static final int HTTP_TIMEOUT_MS = 15000;
    private static final int MAX_BODY = 16 * 1024;
    private static final int MAX_POW_BITS = 26;
    private static final long POW_BUDGET_MS = 60L * 1000L;
    private static final long REFRESH_BEFORE_S = 10L * 60L;
    private static final long USABLE_FOR_S = 60L;
    private static final long ENROLL_BACKOFF_MS = 6L * 3600L * 1000L;
    private static final long REENROLL_GAP_MS = 24L * 3600L * 1000L;
    private static final long TOKEN_BACKOFF_MS = 10L * 60L * 1000L;

    private static final Object LOCK = new Object();
    private static String token;
    private static long tokenExp; // unix seconds
    private static boolean running;
    private static long tokenRetryAt; // SystemClock.elapsedRealtime()
    /** The issuer that answered last in this process; tried first. */
    private static volatile int issuerFirst;

    private NovaRelayToken() {
    }

    /** A token good for at least a minute, or null; may start a background fetch. Never blocks. */
    static String cached() {
        String result = null;
        boolean refresh;
        long now = System.currentTimeMillis() / 1000L;
        synchronized (LOCK) {
            if (token != null && tokenExp - now > USABLE_FOR_S) {
                result = token;
            }
            refresh = token == null || tokenExp - now < REFRESH_BEFORE_S;
        }
        if (refresh) {
            prefetch();
        }
        return result;
    }

    /** Whether a usable token is held right now; starts nothing. */
    static boolean has() {
        synchronized (LOCK) {
            return token != null && tokenExp - System.currentTimeMillis() / 1000L > USABLE_FOR_S;
        }
    }

    /** The relay refused this token: forget it so the next fetch replaces it. */
    static void invalidate(String used) {
        synchronized (LOCK) {
            if (used != null && used.equals(token)) {
                token = null;
                tokenExp = 0;
            }
        }
    }

    /** Starts at most one background fetch, if the setting is on and no back-off is running. */
    static void prefetch() {
        if (!NovaWss.isEnabled() || NovaDecoyState.isActive() || NovaWss.onVpn()) {
            return;
        }
        synchronized (LOCK) {
            if (running || SystemClock.elapsedRealtime() < tokenRetryAt) {
                return;
            }
            running = true;
        }
        Thread thread = new Thread(() -> {
            try {
                work();
            } catch (Throwable e) {
                NovaWss.log("relay token: " + e);
                synchronized (LOCK) {
                    tokenRetryAt = SystemClock.elapsedRealtime() + TOKEN_BACKOFF_MS;
                }
            } finally {
                synchronized (LOCK) {
                    running = false;
                }
            }
        }, "NovaRelayToken");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // --- the work -----------------------------------------------------------

    private static void work() throws Exception {
        String[] cred = readCredential();
        if (cred == null) {
            cred = enroll();
            if (cred == null) {
                return;
            }
        }
        JSONObject answer = new JSONObject();
        long ts = System.currentTimeMillis() / 1000L;
        answer.put("id", cred[0]);
        answer.put("ts", ts);
        answer.put("mac", mac(cred[1], cred[0], ts));
        Result result = post("/token", answer.toString());
        if (result.code == 403) {
            // The issuer no longer knows this install (revoked or reset).
            NovaWss.log("relay credential refused, dropped");
            deleteCredential();
            long gap = ENROLL_BACKOFF_MS;
            long enrolledAt = prefs().getLong(KEY_ENROLLED_AT, 0);
            long now = System.currentTimeMillis();
            long after = enrolledAt + REENROLL_GAP_MS;
            prefs().edit().putLong(KEY_ENROLL_AFTER, Math.max(after, enrolledAt == 0 ? now + gap : 0)).apply();
            synchronized (LOCK) {
                tokenRetryAt = SystemClock.elapsedRealtime() + TOKEN_BACKOFF_MS;
            }
            return;
        }
        if (result.code != 200) {
            throw new IllegalStateException("token http " + result.code);
        }
        JSONObject body = new JSONObject(result.body);
        String issued = body.getString("token");
        long exp = body.getLong("exp");
        if (!issued.startsWith("nova2.") || issued.length() > 2048) {
            throw new IllegalStateException("token malformed");
        }
        synchronized (LOCK) {
            token = issued;
            tokenExp = exp;
        }
        NovaWss.log("relay token fetched");
    }

    private static String[] enroll() throws Exception {
        long now = System.currentTimeMillis();
        if (now < prefs().getLong(KEY_ENROLL_AFTER, 0)) {
            return null;
        }
        try {
            Result challenge = get("/challenge");
            if (challenge.code != 200) {
                throw new IllegalStateException("challenge http " + challenge.code);
            }
            JSONObject ch = new JSONObject(challenge.body);
            String c = ch.getString("c");
            int d = ch.getInt("d");
            if (d < 0 || d > MAX_POW_BITS) {
                throw new IllegalStateException("proof of work too dear: " + d);
            }
            long n = solve(c, d, SystemClock.elapsedRealtime() + POW_BUDGET_MS);
            if (n < 0) {
                throw new IllegalStateException("proof of work out of time");
            }
            JSONObject request = new JSONObject();
            request.put("c", c);
            request.put("e", ch.getInt("e"));
            request.put("d", d);
            request.put("m", ch.getString("m"));
            request.put("n", n);
            Result result = post("/enroll", request.toString());
            if (result.code != 200) {
                throw new IllegalStateException("enroll http " + result.code);
            }
            JSONObject got = new JSONObject(result.body);
            String id = got.getString("id");
            String secret = got.getString("secret");
            if (id.isEmpty() || secret.isEmpty()) {
                throw new IllegalStateException("enroll answer empty");
            }
            writeCredential(id, secret);
            prefs().edit().putLong(KEY_ENROLLED_AT, System.currentTimeMillis())
                    .putLong(KEY_ENROLL_AFTER, 0).apply();
            NovaWss.log("relay enrolled");
            return new String[]{id, secret};
        } catch (Throwable e) {
            prefs().edit().putLong(KEY_ENROLL_AFTER, System.currentTimeMillis() + ENROLL_BACKOFF_MS).apply();
            NovaWss.log("relay enrol failed, next try in 6 h: " + e);
            return null;
        }
    }

    // --- the arithmetic (package-visible for the throwaway checks) ----------

    /** Lowercase hex HMAC-SHA256, key = the UTF-8 bytes of the secret text as received. */
    static String mac(String secret, String id, long ts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(("token|" + id + "|" + ts).getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[out.length * 2];
        for (int i = 0; i < out.length; i++) {
            hex[2 * i] = Character.forDigit((out[i] >> 4) & 15, 16);
            hex[2 * i + 1] = Character.forDigit(out[i] & 15, 16);
        }
        return new String(hex);
    }

    /** Whether the first {@code bits} bits of {@code digest} are zero. */
    static boolean zeroBits(byte[] digest, int bits) {
        int full = bits >> 3;
        for (int i = 0; i < full; i++) {
            if (digest[i] != 0) {
                return false;
            }
        }
        int rest = bits & 7;
        return rest == 0 || (digest[full] & (0xff << (8 - rest))) == 0;
    }

    /** Smallest n >= 0 with SHA256("c|n") having {@code bits} leading zero bits; -1 past the deadline. */
    static long solve(String c, int bits, long deadlineElapsed) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] prefix = (c + "|").getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[prefix.length + 20];
        System.arraycopy(prefix, 0, buffer, 0, prefix.length);
        byte[] digest = new byte[32];
        for (long n = 0; ; n++) {
            int end = buffer.length;
            long rest = n;
            do {
                buffer[--end] = (byte) ('0' + rest % 10);
                rest /= 10;
            } while (rest != 0);
            // The digits sit at the end of the buffer; the prefix is hashed first.
            sha.update(prefix, 0, prefix.length);
            sha.update(buffer, end, buffer.length - end);
            sha.digest(digest, 0, 32);
            if (zeroBits(digest, bits)) {
                return n;
            }
            if ((n & 0x3fff) == 0x3fff && SystemClock.elapsedRealtime() > deadlineElapsed) {
                return -1;
            }
        }
    }

    // --- http ---------------------------------------------------------------

    private static final class Result {
        int code;
        String body = "";
    }

    private static Result get(String path) throws Exception {
        return request("GET", path, null);
    }

    private static Result post(String path, String json) throws Exception {
        return request("POST", path, json);
    }

    /**
     * Tries the issuers in order, the last one that answered first. Only a
     * failure to connect (resolve, connect, timeout, reset) moves on to the
     * next; any HTTP answer, 4xx included, is the issuer's word and is returned.
     */
    private static Result request(String method, String path, String json) throws Exception {
        int first = issuerFirst;
        Exception last = null;
        for (int i = 0; i < ISSUERS.length; i++) {
            int which = (first + i) % ISSUERS.length;
            try {
                Result result = requestOne(ISSUERS[which], method, path, json);
                issuerFirst = which;
                return result;
            } catch (Exception e) {
                last = e;
                NovaWss.log("relay issuer " + ISSUERS[which] + " unreachable: " + e);
            }
        }
        throw last;
    }

    private static Result requestOne(String base, String method, String path, String json) throws Exception {
        java.net.URI uri = java.net.URI.create(base);
        String host = uri.getHost();
        String fullPath = uri.getPath() + path;
        NovaDoh.Answer answer = NovaDoh.resolve(host, NovaDoh.TYPE_A);
        if (answer.isEmpty()) {
            throw new IllegalStateException("could not resolve " + host + " over secure DNS");
        }
        SSLSocket socket = null;
        try {
            socket = NovaHttps.connect(host, answer.first(), Proxy.NO_PROXY, HTTP_TIMEOUT_MS);
            socket.setSoTimeout(HTTP_TIMEOUT_MS);
            Map<String, String> headers = NovaHttps.headers();
            headers.put("User-Agent", "NovaGram");
            headers.put("Accept", "application/json");
            byte[] data = null;
            if (json != null) {
                data = json.getBytes(StandardCharsets.UTF_8);
                headers.put("Content-Type", "application/json");
                headers.put("Content-Length", String.valueOf(data.length));
            }
            OutputStream out = socket.getOutputStream();
            out.write(NovaHttps.head(method, host, fullPath, headers));
            if (data != null) {
                out.write(data);
            }
            out.flush();
            NovaHttps.Reader reader = NovaHttps.open(socket.getInputStream());
            Result result = new Result();
            result.code = reader.response.code;
            if (result.code == 200) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                reader.drain(body, MAX_BODY, 0, null, null);
                result.body = new String(body.toByteArray(), StandardCharsets.UTF_8);
            }
            return result;
        } finally {
            NovaHttps.close(socket);
        }
    }

    // --- the sealed credential ----------------------------------------------

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static File credFile() {
        return new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "novagram_wss"), CRED_FILE);
    }

    private static void deleteCredential() {
        credFile().delete();
    }

    private static void writeCredential(String id, String secret) throws Exception {
        JSONObject plain = new JSONObject();
        plain.put("id", id);
        plain.put("secret", secret);
        SecretKey key = NovaDeviceKeyStore.getOrCreate();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[2 + iv.length + sealed.length];
        blob[0] = MAGIC;
        blob[1] = (byte) iv.length;
        System.arraycopy(iv, 0, blob, 2, iv.length);
        System.arraycopy(sealed, 0, blob, 2 + iv.length, sealed.length);
        File target = credFile();
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("cannot create " + dir);
        }
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(blob);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            throw new java.io.IOException("cannot replace " + target);
        }
    }

    /** {id, secret}, or null when nothing readable is stored (a broken file is dropped). */
    private static String[] readCredential() {
        File file = credFile();
        if (!file.isFile() || file.length() > 4096) {
            return null;
        }
        try {
            byte[] blob = new byte[(int) file.length()];
            try (InputStream in = new FileInputStream(file)) {
                int have = 0;
                while (have < blob.length) {
                    int read = in.read(blob, have, blob.length - have);
                    if (read < 0) {
                        break;
                    }
                    have += read;
                }
                if (have != blob.length) {
                    throw new java.io.IOException("short read");
                }
            }
            int ivLength = blob[1] & 0xff;
            if (blob[0] != MAGIC || ivLength != 12 || blob.length < 2 + ivLength + 16) {
                throw new java.io.IOException("bad container");
            }
            SecretKey key = NovaDeviceKeyStore.getExisting();
            if (key == null) {
                throw new java.io.IOException("device key gone");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 2, ivLength));
            cipher.updateAAD(AAD);
            byte[] plain = cipher.doFinal(blob, 2 + ivLength, blob.length - 2 - ivLength);
            JSONObject object = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            return new String[]{object.getString("id"), object.getString("secret")};
        } catch (Throwable e) {
            NovaWss.log("relay credential unreadable, dropped: " + e);
            file.delete();
            return null;
        }
    }
}
