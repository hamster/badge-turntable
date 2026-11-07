package org.snurkle.badgeturntable.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.App;
import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.remote.RemoteServer;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sessions list: one row per badge, newest first, plus the Camera screen on its own. Kept
 * up to date as captures and the editor change sessions.
 */
public final class MainActivity extends AppCompatActivity implements SessionAdapter.Actions, SessionStore.Listener {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private Station station;
    private SessionAdapter adapter;
    private RemoteBanner banner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        station = App.station(this);
        banner = new RemoteBanner(this, findViewById(R.id.remote_banner));
        adapter = new SessionAdapter(station.sessions, thumbs, this);
        RecyclerView list = findViewById(R.id.sessions);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.new_session).setOnClickListener(v ->
                startActivity(new Intent(this, SessionEditActivity.class)));
        findViewById(R.id.camera).setOnClickListener(v ->
                startActivity(new Intent(this, CaptureActivity.class)));

        // With the editor doing the typing, the camera is what's needed first; Back comes here.
        if (savedInstanceState == null && RemoteServer.enabled(this)) {
            startActivity(new Intent(this, CaptureActivity.class));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        banner.start();
        station.sessions.addListener(this);
        reload();
    }

    @Override
    protected void onStop() {
        station.sessions.removeListener(this);
        banner.stop();
        super.onStop();
    }

    @Override
    public void onSessionChanged(Session s) {
        reload();
    }

    @Override
    public void onSessionRemoved(String id) {
        reload();
    }

    private void reload() {
        io.execute(() -> {
            List<Session> sessions = station.sessions.list();
            Station.Target t = station.target();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                adapter.submit(sessions, t.isSession() ? t.sessionId : null);
                findViewById(R.id.empty).setVisibility(sessions.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        });
    }

    // ---- Rows --------------------------------------------------------------------

    @Override
    public void open(Session s) {
        startActivity(new Intent(this, SessionEditActivity.class).putExtra(SessionEditActivity.EXTRA_ID, s.id));
    }

    @Override
    public void more(Session s) {
        CharSequence[] items = new CharSequence[]{getString(R.string.delete)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(s.name)
                .setItems(items, (d, which) -> confirmDelete(s))
                .show();
    }

    private void confirmDelete(Session s) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_session_title, s.name))
                .setMessage(R.string.delete_session_message)
                .setPositiveButton(R.string.delete, (d, w) -> delete(s))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The open session is closed first; one being captured into can't be deleted. */
    private void delete(Session s) {
        Job running = station.jobs.current();
        if (running != null && s.id.equals(running.targetSessionId())) {
            Toast.makeText(this, getString(R.string.delete_session_busy, s.name), Toast.LENGTH_LONG).show();
            return;
        }
        Station.Target t = station.target();
        if (t.isSession() && s.id.equals(t.sessionId)) {
            try {
                station.closeTarget(Job.PHONE);
            } catch (ApiException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        io.execute(() -> station.sessions.delete(s));
    }

    // ---- Menu --------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        thumbs.shutdownNow();
        super.onDestroy();
    }
}
