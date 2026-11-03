package org.snurkle.badgeturntable.session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** session.json, stored in _capture/<con>/<slug>/. */
public final class SessionJson {
    private SessionJson() {}

    public static JSONObject toJson(Session s) throws JSONException {
        JSONObject o = new JSONObject()
                .put("id", s.id).put("createdAtMs", s.createdAtMs)
                .put("title", s.title).put("con", s.con).put("year", s.year)
                .put("group", s.group).put("event", s.event)
                .put("slug", s.effectiveSlug()).put("slugEdited", s.slugEdited)
                .put("badgeId", s.badgeId)
                .put("captureVideo", s.captureVideo).put("autoMode", s.autoMode).put("syncToSamba", s.syncToSamba)
                .put("syncState", s.syncState.name());
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
        o.put("removed", new JSONArray(s.removed));
        return o;
    }

    public static Session fromJson(JSONObject o) throws JSONException {
        Session s = new Session();
        s.id = o.getString("id");
        s.createdAtMs = o.optLong("createdAtMs", s.createdAtMs);
        s.title = o.optString("title");
        s.con = o.optString("con", "defcon");
        s.year = o.optInt("year");
        s.group = o.optString("group");
        s.event = o.optString("event");
        s.slug = o.optString("slug");
        s.slugEdited = o.optBoolean("slugEdited");
        s.badgeId = o.optString("badgeId");
        s.captureVideo = o.optBoolean("captureVideo", true);
        s.autoMode = o.optBoolean("autoMode", true);
        s.syncToSamba = o.optBoolean("syncToSamba", true);
        s.syncState = Session.SyncState.valueOf(o.optString("syncState", Session.SyncState.LOCAL_ONLY.name()));
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
        JSONArray removed = o.optJSONArray("removed");
        if (removed != null) for (int i = 0; i < removed.length(); i++) s.removed.add(removed.getString(i));
        return s;
    }
}
