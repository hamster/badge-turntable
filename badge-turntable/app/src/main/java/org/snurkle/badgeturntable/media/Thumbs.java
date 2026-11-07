package org.snurkle.badgeturntable.media;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Small JPEG thumbnails of photos and videos (Remote-API.md, ?variant=thumb). */
public final class Thumbs {
    public static final int EDGE = 320;

    private Thumbs() {}

    /**
     * Writes a JPEG about 320 px on the long edge: the photo scaled (EXIF rotation applied),
     * or a frame from the start of the video.
     */
    public static void write(File source, boolean video, File dest) throws IOException {
        Size size = new Size(EDGE, EDGE);
        Bitmap b = video ? ThumbnailUtils.createVideoThumbnail(source, size, null)
                : ThumbnailUtils.createImageThumbnail(source, size, null);
        try {
            File dir = dest.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            File tmp = new File(dest.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, 75, out);
            }
            if (!tmp.renameTo(dest)) throw new IOException("can't write " + dest);
        } finally {
            b.recycle();
        }
    }
}
