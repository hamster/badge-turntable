package org.snurkle.badgeturntable.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A minimal mDNS responder (RFC 6762/6763), so Windows and the editor can find the phone as
 * {@code <name>.local}. It answers A queries for the host name and advertises an
 * {@code _badge-turntable._tcp} service with SRV and TXT records. It doesn't probe for
 * name conflicts: give a second phone its own name in Settings.
 *
 * JmDNS was the plan, but a 200-line responder is easier to keep working than a library
 * that also wants SLF4J, and the packet handling here is unit tested.
 */
public final class MdnsResponder {
    static final String SERVICE = "_badge-turntable._tcp.local";
    private static final String SERVICES_META = "_services._dns-sd._udp.local";
    private static final int MDNS_PORT = 5353;
    private static final int TYPE_A = 1, TYPE_PTR = 12, TYPE_TXT = 16, TYPE_SRV = 33, TYPE_ANY = 255;
    private static final int CLASS_IN = 1, CACHE_FLUSH = 0x8000;
    private static final int TTL_HOST = 120, TTL_SERVICE = 4500;

    private final String host;      // "badge-turntable.local"
    private final String instance;  // "badge-turntable._badge-turntable._tcp.local"
    private final Inet4Address address;
    private final int port;
    private final List<String> txt;
    private volatile MulticastSocket socket;
    private NetworkInterface nif;

    /**
     * @param name    the host name without ".local"
     * @param address the phone's Wi-Fi address
     * @param txt     TXT strings such as "api=1"
     */
    public MdnsResponder(String name, Inet4Address address, int port, List<String> txt) {
        this.host = name + ".local";
        this.instance = name + "." + SERVICE;
        this.address = address;
        this.port = port;
        this.txt = new ArrayList<>(txt);
    }

    public void start() throws IOException {
        nif = NetworkInterface.getByInetAddress(address);
        InetAddress group = InetAddress.getByName("224.0.0.251");
        MulticastSocket s = new MulticastSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(MDNS_PORT));
        s.setTimeToLive(255);
        if (nif != null) {
            s.setNetworkInterface(nif);
            s.joinGroup(new InetSocketAddress(group, MDNS_PORT), nif);
        } else {
            s.joinGroup(new InetSocketAddress(group, MDNS_PORT), null);
        }
        s.setSoTimeout(1000);
        socket = s;
        Thread t = new Thread(this::loop, "mdns");
        t.setDaemon(true);
        t.start();
    }

    /** Sends a goodbye and closes, on its own thread (this is called from onStop, on the main thread). */
    public void stop() {
        MulticastSocket s = socket;
        socket = null;
        if (s == null) return;
        Thread t = new Thread(() -> {
            try {
                send(s, announcement(0), null);  // goodbye: TTL 0 tells caches to forget us
            } catch (IOException | RuntimeException ignored) {
            }
            s.close();
        }, "mdns-stop");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        byte[] buf = new byte[9000];
        int announcements = 0;
        long nextAnnounce = 0;
        MulticastSocket s;
        while ((s = socket) != null) {
            long now = System.currentTimeMillis();
            if (announcements < 2 && now >= nextAnnounce) {
                try {
                    send(s, announcement(1), null);
                } catch (IOException ignored) {
                }
                announcements++;
                nextAnnounce = now + 1000;
            }
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (socket == null) return;
                continue;
            }
            boolean legacy = p.getPort() != MDNS_PORT;
            byte[] reply = answer(buf, p.getLength(), legacy);
            if (reply == null) continue;
            try {
                send(s, reply, legacy ? new InetSocketAddress(p.getAddress(), p.getPort()) : null);
            } catch (IOException ignored) {
            }
        }
    }

    private static void send(MulticastSocket s, byte[] data, InetSocketAddress to) throws IOException {
        InetSocketAddress dest = to != null ? to : new InetSocketAddress(InetAddress.getByName("224.0.0.251"), MDNS_PORT);
        s.send(new DatagramPacket(data, data.length, dest));
    }

    // ---- Packets ---------------------------------------------------------------

    /** Unsolicited response with every record; ttlScale 0 makes it a goodbye. */
    byte[] announcement(int ttlScale) {
        Packet out = new Packet(0);
        out.answer(this::a, ttlScale);
        out.answer(this::ptr, ttlScale);
        out.answer(this::srv, ttlScale);
        out.answer(this::txt, ttlScale);
        return out.bytes(null);
    }

    /**
     * The reply to one query packet, or null if it asks nothing we answer. A legacy unicast
     * query (not from port 5353) gets its ID and questions echoed, as RFC 6762 §6.7 asks.
     */
    byte[] answer(byte[] q, int len, boolean legacyUnicast) {
        try {
            if (len < 12) return null;
            int id = u16(q, 0), flags = u16(q, 2), qd = u16(q, 4);
            if ((flags & 0x8000) != 0) return null;  // a response, not a query
            Packet out = new Packet(legacyUnicast ? id : 0);
            boolean wantA = false, wantPtr = false, wantSrv = false, wantTxt = false, wantMeta = false;
            int pos = 12;
            for (int i = 0; i < qd; i++) {
                StringBuilder name = new StringBuilder();
                pos = readName(q, len, pos, name);
                if (pos + 4 > len) return null;
                int type = u16(q, pos);
                pos += 4;
                String n = name.toString().toLowerCase(Locale.ROOT);
                boolean any = type == TYPE_ANY;
                if (n.equals(host.toLowerCase(Locale.ROOT)) && (any || type == TYPE_A)) wantA = true;
                if (n.equals(SERVICE) && (any || type == TYPE_PTR)) wantPtr = true;
                if (n.equals(instance.toLowerCase(Locale.ROOT))) {
                    if (any || type == TYPE_SRV) wantSrv = true;
                    if (any || type == TYPE_TXT) wantTxt = true;
                }
                if (n.equals(SERVICES_META) && (any || type == TYPE_PTR)) wantMeta = true;
            }
            if (!(wantA || wantPtr || wantSrv || wantTxt || wantMeta)) return null;
            byte[] questions = null;
            if (legacyUnicast) {
                questions = new byte[pos - 12];
                System.arraycopy(q, 12, questions, 0, questions.length);
            }
            int ttl = legacyUnicast ? -10 : 1;  // legacy answers get short TTLs
            if (wantA) out.answer(this::a, ttl);
            if (wantPtr) out.answer(this::ptr, ttl);
            if (wantSrv || wantPtr) out.answer(this::srv, ttl);
            if (wantTxt || wantPtr) out.answer(this::txt, ttl);
            if (wantMeta) out.answer(this::meta, ttl);
            if (!wantA && (wantPtr || wantSrv)) out.answer(this::a, ttl);
            return out.bytes(legacyUnicast ? new Object[]{qd, questions} : null);
        } catch (RuntimeException e) {
            return null;  // malformed packet
        }
    }

    private void a(ByteArrayOutputStream o, int ttl) {
        record(o, host, TYPE_A, CLASS_IN | CACHE_FLUSH, ttl(TTL_HOST, ttl), address.getAddress());
    }

    private void ptr(ByteArrayOutputStream o, int ttl) {
        record(o, SERVICE, TYPE_PTR, CLASS_IN, ttl(TTL_SERVICE, ttl), name(instance));
    }

    private void meta(ByteArrayOutputStream o, int ttl) {
        record(o, SERVICES_META, TYPE_PTR, CLASS_IN, ttl(TTL_SERVICE, ttl), name(SERVICE));
    }

    private void srv(ByteArrayOutputStream o, int ttl) {
        ByteArrayOutputStream rd = new ByteArrayOutputStream();
        rd.write(0);
        rd.write(0);  // priority
        rd.write(0);
        rd.write(0);  // weight
        rd.write(port >> 8);
        rd.write(port);
        byte[] target = name(host);
        rd.write(target, 0, target.length);
        record(o, instance, TYPE_SRV, CLASS_IN | CACHE_FLUSH, ttl(TTL_HOST, ttl), rd.toByteArray());
    }

    private void txt(ByteArrayOutputStream o, int ttl) {
        ByteArrayOutputStream rd = new ByteArrayOutputStream();
        for (String t : txt) {
            byte[] b = t.getBytes(StandardCharsets.UTF_8);
            rd.write(Math.min(b.length, 255));
            rd.write(b, 0, Math.min(b.length, 255));
        }
        record(o, instance, TYPE_TXT, CLASS_IN | CACHE_FLUSH, ttl(TTL_SERVICE, ttl), rd.toByteArray());
    }

    /** ttl: 1 = normal, 0 = goodbye, negative = that many seconds at most (legacy unicast). */
    private static int ttl(int normal, int ttl) {
        return ttl >= 0 ? normal * ttl : Math.min(normal, -ttl);
    }

    private static void record(ByteArrayOutputStream o, String name, int type, int cls, int ttl, byte[] rdata) {
        byte[] n = name(name);
        o.write(n, 0, n.length);
        o.write(type >> 8);
        o.write(type);
        o.write(cls >> 8);
        o.write(cls);
        o.write(ttl >>> 24);
        o.write(ttl >> 16);
        o.write(ttl >> 8);
        o.write(ttl);
        o.write(rdata.length >> 8);
        o.write(rdata.length);
        o.write(rdata, 0, rdata.length);
    }

    static byte[] name(String dotted) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String label : dotted.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        return o.toByteArray();
    }

    /** Reads a possibly compressed name at pos; returns the position after it. */
    static int readName(byte[] p, int len, int pos, StringBuilder out) {
        int end = -1, jumps = 0;
        while (true) {
            if (pos >= len) throw new IllegalArgumentException("name runs off the packet");
            int l = p[pos] & 0xFF;
            if (l == 0) {
                pos++;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len || ++jumps > 20) throw new IllegalArgumentException("bad pointer");
                if (end < 0) end = pos + 2;
                pos = ((l & 0x3F) << 8) | (p[pos + 1] & 0xFF);
                continue;
            }
            if (pos + 1 + l > len) throw new IllegalArgumentException("label runs off the packet");
            if (out.length() > 0) out.append('.');
            out.append(new String(p, pos + 1, l, StandardCharsets.UTF_8));
            pos += 1 + l;
        }
        return end >= 0 ? end : pos;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private interface RecordWriter {
        void write(ByteArrayOutputStream o, int ttl);
    }

    private static final class Packet {
        private final int id;
        private final ByteArrayOutputStream answers = new ByteArrayOutputStream();
        private int count;

        Packet(int id) {
            this.id = id;
        }

        void answer(RecordWriter w, int ttl) {
            w.write(answers, ttl);
            count++;
        }

        /** @param questions null, or {question count, raw question bytes} to echo */
        byte[] bytes(Object[] questions) {
            int qd = questions == null ? 0 : (Integer) questions[0];
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int[] head = {id, 0x8400, qd, count, 0, 0};  // response, authoritative
            for (int h : head) {
                o.write(h >> 8);
                o.write(h);
            }
            if (questions != null) {
                byte[] raw = (byte[]) questions[1];
                o.write(raw, 0, raw.length);
            }
            byte[] a = answers.toByteArray();
            o.write(a, 0, a.length);
            return o.toByteArray();
        }
    }
}
