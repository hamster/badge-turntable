package org.snurkle.badgeturntable.remote;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Turns an analysis frame (YUV_420_888) into the editor's preview JPEG: upright, scaled so
 * its long edge is the requested size, quality 70. Nearest-neighbour sampling straight from
 * the planes, rotating as it goes, so a 640 px frame costs a few milliseconds and no extra
 * full-size copies. Analysis thread only (it reuses its buffers).
 */
final class PreviewEncoder {
    static final int QUALITY = 70;

    private int[] argb = new int[0];
    private Bitmap bitmap;
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);

    /** @return the JPEG, or null if the frame isn't YUV */
    byte[] encode(ImageProxy image, int longEdge) {
        if (image.getFormat() != ImageFormat.YUV_420_888 || image.getPlanes().length < 3) return null;
        int w = image.getWidth(), h = image.getHeight();
        int rot = image.getImageInfo().getRotationDegrees();
        boolean swap = rot == 90 || rot == 270;
        int uw = swap ? h : w, uh = swap ? w : h;  // upright size
        double scale = Math.min(1.0, (double) longEdge / Math.max(uw, uh));
        int ow = Math.max(1, (int) Math.round(uw * scale)), oh = Math.max(1, (int) Math.round(uh * scale));

        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yb = planes[0].getBuffer(), ub = planes[1].getBuffer(), vb = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        if (argb.length < ow * oh) argb = new int[ow * oh];
        double step = 1.0 / scale;
        for (int oy = 0; oy < oh; oy++) {
            int uy = Math.min(uh - 1, (int) (oy * step));
            for (int ox = 0; ox < ow; ox++) {
                int ux = Math.min(uw - 1, (int) (ox * step));
                int sx, sy;  // upright point back to the sensor buffer
                switch (rot) {
                    case 90: sx = uy; sy = h - 1 - ux; break;
                    case 180: sx = w - 1 - ux; sy = h - 1 - uy; break;
                    case 270: sx = w - 1 - uy; sy = ux; break;
                    default: sx = ux; sy = uy;
                }
                int y = yb.get(sy * yRow + sx * yPix) & 0xFF;
                int cx = sx >> 1, cy = sy >> 1;
                int u = (ub.get(cy * uRow + cx * uPix) & 0xFF) - 128;
                int v = (vb.get(cy * vRow + cx * vPix) & 0xFF) - 128;
                int r = y + ((91881 * v) >> 16);
                int g = y - ((22554 * u + 46802 * v) >> 16);
                int b = y + ((116130 * u) >> 16);
                argb[oy * ow + ox] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        if (bitmap == null || bitmap.getWidth() != ow || bitmap.getHeight() != oh) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(argb, 0, ow, 0, 0, ow, oh);
        jpeg.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg);
        return jpeg.toByteArray();
    }

    private static int clamp(int c) {
        return c < 0 ? 0 : Math.min(c, 255);
    }
}
