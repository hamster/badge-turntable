package org.snurkle.badgeturntable.ui;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
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

/**
 * The Session screen's strip of captured photos and videos: tap to view, long-press to
 * delete. Thumbnails are the store's 320 px ones, made on the given executor.
 */
public final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {

    public interface Actions {
        void open(Session.Media media, File file);

        void delete(Session.Media media);
    }

    private final List<Session.Media> items = new ArrayList<>();
    private final SessionStore store;
    private final Executor decoder;
    private final Actions actions;
    private Session session;
    /** By path and modified time; a few dozen small thumbnails at most. */
    private final LruCache<String, Bitmap> cache = new LruCache<>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public MediaAdapter(SessionStore store, Executor decoder, Actions actions) {
        this.store = store;
        this.decoder = decoder;
        this.actions = actions;
    }

    @SuppressWarnings("NotifyDataSetChanged")  // the whole session is reloaded each time
    public void submit(Session s) {
        session = s;
        items.clear();
        items.addAll(s.media);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Session.Media m = items.get(position);
        Session s = session;
        boolean video = m.kind == Session.Media.Kind.VIDEO;
        holder.caption.setText(m.caption != null && !m.caption.isEmpty() ? m.caption : m.filename);
        holder.playIcon.setVisibility(video ? View.VISIBLE : View.GONE);

        File file = store.file(s, m.filename);
        holder.itemView.setOnClickListener(v -> actions.open(m, file));
        holder.itemView.setOnLongClickListener(v -> {
            actions.delete(m);
            return true;
        });

        String key = file.getPath() + "@" + file.lastModified();
        holder.boundKey = key;
        Bitmap cached = cache.get(key);
        holder.thumb.setImageBitmap(cached);
        if (cached != null) return;
        try {
            decoder.execute(() -> {
                Bitmap thumb = null;
                try {
                    File t = store.thumb(s, m.filename);
                    if (t != null) thumb = android.graphics.BitmapFactory.decodeFile(t.getPath());
                } catch (Exception ignored) {
                    // left blank
                }
                Bitmap b = thumb;
                holder.thumb.post(() -> {
                    if (b != null) cache.put(key, b);
                    // The holder may have been recycled for another file meanwhile.
                    if (key.equals(holder.boundKey)) holder.thumb.setImageBitmap(b);
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
        final ImageView thumb, playIcon;
        final TextView caption;
        String boundKey;

        Holder(View v) {
            super(v);
            thumb = v.findViewById(R.id.media_thumb);
            playIcon = v.findViewById(R.id.play_icon);
            caption = v.findViewById(R.id.media_caption);
        }
    }
}
