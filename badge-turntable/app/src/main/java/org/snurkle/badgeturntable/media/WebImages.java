package org.snurkle.badgeturntable.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The web copy that goes in the badge folder: 2400 px on the long edge, JPEG
 * quality 85, upright (EXIF rotation applied), aiming for under 1 MB as the
 * repo README asks, with the {@link SideBars} painted in if they're on. The full-size
 * original is deleted afterwards (only web copies are kept), so the bars can't be undone.
 */
public final class WebImages {
    public static final int LONG_EDGE = 2400;
    public static final int QUALITY = 85;

    private WebImages() {}

    public static void writeWebCopy(File original, File dest) throws IOException {
        writeWebCopy(original, dest, SideBars.NONE);
    }

    /** As above, with the side bars painted over the upright picture. */
    public static void writeWebCopy(File original, File dest, SideBars bars) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(original.getPath(), bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= 0) throw new IOException("can't read " + original);

        // Decode at the smallest power-of-two reduction that's still >= the target.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (longest / (opts.inSampleSize * 2) >= LONG_EDGE) opts.inSampleSize *= 2;
        Bitmap bmp = BitmapFactory.decodeFile(original.getPath(), opts);
        if (bmp == null) throw new IOException("can't decode " + original);

        float scale = Math.min(1f, (float) LONG_EDGE / Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        int rotation = new ExifInterface(original.getPath()).getRotationDegrees();
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        if (bars.active()) {
            if (!out.isMutable()) {
                Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
                out.recycle();
                out = copy;
            }
            int w = out.getWidth(), h = out.getHeight();
            int[] px = new int[w * h];
            out.getPixels(px, 0, w, 0, 0, w, h);
            bars.apply(px, w, h);
            out.setPixels(px, 0, w, 0, 0, w, h);
        }

        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent);
        try (OutputStream os = new FileOutputStream(dest)) {
            if (!out.compress(Bitmap.CompressFormat.JPEG, QUALITY, os)) throw new IOException("JPEG encode failed");
        } finally {
            out.recycle();
        }
    }
}
