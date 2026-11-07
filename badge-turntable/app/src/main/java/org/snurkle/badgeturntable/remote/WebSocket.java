package org.snurkle.badgeturntable.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The server side of one WebSocket (RFC 6455), just what /api/live needs. Text messages
 * queue in order; binary messages don't: a newer one replaces one still waiting, so
 * preview frames never pile up behind a slow connection. A ping goes out every 15 s,
 * and a socket that hasn't been heard from in 30 s is dropped. So is one whose text
 * queue backs up (the writer is stuck on a dead connection and can't time out itself).
 */
public final class WebSocket {
    public interface Listener {
        void onOpen(WebSocket ws);

        void onText(WebSocket ws, String text);

        /** The socket has closed, from either side. Called once. */
        void onClose(WebSocket ws);
    }

    static final long PING_EVERY_MS = 15_000;
    static final long DEAD_AFTER_MS = 30_000;
    private static final int MAX_MESSAGE = 64 * 1024;
    /** About 30 s of status and ring messages: more than this waiting means the link is dead. */
    static final int MAX_QUEUED_TEXTS = 200;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;

    // Guarded by this.
    private final Deque<byte[]> texts = new ArrayDeque<>();
    private byte[] binary;
    private int closeCode;
    private String closeReason;
    private boolean closed;
    private volatile long lastHeardMs = System.currentTimeMillis();
    /** Free for the owner to use (the remote server keeps its client state here). */
    public volatile Object attachment;

    WebSocket(Socket socket, InputStream in, OutputStream out, Listener listener) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    public synchronized void sendText(String text) {
        if (closed || closeCode != 0) return;
        if (texts.size() >= MAX_QUEUED_TEXTS) {
            // Closing the socket ends the blocked write and the reader, which reports onClose.
            closed = true;
            texts.clear();
            binary = null;
            notifyAll();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        texts.add(text.getBytes(StandardCharsets.UTF_8));
        notifyAll();
    }

    /** Offers a binary message; replaces one that hasn't been sent yet. */
    public synchronized void offerBinary(byte[] data) {
        if (closed || closeCode != 0) return;
        binary = data;
        notifyAll();
    }

    /** True while a binary message is still waiting to go out. */
    public synchronized boolean binaryPending() {
        return binary != null;
    }

    /** Sends a close frame with this code (after any queued text) and ends the connection. */
    public synchronized void close(int code, String reason) {
        if (closed || closeCode != 0) return;
        closeCode = code;
        closeReason = reason == null ? "" : reason;
        notifyAll();
    }

    /** Runs the connection: the reader on this thread, the writer on another. Returns when closed. */
    void run() {
        Thread writer = new Thread(this::writeLoop, "ws-writer");
        writer.setDaemon(true);
        writer.start();
        try {
            listener.onOpen(this);
            readLoop();
        } catch (IOException e) {
            // the client went away
        } finally {
            synchronized (this) {
                closed = true;
                notifyAll();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            listener.onClose(this);
        }
    }

    private void readLoop() throws IOException {
        ByteArrayOutputStream message = null;
        int messageOp = 0;
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            lastHeardMs = System.currentTimeMillis();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long n = b1 & 0x7F;
            if (n == 126) n = (readByte() << 8) | readByte();
            else if (n == 127) {
                n = 0;
                for (int i = 0; i < 8; i++) n = (n << 8) | readByte();
            }
            if (n > MAX_MESSAGE) throw new IOException("frame too large");
            byte[] mask = new byte[4];
            if (masked) readFully(mask);
            byte[] data = new byte[(int) n];
            readFully(data);
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];

            switch (op) {
                case 0x8:  // close: echo the code, then end
                    boolean echo;
                    synchronized (this) {
                        echo = closeCode == 0;
                        if (echo) {
                            closeCode = 1000;
                            closeReason = "";
                        }
                    }
                    if (echo) writeFrame(0x8, data.length >= 2 ? new byte[]{data[0], data[1]} : new byte[0]);
                    return;
                case 0x9:  // ping
                    writeFrame(0xA, data);
                    break;
                case 0xA:  // pong
                    break;
                case 0x0:  // continuation
                    if (message == null) throw new IOException("unexpected continuation");
                    message.write(data);
                    if (message.size() > MAX_MESSAGE) throw new IOException("message too large");
                    if (fin) {
                        if (messageOp == 0x1) listener.onText(this, message.toString("UTF-8"));
                        message = null;
                    }
                    break;
                case 0x1:
                case 0x2:
                    if (fin) {
                        if (op == 0x1) listener.onText(this, new String(data, StandardCharsets.UTF_8));
                    } else {
                        message = new ByteArrayOutputStream();
                        message.write(data);
                        messageOp = op;
                    }
                    break;
                default:
                    throw new IOException("unknown opcode " + op);
            }
        }
    }

    private void writeLoop() {
        long lastPing = System.currentTimeMillis();
        try {
            while (true) {
                byte[][] batch;
                byte[] frame;
                int code;
                String reason;
                synchronized (this) {
                    while (!closed && texts.isEmpty() && binary == null && closeCode == 0
                            && System.currentTimeMillis() - lastPing < PING_EVERY_MS) {
                        wait(Math.max(1, PING_EVERY_MS - (System.currentTimeMillis() - lastPing)));
                    }
                    if (closed) return;
                    batch = texts.toArray(new byte[0][]);
                    texts.clear();
                    frame = binary;
                    binary = null;
                    code = closeCode;
                    reason = closeReason;
                }
                for (byte[] t : batch) writeFrame(0x1, t);
                if (frame != null) writeFrame(0x2, frame);
                if (code != 0) {
                    byte[] r = reason.getBytes(StandardCharsets.UTF_8);
                    byte[] payload = new byte[2 + Math.min(r.length, 120)];
                    payload[0] = (byte) (code >> 8);
                    payload[1] = (byte) code;
                    System.arraycopy(r, 0, payload, 2, payload.length - 2);
                    writeFrame(0x8, payload);
                    socket.close();  // the reader ends; no need to wait for the client's close
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastHeardMs > DEAD_AFTER_MS) {
                    socket.close();
                    return;
                }
                if (now - lastPing >= PING_EVERY_MS) {
                    writeFrame(0x9, new byte[0]);
                    lastPing = now;
                }
            }
        } catch (IOException | InterruptedException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Frames from the writer thread and pongs from the reader share the stream. */
    private void writeFrame(int op, byte[] payload) throws IOException {
        synchronized (out) {
            out.write(0x80 | op);
            int n = payload.length;
            if (n < 126) {
                out.write(n);
            } else if (n < 65536) {
                out.write(126);
                out.write(n >> 8);
                out.write(n);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((long) n >> (8 * i)));
            }
            out.write(payload);
            out.flush();
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
    }
}
