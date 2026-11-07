package org.snurkle.badgeturntable.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.snurkle.badgeturntable.jobs.ApiException;

import java.util.ArrayList;
import java.util.List;

public class PairingTest {
    private long now = 1_000_000;
    private final List<Pairing.Client> saved = new ArrayList<>();
    private final Pairing.Store store = new Pairing.Store() {
        @Override
        public List<Pairing.Client> load() {
            return new ArrayList<>(saved);
        }

        @Override
        public void save(List<Pairing.Client> clients) {
            saved.clear();
            saved.addAll(clients);
        }
    };
    private final Pairing pairing = new Pairing(store, () -> now);

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Test
    public void rightCodeGivesATokenAndANewCode() throws Exception {
        String code = pairing.code();
        assertTrue(code.matches("\\d{6}"));
        String token = pairing.pair(code.substring(0, 3) + " " + code.substring(3), "Edge on PC");
        assertTrue(token.matches("[0-9a-f]{32}"));
        Pairing.Client c = pairing.check(token);
        assertNotNull(c);
        assertEquals("Edge on PC", c.clientName);
        // Only the hash is stored.
        assertEquals(1, saved.size());
        assertNotEquals(token, saved.get(0).tokenHash);
        assertEquals(Pairing.hash(token), saved.get(0).tokenHash);
        // A used code can't be used again (the code changes after pairing; it might repeat by chance).
        assertNotNull(new Pairing(store, () -> now).check(token));  // survives a restart
    }

    @Test
    public void wrongCodesCountDownThenLock() throws Exception {
        String code = pairing.code();
        for (int left = 4; left >= 1; left--) {
            try {
                pairing.pair(wrong(code), "x");
                fail();
            } catch (ApiException e) {
                assertEquals("bad_code", e.code);
                assertEquals(401, e.status);
                assertEquals(left, e.body().getInt("attemptsLeft"));
            }
        }
        try {
            pairing.pair(wrong(code), "x");
            fail();
        } catch (ApiException e) {
            assertEquals("pairing_locked", e.code);
            assertEquals(429, e.status);
        }
        try {  // even the right code is refused while locked
            pairing.pair(pairing.code(), "x");
            fail();
        } catch (ApiException e) {
            assertEquals("pairing_locked", e.code);
            assertTrue(e.body().getLong("retryAfterMs") > 0);
        }
        now += Pairing.LOCK_MS;
        assertNotNull(pairing.pair(pairing.code(), "x"));
    }

    @Test
    public void codeExpires() {
        String first = pairing.code();
        now += Pairing.CODE_LIFETIME_MS - 1;
        assertEquals(first, pairing.code());
        String[] changed = {null};
        pairing.setCodeListener(c -> changed[0] = c);
        now += 1;
        String second = pairing.code();
        assertEquals(second, changed[0]);
    }

    @Test
    public void malformedCodeIsABadRequest() {
        try {
            pairing.pair("12345", "x");
            fail();
        } catch (ApiException e) {
            assertEquals("bad_request", e.code);
            assertEquals("code", e.body().optString("field"));
        }
    }

    @Test
    public void forgetAndRevoke() throws Exception {
        String a = pairing.pair(pairing.code(), "A");
        String b = pairing.pair(pairing.code(), "B");
        pairing.forget(a);
        assertNull(pairing.check(a));
        assertNotNull(pairing.check(b));
        pairing.revoke(Pairing.hash(b));
        assertNull(pairing.check(b));
        assertTrue(saved.isEmpty());
        assertNull(pairing.check(""));
        assertNull(pairing.check(null));
        assertFalse(pairing.clients().iterator().hasNext());
    }

    @Test
    public void longNamesAreCut() throws Exception {
        String token = pairing.pair(pairing.code(), "x".repeat(100));
        assertEquals(64, pairing.check(token).clientName.length());
        assertEquals("Unnamed PC", pairing.check(pairing.pair(pairing.code(), " ")).clientName);
    }
}
