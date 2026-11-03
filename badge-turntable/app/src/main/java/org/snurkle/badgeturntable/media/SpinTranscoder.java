package org.snurkle.badgeturntable.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Turns the raw spin recording into the web clip, matching the site's
 * publish-time settings (.github/scripts/optimize_media.py):
 * trimmed to exactly one revolution, fits in 1080 px, 24 fps, H.264, no audio,
 * HDR tone-mapped to SDR, bitrate capped so the file stays under 10 MB.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SpinTranscoder {
    public static final long LIMIT_BYTES = 10_000_000;
    private static final int MAX_BPS = 2_600_000;
    private static final double HEADROOM = 0.92;

    private SpinTranscoder() {}

    /**
     * @param startUs clip start, from the angle log (relative to the recording start)
     * @param endUs   clip end, one revolution later
     * Blocks until done; call off the main thread.
     */
    public static void transcode(Context context, File source, File dest, long startUs, long endUs) throws Exception {
        double seconds = (endUs - startUs) / 1e6;
        // TODO: if one revolution is over 30 s, speed the clip up to fit (EditedMediaItem speed
        //  control in newer Media3). At the JAYEGT's 15 s/rev setting this doesn't arise.
        int bitrate = (int) Math.min(MAX_BPS, LIMIT_BYTES * 8 * HEADROOM / Math.max(seconds, 1));

        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(startUs)
                        .setEndPositionUs(endUs)
                        .build())
                .build();
        Effects effects = new Effects(Collections.emptyList(), List.of(
                Presentation.createForWidthAndHeight(1080, 1080, Presentation.LAYOUT_SCALE_TO_FIT),
                FrameDropEffect.createDefaultFrameDropEffect(24f)));
        EditedMediaItem edited = new EditedMediaItem.Builder(item)
                .setRemoveAudio(true)
                .setEffects(effects)
                .build();
        Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(edited).build())
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build();

        CompletableFuture<Void> done = new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            Transformer t = new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                                    .setBitrate(bitrate).build())
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(@NonNull Composition c, @NonNull ExportResult r) {
                            done.complete(null);
                        }

                        @Override
                        public void onError(@NonNull Composition c, @NonNull ExportResult r, @NonNull ExportException e) {
                            done.completeExceptionally(e);
                        }
                    })
                    .build();
            t.start(composition, dest.getPath());
        });
        done.get(5, TimeUnit.MINUTES);
    }
}
