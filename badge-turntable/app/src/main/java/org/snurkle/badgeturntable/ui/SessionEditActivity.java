package org.snurkle.badgeturntable.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import org.snurkle.badgeturntable.App;
import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.ir.IrTransmitter;
import org.snurkle.badgeturntable.ir.Presets;
import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A session: its name, notes and options, and the strip of what's been captured, which can
 * be viewed and deleted (Spec.md, Session screen). Save, or Open camera, which saves and
 * opens the Capture screen with this session open (without starting a capture).
 */
public final class SessionEditActivity extends AppCompatActivity implements SessionStore.Listener {
    public static final String EXTRA_ID = "session_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Thumbnail decoding, kept off {@link #io} so a save never waits behind it. */
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private Station station;
    private SessionStore store;
    /** The session as last loaded; null until then. A new one isn't saved until Save. */
    private Session session;
    private boolean isNew;

    private TextInputEditText name, notes;
    private CheckBox video, auto;
    private Button saveButton, cameraButton, deleteButton;
    private TextView saveHint, mediaEmpty;
    private RecyclerView mediaList;
    private MediaAdapter mediaAdapter;
    private RemoteBanner banner;

    /** Null if this phone can drive the turntable; otherwise why not. */
    private Integer autoUnavailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_edit);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        station = App.station(this);
        store = station.sessions;
        banner = new RemoteBanner(this, findViewById(R.id.remote_banner));
        name = findViewById(R.id.title);
        notes = findViewById(R.id.notes);
        video = findViewById(R.id.opt_video);
        auto = findViewById(R.id.opt_auto);
        saveButton = findViewById(R.id.save);
        cameraButton = findViewById(R.id.capture);
        saveHint = findViewById(R.id.save_hint);
        mediaList = findViewById(R.id.media_list);
        mediaEmpty = findViewById(R.id.media_empty);

        mediaList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mediaAdapter = new MediaAdapter(store, thumbs, new MediaAdapter.Actions() {
            @Override
            public void open(Session.Media media, File file) {
                openMedia(media, file);
            }

            @Override
            public void delete(Session.Media media) {
                confirmDelete(media);
            }
        });
        mediaList.setAdapter(mediaAdapter);

        saveButton.setOnClickListener(v -> save(false));
        cameraButton.setOnClickListener(v -> save(true));
        // Deleting closes this screen: the store tells onSessionRemoved.
        deleteButton = findViewById(R.id.delete_session);
        deleteButton.setOnClickListener(v -> {
            if (session != null && !isNew) SessionDelete.confirm(this, station, io, session);
        });
        saveButton.setEnabled(false);
        cameraButton.setEnabled(false);
        name.addTextChangedListener(new SimpleWatcher(this::checkSavable));

        // Automatic turntable needs an IR blaster and a profile with a remote.
        TurntableProfile profile = Presets.byId(this, station.prefs.getString("turntable_model", Presets.JAYEGT_574));
        if (!new IrTransmitter(this).available()) autoUnavailable = R.string.opt_auto_no_ir;
        else if (!profile.supportsAuto()) autoUnavailable = R.string.opt_auto_no_remote;
        if (autoUnavailable != null) {
            auto.setEnabled(false);
            auto.setText(autoUnavailable);
        }

        String id = getIntent().getStringExtra(EXTRA_ID);
        isNew = id == null;
        setTitle(isNew ? R.string.new_session : R.string.edit_session);
        io.execute(() -> {
            Session s = isNew ? store.newSession("", "") : store.get(id);
            runOnUiThread(() -> {
                if (s == null) {
                    Toast.makeText(this, R.string.session_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                bindFields(s);
                showMedia(s);
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        banner.start();
        store.addListener(this);
    }

    @Override
    protected void onStop() {
        store.removeListener(this);
        banner.stop();
        super.onStop();
    }

    private void bindFields(Session s) {
        session = s;
        name.setText(s.name);
        notes.setText(s.notes);
        video.setChecked(s.captureVideo);
        auto.setChecked(s.autoMode && autoUnavailable == null);
        deleteButton.setVisibility(isNew ? View.GONE : View.VISIBLE);
        checkSavable();
    }

    private void showMedia(Session s) {
        boolean empty = s.media.isEmpty();
        mediaEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mediaList.setVisibility(empty ? View.GONE : View.VISIBLE);
        mediaAdapter.submit(s);
    }

    // ---- Changes from elsewhere (a capture, the editor) --------------------------------

    @Override
    public void onSessionChanged(Session s) {
        // Files come and go with captures and the editor; the fields being typed in are left alone.
        if (session != null && s.id.equals(session.id)) runOnUiThread(() -> showMedia(s));
    }

    @Override
    public void onSessionRemoved(String id) {
        if (session != null && id.equals(session.id)) {
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.session_deleted, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    // ---- Media ---------------------------------------------------------------------------

    private void openMedia(Session.Media m, File file) {
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.media_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            String mime = m.kind == Session.Media.Kind.VIDEO ? "video/*" : "image/*";
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.media_open_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(Session.Media m) {
        if (session == null || isNew) return;
        String label = m.caption != null && !m.caption.isEmpty() ? m.caption : m.filename;
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.media_delete_title, label))
                .setMessage(R.string.media_delete_message)
                .setPositiveButton(R.string.delete, (d, w) -> deleteMedia(m))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteMedia(Session.Media m) {
        String id = session.id;
        Job running = station.jobs.current();
        if (running != null && id.equals(running.targetSessionId())) {
            Toast.makeText(this, getString(R.string.delete_session_busy, session.name), Toast.LENGTH_LONG).show();
            return;
        }
        io.execute(() -> {
            try {
                store.deleteFile(id, m.filename);  // the store's listener refreshes the strip
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---- Saving ----------------------------------------------------------------------------

    private void checkSavable() {
        if (session == null) return;
        boolean canSave = !text(name).isEmpty();
        saveButton.setEnabled(canSave);
        cameraButton.setEnabled(canSave);
        saveHint.setText(canSave ? "" : getString(R.string.title_required));
    }

    /** Saves name, notes and options (not the media list, which captures may be changing). */
    private void save(boolean thenCamera) {
        if (session == null || !saveButton.isEnabled()) return;
        String n = text(name), t = text(notes);
        boolean v = video.isChecked();
        Boolean a = autoUnavailable == null ? auto.isChecked() : null;
        Session s = session;
        io.execute(() -> {
            try {
                if (isNew) {
                    s.name = n;
                    s.notes = t;
                    s.captureVideo = v;
                    if (a != null) s.autoMode = a;
                    store.save(s);
                } else if (store.update(s.id, x -> {
                    x.name = n;
                    x.notes = t;
                    x.captureVideo = v;
                    if (a != null) x.autoMode = a;
                }) == null) {
                    throw new IOException(getString(R.string.session_deleted));
                }
                runOnUiThread(() -> {
                    if (thenCamera) {
                        startActivity(new Intent(this, CaptureActivity.class).putExtra(EXTRA_ID, s.id));
                    }
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static String text(TextView v) {
        return v.getText() == null ? "" : v.getText().toString().trim();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        thumbs.shutdownNow();
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
