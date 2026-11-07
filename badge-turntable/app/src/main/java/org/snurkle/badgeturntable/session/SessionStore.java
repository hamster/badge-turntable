package org.snurkle.badgeturntable.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.snurkle.badgeturntable.media.Thumbs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sessions on this phone, in app-specific storage, one folder per session named after
 * its ID, so renaming moves nothing and two badges with the same name can't collide:
 *
 * <pre>
 * sessions/&lt;id&gt;/session.json      name, notes, options, media list
 * sessions/&lt;id&gt;/front.jpg …         web copies (2400 px JPEG) and spin.mp4
 * sessions/&lt;id&gt;/thumbs/&lt;file&gt;.jpg  320 px thumbnails, made on demand
 * sessions/.scratch/                  full-size stills and source videos while they're processed
 * </pre>
 *
 * Only the web copies are kept (Spec.md, Output). One instance for the app, from the
 * Station, so every change reaches the listeners (the screens and the editor).
 */
public final class SessionStore {
    private static final String TAG = "SessionStore";
    private static final String PREFS = "session_defaults";

    public interface Listener {
        /** A session was created, saved or captured into. Any thread. */
        void onSessionChanged(Session s);

        void onSessionRemoved(String id);
    }

    private final Context context;
    private final File base;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Count and bytes for status, recomputed after a change; guarded by this. */
    private long[] totals;

    public SessionStore(Context context) {
        this.context = context.getApplicationContext();
        File external = context.getExternalFilesDir(null);
        this.base = new File(external != null ? external : context.getFilesDir(), "sessions");
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public File dir(Session s) {
        return dir(s.id);
    }

    private File dir(String id) {
        return new File(base, id);
    }

    /** A session's file (web copy or spin video). */
    public File file(Session s, String filename) {
        return file(s.id, filename);
    }

    public File file(String sessionId, String filename) {
        return new File(dir(sessionId), filename);
    }

    /** Where full-size stills and source videos wait while their web copies are made. */
    public File scratchDir() {
        return new File(base, ".scratch");
    }

    /** Clears scratch files left by a capture the app didn't finish. */
    public void clearScratch() {
        deleteTree(scratchDir());
    }

    // ---- Reading -------------------------------------------------------------------

    /** Newest first. */
    public List<Session> list() {
        List<Session> out = new ArrayList<>();
        File[] dirs = base.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (dirs == null) return out;
        for (File d : dirs) {
            Session s = read(new File(d, "session.json"));
            if (s != null) out.add(s);
        }
        out.sort(Comparator.comparingLong((Session s) -> s.createdAtMs).reversed());
        return out;
    }

    public Session get(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.startsWith(".")) return null;
        return read(new File(dir(id), "session.json"));
    }

    private static Session read(File f) {
        if (!f.isFile()) return null;
        try {
            return SessionJson.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "can't read " + f, e);
            return null;
        }
    }

    /** Bytes of a session's files (web copies and videos, not thumbnails). */
    public long bytes(Session s) {
        long n = 0;
        for (Session.Media m : s.media) n += file(s, m.filename).length();
        return n;
    }

    /** {count, bytes} of every session, for status. */
    public synchronized long[] totals() {
        if (totals == null) {
            long count = 0, bytes = 0;
            for (Session s : list()) {
                count++;
                bytes += bytes(s);
            }
            totals = new long[]{count, bytes};
        }
        return totals.clone();
    }

    /**
     * A file's 320 px thumbnail, made the first time it's asked for.
     * @return null if there's no such file
     */
    public File thumb(Session s, String filename) throws IOException {
        Session.Media m = s.find(filename);
        if (m == null) return null;
        File src = file(s, filename);
        if (!src.isFile()) return null;
        File t = new File(new File(dir(s), "thumbs"), filename + ".jpg");
        if (!t.isFile() || t.lastModified() < src.lastModified()) {
            Thumbs.write(src, m.kind == Session.Media.Kind.VIDEO, t);
        }
        return t;
    }

    // ---- Changing ------------------------------------------------------------------

    /** Saves a new session's session.json. To change a saved one, use {@link #update}. */
    public void save(Session s) throws IOException {
        synchronized (this) {
            write(s);
        }
        fireChanged(s);
    }

    /** Writes session.json atomically and stamps updatedAtMs. Holding this. */
    private void write(Session s) throws IOException {
        File dir = dir(s);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        s.updatedAtMs = Math.max(System.currentTimeMillis(), s.updatedAtMs + 1);
        try {
            File tmp = new File(dir, "session.json.tmp");
            Files.write(tmp.toPath(), SessionJson.toJson(s).toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), new File(dir, "session.json").toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        totals = null;
    }

    private void fireChanged(Session s) {
        rememberOptions(s);
        for (Listener l : listeners) l.onSessionChanged(s);
    }

    /**
     * Reads the session as saved, changes it and saves it, all under the store's lock, so a
     * capture adding files and the Session screen changing the name don't undo each other.
     * @return the saved session, or null if there's no such session (nothing is called)
     */
    public Session update(String id, java.util.function.Consumer<Session> change) throws IOException {
        Session s;
        synchronized (this) {
            s = get(id);
            if (s == null) return null;
            change.accept(s);
            write(s);
        }
        fireChanged(s);
        return s;
    }

    /**
     * Deletes one file (and its thumbnail) and saves the session.
     * @return false if there's no such session or file
     */
    public boolean deleteFile(String id, String filename) throws IOException {
        boolean[] found = {false};
        Session s = update(id, x -> {
            found[0] = x.remove(filename);
            if (!found[0]) return;
            //noinspection ResultOfMethodCallIgnored
            file(x, filename).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(new File(dir(x), "thumbs"), filename + ".jpg").delete();
        });
        return s != null && found[0];
    }

    public void delete(Session s) {
        synchronized (this) {
            deleteTree(dir(s));
            totals = null;
        }
        for (Listener l : listeners) l.onSessionRemoved(s.id);
    }

    /** A new session, starting with the options used last time. Not saved yet. */
    public Session newSession(String name, String notes) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Session s = new Session();
        s.name = name == null ? "" : name.trim();
        s.notes = notes == null ? "" : notes.trim();
        s.captureVideo = p.getBoolean("captureVideo", true);
        s.autoMode = p.getBoolean("autoMode", true);
        return s;
    }

    private void rememberOptions(Session s) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("captureVideo", s.captureVideo)
                .putBoolean("autoMode", s.autoMode)
                .apply();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
