package org.snurkle.badgeturntable.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;

public class MdnsResponderTest {
    private final MdnsResponder mdns;

    public MdnsResponderTest() throws Exception {
        mdns = new MdnsResponder("badge-turntable", (Inet4Address) InetAddress.getByName("192.168.10.23"), 8737,
                Arrays.asList("api=1", "id=6f1c0b9e2a7d4c35", "path=/api/"));
    }

    private static byte[] query(int id, String name, int type) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int[] head = {id, 0, 1, 0, 0, 0};
        for (int h : head) {
            o.write(h >> 8);
            o.write(h);
        }
        byte[] n = MdnsResponder.name(name);
        o.write(n, 0, n.length);
        o.write(type >> 8);
        o.write(type);
        o.write(0);
        o.write(1);
        return o.toByteArray();
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    @Test
    public void answersTheHostName() {
        byte[] q = query(0, "Badge-Turntable.local", 1);  // names are case-insensitive
        byte[] r = mdns.answer(q, q.length, false);
        assertNotNull(r);
        assertEquals(0x8400, u16(r, 2));
        assertEquals(0, u16(r, 4));  // no questions echoed for multicast
        assertEquals(1, u16(r, 6));
        assertTrue(indexOf(r, new byte[]{(byte) 192, (byte) 168, 10, 23}) > 0);
    }

    @Test
    public void legacyUnicastEchoesIdAndQuestion() {
        byte[] q = query(0x1234, "badge-turntable.local", 1);
        byte[] r = mdns.answer(q, q.length, true);
        assertNotNull(r);
        assertEquals(0x1234, u16(r, 0));
        assertEquals(1, u16(r, 4));
        StringBuilder name = new StringBuilder();
        MdnsResponder.readName(r, r.length, 12, name);
        assertEquals("badge-turntable.local", name.toString());
    }

    @Test
    public void serviceBrowseGetsPtrSrvTxtAndA() {
        byte[] q = query(0, MdnsResponder.SERVICE, 12);
        byte[] r = mdns.answer(q, q.length, false);
        assertNotNull(r);
        assertEquals(4, u16(r, 6));  // PTR, SRV, TXT, A
        assertTrue(indexOf(r, "path=/api/".getBytes()) > 0);
        assertTrue(indexOf(r, new byte[]{0x22, 0x21}) > 0);  // port 8737
    }

    @Test
    public void ignoresOtherNamesAndResponses() {
        byte[] q = query(0, "printer.local", 1);
        assertNull(mdns.answer(q, q.length, false));
        byte[] aaaa = query(0, "badge-turntable.local", 28);
        assertNull(mdns.answer(aaaa, aaaa.length, false));
        byte[] response = query(0, "badge-turntable.local", 1);
        response[2] = (byte) 0x84;
        assertNull(mdns.answer(response, response.length, false));
        assertNull(mdns.answer(new byte[5], 5, false));
    }

    @Test
    public void readsCompressedNames() {
        // "local" at 12, then a name "badge-turntable" + pointer to 12.
        byte[] p = new byte[64];
        byte[] local = MdnsResponder.name("local");
        System.arraycopy(local, 0, p, 12, local.length);
        int at = 12 + local.length;
        p[at] = 15;
        System.arraycopy("badge-turntable".getBytes(), 0, p, at + 1, 15);
        p[at + 16] = (byte) 0xC0;
        p[at + 17] = 12;
        StringBuilder name = new StringBuilder();
        int end = MdnsResponder.readName(p, p.length, at, name);
        assertEquals("badge-turntable.local", name.toString());
        assertEquals(at + 18, end);
    }

    @Test
    public void goodbyeHasZeroTtl() {
        byte[] bye = mdns.announcement(0);
        // First record: the A record. Its TTL follows name, type and class.
        int ttlAt = 12 + MdnsResponder.name("badge-turntable.local").length + 4;
        assertEquals(0, u16(bye, ttlAt) | u16(bye, ttlAt + 2));
        byte[] hello = mdns.announcement(1);
        assertEquals(120, u16(hello, ttlAt + 2));
    }
}
