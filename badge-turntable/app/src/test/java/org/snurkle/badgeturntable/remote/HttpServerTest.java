package org.snurkle.badgeturntable.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpServerTest {
    private HttpServer server;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws IOException {
        server = new HttpServer(0, req -> {
            if (req.path.equals("/flood")) {
                // Sends big text messages as fast as it can, until the socket is dropped.
                AtomicBoolean done = new AtomicBoolean();
                return HttpServer.Response.webSocket(new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        String big = "x".repeat(32 * 1024);
                        Thread t = new Thread(() -> {
                            try {
                                while (!done.get()) {
                                    ws.sendText(big);
                                    Thread.sleep(1);
                                }
                            } catch (InterruptedException ignored) {
                            }
                        });
                        t.setDaemon(true);
                        t.start();
                    }

                    @Override
                    public void onText(WebSocket ws, String text) {
                    }

                    @Override
                    public void onClose(WebSocket ws) {
                        done.set(true);
                        received.add("flood closed");
                    }
                });
            }
            if (req.path.equals("/ws")) {
                return HttpServer.Response.webSocket(new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.sendText("hello");
                    }

                    @Override
                    public void onText(WebSocket ws, String text) {
                        received.add(text);
                        if (text.equals("bye")) ws.close(4401, "Not paired");
                        else {
                            ws.offerBinary(new byte[]{1});
                            ws.offerBinary(new byte[]{2, 3});  // replaces the one not yet sent, or follows it
                            ws.sendText("echo " + text);
                        }
                    }

                    @Override
                    public void onClose(WebSocket ws) {
                        received.add("closed");
                    }
                });
            }
            if (req.bodyTooLarge) return HttpServer.Response.json(400, "{\"error\":\"bad_request\"}");
            String body = req.method + " " + req.path + " q=" + req.query.get("variant") + " auth="
                    + req.header("Authorization") + " body=" + new String(req.body, StandardCharsets.UTF_8);
            return HttpServer.Response.bytes(200, "text/plain", body.getBytes(StandardCharsets.UTF_8))
                    .header("X-Test", "1");
        }, a -> true);
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(5000);
        return s;
    }

    /** Reads one response: the head, then Content-Length bytes. */
    private static String[] readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("eof");
            head.write(b);
            state = (b == '\r' || b == '\n') ? state + 1 : 0;
        }
        String h = head.toString("ISO-8859-1");
        int len = 0;
        for (String line : h.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = new byte[len];
        new DataInputStream(in).readFully(body);
        return new String[]{h, new String(body, StandardCharsets.UTF_8)};
    }

    @Test
    public void keepAliveServesSeveralRequests() throws IOException {
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/shots/s-1?variant=thumb HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer abc\r\n\r\n"
                    + "POST /api/pair HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 17\r\n\r\n"
                    + "{\"code\":\"123456\"}").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String[] first = readResponse(s.getInputStream());
            assertTrue(first[0], first[0].startsWith("HTTP/1.1 200 OK"));
            assertTrue(first[0].contains("X-Test: 1"));
            assertEquals("GET /api/shots/s-1 q=thumb auth=Bearer abc body=", first[1]);
            String[] second = readResponse(s.getInputStream());
            assertEquals("POST /api/pair q=null auth=null body={\"code\":\"123456\"}", second[1]);
        }
    }

    @Test
    public void bodiesOverTheLimitAreRefused() throws IOException {
        try (Socket s = connect()) {
            s.getOutputStream().write(("POST /api/camera HTTP/1.1\r\nContent-Length: " + (HttpServer.MAX_BODY + 1)
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String[] r = readResponse(s.getInputStream());
            assertTrue(r[0].startsWith("HTTP/1.1 400"));
            assertTrue(r[0].contains("Connection: close"));
        }
    }

    @Test
    public void acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", HttpServer.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void webSocketTextBinaryAndCloseCodes() throws Exception {
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            InputStream in = s.getInputStream();
            String head = readHead(in);
            assertTrue(head, head.startsWith("HTTP/1.1 101"));
            assertTrue(head.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));

            Frame hello = readFrame(in);
            assertEquals(1, hello.op);
            assertEquals("hello", new String(hello.data, StandardCharsets.UTF_8));

            writeMasked(out, 1, "{\"type\":\"auth\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals("{\"type\":\"auth\"}", received.poll(5, TimeUnit.SECONDS));
            // The text arrives; of the two binary frames, the newer always does (the older may be dropped).
            boolean echoed = false, newest = false;
            while (!echoed || !newest) {
                Frame f = readFrame(in);
                if (f.op == 2) newest = f.data.length == 2;
                else if (f.op == 1) echoed = new String(f.data, StandardCharsets.UTF_8).equals("echo {\"type\":\"auth\"}");
            }

            writeMasked(out, 1, "bye".getBytes(StandardCharsets.UTF_8));
            Frame close = readFrame(in);
            while (close.op != 8) close = readFrame(in);
            assertEquals(4401, ((close.data[0] & 0xFF) << 8) | (close.data[1] & 0xFF));
            assertEquals("Not paired", new String(close.data, 2, close.data.length - 2, StandardCharsets.UTF_8));
            assertEquals("bye", received.poll(5, TimeUnit.SECONDS));
            assertEquals("closed", received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aClientThatStopsReadingIsDropped() throws Exception {
        try (Socket s = new Socket()) {
            s.setReceiveBufferSize(4096);
            s.connect(new InetSocketAddress("127.0.0.1", server.port()));
            s.getOutputStream().write(("GET /flood HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            // Never read: the server's writer blocks, its queue backs up, and it gives up on us.
            assertEquals("flood closed", received.poll(20, TimeUnit.SECONDS));
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("eof");
            head.write(b);
            state = (b == '\r' || b == '\n') ? state + 1 : 0;
        }
        return head.toString("ISO-8859-1");
    }

    private static final class Frame {
        int op;
        byte[] data;
    }

    private static Frame readFrame(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int b0 = d.readUnsignedByte(), b1 = d.readUnsignedByte();
        long n = b1 & 0x7F;
        if (n == 126) n = d.readUnsignedShort();
        else if (n == 127) n = d.readLong();
        Frame f = new Frame();
        f.op = b0 & 0x0F;
        f.data = new byte[(int) n];
        d.readFully(f.data);
        return f;
    }

    private static void writeMasked(OutputStream out, int op, byte[] payload) throws IOException {
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        out.write(0x80 | op);
        out.write(0x80 | payload.length);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
        out.flush();
    }
}
