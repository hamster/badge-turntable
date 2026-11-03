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

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;
import org.snurkle.badgeturntable.sync.SyncWorker;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sessions list: one row per badge, newest first. */
public final class MainActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SessionStore store;
    private SessionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        store = new SessionStore(this);
        adapter = new SessionAdapter(session -> startActivity(
                new Intent(this, SessionEditActivity.class).putExtra(SessionEditActivity.EXTRA_ID, session.id)));
        RecyclerView list = findViewById(R.id.sessions);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.new_session).setOnClickListener(v ->
                startActivity(new Intent(this, SessionEditActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        io.execute(() -> {
            List<Session> sessions = store.list();
            runOnUiThread(() -> {
                adapter.submit(sessions, store);
                findViewById(R.id.empty).setVisibility(sessions.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sync) {
            SyncWorker.enqueue(this);
            Toast.makeText(this, R.string.sync_queued, Toast.LENGTH_SHORT).show();
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
