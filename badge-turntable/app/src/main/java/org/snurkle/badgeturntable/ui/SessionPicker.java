package org.snurkle.badgeturntable.ui;

import android.app.Activity;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The Capture screen's session picker (Spec.md, The open session): the phone's sessions,
 * newest first, New session…, Shots for the editor while one is connected, and Close
 * session. Picking one only opens it; the next Capture starts the capture.
 */
final class SessionPicker {
    private SessionPicker() {}

    static void show(Activity activity, Station station, Executor io) {
        io.execute(() -> {
            List<Session> sessions = station.sessions.list();
            activity.runOnUiThread(() -> showList(activity, station, io, sessions));
        });
    }

    private static void showList(Activity activity, Station station, Executor io, List<Session> sessions) {
        if (activity.isFinishing()) return;
        Station.Target t = station.target();
        boolean editor = station.server() != null && !station.server().clientNames().isEmpty();
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(activity.getString(R.string.picker_new));
        actions.add(() -> askNew(activity, station, io));
        for (Session s : sessions) {
            boolean open = t.isSession() && s.id.equals(t.sessionId);
            items.add(open ? "✓ " + s.name : s.name);
            actions.add(() -> run(activity, () -> station.openSession(s.id, Job.PHONE)));
        }
        if (editor || t.isShots()) {
            items.add((t.isShots() ? "✓ " : "") + activity.getString(R.string.picker_shots));
            actions.add(() -> run(activity, () -> station.openShots(Job.PHONE)));
        }
        if (!t.isNone()) {
            items.add(activity.getString(R.string.picker_close));
            actions.add(() -> run(activity, () -> station.closeTarget(Job.PHONE)));
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.picker_title)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Name (required) and notes, then opens the new session. */
    private static void askNew(Activity activity, Station station, Executor io) {
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText name = new EditText(activity);
        name.setHint(R.string.new_session_name_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText notes = new EditText(activity);
        notes.setHint(R.string.new_session_notes_hint);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setMinLines(2);
        form.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(notes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.new_session)
                .setView(form)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(activity, R.string.title_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String text = notes.getText().toString();
                    io.execute(() -> {
                        Session s = station.sessions.newSession(n, text);
                        try {
                            station.sessions.save(s);
                            activity.runOnUiThread(() -> run(activity, () -> station.openSession(s.id, Job.PHONE)));
                        } catch (Exception e) {
                            activity.runOnUiThread(() -> Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface Change {
        void run() throws ApiException;
    }

    private static void run(Activity activity, Change change) {
        try {
            change.run();
        } catch (ApiException e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
