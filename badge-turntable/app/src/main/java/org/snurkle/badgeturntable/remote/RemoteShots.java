package org.snurkle.badgeturntable.remote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.snurkle.badgeturntable.capture.CaptureTarget;
import org.snurkle.badgeturntable.capture.View;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.jobs.Json;
import org.snurkle.badgeturntable.session.Session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one unnamed set of shots the phone holds for the editor (Remote-API.md, Shots): no
 * badge identity, slug or index.md. Files keep the standard names; the editor decides where
 * they go. Kept in app storage with a shots.json index, so they survive the editor
 * disconnecting and the app restarting, until the editor deletes them.
 *
 * <pre>
 * files/remote/web/        web copies (2400 px JPEG, transcoded MP4)
 * files/remote/originals/  full-resolution stills, source videos
 * files/remote/thumbs/     320 px JPEG per shot ID
 * files/remote/shots.json
 * </pre>
 */
public final class RemoteShots {
    private static final String TAG = "RemoteShots";
    private static final int THUMB_EDGE = 320;

    public interface Listener {
        /** A shot was added or changed (a spin became ready). */
        void onShot(JSONObject shot);

        void onShotRemoved(String id);
    }

    static final class Info {
        String mime;
        long bytes;
        Integer width, height;
        String filename;

        JSONObject toJson() {
            return Json.obj("mime", mime, "bytes", bytes, "width", width, "height", height, "filename", filename);
        }

        static Info fromJson(JSONObject o) {
            if (o == null) return null;
            Info i = new Info();
            i.mime = o.optString("mime");
            i.bytes = o.optLong("bytes");
            i.width = o.isNull("width") ? null : o.optInt("width");
            i.height = o.isNull("height") ? null : o.optInt("height");
            i.filename = o.optString("filename");
            return i;
        }
    }

    static final class Shot {
        String id, kind, setLabel, view, label, filename, caption, jobId, originalName;
        double nominal = Double.NaN, actual = Double.NaN;
        long takenAtMs;
        boolean ready = true;
        Info web, original;
        Long durationMs;

        JSONObject toJson() {
            return Json.obj("id", id, "kind", kind, "setLabel", setLabel, "view", view, "label", label,
                    "filename", filename, "caption", caption, "nominalDeg", Json.deg(nominal),
                    "actualDeg", Json.deg(actual), "takenAt", Json.time(takenAtMs), "jobId", jobId,
                    "ready", ready, "web", web == null ? null : web.toJson(),
                    "original", original == null ? null : original.toJson(), "durationMs", durationMs);
        }

        JSONObject storeJson() {
            JSONObject o = toJson();
            Json.put(o, "originalName", originalName);
            Json.put(o, "takenAtMs", takenAtMs);
            return o;
        }

        static Shot fromJson(JSONObject o) {
            Shot s = new Shot();
            s.id = o.optString("id");
            s.kind = o.optString("kind");
            s.setLabel = str(o, "setLabel");
            s.view = str(o, "view");
            s.label = str(o, "label");
            s.filename = o.optString("filename");
            s.caption = o.optString("caption");
            s.jobId = str(o, "jobId");
            s.originalName = o.optString("originalName", s.filename);
            s.nominal = o.isNull("nominalDeg") ? Double.NaN : o.optDouble("nominalDeg");
            s.actual = o.isNull("actualDeg") ? Double.NaN : o.optDouble("actualDeg");
            s.takenAtMs = o.optLong("takenAtMs");
            s.ready = o.optBoolean("ready", true);
            s.web = Info.fromJson(o.optJSONObject("web"));
            s.original = Info.fromJson(o.optJSONObject("original"));
            s.durationMs = o.isNull("durationMs") ? null : o.optLong("durationMs");
            return s;
        }

        private static String str(JSONObject o, String key) {
            return o.isNull(key) ? null : o.optString(key);
        }
    }

    private final File web, originals, thumbs, index;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    // Guarded by this.
    private final List<Shot> shots = new ArrayList<>();
    private final List<String> setOrder = new ArrayList<>();
    /** session.json records of the jobs that produced held shots, by job ID. */
    private final Map<String, JSONObject> jobRecords = new LinkedHashMap<>();
    private int shotN;

    public RemoteShots(File filesDir) {
        File dir = new File(filesDir, "remote");
        web = new File(dir, "web");
        originals = new File(dir, "originals");
        thumbs = new File(dir, "thumbs");
        index = new File(dir, "shots.json");
        load();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- Reading ---------------------------------------------------------------

    /** Every held shot, in the standard order. */
    public synchronized List<JSONObject> list() {
        List<JSONObject> out = new ArrayList<>();
        for (Shot s : ordered()) out.add(s.toJson());
        return out;
    }

    public synchronized JSONObject json(String id) {
        Shot s = find(id);
        return s == null ? null : s.toJson();
    }

    public synchronized int count() {
        return shots.size();
    }

    public synchronized long bytes() {
        long n = 0;
        for (Shot s : shots) if (s.web != null) n += s.web.bytes;
        return n;
    }

    /** The job IDs of held shots, oldest job first, with their stored records. */
    public synchronized List<JSONObject> jobRecords() {
        Set<String> held = new HashSet<>();
        for (Shot s : shots) held.add(s.jobId);
        List<JSONObject> out = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : jobRecords.entrySet()) if (held.contains(e.getKey())) out.add(e.getValue());
        return out;
    }

    /** A shot's file for a variant ("web", "original" or "thumb"), or null if there's no such shot. */
    public synchronized File file(String id, String variant) {
        Shot s = find(id);
        if (s == null) return null;
        switch (variant) {
            case "original": return new File(originals, s.originalName);
            case "thumb": return new File(thumbs, s.id + ".jpg");
            default: return new File(web, s.filename);
        }
    }

    /** MIME type and download name for a variant. */
    public synchronized String[] mimeAndName(String id, String variant) {
        Shot s = find(id);
        if (s == null) return null;
        if ("thumb".equals(variant)) return new String[]{"image/jpeg", stem(s.filename) + "-thumb.jpg"};
        Info i = "original".equals(variant) ? s.original : s.web;
        String mime = i != null ? i.mime : "spin".equals(s.kind) ? "video/mp4" : "image/jpeg";
        return new String[]{mime, "original".equals(variant) ? s.originalName : s.filename};
    }

    public synchronized boolean ready(String id) {
        Shot s = find(id);
        return s != null && s.ready;
    }

    // ---- Changing --------------------------------------------------------------

    /** @return false if there's no such shot */
    public boolean delete(String id) {
        synchronized (this) {
            Shot s = find(id);
            if (s == null) return false;
            shots.remove(s);
            deleteFiles(s);
            pruneSets();
            save();
        }
        fireRemoved(id);
        return true;
    }

    public int deleteAll() {
        List<String> ids = new ArrayList<>();
        synchronized (this) {
            for (Shot s : shots) {
                ids.add(s.id);
                deleteFiles(s);
            }
            shots.clear();
            setOrder.clear();
            jobRecords.clear();
            save();
        }
        for (String id : ids) fireRemoved(id);
        return ids.size();
    }

    /** Stores a finished job's record for session.json, if it produced any held shots. */
    public void recordJob(Job job) {
        if (job.shotIds().isEmpty()) return;
        synchronized (this) {
            jobRecords.put(job.id, job.recordJson());
            save();
        }
    }

    /** Where a job's capture writes. */
    public CaptureTarget target(Job job) {
        return new CaptureTarget() {
            @Override
            public File originalFile(String filename) {
                return new File(originals, filename);
            }

            @Override
            public File webFile(String filename) {
                return new File(web, filename);
            }

            @Override
            public Set<String> filenames() {
                synchronized (RemoteShots.this) {
                    Set<String> names = new HashSet<>();
                    for (Shot s : shots) names.add(s.filename);
                    return names;
                }
            }

            @Override
            public void encoding(Session.Media m) {
                Shot s = newShot(m, job);
                s.ready = false;
                s.original = videoInfo(new File(originals, s.originalName), s.originalName);
                writeThumb(s, null, new File(originals, s.originalName));
                store(s, job);
            }

            @Override
            public void add(Session.Media m) {
                Shot pending = null;
                synchronized (RemoteShots.this) {
                    for (Shot s : shots) {
                        if (!s.ready && s.filename.equals(m.filename) && job.id.equals(s.jobId)) pending = s;
                    }
                    if (pending != null) {
                        fillFiles(pending);
                        pending.ready = true;
                        save();
                    }
                }
                if (pending != null) {
                    fireShot(pending);
                    return;
                }
                Shot s = newShot(m, job);
                fillFiles(s);
                store(s, job);
            }
        };
    }

    private Shot newShot(Session.Media m, Job job) {
        Shot s = new Shot();
        synchronized (this) {
            s.id = "s-" + (++shotN);
        }
        s.jobId = job.id;
        s.filename = m.filename;
        s.setLabel = m.setLabel;
        s.takenAtMs = m.capturedAtMs > 0 ? m.capturedAtMs : System.currentTimeMillis();
        if (m.kind == Session.Media.Kind.VIDEO) {
            s.kind = "spin";
            s.caption = m.setLabel != null ? m.setLabel + " (spin)" : "Spin";
            s.originalName = m.filename.substring(0, m.filename.length() - "spin.mp4".length()) + "spin-source.mp4";
        } else if (!Double.isNaN(m.angleNominal)) {
            s.kind = "view";
            s.caption = m.caption;
            s.originalName = m.filename;
            s.nominal = m.angleNominal;
            s.actual = m.angleActual;
            for (View v : View.values()) if (v.angle == (int) Math.round(m.angleNominal)) s.view = v.baseName;
        } else {
            s.kind = "extra";
            s.caption = m.caption;
            s.label = m.caption;
            s.originalName = m.filename;
        }
        return s;
    }

    /** Sizes and the thumbnail, once the files are written. */
    private void fillFiles(Shot s) {
        File w = new File(web, s.filename), o = new File(originals, s.originalName);
        if ("spin".equals(s.kind)) {
            s.web = videoInfo(w, s.filename);
            s.original = videoInfo(o, s.originalName);
            s.durationMs = durationMs(w);
            if (!new File(thumbs, s.id + ".jpg").isFile()) writeThumb(s, null, o);
        } else {
            s.web = imageInfo(w, s.filename);
            s.original = imageInfo(o, s.originalName);
            writeThumb(s, w, null);
        }
    }

    /** Adds a new shot, replacing a held view or spin with the same name. */
    private void store(Shot s, Job job) {
        List<String> replaced = new ArrayList<>();
        synchronized (this) {
            if (!"extra".equals(s.kind)) {
                for (Shot old : new ArrayList<>(shots)) {
                    if (old.filename.equals(s.filename)) {
                        shots.remove(old);
                        //noinspection ResultOfMethodCallIgnored
                        new File(thumbs, old.id + ".jpg").delete();  // the other files now hold the new shot
                        replaced.add(old.id);
                    }
                }
            }
            if (s.setLabel != null && !setOrder.contains(s.setLabel)) setOrder.add(s.setLabel);
            shots.add(s);
            save();
        }
        for (String id : replaced) fireRemoved(id);
        job.addShot(s.id);
        fireShot(s);
    }

    // ---- Order, files, persistence ---------------------------------------------

    /** The main set's views, its spin, each extra set in the order first shot, then extra shots by time. */
    private List<Shot> ordered() {
        List<Shot> out = new ArrayList<>(shots);
        out.sort(Comparator.<Shot>comparingInt(s -> "extra".equals(s.kind) ? 2 : s.setLabel != null ? 1 : 0)
                .thenComparingInt(s -> s.setLabel == null ? 0 : setOrder.indexOf(s.setLabel))
                .thenComparingInt(RemoteShots::viewIndex)
                .thenComparingLong(s -> s.takenAtMs));
        return out;
    }

    private static int viewIndex(Shot s) {
        if ("extra".equals(s.kind)) return 0;
        View[] views = View.values();
        for (int i = 0; i < views.length; i++) if (views[i].baseName.equals(s.view)) return i;
        return views.length;  // the spin, after the views
    }

    private Shot find(String id) {
        for (Shot s : shots) if (s.id.equals(id)) return s;
        return null;
    }

    private void pruneSets() {
        Set<String> used = new HashSet<>();
        for (Shot s : shots) if (s.setLabel != null) used.add(s.setLabel);
        setOrder.retainAll(used);
    }

    private void deleteFiles(Shot s) {
        for (File f : new File[]{new File(web, s.filename), new File(originals, s.originalName),
                new File(thumbs, s.id + ".jpg")}) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void load() {
        if (!index.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
            shotN = o.optInt("shotN");
            JSONArray sets = o.optJSONArray("setOrder");
            if (sets != null) for (int i = 0; i < sets.length(); i++) setOrder.add(sets.getString(i));
            JSONArray list = o.optJSONArray("shots");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    Shot s = Shot.fromJson(list.getJSONObject(i));
                    if (s.ready) shots.add(s);  // a spin still encoding when the app stopped is incomplete
                    else deleteFiles(s);
                }
            }
            JSONObject jobs = o.optJSONObject("jobs");
            if (jobs != null) {
                JSONArray names = jobs.names();
                if (names != null) for (int i = 0; i < names.length(); i++) {
                    jobRecords.put(names.getString(i), jobs.getJSONObject(names.getString(i)));
                }
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "can't read " + index, e);
        }
    }

    private void save() {
        try {
            File dir = index.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            JSONArray list = new JSONArray();
            for (Shot s : shots) list.put(s.storeJson());
            JSONObject jobs = new JSONObject();
            for (Map.Entry<String, JSONObject> e : jobRecords.entrySet()) Json.put(jobs, e.getKey(), e.getValue());
            JSONObject o = Json.obj("shotN", shotN, "setOrder", Json.arr(setOrder), "shots", list, "jobs", jobs);
            File tmp = new File(dir, "shots.json.tmp");
            Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.w(TAG, "can't write " + index, e);
        }
    }

    private static Info imageInfo(File f, String name) {
        Info i = new Info();
        i.mime = "image/jpeg";
        i.bytes = f.length();
        i.filename = name;
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        if (b.outWidth > 0) {
            boolean swap = false;
            try {
                int rot = new ExifInterface(f.getPath()).getRotationDegrees();
                swap = rot == 90 || rot == 270;
            } catch (IOException ignored) {
            }
            i.width = swap ? b.outHeight : b.outWidth;
            i.height = swap ? b.outWidth : b.outHeight;
        }
        return i;
    }

    private static Info videoInfo(File f, String name) {
        Info i = new Info();
        i.mime = "video/mp4";
        i.bytes = f.length();
        i.filename = name;
        if (!f.isFile() || f.length() == 0) return i;
        try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
            r.setDataSource(f.getPath());
            Integer w = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            Integer h = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            Integer rot = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            boolean swap = rot != null && (rot == 90 || rot == 270);
            i.width = swap ? h : w;
            i.height = swap ? w : h;
        } catch (Exception e) {
            Log.w(TAG, "can't read " + f, e);
        }
        return i;
    }

    private static Long durationMs(File f) {
        if (!f.isFile() || f.length() == 0) return null;
        try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
            r.setDataSource(f.getPath());
            Integer d = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            return d == null ? null : d.longValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parse(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A 320 px JPEG: from the web image, or a frame from the start of the video. */
    private void writeThumb(Shot s, File image, File video) {
        Bitmap src = null;
        try {
            if (image != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;  // 2400 px web copy -> 600 px
                src = BitmapFactory.decodeFile(image.getPath(), o);
            } else if (video != null && video.isFile()) {
                try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
                    r.setDataSource(video.getPath());
                    src = r.getFrameAtTime(0);
                }
            }
            if (src == null) return;
            float scale = Math.min(1f, (float) THUMB_EDGE / Math.max(src.getWidth(), src.getHeight()));
            Bitmap thumb = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * scale)),
                    Math.max(1, Math.round(src.getHeight() * scale)), true);
            if (!thumbs.isDirectory() && !thumbs.mkdirs()) throw new IOException("can't create " + thumbs);
            try (OutputStream out = new FileOutputStream(new File(thumbs, s.id + ".jpg"))) {
                thumb.compress(Bitmap.CompressFormat.JPEG, 75, out);
            }
            if (thumb != src) thumb.recycle();
        } catch (Exception e) {
            Log.w(TAG, "thumbnail failed for " + s.filename, e);
        } finally {
            if (src != null) src.recycle();
        }
    }

    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private void fireShot(Shot s) {
        JSONObject j;
        synchronized (this) {
            j = s.toJson();
        }
        for (Listener l : listeners) l.onShot(j);
    }

    private void fireRemoved(String id) {
        for (Listener l : listeners) l.onShotRemoved(id);
    }
}
