package com.my.newproject;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small RFC 6455 WebSocket client — the admin app's copy of the user app's
 * {@code CigramWs}, byte for byte apart from the package and the class name.
 *
 * The two apps are separate Sketchware projects with no shared module, so the file
 * is duplicated rather than extracted; if you change one, change the other. It is
 * covered by user-app/test/run-ws-test.sh.
 *
 * Neither app bundles a WebSocket library (no OkHttp, no Java-WebSocket), and
 * adding one to a Sketchware project is awkward — so this is the whole client:
 * one reader thread, one writer lock, no dependencies beyond the JDK and
 * android.util.Base64.
 *
 * What it implements: the handshake with Sec-WebSocket-Accept verification,
 * client-masked text frames, fragmented messages, server ping (answered with a
 * pong), server pong, close with the code echoed back, and a periodic keepalive
 * ping. Binary frames are received and ignored — this protocol is JSON only.
 *
 * What it does NOT do: permessage-deflate, sub-protocols, redirects. The server
 * side ({@code AdsChatDO}) uses none of them.
 *
 * Every callback arrives on the main thread. {@link #close()} is safe to call
 * twice and from any thread; after it, no further callback fires.
 */
final class CgWs {

    interface Listener {
        void onOpen();

        void onText(String text);

        /** Called exactly once. {@code reason} is already a human sentence. */
        void onClosed(boolean clean, String reason);
    }

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    /** No traffic for this long and the read fails, which triggers the reconnect. */
    private static final int READ_TIMEOUT_MS = 70000;
    private static final long PING_EVERY_MS = 25000L;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static Handler main;

    private final String url;
    private final List<String[]> headers = new ArrayList<String[]>();
    private final Object writeLock = new Object();
    private volatile Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean closed;
    private volatile boolean opened;
    private Thread reader;
    private Thread pinger;

    public CgWs(String url, Listener listener) {
        this.url = url == null ? "" : url.trim();
        this.listener = listener;
    }

    public CgWs header(String name, String value) {
        if (name != null && value != null) headers.add(new String[]{name, value});
        return this;
    }

    public boolean isOpen() {
        return opened && !closed;
    }

    // ----------------------------------------------------------------- connect

    /** Returns immediately; the handshake runs on its own thread. */
    public void connect() {
        reader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    handshakeAndRead();
                } catch (Throwable error) {
                    finish(false, friendly(error));
                }
            }
        }, "cigram-ws");
        reader.setDaemon(true);
        reader.start();
    }

    private void handshakeAndRead() throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        boolean secure = "wss".equals(scheme) || "https".equals(scheme);
        if (!secure && !"ws".equals(scheme) && !"http".equals(scheme)) {
            throw new IOException("unsupported scheme");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        String host = uri.getHost();
        if (host == null) throw new IOException("no host");

        String path = uri.getRawPath() == null || uri.getRawPath().length() == 0 ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null && uri.getRawQuery().length() > 0) path += "?" + uri.getRawQuery();

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        raw.setTcpNoDelay(true);

        Socket active = raw;
        if (secure) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
            // SNI + hostname verification: without this a TLS socket accepts any cert.
            try {
                javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
            } catch (Throwable ignored) {
                // Older devices: fall back to an explicit check after the handshake.
            }
            ssl.startHandshake();
            try {
                javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                if (params.getEndpointIdentificationAlgorithm() == null) {
                    javax.net.ssl.HostnameVerifier verifier =
                            javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
                    if (!verifier.verify(host, ssl.getSession())) {
                        throw new IOException("hostname mismatch");
                    }
                }
            } catch (IOException bad) {
                throw bad;
            } catch (Throwable ignored) { }
            active = ssl;
        }

        socket = active;
        out = active.getOutputStream();
        InputStream in = active.getInputStream();

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);

        StringBuilder request = new StringBuilder();
        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host);
        if ((secure && port != 443) || (!secure && port != 80)) request.append(":").append(port);
        request.append("\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        for (String[] extra : headers) {
            request.append(extra[0]).append(": ").append(extra[1]).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes("UTF-8"));
        out.flush();

        String statusLine = readLine(in);
        if (statusLine == null || statusLine.indexOf(" 101") < 0) {
            throw new IOException("handshake rejected: " + statusLine);
        }
        String accept = null;
        for (;;) {
            String line = readLine(in);
            if (line == null) throw new IOException("handshake truncated");
            if (line.length() == 0) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            if ("sec-websocket-accept".equals(name)) accept = line.substring(colon + 1).trim();
        }
        String expected = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes("UTF-8")),
                Base64.NO_WRAP);
        if (accept == null || !accept.equals(expected)) throw new IOException("bad accept key");

        opened = true;
        post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) l.onOpen();
            }
        });
        startPinger();
        readLoop(in);
    }

    private void startPinger() {
        pinger = new Thread(new Runnable() {
            @Override public void run() {
                while (isOpen()) {
                    try {
                        Thread.sleep(PING_EVERY_MS);
                    } catch (InterruptedException stop) {
                        return;
                    }
                    if (!isOpen()) return;
                    try {
                        writeFrame(OP_PING, new byte[0]);
                    } catch (Throwable error) {
                        return; // the reader will notice and report the close
                    }
                }
            }
        }, "cigram-ws-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    // -------------------------------------------------------------- read loop

    private void readLoop(InputStream in) throws Exception {
        ByteArrayOutputStream assembling = new ByteArrayOutputStream();
        int assemblingOpcode = -1;

        while (!closed) {
            int b0 = in.read();
            if (b0 < 0) throw new IOException("stream closed");
            int b1 = in.read();
            if (b1 < 0) throw new IOException("stream closed");

            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;

            if (length == 126) {
                length = ((long) readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
            }
            if (length < 0 || length > MAX_MESSAGE_BYTES) throw new IOException("frame too large");

            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask, 4);
            }
            byte[] payload = new byte[(int) length];
            readFully(in, payload, (int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i & 3]);
            }

            if (opcode == OP_PING) {
                writeFrame(OP_PONG, payload);
                continue;
            }
            if (opcode == OP_PONG) {
                continue;
            }
            if (opcode == OP_CLOSE) {
                int code = payload.length >= 2
                        ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
                        : 1005;
                try {
                    writeFrame(OP_CLOSE, payload.length >= 2
                            ? new byte[]{payload[0], payload[1]}
                            : new byte[0]);
                } catch (Throwable ignored) { }
                finish(code == 1000 || code == 1001 || code == 1005, "أُغلق الاتصال");
                return;
            }

            if (opcode == OP_TEXT || opcode == OP_BINARY) {
                if (assemblingOpcode != -1) throw new IOException("interleaved frames");
                if (fin) {
                    if (opcode == OP_TEXT) deliver(new String(payload, "UTF-8"));
                    continue;
                }
                assemblingOpcode = opcode;
                assembling.reset();
                assembling.write(payload);
                continue;
            }

            if (opcode == OP_CONTINUATION) {
                if (assemblingOpcode == -1) throw new IOException("stray continuation");
                assembling.write(payload);
                if (assembling.size() > MAX_MESSAGE_BYTES) throw new IOException("message too large");
                if (fin) {
                    if (assemblingOpcode == OP_TEXT) {
                        deliver(new String(assembling.toByteArray(), "UTF-8"));
                    }
                    assembling.reset();
                    assemblingOpcode = -1;
                }
                continue;
            }
            throw new IOException("unknown opcode " + opcode);
        }
    }

    private void deliver(final String text) {
        post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) l.onText(text);
            }
        });
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("stream closed");
        return value;
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) throw new IOException("stream closed");
            read += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        for (;;) {
            int value = in.read();
            if (value < 0) return line.size() == 0 ? null : line.toString("UTF-8");
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), "UTF-8");
            }
            line.write(value);
            previous = value;
            if (line.size() > 16384) throw new IOException("header too long");
        }
    }

    // ------------------------------------------------------------------ write

    /** Sends one text message. Returns false when the socket is not usable. */
    public boolean send(String text) {
        if (!isOpen() || text == null) return false;
        try {
            writeFrame(OP_TEXT, text.getBytes("UTF-8"));
            return true;
        } catch (Throwable error) {
            finish(false, friendly(error));
            return false;
        }
    }

    /** Client frames are always masked — a server must reject an unmasked one. */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        OutputStream stream = out;
        if (stream == null) throw new IOException("not connected");
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);

        synchronized (writeLock) {
            stream.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                stream.write(0x80 | length);
            } else if (length < 65536) {
                stream.write(0x80 | 126);
                stream.write((length >>> 8) & 0xFF);
                stream.write(length & 0xFF);
            } else {
                stream.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) stream.write((int) (((long) length >>> (8 * i)) & 0xFF));
            }
            stream.write(mask);
            byte[] masked = new byte[length];
            for (int i = 0; i < length; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            stream.write(masked);
            stream.flush();
        }
    }

    // ------------------------------------------------------------------ close

    public void close() {
        if (closed) return;
        try {
            if (isOpen()) writeFrame(OP_CLOSE, new byte[]{0x03, (byte) 0xE8}); // 1000
        } catch (Throwable ignored) { }
        finish(true, "أُغلق الاتصال");
    }

    private void finish(final boolean clean, final String reason) {
        if (closed) return;
        closed = true;
        final Listener l = listener;
        listener = null; // no callback after close, by construction
        try {
            if (pinger != null) pinger.interrupt();
        } catch (Throwable ignored) { }
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Throwable ignored) { }
        socket = null;
        out = null;
        if (l == null) return;
        post(new Runnable() {
            @Override public void run() {
                l.onClosed(clean, reason);
            }
        });
    }

    private static synchronized Handler mainHandler() {
        if (main == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) main = new Handler(looper);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return main;
    }

    /**
     * Callbacks go to the main thread. If there is no main Looper at all (only
     * possible off-device, in the frame-codec test) the callback runs inline
     * rather than being dropped.
     */
    private static void post(Runnable job) {
        Handler handler = mainHandler();
        if (handler != null) {
            try {
                handler.post(job);
                return;
            } catch (Throwable ignored) { }
        }
        try {
            job.run();
        } catch (Throwable ignored) { }
    }

    private static String friendly(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof java.net.UnknownHostException) return "لا يوجد اتصال بالإنترنت.";
        if (error instanceof java.net.SocketTimeoutException) return "انقطع الاتصال اللحظي.";
        if (message.indexOf("handshake rejected") >= 0) return "تعذر بدء الاتصال اللحظي.";
        return "انقطع الاتصال اللحظي.";
    }
}
