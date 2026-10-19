package org.telegram.messenger.novagram.net;

import android.os.SystemClock;

import org.telegram.messenger.FileLog;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/**
 * The loopback listener that tgnet's sockets are pointed at while
 * {@link NovaWss} is on. Each accepted connection carries one obfuscated MTProto
 * stream: this reads the 64-byte obfuscation header to learn which data centre
 * it is for, walks the ladder of {@link NovaWss#attempt}, opens the WebSocket
 * and pumps bytes both ways.
 *
 * <p>Nothing here changes what is sent: the bytes the native side wrote are the
 * bytes that travel inside the frames, and the bytes in the server's frames are
 * the bytes handed back. A relay sees the same stream a TCP path would.</p>
 */
final class NovaWssBridge {
    // Header names, order and values as Yandex Browser writes them
    // (kb/censorship-transport.md); no Sec-CH-UA and no Sec-Fetch-*, those
    // belong to the page and not to the upgrade.
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 YaBrowser/26.10.0.0 Safari/537.36";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int ROUTE_TIMEOUT_MS = 3500;
    private static final int ROUTES_PER_ATTEMPT = 2;
    private static final int HEADER_WAIT_MS = 15000;
    private static final int FIRST_PACKET_WAIT_MS = 60;
    private static final int MAX_HANDSHAKE = 16 * 1024;
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int CHUNK = 16 * 1024;

    private static final Object LOCK = new Object();
    private static ServerSocket server;
    private static volatile int port;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Per-process secret every local client must send first; only tgnet in this process knows it. */
    private static final byte[] TOKEN = new byte[16];

    static {
        RANDOM.nextBytes(TOKEN);
    }

    static byte[] token() {
        return TOKEN.clone();
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "NovaWss");
            thread.setDaemon(true);
            return thread;
        }
    });

    private NovaWssBridge() {
    }

    static int port() {
        return port;
    }

    /** Starts the listener on first use; returns its port, or 0 when it cannot run. */
    static int ensureStarted() {
        synchronized (LOCK) {
            if (server != null && !server.isClosed()) {
                return port;
            }
            try {
                server = new ServerSocket(0, 64, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
                port = server.getLocalPort();
                final ServerSocket listening = server;
                POOL.execute(() -> acceptLoop(listening));
                NovaWss.log("bridge listening on 127.0.0.1:" + port);
            } catch (Throwable e) {
                FileLog.e(e, false);
                server = null;
                port = 0;
            }
            return port;
        }
    }

    private static void acceptLoop(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                final Socket client = listening.accept();
                POOL.execute(() -> serve(client));
            } catch (Throwable e) {
                if (listening.isClosed()) {
                    return;
                }
                FileLog.e(e, false);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /**
     * Splits the client's stream into transport packets, one WebSocket frame
     * each. The relay takes one packet per frame, as a browser sends them:
     * tgnet writes an acknowledgement and the next request back to back, and
     * the relay answers only the first of two packets that share a frame. The
     * stream is already encrypted, so a copy of the same AES-CTR keystream is
     * kept here just to read the length prefixes; the bytes sent on are the
     * original ones.
     */
    private static final class Splitter {
        final Cipher cipher;
        final int tag;
        final int dc;
        byte[] enc = new byte[2 * CHUNK];
        byte[] dec = new byte[2 * CHUNK];
        int length;

        Splitter(byte[] header) throws Exception {
            byte[] key = new byte[32];
            byte[] iv = new byte[16];
            System.arraycopy(header, 8, key, 0, 32);
            System.arraycopy(header, 40, iv, 0, 16);
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.update(header, 0, 64);
            int t = plain[56] & 0xff;
            boolean good = plain[57] == plain[56] && plain[58] == plain[56] && plain[59] == plain[56]
                    && (t == 0xef || t == 0xdd || t == 0xee);
            tag = good ? t : 0;
            // The data centre named in the header: negative for media, 0 if it is not a header.
            dc = good ? (short) ((plain[60] & 0xff) | ((plain[61] & 0xff) << 8)) : 0;
        }

        void feed(byte[] data, int offset, int count) {
            if (length + count > enc.length) {
                int size = Math.max(enc.length * 2, length + count);
                enc = java.util.Arrays.copyOf(enc, size);
                dec = java.util.Arrays.copyOf(dec, size);
            }
            System.arraycopy(data, offset, enc, length, count);
            byte[] plain = cipher.update(data, offset, count);
            System.arraycopy(plain, 0, dec, length, count);
            length += count;
        }

        /** Size of the packet at the front of the buffer, 0 if it is not complete yet. */
        int nextSize() throws IOException {
            if (length < 1) {
                return 0;
            }
            long total;
            if (tag == 0xef) {
                int first = dec[0] & 0x7f;
                if (first < 0x7f) {
                    total = 1 + first * 4L;
                } else {
                    if (length < 4) {
                        return 0;
                    }
                    total = 4 + ((dec[1] & 0xffL) | ((dec[2] & 0xffL) << 8) | ((dec[3] & 0xffL) << 16)) * 4L;
                }
            } else {
                if (length < 4) {
                    return 0;
                }
                total = 4 + (((dec[0] & 0xffL) | ((dec[1] & 0xffL) << 8) | ((dec[2] & 0xffL) << 16)
                        | ((dec[3] & 0xffL) << 24)) & 0x7fffffffL);
            }
            if (total > 8L * 1024 * 1024) {
                throw new IOException("packet too large: " + total);
            }
            return total <= length ? (int) total : 0;
        }

        void drop(int count) {
            System.arraycopy(enc, count, enc, 0, length - count);
            System.arraycopy(dec, count, dec, 0, length - count);
            length -= count;
        }
    }

    private static void serve(Socket client) {
        Ws ws = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(HEADER_WAIT_MS);
            InputStream in = client.getInputStream();
            byte[] buffer = new byte[CHUNK];
            byte[] presented = new byte[TOKEN.length];
            int got = 0;
            while (got < presented.length) {
                int read = in.read(presented, got, presented.length - got);
                if (read < 0) {
                    return;
                }
                got += read;
            }
            if (!java.security.MessageDigest.isEqual(presented, TOKEN)) {
                NovaWss.log("local client without the process token, dropped");
                return;
            }
            byte[] header = new byte[64];
            int have = 0;
            while (have < 64) {
                int read = in.read(header, have, 64 - have);
                if (read < 0) {
                    return;
                }
                have += read;
            }
            Splitter splitter = new Splitter(header);
            if (!NovaWss.supports(splitter.dc)) {
                NovaWss.log("stream with data centre " + splitter.dc + " cannot be carried, dropped");
                return;
            }
            // The first frame carries the whole header and the first packet: a
            // relay that gets less answers nothing at all.
            int size;
            while ((size = splitter.nextSize()) == 0) {
                int read = in.read(buffer);
                if (read < 0) {
                    return;
                }
                splitter.feed(buffer, 0, read);
            }
            client.setSoTimeout(0);
            ws = connectAny(splitter.dc);
            if (ws == null) {
                return;
            }
            byte[] first = new byte[64 + size];
            System.arraycopy(header, 0, first, 0, 64);
            System.arraycopy(splitter.enc, 0, first, 64, size);
            splitter.drop(size);
            ws.sendBinary(first, 0, first.length);
            final Ws opened = ws;
            final Socket target = client;
            POOL.execute(() -> {
                try {
                    opened.pumpToClient(target.getOutputStream());
                } catch (Throwable e) {
                    NovaWss.log("down end: " + e);
                } finally {
                    opened.close();
                    NovaHttps.close(target);
                }
            });
            while (true) {
                while ((size = splitter.nextSize()) != 0) {
                    ws.sendBinary(splitter.enc, 0, size);
                    splitter.drop(size);
                }
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                splitter.feed(buffer, 0, read);
            }
        } catch (Throwable e) {
            // Any end of either side ends the stream; tgnet reconnects.
            NovaWss.log("up end: " + e);
        } finally {
            if (ws != null) {
                ws.close();
            }
            NovaHttps.close(client);
        }
    }

    private static Ws connectAny(int dc) {
        List<NovaWss.Route> routes = NovaWss.attempt(dc, ROUTES_PER_ATTEMPT);
        for (NovaWss.Route route : routes) {
            long deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS;
            try {
                Ws ws = open(route, deadline);
                NovaWss.noteWorked(dc, route);
                NovaWss.log("dc " + dc + " open over " + route.host
                        + (route.index == 0 ? " (official relay)" : " (mirror)"));
                return ws;
            } catch (Throwable e) {
                NovaWss.log("dc " + dc + " route " + route.host + " failed: " + e);
                NovaWss.noteFailed(dc, route);
            }
        }
        return null;
    }

    private static int left(long deadline) throws IOException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 100) {
            throw new SocketTimeoutException("route timeout");
        }
        return (int) remaining;
    }

    private static Ws open(NovaWss.Route route, long deadline) throws Exception {
        String ip = route.ip;
        if (ip == null || ip.isEmpty()) {
            // Never the system resolver (I5).
            NovaDoh.Answer answer = NovaDoh.resolve(route.host, NovaDoh.TYPE_A);
            if (answer.isEmpty()) {
                throw new IOException("could not resolve " + route.host + " over secure DNS");
            }
            ip = answer.first();
        }
        SSLSocket socket = NovaHttps.connect(route.host, ip, Proxy.NO_PROXY, left(deadline));
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(left(deadline));
            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            String key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);
            // Header names and their order as the browser writes them. The offer
            // of permessage-deflate is the browser's too; the relays ignore it,
            // and a server that accepted it would be refused, since no inflater
            // is here.
            String request = "GET " + route.path + " HTTP/1.1\r\nHost: " + route.host
                    + "\r\nConnection: Upgrade\r\nPragma: no-cache\r\nCache-Control: no-cache\r\n"
                    + "User-Agent: " + USER_AGENT
                    + "\r\nUpgrade: websocket\r\nOrigin: https://web.telegram.org\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Accept-Encoding: gzip, deflate, br, zstd\r\n"
                    + "Accept-Language: ru,en;q=0.9\r\n"
                    + "Sec-WebSocket-Key: " + key
                    + "\r\nSec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n"
                    + "Sec-WebSocket-Protocol: binary\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("closed during the upgrade");
                }
                head.write(b);
                if (head.size() > MAX_HANDSHAKE) {
                    throw new IOException("upgrade answer too long");
                }
                if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) {
                    state++;
                } else {
                    state = (b == '\r') ? 1 : 0;
                }
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r?\n");
            if (lines.length == 0 || !lines[0].startsWith("HTTP/1.1 101")) {
                throw new IOException("refused the upgrade: "
                        + (lines.length == 0 ? "" : lines[0].substring(0, Math.min(80, lines[0].length()))));
            }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String expected = android.util.Base64.encodeToString(
                    sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII)),
                    android.util.Base64.NO_WRAP);
            boolean accepted = false;
            boolean extended = false;
            for (String line : lines) {
                String lower = line.toLowerCase(java.util.Locale.US);
                if (lower.startsWith("sec-websocket-accept:")) {
                    accepted = line.substring(line.indexOf(':') + 1).trim().equals(expected);
                } else if (lower.startsWith("sec-websocket-extensions:")) {
                    extended = true;
                }
            }
            if (!accepted || extended) {
                throw new IOException("bad upgrade answer");
            }
            socket.setSoTimeout(0);
            return new Ws(socket, in, out);
        } catch (Throwable e) {
            NovaHttps.close(socket);
            if (e instanceof Exception) {
                throw (Exception) e;
            }
            throw new Exception(e);
        }
    }

    /** One open WebSocket (RFC 6455, binary frames only). */
    private static final class Ws {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private volatile boolean closed;

        Ws(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void close() {
            closed = true;
            NovaHttps.close(socket);
        }

        void sendBinary(byte[] data, int offset, int length) throws IOException {
            sendFrame(0x2, data, offset, length);
        }

        private void sendFrame(int opcode, byte[] data, int offset, int length) throws IOException {
            byte[] frame = new byte[length + 14];
            int p = 0;
            frame[p++] = (byte) (0x80 | opcode);
            if (length < 126) {
                frame[p++] = (byte) (0x80 | length);
            } else if (length < 65536) {
                frame[p++] = (byte) (0x80 | 126);
                frame[p++] = (byte) (length >> 8);
                frame[p++] = (byte) length;
            } else {
                frame[p++] = (byte) (0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame[p++] = (byte) (((long) length) >> (8 * i));
                }
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            System.arraycopy(mask, 0, frame, p, 4);
            p += 4;
            for (int i = 0; i < length; i++) {
                frame[p + i] = (byte) (data[offset + i] ^ mask[i & 3]);
            }
            synchronized (writeLock) {
                out.write(frame, 0, p + length);
                out.flush();
            }
        }

        private void readFully(byte[] buffer, int length) throws IOException {
            int have = 0;
            while (have < length) {
                int read = in.read(buffer, have, length - have);
                if (read < 0) {
                    throw new IOException("closed");
                }
                have += read;
            }
        }

        /** Hands the payload of the server's frames to the client until the stream ends. */
        void pumpToClient(OutputStream client) throws IOException {
            byte[] head = new byte[8];
            while (!closed) {
                readFully(head, 2);
                int opcode = head[0] & 0x0F;
                boolean masked = (head[1] & 0x80) != 0;
                long length = head[1] & 0x7F;
                if (length == 126) {
                    readFully(head, 2);
                    length = ((head[0] & 0xff) << 8) | (head[1] & 0xff);
                } else if (length == 127) {
                    byte[] eight = new byte[8];
                    readFully(eight, 8);
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | (eight[i] & 0xff);
                    }
                }
                if (length < 0 || length > MAX_FRAME) {
                    throw new IOException("frame too large");
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask, 4);
                }
                byte[] payload = new byte[(int) length];
                readFully(payload, payload.length);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                switch (opcode) {
                    case 0x0:
                    case 0x1:
                    case 0x2:
                        // Fragmentation is of no interest: the stream is bytes.
                        client.write(payload);
                        client.flush();
                        break;
                    case 0x8:
                        return;
                    case 0x9:
                        sendFrame(0xA, payload, 0, payload.length);
                        break;
                    default:
                        break;
                }
            }
        }
    }
}
