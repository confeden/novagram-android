package org.telegram.messenger.novagram.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

import android.util.Base64;

/**
 * A WebSocket client for the one stream the wallet opens itself.
 *
 * <p>The library this replaces opened the socket from the URL the server had put
 * in a TL answer: system resolver, no proxy, any host. Here the name has to be
 * toncenter.com or a subdomain, it is resolved over the pinned DoH endpoints
 * only (a miss is a failure, never a fall-back to the system), the TCP leg goes
 * through the proxy the fork decides on, and the TLS is {@link NovaHttps#connect},
 * which checks the certificate against the real name while dialling the
 * literal address.</p>
 */
public final class NovaWebSocket {

    public interface Listener {
        void onOpen(NovaWebSocket ws);

        void onText(NovaWebSocket ws, String text);

        /** Called once, whatever ended the socket, including {@link #disconnect()}. */
        void onClosed(NovaWebSocket ws, String reason);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int MAX_HEADER = 8 * 1024;
    private static final long MAX_PAYLOAD = 4L * 1024 * 1024;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String host;
    private final int port;
    private final String target;
    private volatile Listener listener;
    private volatile Socket socket;
    private OutputStream out;
    private volatile boolean closed;
    private boolean reported;

    /** @throws IllegalArgumentException when the URL may not be dialled at all. */
    public NovaWebSocket(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("wss".equals(scheme) || "https".equals(scheme)) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("scheme");
        }
        String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHost(h)) {
            throw new IllegalArgumentException("host");
        }
        int p = uri.getPort() < 0 ? 443 : uri.getPort();
        if (p <= 0 || p > 65535) {
            throw new IllegalArgumentException("port");
        }
        host = h;
        port = p;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    public static boolean allowedHost(String host) {
        return host.equals("toncenter.com") || host.endsWith(".toncenter.com");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void connectAsynchronously() {
        Thread thread = new Thread(this::run, "nova-wallet-ws");
        thread.setDaemon(true);
        thread.start();
    }

    public void sendText(String text) {
        write(1, text.getBytes(StandardCharsets.UTF_8));
    }

    public void disconnect() {
        closed = true;
        NovaHttps.close(socket);
        report("disconnected");
    }

    private void report(String reason) {
        Listener l;
        synchronized (this) {
            if (reported) {
                return;
            }
            reported = true;
            l = listener;
        }
        if (l != null) {
            l.onClosed(this, reason);
        }
    }

    private String address() throws IOException {
        for (int type : new int[]{NovaDoh.TYPE_A, NovaDoh.TYPE_AAAA}) {
            NovaDoh.Answer answer = NovaDoh.resolve(host, type);
            List<String> values = answer == null ? null : answer.values;
            if (values == null) {
                continue;
            }
            for (String value : values) {
                // A literal only: getByName on a name would reach the system resolver.
                boolean literal = value.indexOf(':') >= 0 || value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
                if (literal) {
                    return value;
                }
            }
        }
        throw new IOException("no DoH answer for the stream host");
    }

    private void run() {
        String reason = "closed";
        try {
            Socket s = NovaHttps.connect(host, address(), NovaDoh.proxyFor(), CONNECT_TIMEOUT_MS, port);
            socket = s;
            if (closed) {
                NovaHttps.close(s);
                return;
            }
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            handshake(in);
            s.setSoTimeout(0);
            Listener l = listener;
            if (l != null) {
                l.onOpen(this);
            }
            readLoop(in);
        } catch (Throwable e) {
            reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            NovaHttps.close(socket);
            report(reason);
        }
    }

    private void handshake(InputStream in) throws Exception {
        byte[] keyBytes = new byte[16];
        RANDOM.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String hostHeader = port == 443 ? host : host + ":" + port;
        String request = "GET " + target + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("upgrade");
            }
            if (head.size() >= MAX_HEADER) {
                throw new IOException("upgrade response too long");
            }
            head.write(b);
            int n = head.size();
            if (b == '\n' && n >= 4) {
                byte[] raw = head.toByteArray();
                if (raw[n - 2] == '\r' && raw[n - 3] == '\n' && raw[n - 4] == '\r') {
                    break;
                }
            }
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        if (lines.length == 0 || !lines[0].contains(" 101")) {
            throw new IOException("upgrade refused: " + (lines.length == 0 ? "" : lines[0]));
        }
        String expected = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)),
                Base64.NO_WRAP);
        boolean accepted = false;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("sec-websocket-accept")) {
                accepted = line.substring(colon + 1).trim().equals(expected);
                break;
            }
        }
        if (!accepted) {
            throw new IOException("bad accept");
        }
    }

    private void readLoop(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean textMessage = false;
        while (!closed) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                throw new EOFException("closed by peer");
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) {
                length = (readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
            }
            if (length < 0 || length > MAX_PAYLOAD) {
                throw new IOException("frame too large");
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) length];
            readFully(in, payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode) {
                case 0:
                case 1:
                case 2:
                    if (opcode != 0) {
                        message.reset();
                        textMessage = opcode == 1;
                    }
                    if (message.size() + payload.length > MAX_PAYLOAD) {
                        throw new IOException("message too large");
                    }
                    message.write(payload);
                    if (fin) {
                        if (textMessage) {
                            Listener l = listener;
                            if (l != null) {
                                l.onText(this, new String(message.toByteArray(), StandardCharsets.UTF_8));
                            }
                        }
                        message.reset();
                    }
                    break;
                case 8:
                    write(8, new byte[0]);
                    return;
                case 9:
                    write(10, payload);
                    break;
                default:
                    break;
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] into) throws IOException {
        int done = 0;
        while (done < into.length) {
            int n = in.read(into, done, into.length - done);
            if (n < 0) {
                throw new EOFException();
            }
            done += n;
        }
    }

    private synchronized void write(int opcode, byte[] payload) {
        OutputStream stream = out;
        if (stream == null || closed) {
            return;
        }
        try {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(0x80 | payload.length);
            } else if (payload.length < 65536) {
                frame.write(0x80 | 126);
                frame.write(payload.length >> 8);
                frame.write(payload.length & 0xff);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) (((long) payload.length >> (8 * i)) & 0xff));
                }
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i & 3]);
            }
            stream.write(frame.toByteArray());
            stream.flush();
        } catch (IOException e) {
            NovaHttps.close(socket);
        }
    }
}
