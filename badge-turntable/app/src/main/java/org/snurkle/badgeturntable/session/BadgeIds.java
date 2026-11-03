package org.snurkle.badgeturntable.session;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** The 3-digit hex badge_id stickered on each physical badge (e.g. 00E, A3F). */
public final class BadgeIds {
    private static final Pattern VALID = Pattern.compile("[0-9A-F]{3}");

    private BadgeIds() {}

    /** Uppercased and zero-padded ("e" -> "00E"), or null if it isn't hex. */
    public static String normalize(String id) {
        if (id == null) return null;
        String s = id.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 3 || !s.matches("[0-9A-F]+")) return null;
        return String.format("%3s", s).replace(' ', '0');
    }

    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    /** One more than the highest known ID, or "001" if none are known; null when FFF is taken. */
    public static String suggestNext(Collection<String> known) {
        int max = 0;
        for (String k : known) {
            String n = normalize(k);
            if (n != null) max = Math.max(max, Integer.parseInt(n, 16));
        }
        if (max >= 0xFFF) return null;
        return String.format("%03X", max + 1);
    }
}
