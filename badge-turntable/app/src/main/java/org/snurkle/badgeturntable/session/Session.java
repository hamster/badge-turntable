package org.snurkle.badgeturntable.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One physical badge as the phone sees it: a name, optional notes and the files captured
 * for it (Spec.md, Sessions). Con, year, slug and badge ID are entered in the editor, not
 * here. Plain data (no Android types) so it can be unit tested; {@link SessionJson} reads
 * and writes it.
 */
public final class Session {

    /** A file the app produced for this badge, in capture order. */
    public static final class Media {
        public enum Kind { IMAGE, VIDEO }

        public String filename;
        public String caption;
        public Kind kind;
        /** Nominal and measured table angle for turntable views; NaN otherwise. */
        public double angleNominal = Double.NaN;
        public double angleActual = Double.NaN;
        public long capturedAtMs;
        /** Label of an extra set ("With SAOs installed"), or null for the main set / extra shots. */
        public String setLabel;

        public Media() {}

        public Media(String filename, String caption, Kind kind) {
            this.filename = filename;
            this.caption = caption;
            this.kind = kind;
            this.capturedAtMs = System.currentTimeMillis();
        }
    }

    /** Never changes; the session's folder is named after it, so renaming moves nothing. */
    public String id = UUID.randomUUID().toString();
    public long createdAtMs = System.currentTimeMillis();
    /** Set on every save. */
    public long updatedAtMs = createdAtMs;

    public String name = "";
    /** Free text, Markdown allowed; the editor uses it as the badge page's body. */
    public String notes = "";

    // Options, remembered as defaults for the next session
    public boolean captureVideo = true;
    public boolean autoMode = true;

    public final List<Media> media = new ArrayList<>();

    public Media find(String filename) {
        for (Media m : media) if (m.filename.equals(filename)) return m;
        return null;
    }

    /** Adds or replaces (re-shoot) a file, keeping its position. */
    public void put(Media m) {
        for (int i = 0; i < media.size(); i++) {
            if (media.get(i).filename.equals(m.filename)) {
                media.set(i, m);
                return;
            }
        }
        media.add(m);
    }

    /** @return whether there was such a file */
    public boolean remove(String filename) {
        return media.removeIf(m -> m.filename.equals(filename));
    }

    /** The card thumbnail: front.jpg, else the first photo, else null. */
    public String cover() {
        String first = null;
        for (Media m : media) {
            if (m.kind != Media.Kind.IMAGE) continue;
            if (m.filename.equals("front.jpg")) return m.filename;
            if (first == null) first = m.filename;
        }
        return first;
    }
}
