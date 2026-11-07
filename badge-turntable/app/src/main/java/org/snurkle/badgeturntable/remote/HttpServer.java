package org.snurkle.badgeturntable.remote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

/**
 * A small HTTP/1.1 server with WebSocket upgrade: just what the remote API needs, on
 * java.net only. One thread per connection, keep-alive, request bodies up to
 * {@link #MAX_BODY} bytes with Content-Length (browsers' fetch always sends one).
 *
 * NanoHTTPD was the plan, but its WebSocket close codes are a fixed enum, so it can't send
 * the API's 4401/4408/4409, and it's been unmaintained since 2016.
 */
public final class HttpServer {
    public static final int MAX_BODY = 16 * 1024;
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_CONNECTIONS = 24;
    private static final int IDLE_TIMEOUT_MS = 30_000;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public interface Handler {
        Response handle(Request request);
    }

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        /** Header names in lower case. */
        public final Map<String, String> headers;
        public final byte[] body;
        /** The body was over MAX_BODY and wasn't read; the handler should answer 400. */
        public final boolean bodyTooLarge;
        public final InetAddress remote;

        Request(String method, String path, Map<String, String> query, Map<String, String> headers,
                byte[] body, boolean bodyTooLarge, InetAddress remote) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
            this.bodyTooLarge = bodyTooLarge;
            this.remote = remote;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public boolean isWebSocketUpgrade() {
            String upgrade = header("upgrade");
            return "GET".equals(method) && upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket")
                    && header("sec-websocket-key") != null;
        }
    }

    public static final class Response {
        public final int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        final byte[] body;
        final InputStream stream;
        final long length;
        final WebSocket.Listener upgrade;

        private Response(int status, byte[] body, InputStream stream, long length, WebSocket.Listener upgrade) {
            this.status = status;
            this.body = body;
            this.stream = stream;
            this.length = length;
            this.upgrade = upgrade;
        }

        public static Response bytes(int status, String contentType, byte[] body) {
            Response r = new Response(status, body, null, body.length, null);
            if (contentType != null) r.header("Content-Type", contentType);
            return r;
        }

        public static Response json(int status, String json) {
            return bytes(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }

        public static Response empty(int status) {
            return new Response(status, new byte[0], null, 0, null);
        }

        /** Streams length bytes from the stream, which is closed afterwards. */
        public static Response stream(int status, String contentType, InputStream in, long length) {
            Response r = new Response(status, null, in, length, null);
            r.header("Content-Type", contentType);
            return r;
        }

        /** Switches the connection to a WebSocket handled by the listener. */
        public static Response webSocket(WebSocket.Listener listener) {
            return new Response(101, null, null, 0, listener);
        }

        public Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    private final int port;
    private final Handler handler;
    private final Predicate<InetAddress> localAllowed;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "http");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    /**
     * @param localAllowed which of the phone's own addresses may accept connections
     *                     (LAN and loopback only, not mobile data)
     */
    public HttpServer(int port, Handler handler, Predicate<InetAddress> localAllowed) {
        this.port = port;
        this.handler = handler;
        this.localAllowed = localAllowed;
    }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        Thread accept = new Thread(this::acceptLoop, "http-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** The bound port (useful when started with port 0). */
    public int port() {
        ServerSocket s = server;
        return s == null ? port : s.getLocalPort();
    }

    /**
     * Stops listening at once (so the port is free again), and closes open connections after
     * graceMs, giving WebSocket close frames time to go out.
     */
    public void stop(long graceMs) {
        ServerSocket s = server;
        server = null;
        if (s != null) closeQuietly(s);
        Runnable closeAll = () -> {
            for (Socket c : open) closeQuietly(c);
            pool.shutdownNow();
        };
        if (graceMs <= 0) {
            closeAll.run();
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(graceMs);
            } catch (InterruptedException ignored) {
            }
            closeAll.run();
        }, "http-stop");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        ServerSocket s;
        while ((s = server) != null) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                if (server == null) return;
                continue;
            }
            if (!localAllowed.test(c.getLocalAddress()) || !slots.tryAcquire()) {
                closeQuietly(c);
                continue;
            }
            open.add(c);
            try {
                pool.execute(() -> {
                    try {
                        serve(c);
                    } finally {
                        open.remove(c);
                        closeQuietly(c);
                        slots.release();
                    }
                });
            } catch (RuntimeException e) {  // pool shut down
                open.remove(c);
                closeQuietly(c);
                slots.release();
            }
        }
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(IDLE_TIMEOUT_MS);
            c.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = new BufferedOutputStream(c.getOutputStream(), 64 * 1024);
            while (true) {
                Request req = read(in, c.getInetAddress());
                if (req == null) return;
                Response resp;
                try {
                    resp = handler.handle(req);
                } catch (RuntimeException e) {
                    resp = Response.json(500, "{\"error\":\"internal\",\"message\":" + quote(String.valueOf(e)) + "}");
                }
                if (resp.upgrade != null && req.isWebSocketUpgrade()) {
                    writeHandshake(out, req.header("sec-websocket-key"));
                    c.setSoTimeout(0);
                    new WebSocket(c, in, out, resp.upgrade).run();
                    return;
                }
                boolean keepAlive = !req.bodyTooLarge
                        && !"close".equalsIgnoreCase(req.header("connection"));
                write(out, req, resp, keepAlive);
                if (!keepAlive) return;
            }
        } catch (SocketTimeoutException | SocketException e) {
            // idle keep-alive connection, or the client went away
        } catch (IOException e) {
            // malformed request or broken connection: just drop it
        }
    }

    /** Reads one request; null at a clean end of stream. */
    static Request read(InputStream in, InetAddress remote) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        while (line.isEmpty()) {  // tolerate stray CRLF between requests
            line = readLine(in);
            if (line == null) return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 3) throw new IOException("bad request line");
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; ; i++) {
            String h = readLine(in);
            if (h == null) throw new IOException("eof in headers");
            if (h.isEmpty()) break;
            if (i >= MAX_HEADERS) throw new IOException("too many headers");
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        int q = target.indexOf('?');
        String path = decode(q < 0 ? target : target.substring(0, q));
        Map<String, String> query = new HashMap<>();
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                query.put(decode(eq < 0 ? kv : kv.substring(0, eq)), eq < 0 ? "" : decode(kv.substring(eq + 1)));
            }
        }
        if (headers.containsKey("transfer-encoding")) throw new IOException("chunked bodies aren't supported");
        long length = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                length = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad content-length");
            }
        }
        if (length > MAX_BODY) return new Request(method, path, query, headers, new byte[0], true, remote);
        byte[] body = new byte[(int) length];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n < 0) throw new IOException("eof in body");
            off += n;
        }
        return new Request(method, path, query, headers, body, false, remote);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (buf.size() == 0) return null;
                throw new IOException("eof in line");
            }
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > MAX_LINE) throw new IOException("line too long");
        }
        return buf.toString("ISO-8859-1");
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void write(OutputStream out, Request req, Response resp, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status)).append("\r\n");
        for (Map.Entry<String, String> h : resp.headers.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        if (resp.status != 204 && resp.status != 304) head.append("Content-Length: ").append(resp.length).append("\r\n");
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        boolean sendBody = !"HEAD".equals(req.method);
        if (resp.stream != null) {
            try (InputStream in = resp.stream) {
                if (sendBody) {
                    byte[] buf = new byte[64 * 1024];
                    long left = resp.length;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("file shorter than expected");
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
            }
        } else if (sendBody && resp.body != null) {
            out.write(resp.body);
        }
        out.flush();
    }

    private static void writeHandshake(OutputStream out, String key) throws IOException {
        String head = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key.trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return status >= 500 ? "Internal Server Error" : "Status";
        }
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
