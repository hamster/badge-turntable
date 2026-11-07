package org.snurkle.badgeturntable.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One row per badge: thumbnail, title, details, and (with Samba set up) its sync state and a Sync button. */
final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {
    interface Actions {
        void open(Session s);

        /** Long-press: Sync, Delete. */
        void more(Session s);

        void sync(Session s);
    }

    private final List<Session> items = new ArrayList<>();
    private final Actions actions;
    private SessionStore store;
    private boolean samba;

    SessionAdapter(Actions actions) {
        this.actions = actions;
    }

    /** @param sambaConfigured show sync state and buttons */
    @SuppressWarnings("NotifyDataSetChanged")  // the whole list is reloaded each time
    void submit(List<Session> sessions, SessionStore store, boolean sambaConfigured) {
        this.store = store;
        this.samba = sambaConfigured;
        items.clear();
        items.addAll(sessions);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Session s = items.get(position);
        android.content.Context c = h.itemView.getContext();
        h.title.setText(s.title.isEmpty() ? s.effectiveSlug() : s.title);
        String id = s.badgeId == null || s.badgeId.isEmpty() ? "" : "  #" + s.badgeId;
        int files = s.media.size();
        h.subtitle.setText(c.getResources().getQuantityString(R.plurals.session_subtitle, files, s.con, s.year, id, files));
        h.status.setVisibility(samba ? android.view.View.VISIBLE : android.view.View.GONE);
        h.sync.setVisibility(samba ? android.view.View.VISIBLE : android.view.View.GONE);
        h.status.setText(statusText(s.syncState));
        h.sync.setEnabled(s.syncState != Session.SyncState.PENDING);
        h.thumb.setImageBitmap(thumbnail(new File(store.badgeDir(s), "front.jpg")));
        h.itemView.setOnClickListener(v -> actions.open(s));
        h.itemView.setOnLongClickListener(v -> {
            actions.more(s);
            return true;
        });
        h.sync.setOnClickListener(v -> actions.sync(s));
    }

    private static int statusText(Session.SyncState state) {
        switch (state) {
            case PENDING: return R.string.sync_pending;
            case SYNCED: return R.string.sync_synced;
            case FAILED: return R.string.sync_failed;
            default: return R.string.sync_local;
        }
    }

    private static Bitmap thumbnail(File f) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 16;  // 2400 px web copy -> 150 px
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, subtitle, status;
        final Button sync;

        Holder(android.view.View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            status = v.findViewById(R.id.status);
            sync = v.findViewById(R.id.sync);
        }
    }
}
