package org.snurkle.badgeturntable.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.snurkle.badgeturntable.session.BadgeIds;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Badge IDs the app knows are taken, for suggesting a new one that doesn't collide:
 * sessions on this phone, IDs found on the Samba share, and the published site's
 * badge-ids.json. Remote results are cached for offline use.
 */
public final class KnownBadgeIds {
    public static final String KEY_SITE_URL = "badge_ids_url";
    public static final String DEFAULT_SITE_URL = "https://hamster.github.io/badges/badge-ids.json";
    private static final String PREFS = "known_badge_ids";

    private KnownBadgeIds() {}

    /** ID -> who has it ("dczia-mk9-2026", or "share"/"site" when the slug isn't known). */
    public static Map<String, String> all(Context context) {
        Map<String, String> out = new HashMap<>();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String entry : p.getStringSet("remote", new HashSet<>())) {
            String[] kv = entry.split("=", 2);
            out.put(kv[0], kv.length > 1 ? kv[1] : "elsewhere");
        }
        for (Session s : new SessionStore(context).list()) {
            String id = BadgeIds.normalize(s.badgeId);
            if (id != null) out.put(id, s.effectiveSlug());
        }
        return out;
    }

    public static String suggest(Context context) {
        return BadgeIds.suggestNext(all(context).keySet());
    }

    static void rememberFromShare(Context context, Set<String> ids) {
        Map<String, String> tagged = new HashMap<>();
        for (String id : ids) tagged.put(id, "on the share");
        merge(context, tagged);
    }

    /** Fetches badge-ids.json from the site. Blocking; call off the main thread. */
    public static void refreshFromSite(Context context) throws Exception {
        String url = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_SITE_URL, DEFAULT_SITE_URL);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) {
            JSONArray arr = new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, String> ids = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                String id = BadgeIds.normalize(arr.getJSONObject(i).optString("badge_id"));
                if (id != null) ids.put(id, arr.getJSONObject(i).optString("slug", "on the site"));
            }
            merge(context, ids);
        } finally {
            c.disconnect();
        }
    }

    private static synchronized void merge(Context context, Map<String, String> ids) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(p.getStringSet("remote", new HashSet<>()));
        for (Map.Entry<String, String> e : ids.entrySet()) {
            set.removeIf(s -> s.startsWith(e.getKey() + "="));
            set.add(e.getKey() + "=" + e.getValue());
        }
        p.edit().putStringSet("remote", set).apply();
    }
}
