package org.snurkle.badgeturntable.remote;

import org.json.JSONObject;
import org.snurkle.badgeturntable.capture.View;
import org.snurkle.badgeturntable.jobs.Json;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A session's files as the API shows them (Remote-API.md, Sessions): the Shot object's
 * fields without id and jobId, in the same standard order as shots.
 */
final class SessionMedia {
    private SessionMedia() {}

    static String kind(Session.Media m) {
        if (m.kind == Session.Media.Kind.VIDEO) return "spin";
        return Double.isNaN(m.angleNominal) ? "extra" : "view";
    }

    /** The main set's views, its spin, each extra set in the order first shot, then extra shots by time. */
    static List<Session.Media> ordered(Session s) {
        List<String> setOrder = new ArrayList<>();
        for (Session.Media m : s.media) if (m.setLabel != null && !setOrder.contains(m.setLabel)) setOrder.add(m.setLabel);
        List<Session.Media> out = new ArrayList<>(s.media);
        out.sort(Comparator.<Session.Media>comparingInt(m -> "extra".equals(kind(m)) ? 2 : m.setLabel != null ? 1 : 0)
                .thenComparingInt(m -> m.setLabel == null ? 0 : setOrder.indexOf(m.setLabel))
                .thenComparingInt(SessionMedia::viewIndex)
                .thenComparingLong(m -> m.capturedAtMs));
        return out;
    }

    private static int viewIndex(Session.Media m) {
        if ("extra".equals(kind(m))) return 0;
        View v = view(m);
        return v == null ? View.values().length : v.ordinal();  // the spin after the views
    }

    private static View view(Session.Media m) {
        if (Double.isNaN(m.angleNominal)) return null;
        for (View v : View.values()) if (v.angle == (int) Math.round(m.angleNominal)) return v;
        return null;
    }

    static JSONObject toJson(SessionStore store, Session s, Session.Media m) {
        String kind = kind(m);
        File f = store.file(s, m.filename);
        View v = view(m);
        String caption = "spin".equals(kind)
                ? (m.setLabel != null ? m.setLabel + " (spin)" : "Spin")
                : m.caption;
        RemoteShots.Info web = "spin".equals(kind) ? RemoteShots.videoInfo(f, m.filename) : RemoteShots.imageInfo(f, m.filename);
        return Json.obj("filename", m.filename, "kind", kind, "setLabel", m.setLabel,
                "view", v == null ? null : v.baseName,
                "label", "extra".equals(kind) ? m.caption : null,
                "caption", caption,
                "nominalDeg", Json.deg(m.angleNominal), "actualDeg", Json.deg(m.angleActual),
                "takenAt", Json.time(m.capturedAtMs),
                "ready", true,
                "web", web.toJson(),
                "original", null,
                "durationMs", "spin".equals(kind) ? RemoteShots.durationMs(f) : null);
    }
}
