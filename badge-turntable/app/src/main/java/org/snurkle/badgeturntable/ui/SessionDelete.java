package org.snurkle.badgeturntable.ui;

import android.app.Activity;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.session.Session;

import java.util.concurrent.Executor;

/**
 * Deleting a session from the phone (the Sessions list and the Session screen): asks first,
 * refuses one being captured into, and closes it first if it's the open session.
 */
final class SessionDelete {
    private SessionDelete() {}

    /** Asks, then deletes on io. The store's listeners hear about it (lists refresh, screens close). */
    static void confirm(Activity a, Station station, Executor io, Session s) {
        new MaterialAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.delete_session_title, s.name))
                .setMessage(R.string.delete_session_message)
                .setPositiveButton(R.string.delete, (d, w) -> delete(a, station, io, s))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void delete(Activity a, Station station, Executor io, Session s) {
        Job running = station.jobs.current();
        if (running != null && s.id.equals(running.targetSessionId())) {
            Toast.makeText(a, a.getString(R.string.delete_session_busy, s.name), Toast.LENGTH_LONG).show();
            return;
        }
        Station.Target t = station.target();
        if (t.isSession() && s.id.equals(t.sessionId)) {
            try {
                station.closeTarget(Job.PHONE);
            } catch (ApiException e) {
                Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        io.execute(() -> station.sessions.delete(s));
    }
}
