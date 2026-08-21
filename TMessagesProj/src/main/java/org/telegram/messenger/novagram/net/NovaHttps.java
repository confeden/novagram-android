package org.telegram.messenger.novagram.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS to an address, under a name.
 *
 * <p>Everything in the fork that resolves a name itself ends up here: the socket
 * is opened on a literal address that {@link NovaDoh} produced, and the name
 * travels in SNI, in the certificate check and in the {@code Host} header.</p>
 *
 * <p><b>Why not HttpURLConnection.</b> It resolves the name it is given, and
 * there is no way to tell it which address to use. Giving it the address
 * instead does not work either: the platform stack sets SNI from the host of
 * the URL, which would then be a literal address, and Conscrypt sends no SNI at
 * all for one of those. Google documents that {@code dns.google} requires SNI,
 * and any host that serves more than one certificate — GitHub, for one — would
 * hand back the wrong one. So the socket is opened by hand, and what goes into
 * SNI, what the certificate is checked against and what the {@code Host} header
 * says are all decided in one place.</p>
 *
 * <p>The HTTP written here is the smallest thing that is still honest: one
 * request per connection, a status line, headers, and a body that is either
 * chunked or counted. It is not a general HTTP client and must not grow into
 * one — what it exists for is that a general one comes with a URL, and a URL
 * comes with a name to resolve.</p>
 */
public final class NovaHttps {
    public interface Progress {
        void report(long done, long total);
    }

    public interface Cancel {
        boolean cancelled() throws Exception;
    }

    public static final class Response {
        public int code;
        /** Where a 3xx points, verbatim; may be relative. */
        public String location;
        /** The {@code Date} header as sent, for {@link NovaDoh#parseHttpDate}. */
        public String date;
        /** {@code Content-Length}, or -1 when the server did not say. */
        public long contentLength = -1;
        /** How many bytes of body reached the sink. */
        public long written;
    }

    private static final int MAX_HEADERS = 100;
    private static final int MAX_HEADER_LINE = 8192;

    private NovaHttps() {
    }

    /**
     * Opens a verified TLS connection to {@code address}, under {@code name}.
     *
     * <p>The name is checked against the certificate here rather than left to
     * the socket: below API 24 the socket does not check it at all, and a
     * certificate that does not name the host is exactly what a network
     * standing in its place would present.</p>
     */
    public static SSLSocket connect(String name, String address, Proxy proxy, int timeoutMs)
            throws Exception {
        Socket raw = new Socket(proxy == null ? Proxy.NO_PROXY : proxy);
        SSLSocket ssl = null;
        try {
            raw.connect(new InetSocketAddress(InetAddress.getByName(address), 443), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, name, 443, true);
            applyServerName(ssl, name);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(name, ssl.getSession())) {
                throw new SSLException("certificate does not name " + name);
            }
            return ssl;
        } catch (Throwable e) {
            close(ssl);
            close(raw);
            if (e instanceof Exception) {
                throw (Exception) e;
            }
            throw new Exception(e);
        }
    }

    /**
     * Puts the name into SNI, twice over.
     *
     * <p>The four-argument {@code createSocket} above already hands the name to
     * the socket, which is enough on every version this fork supports; the
     * explicit parameters are the documented way to say it, and
     * {@code setEndpointIdentificationAlgorithm} makes the handshake itself
     * refuse a certificate that does not name the host. Below API 24 neither
     * exists, hence the reflective {@code setHostname} that Conscrypt has
     * carried since API 17 — and hence the explicit verification above, which
     * is what actually guarantees the check.</p>
     */
    private static void applyServerName(SSLSocket socket, String name) {
        try {
            java.lang.reflect.Method method =
                    socket.getClass().getMethod("setHostname", String.class);
            method.invoke(socket, name);
        } catch (Throwable ignored) {
        }
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            applyModernServerName(socket, name);
        }
    }

    @android.annotation.TargetApi(24)
    private static void applyModernServerName(SSLSocket socket, String name) {
        try {
            javax.net.ssl.SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>singletonList(
                    new javax.net.ssl.SNIHostName(name)));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        } catch (Throwable ignored) {
        }
    }

    public static void close(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Writes a request head; the caller writes the body, if any. */
    public static byte[] head(String method, String name, String path, Map<String, String> headers) {
        StringBuilder builder = new StringBuilder();
        builder.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        builder.append("Host: ").append(name).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        // One request per connection. Keeping it alive would save a handshake
        // and cost a socket held open to a stranger for as long as the app
        // lives.
        builder.append("Connection: close\r\n\r\n");
        return builder.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * The status line and headers, read; the body left where it is.
     *
     * <p>Split in two because the code decides what the body is for: a 3xx
     * carries a page nobody wants, and a 206 carries the tail of a file that
     * has to be appended rather than replace what is held. Reading the body
     * before the caller has seen the code would settle that question in the
     * wrong place.</p>
     */
    public static final class Reader {
        public final Response response = new Response();
        private final InputStream in;
        private boolean chunked;

        Reader(InputStream stream) throws Exception {
            in = new java.io.BufferedInputStream(stream, 16 * 1024);

            String status = readLine(in);
            if (status == null) {
                throw new java.io.EOFException("no status line");
            }
            String[] parts = status.split(" ");
            if (parts.length < 2) {
                throw new java.io.IOException("bad status line: " + status);
            }
            response.code = Integer.parseInt(parts[1]);

            String line;
            int headers = 0;
            while ((line = readLine(in)) != null && line.length() > 0) {
                if (++headers > MAX_HEADERS) {
                    throw new java.io.IOException("too many headers");
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("content-length".equalsIgnoreCase(name)) {
                    try {
                        response.contentLength = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("transfer-encoding".equalsIgnoreCase(name)) {
                    chunked = value.toLowerCase(Locale.US).contains("chunked");
                } else if ("location".equalsIgnoreCase(name)) {
                    response.location = value;
                } else if ("date".equalsIgnoreCase(name)) {
                    response.date = value;
                }
            }
        }

        /**
         * Streams the body into {@code sink}, which may be null when the body
         * is of no interest.
         *
         * @param limit the most body bytes that will be accepted; over it the
         *              read fails rather than truncating, because a truncated
         *              answer that looks complete is worse than none.
         * @param base  how many bytes the caller already holds, for the
         *              progress report only.
         */
        public long drain(OutputStream sink, long limit, long base, Progress progress, Cancel cancel)
                throws Exception {
            long total = (response.contentLength >= 0) ? (base + response.contentLength) : 0;
            if (chunked) {
                while (true) {
                    String header = readLine(in);
                    if (header == null) {
                        break;
                    }
                    int semicolon = header.indexOf(';');
                    if (semicolon >= 0) {
                        header = header.substring(0, semicolon);
                    }
                    header = header.trim();
                    if (header.length() == 0) {
                        continue;
                    }
                    long size = Long.parseLong(header, 16);
                    if (size == 0) {
                        break;
                    }
                    response.written += copy(
                            in, sink, size, limit, response.written, base, total, progress, cancel);
                    readLine(in); // The CRLF that closes the chunk.
                }
            } else if (response.contentLength >= 0) {
                response.written = copy(
                        in, sink, response.contentLength, limit, 0, base, total, progress, cancel);
            } else {
                response.written = copy(
                        in, sink, Long.MAX_VALUE, limit, 0, base, total, progress, cancel);
            }
            return response.written;
        }
    }

    public static Reader open(InputStream stream) throws Exception {
        return new Reader(stream);
    }

    private static long copy(
            InputStream in,
            OutputStream sink,
            long count,
            long limit,
            long already,
            long base,
            long total,
            Progress progress,
            Cancel cancel) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        long left = count;
        long done = 0;
        while (left > 0) {
            if (cancel != null && cancel.cancelled()) {
                throw new Cancelled();
            }
            int want = (int) Math.min(buffer.length, left);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                // Only "read until the connection closes" may legitimately end
                // this way; a counted body that stops early is a broken
                // transfer, and the caller learns that from the byte count.
                break;
            }
            if (already + done + read > limit) {
                throw new IllegalStateException("answer is too large");
            }
            if (sink != null) {
                sink.write(buffer, 0, read);
            }
            done += read;
            left -= read;
            if (progress != null && total > 0) {
                progress.report(base + already + done, total);
            }
        }
        return done;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                out.write(value);
            }
            if (out.size() > MAX_HEADER_LINE) {
                throw new java.io.IOException("header line is too long");
            }
        }
        if (value < 0 && out.size() == 0) {
            return null;
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Asked for by the user, not a failure. Carried through unchanged. */
    public static class Cancelled extends Exception {
    }

    public static Map<String, String> headers() {
        return new HashMap<>();
    }
}
