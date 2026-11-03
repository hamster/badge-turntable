package org.snurkle.badgeturntable.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.session.BadgeIds;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;
import org.snurkle.badgeturntable.session.Slugs;
import org.snurkle.badgeturntable.sync.KnownBadgeIds;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Badge identity and session options. New sessions get a suggested, non-colliding badge ID. */
public final class SessionEditActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "session_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SessionStore store;
    private Session session;
    private Map<String, String> knownIds = new HashMap<>();

    private TextInputEditText title, year, group, event, slug, badgeId;
    private TextInputLayout badgeIdLayout;
    private MaterialAutoCompleteTextView con;
    private CheckBox video, auto, sync;
    private boolean updatingSlug;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_edit);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        store = new SessionStore(this);
        title = findViewById(R.id.title);
        year = findViewById(R.id.year);
        group = findViewById(R.id.group);
        event = findViewById(R.id.event);
        slug = findViewById(R.id.slug);
        badgeId = findViewById(R.id.badge_id);
        badgeIdLayout = findViewById(R.id.badge_id_layout);
        con = findViewById(R.id.con);
        video = findViewById(R.id.opt_video);
        auto = findViewById(R.id.opt_auto);
        sync = findViewById(R.id.opt_sync);

        findViewById(R.id.save).setOnClickListener(v -> save(false));
        findViewById(R.id.capture).setOnClickListener(v -> save(true));

        String id = getIntent().getStringExtra(EXTRA_ID);
        io.execute(() -> {
            Session s = id == null ? store.newSession() : store.get(id);
            List<String> cons = store.conChoices();
            Map<String, String> ids = KnownBadgeIds.all(this);
            runOnUiThread(() -> bind(s, cons, ids, id == null));
            try {
                KnownBadgeIds.refreshFromSite(this);  // best effort; cached for next time
                Map<String, String> fresh = KnownBadgeIds.all(this);
                runOnUiThread(() -> {
                    knownIds = fresh;
                    checkBadgeId();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void bind(Session s, List<String> cons, Map<String, String> ids, boolean isNew) {
        session = s;
        knownIds = ids;
        setTitle(isNew ? R.string.new_session : R.string.edit_session);
        con.setSimpleItems(cons.toArray(new String[0]));
        title.setText(s.title);
        con.setText(s.con, false);
        year.setText(s.year > 0 ? String.valueOf(s.year) : "");
        group.setText(s.group);
        event.setText(s.event);
        slug.setText(s.effectiveSlug());
        video.setChecked(s.captureVideo);
        auto.setChecked(s.autoMode);
        sync.setChecked(s.syncToSamba);
        if (isNew) {
            String suggestion = BadgeIds.suggestNext(ids.keySet());
            badgeId.setText(suggestion == null ? "" : suggestion);
        } else {
            badgeId.setText(s.badgeId);
        }

        TextWatcher regen = new SimpleWatcher(() -> {
            if (session.slugEdited) return;
            updatingSlug = true;
            slug.setText(Slugs.make(text(group), text(title), parseYear()));
            updatingSlug = false;
        });
        title.addTextChangedListener(regen);
        group.addTextChangedListener(regen);
        year.addTextChangedListener(regen);
        slug.addTextChangedListener(new SimpleWatcher(() -> {
            if (!updatingSlug) session.slugEdited = true;
        }));
        badgeId.addTextChangedListener(new SimpleWatcher(this::checkBadgeId));
        checkBadgeId();
    }

    private void checkBadgeId() {
        if (session == null) return;
        String raw = text(badgeId);
        if (raw.isEmpty()) {
            badgeIdLayout.setError(null);
            badgeIdLayout.setHelperText(getString(R.string.badge_id_blank));
            return;
        }
        String id = BadgeIds.normalize(raw);
        if (id == null) {
            badgeIdLayout.setError(getString(R.string.badge_id_invalid));
            return;
        }
        String owner = knownIds.get(id);
        if (owner != null && !owner.equals(session.effectiveSlug())) {
            badgeIdLayout.setError(getString(R.string.badge_id_taken, id, owner));
        } else {
            badgeIdLayout.setError(null);
            badgeIdLayout.setHelperText(getString(R.string.badge_id_ok));
        }
    }

    private void save(boolean thenCapture) {
        if (session == null) return;
        if (text(title).isEmpty()) {
            title.setError(getString(R.string.title_required));
            return;
        }
        session.title = text(title);
        session.con = text(con).isEmpty() ? "defcon" : text(con);
        session.year = parseYear();
        session.group = text(group);
        session.event = text(event);
        session.slug = Slugs.kebab(text(slug));
        String id = BadgeIds.normalize(text(badgeId));
        session.badgeId = id == null ? "" : id;
        session.captureVideo = video.isChecked();
        session.autoMode = auto.isChecked();
        session.syncToSamba = sync.isChecked();

        Session s = session;
        io.execute(() -> {
            try {
                store.save(s);
                if (new File(store.badgeDir(s), "index.md").isFile()) store.writeIndex(s);
                runOnUiThread(() -> {
                    if (thenCapture) {
                        startActivity(new Intent(this, CaptureActivity.class).putExtra(EXTRA_ID, s.id));
                    }
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private int parseYear() {
        try {
            return Integer.parseInt(text(year));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(android.widget.TextView v) {
        return v.getText() == null ? "" : v.getText().toString().trim();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable e) {
            onChange.run();
        }
    }
}
