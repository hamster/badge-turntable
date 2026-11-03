package org.snurkle.badgeturntable.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.ViewGroup;
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
import java.util.function.Consumer;

final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {
    private final List<Session> items = new ArrayList<>();
    private final Consumer<Session> onClick;
    private SessionStore store;

    SessionAdapter(Consumer<Session> onClick) {
        this.onClick = onClick;
    }

    void submit(List<Session> sessions, SessionStore store) {
        this.store = store;
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
        h.title.setText(s.title.isEmpty() ? s.effectiveSlug() : s.title);
        String id = s.badgeId == null || s.badgeId.isEmpty() ? "" : "  #" + s.badgeId;
        h.subtitle.setText(s.con + " · " + s.year + id + " · " + s.media.size() + " files");
        h.status.setText(statusText(s.syncState));
        h.thumb.setImageBitmap(thumbnail(new File(store.badgeDir(s), "front.jpg")));
        h.itemView.setOnClickListener(v -> onClick.accept(s));
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

        Holder(android.view.View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            status = v.findViewById(R.id.status);
        }
    }
}
