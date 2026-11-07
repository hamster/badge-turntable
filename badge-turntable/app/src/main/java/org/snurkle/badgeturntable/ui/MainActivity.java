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
import androidx.work.WorkManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.remote.RemoteServer;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;
import org.snurkle.badgeturntable.sync.SmbSettings;
import org.snurkle.badgeturntable.sync.SyncWorker;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sessions list: one row per badge, newest first, plus the Camera screen on its own. With
 * Samba set up, each row shows its sync state with a Sync button, and the menu has Sync all.
 * Long-press a row to sync or delete it.
 */
public final class MainActivity extends AppCompatActivity implements SessionAdapter.Actions {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SessionStore store;
    private SessionAdapter adapter;
    private boolean samba;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        store = new SessionStore(this);
        adapter = new SessionAdapter(this);
        RecyclerView list = findViewById(R.id.sessions);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.new_session).setOnClickListener(v ->
                startActivity(new Intent(this, SessionEditActivity.class)));
        findViewById(R.id.camera).setOnClickListener(v ->
                startActivity(new Intent(this, CaptureActivity.class)));
        // Rows change as the sync worker queues, uploads and finishes.
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(SyncWorker.WORK_NAME)
                .observe(this, infos -> reload());

        // With the editor doing the typing, the camera is what's needed first; Back comes here.
        if (savedInstanceState == null && RemoteServer.enabled(this)) {
            startActivity(new Intent(this, CaptureActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        io.execute(() -> {
            List<Session> sessions = store.list();
            boolean configured = SmbSettings.load(this).isConfigured();
            runOnUiThread(() -> {
                samba = configured;
                adapter.submit(sessions, store, configured);
                findViewById(R.id.empty).setVisibility(sessions.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                invalidateOptionsMenu();
            });
        });
    }

    // ---- Rows --------------------------------------------------------------------

    @Override
    public void open(Session s) {
        startActivity(new Intent(this, SessionEditActivity.class).putExtra(SessionEditActivity.EXTRA_ID, s.id));
    }

    @Override
    public void sync(Session s) {
        queueSync(s);
    }

    @Override
    public void more(Session s) {
        String name = s.title.isEmpty() ? s.effectiveSlug() : s.title;
        CharSequence[] items = samba
                ? new CharSequence[]{getString(R.string.sync), getString(R.string.delete)}
                : new CharSequence[]{getString(R.string.delete)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(items, (d, which) -> {
                    if (samba && which == 0) queueSync(s);
                    else confirmDelete(s, name);
                })
                .show();
    }

    private void confirmDelete(Session s, String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_session_title, name))
                .setMessage(samba ? R.string.delete_session_message_samba : R.string.delete_session_message)
                .setPositiveButton(R.string.delete, (d, w) -> io.execute(() -> {
                    store.delete(s);
                    SyncWorker.forget(this, store, s);
                    reload();
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One badge, or all that aren't synced (null). */
    private void queueSync(Session only) {
        io.execute(() -> {
            try {
                SyncWorker.syncNow(this, only);
                runOnUiThread(() -> Toast.makeText(this, R.string.sync_queued, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
            reload();
        });
    }

    // ---- Menu --------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem sync = menu.findItem(R.id.action_sync);
        if (sync != null) {
            sync.setEnabled(samba);
            sync.setTitle(samba ? R.string.action_sync : R.string.action_sync_not_set_up);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sync) {
            queueSync(null);
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
