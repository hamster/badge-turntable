package org.snurkle.badgeturntable.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.snurkle.badgeturntable.media.AngleLog;

import java.util.Collections;
import java.util.Set;

public class SessionTest {

    @Test
    public void slugs() {
        assertEquals("electric-sampler", Slugs.kebab("Électric  Sampler!"));
        assertEquals("lanyard.jpg", Slugs.extraShotName("Lanyard", Collections.emptySet()));
        assertEquals("lanyard-3.jpg", Slugs.extraShotName("Lanyard", Set.of("lanyard.jpg", "lanyard-2.jpg")));
        assertEquals("with-saos-installed-", Slugs.setPrefix("With SAOs installed"));
    }

    @Test
    public void sessionJsonRoundTrip() throws Exception {
        Session s = new Session();
        s.name = "Mk9";
        s.notes = "Blinks.\n\nSecond paragraph.";
        s.captureVideo = false;
        Session.Media m = new Session.Media("front.jpg", "Front", Session.Media.Kind.IMAGE);
        m.angleNominal = 0;
        m.angleActual = 0.4;
        s.put(m);
        s.put(new Session.Media("lanyard.jpg", "Lanyard", Session.Media.Kind.IMAGE));
        assertTrue(s.remove("lanyard.jpg"));
        assertFalse(s.remove("lanyard.jpg"));

        Session back = SessionJson.fromJson(new org.json.JSONObject(SessionJson.toJson(s).toString()));
        assertEquals(s.id, back.id);
        assertEquals("Mk9", back.name);
        assertEquals("Blinks.\n\nSecond paragraph.", back.notes);
        assertEquals(s.updatedAtMs, back.updatedAtMs);
        assertFalse(back.captureVideo);
        assertEquals(1, back.media.size());
        assertEquals(0.4, back.media.get(0).angleActual, 1e-9);
    }

    @Test
    public void coverIsTheFrontElseTheFirstPhoto() {
        Session s = new Session();
        assertNull(s.cover());
        s.put(new Session.Media("spin.mp4", "", Session.Media.Kind.VIDEO));
        s.put(new Session.Media("lanyard.jpg", "Lanyard", Session.Media.Kind.IMAGE));
        assertEquals("lanyard.jpg", s.cover());
        s.put(new Session.Media("front.jpg", "Front", Session.Media.Kind.IMAGE));
        assertEquals("front.jpg", s.cover());
    }

    @Test
    public void angleLogFindsOneRevolution() {
        AngleLog log = new AngleLog();
        // 24 degrees per 100 ms, starting at 350 and wrapping past 360.
        for (int i = 0; i <= 20; i++) log.add(i * 100_000_000L, (350 + i * 24) % 360);
        long[] rev = log.oneRevolution();
        assertEquals(0, rev[0]);
        assertEquals(1_500_000_000L, rev[1]);  // 360 / 24 = 15 frames
        assertEquals(480, log.travelled(), 1e-9);
    }

    @Test
    public void angleLogNeedsAFullTurn() {
        AngleLog log = new AngleLog();
        for (int i = 0; i < 10; i++) log.add(i, i * 10);
        assertNull(log.oneRevolution());
    }
}
