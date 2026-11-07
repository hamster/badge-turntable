package org.snurkle.badgeturntable.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.snurkle.badgeturntable.session.Session;

import java.util.ArrayList;
import java.util.List;

public class SessionMediaTest {

    private static Session.Media media(String filename, Session.Media.Kind kind, double angle, String set, long at) {
        Session.Media m = new Session.Media(filename, filename, kind);
        m.angleNominal = angle;
        m.setLabel = set;
        m.capturedAtMs = at;
        return m;
    }

    @Test
    public void standardOrder() {
        Session s = new Session();
        // Taken in a muddled order: an extra shot first, an extra set, then the main set.
        s.media.add(media("lanyard.jpg", Session.Media.Kind.IMAGE, Double.NaN, null, 1));
        s.media.add(media("with-saos-spin.mp4", Session.Media.Kind.VIDEO, Double.NaN, "With SAOs", 2));
        s.media.add(media("with-saos-front.jpg", Session.Media.Kind.IMAGE, 0, "With SAOs", 3));
        s.media.add(media("spin.mp4", Session.Media.Kind.VIDEO, Double.NaN, null, 4));
        s.media.add(media("back.jpg", Session.Media.Kind.IMAGE, 180, null, 5));
        s.media.add(media("front.jpg", Session.Media.Kind.IMAGE, 0, null, 6));
        s.media.add(media("detail.jpg", Session.Media.Kind.IMAGE, Double.NaN, null, 0));

        List<String> names = new ArrayList<>();
        for (Session.Media m : SessionMedia.ordered(s)) names.add(m.filename);
        assertEquals(List.of("front.jpg", "back.jpg", "spin.mp4", "with-saos-front.jpg", "with-saos-spin.mp4",
                "detail.jpg", "lanyard.jpg"), names);
    }

    @Test
    public void kinds() {
        assertEquals("spin", SessionMedia.kind(media("spin.mp4", Session.Media.Kind.VIDEO, Double.NaN, null, 0)));
        assertEquals("view", SessionMedia.kind(media("front.jpg", Session.Media.Kind.IMAGE, 0, null, 0)));
        assertEquals("extra", SessionMedia.kind(media("lanyard.jpg", Session.Media.Kind.IMAGE, Double.NaN, null, 0)));
    }
}
