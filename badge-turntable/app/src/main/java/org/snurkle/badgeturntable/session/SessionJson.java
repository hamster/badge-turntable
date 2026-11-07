package org.snurkle.badgeturntable.session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** session.json, stored in sessions/&lt;id&gt;/. */
public final class SessionJson {
    private SessionJson() {}

    public static JSONObject toJson(Session s) throws JSONException {
        JSONObject o = new JSONObject()
                .put("id", s.id).put("createdAtMs", s.createdAtMs).put("updatedAtMs", s.updatedAtMs)
                .put("name", s.name).put("notes", s.notes)
                .put("captureVideo", s.captureVideo).put("autoMode", s.autoMode);
        JSONArray media = new JSONArray();
        for (Session.Media m : s.media) {
            JSONObject j = new JSONObject()
                    .put("filename", m.filename).put("caption", m.caption).put("kind", m.kind.name())
                    .put("capturedAtMs", m.capturedAtMs);
            if (!Double.isNaN(m.angleNominal)) j.put("angleNominal", m.angleNominal);
            if (!Double.isNaN(m.angleActual)) j.put("angleActual", m.angleActual);
            if (m.setLabel != null) j.put("setLabel", m.setLabel);
            media.put(j);
        }
        o.put("media", media);
        return o;
    }

    public static Session fromJson(JSONObject o) throws JSONException {
        Session s = new Session();
        s.id = o.getString("id");
        s.createdAtMs = o.optLong("createdAtMs", s.createdAtMs);
        s.updatedAtMs = o.optLong("updatedAtMs", s.createdAtMs);
        s.name = o.optString("name");
        s.notes = o.optString("notes");
        s.captureVideo = o.optBoolean("captureVideo", true);
        s.autoMode = o.optBoolean("autoMode", true);
        JSONArray media = o.optJSONArray("media");
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject j = media.getJSONObject(i);
                Session.Media m = new Session.Media(j.getString("filename"), j.optString("caption"),
                        Session.Media.Kind.valueOf(j.getString("kind")));
                m.capturedAtMs = j.optLong("capturedAtMs");
                m.angleNominal = j.optDouble("angleNominal", Double.NaN);
                m.angleActual = j.optDouble("angleActual", Double.NaN);
                m.setLabel = j.has("setLabel") ? j.getString("setLabel") : null;
                s.media.add(m);
            }
        }
        return s;
    }
}
