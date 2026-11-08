package org.snurkle.badgeturntable.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.App;
import org.snurkle.badgeturntable.R;
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
                .setItems(items, (d, which) -> SessionDelete.confirm(this, station, io, s))
                .show();
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
