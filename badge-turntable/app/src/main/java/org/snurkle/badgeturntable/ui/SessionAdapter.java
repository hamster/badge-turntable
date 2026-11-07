package org.snurkle.badgeturntable.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.format.DateUtils;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One row per session: thumbnail, name, date and file count. */
final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {
    interface Actions {
        void open(Session s);

        /** Long-press: Delete. */
        void more(Session s);
    }

    private final List<Session> items = new ArrayList<>();
    private final Actions actions;
    private final SessionStore store;
    private final Executor decoder;
    private String openId;

    SessionAdapter(SessionStore store, Executor decoder, Actions actions) {
        this.store = store;
        this.decoder = decoder;
        this.actions = actions;
    }

    /** @param openId the session open on the Camera screen, or null */
    @SuppressWarnings("NotifyDataSetChanged")  // the whole list is reloaded each time
    void submit(List<Session> sessions, String openId) {
        this.openId = openId;
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
        h.title.setText(s.name);
        int files = s.media.size();
        String date = DateUtils.formatDateTime(c, s.createdAtMs, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        String sub = c.getResources().getQuantityString(R.plurals.session_subtitle, files, date, files);
        if (s.id.equals(openId)) sub += " · " + c.getString(R.string.session_open_now);
        h.subtitle.setText(sub);
        h.itemView.setOnClickListener(v -> actions.open(s));
        h.itemView.setOnLongClickListener(v -> {
            actions.more(s);
            return true;
        });

        String cover = s.cover();
        String key = s.id + "/" + cover + "@" + s.updatedAtMs;
        h.boundKey = key;
        h.thumb.setImageBitmap(null);
        if (cover == null) return;
        try {
            decoder.execute(() -> {
                Bitmap b = null;
                try {
                    File t = store.thumb(s, cover);
                    if (t != null) b = BitmapFactory.decodeFile(t.getPath());
                } catch (Exception ignored) {
                    // left blank
                }
                Bitmap thumb = b;
                h.thumb.post(() -> {
                    if (key.equals(h.boundKey)) h.thumb.setImageBitmap(thumb);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // the screen is closing
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, subtitle;
        String boundKey;

        Holder(android.view.View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
