package org.snurkle.badgeturntable.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One physical badge being photographed. Plain data (no Android types) so it can
 * be unit tested; {@link SessionJson} reads and writes it.
 */
public final class Session {

    public enum SyncState { LOCAL_ONLY, PENDING, SYNCED, FAILED }

    /** A file the app produced for this badge, in index.md order. */
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

    public String id = UUID.randomUUID().toString();
    public long createdAtMs = System.currentTimeMillis();

    // Identity, written into index.md
    public String title = "";
    public String con = "defcon";
    public int year;
    public String group = "";
    public String event = "";
    public String slug = "";
    public boolean slugEdited;
    public String badgeId = "";

    // Options, remembered as defaults for the next session
    public boolean captureVideo = true;
    public boolean autoMode = true;
    public boolean syncToSamba = true;

    public final List<Media> media = new ArrayList<>();
    /** Files the app produced earlier and the user deleted; removed from index.md on the next write. */
    public final List<String> removed = new ArrayList<>();
    public SyncState syncState = SyncState.LOCAL_ONLY;

    public String effectiveSlug() {
        return slugEdited && !slug.isEmpty() ? slug : Slugs.make(group, title, year);
    }

    public Media find(String filename) {
        for (Media m : media) if (m.filename.equals(filename)) return m;
        return null;
    }

    /** Adds or replaces (re-shoot) a file, keeping its position. */
    public void put(Media m) {
        removed.remove(m.filename);
        for (int i = 0; i < media.size(); i++) {
            if (media.get(i).filename.equals(m.filename)) {
                media.set(i, m);
                return;
            }
        }
        media.add(m);
    }

    public void remove(String filename) {
        if (media.removeIf(m -> m.filename.equals(filename))) removed.add(filename);
    }
}
