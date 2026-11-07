package org.snurkle.badgeturntable.capture;

import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Captures into a badge session: _badges/ and _capture/ folders and index.md. Syncing to
 * Samba is manual (the Sessions screen), so a capture only marks a synced badge as changed.
 */
public final class SessionTarget implements CaptureTarget {
    private final SessionStore store;
    public final Session session;

    public SessionTarget(SessionStore store, Session session) {
        this.store = store;
        this.session = session;
    }

    @Override
    public File originalFile(String filename) {
        return new File(store.originalsDir(session), filename);
    }

    @Override
    public File webFile(String filename) {
        return new File(store.badgeDir(session), filename);
    }

    @Override
    public Set<String> filenames() {
        Set<String> taken = new HashSet<>();
        for (Session.Media m : session.media) taken.add(m.filename);
        return taken;
    }

    @Override
    public void add(Session.Media m) throws IOException {
        session.put(m);
        session.markChanged();
        store.save(session);
    }

    @Override
    public void finish() throws IOException {
        store.writeIndex(session);
        store.save(session);
    }
}
