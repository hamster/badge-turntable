package org.snurkle.badgeturntable.session;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** File names, following the repo's conventions (lowercase kebab-case). */
public final class Slugs {
    private Slugs() {}

    /** Lowercase kebab-case ASCII. */
    public static String kebab(String s) {
        if (s == null) return "";
        String ascii = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * Filename for an extra shot: the label in kebab-case, numbered on repeats
     * ("lanyard.jpg", "lanyard-2.jpg").
     */
    public static String extraShotName(String label, Set<String> taken) {
        String base = kebab(label);
        if (base.isEmpty()) base = "extra";
        String name = base + ".jpg";
        for (int n = 2; taken.contains(name); n++) name = base + "-" + n + ".jpg";
        return name;
    }

    /** Prefix for an extra set's files: "With SAOs installed" -> "with-saos-installed-". */
    public static String setPrefix(String setLabel) {
        String k = kebab(setLabel);
        return k.isEmpty() ? "" : k + "-";
    }
}
