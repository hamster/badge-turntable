package org.snurkle.badgeturntable.capture;

import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Captures into a session: its web copies go in the session's folder, and session.json is
 * updated after each file, so whatever was captured survives a stop or a switch of session.
 * A spin shows up once it's transcoded.
 */
public final class SessionTarget implements CaptureTarget {
    private final SessionStore store;
    private final String sessionId;
    private final Job job;

    /** @param job the job writing, told each file's name; may be null */
    public SessionTarget(SessionStore store, String sessionId, Job job) {
        this.store = store;
        this.sessionId = sessionId;
        this.job = job;
    }

    @Override
    public File scratchFile(String filename) {
        return new File(store.scratchDir(), filename);
    }

    @Override
    public File webFile(String filename) {
        return store.file(sessionId, filename);
    }

    @Override
    public Set<String> filenames() {
        Set<String> taken = new HashSet<>();
        Session s = store.get(sessionId);
        if (s != null) for (Session.Media m : s.media) taken.add(m.filename);
        return taken;
    }

    @Override
    public void add(Session.Media m) throws IOException {
        if (store.update(sessionId, s -> s.put(m)) == null) throw new IOException("The session was deleted.");
        if (job != null) job.addFile(m.filename);
    }
}
