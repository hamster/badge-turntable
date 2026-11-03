package org.snurkle.badgeturntable.session;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sessions on this phone, in app-specific storage, laid out exactly as on the share:
 *
 * <pre>
 * museum/
 *   _badges/&lt;con&gt;/&lt;slug&gt;/    index.md, web copies, spin.mp4   (drop-in for the repo)
 *   _capture/&lt;con&gt;/&lt;slug&gt;/   session.json, originals/         (archive)
 * </pre>
 */
public final class SessionStore {
    private static final String PREFS = "session_defaults";

    private final Context context;
    private final File base;

    public SessionStore(Context context) {
        this.context = context.getApplicationContext();
        this.base = new File(context.getExternalFilesDir(null), "museum");
    }

    public File base() {
        return base;
    }

    public File badgeDir(Session s) {
        return new File(base, "_badges/" + s.con + "/" + s.effectiveSlug());
    }

    public File captureDir(Session s) {
        return new File(base, "_capture/" + s.con + "/" + s.effectiveSlug());
    }

    public File originalsDir(Session s) {
        return new File(captureDir(s), "originals");
    }

    public List<Session> list() {
        List<Session> out = new ArrayList<>();
        File[] cons = new File(base, "_capture").listFiles(File::isDirectory);
        if (cons == null) return out;
        for (File con : cons) {
            File[] slugs = con.listFiles(File::isDirectory);
            if (slugs == null) continue;
            for (File slug : slugs) {
                Session s = read(new File(slug, "session.json"));
                if (s != null) out.add(s);
            }
        }
        out.sort(Comparator.comparingLong((Session s) -> s.createdAtMs).reversed());
        return out;
    }

    public Session get(String id) {
        for (Session s : list()) if (s.id.equals(id)) return s;
        return null;
    }

    private static Session read(File f) {
        if (!f.isFile()) return null;
        try {
            return SessionJson.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Saves session.json, moving the folders first if con or slug changed. */
    public synchronized void save(Session s) throws IOException {
        Session old = get(s.id);
        if (old != null && (!old.con.equals(s.con) || !old.effectiveSlug().equals(s.effectiveSlug()))) {
            move(badgeDir(old), badgeDir(s));
            move(captureDir(old), captureDir(s));
        }
        File dir = captureDir(s);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        try {
            File tmp = new File(dir, "session.json.tmp");
            Files.write(tmp.toPath(), SessionJson.toJson(s).toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), new File(dir, "session.json").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        rememberOptions(s);
    }

    private static void move(File from, File to) throws IOException {
        if (!from.exists()) return;
        if (to.exists()) throw new IOException(to + " already exists");
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent);
        if (!from.renameTo(to)) throw new IOException("can't move " + from + " to " + to);
    }

    /** Writes index.md from the existing file, or from the bundled template for a new badge. */
    public void writeIndex(Session s) throws IOException {
        File dir = badgeDir(s);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File index = new File(dir, "index.md");
        String source = index.isFile()
                ? new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8)
                : template();
        Files.write(index.toPath(), IndexMdWriter.write(source, s).getBytes(StandardCharsets.UTF_8));
    }

    public String template() throws IOException {
        try (InputStream in = context.getAssets().open("template/index.md")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Con choices from the template's comment ("defcon | saintcon | ..."). */
    public List<String> conChoices() {
        List<String> out = new ArrayList<>();
        try {
            for (String line : template().split("\n")) {
                String t = line.trim();
                if (t.startsWith("#") && t.contains("defcon |")) {
                    for (String c : t.substring(1).split("\\|")) out.add(c.trim());
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        if (out.isEmpty()) out.add("defcon");
        return out;
    }

    /** A new session, starting with the options used last time. */
    public Session newSession() {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Session s = new Session();
        s.captureVideo = p.getBoolean("captureVideo", true);
        s.autoMode = p.getBoolean("autoMode", true);
        s.syncToSamba = p.getBoolean("syncToSamba", true);
        s.con = p.getString("con", "defcon");
        s.year = java.time.Year.now().getValue();
        return s;
    }

    private void rememberOptions(Session s) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("captureVideo", s.captureVideo)
                .putBoolean("autoMode", s.autoMode)
                .putBoolean("syncToSamba", s.syncToSamba)
                .putString("con", s.con)
                .apply();
    }

    public void delete(Session s) {
        deleteTree(badgeDir(s));
        deleteTree(captureDir(s));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
