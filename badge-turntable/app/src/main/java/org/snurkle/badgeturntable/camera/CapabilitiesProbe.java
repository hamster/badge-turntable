package org.snurkle.badgeturntable.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;

import org.snurkle.badgeturntable.ir.IrTransmitter;

import java.util.Arrays;

/**
 * Reports what this phone lets a third-party app do: camera hardware level,
 * manual-control capabilities, lenses and resolutions, and the IR blaster.
 * Phase 1 of the build starts here, so the controls offered match the phone.
 */
public final class CapabilitiesProbe {
    private CapabilitiesProbe() {}

    public static String report(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append(new IrTransmitter(context).describe()).append("\n\n");

        CameraManager cm = context.getSystemService(CameraManager.class);
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                describe(sb, id, c);
                for (String phys : c.getPhysicalCameraIds()) {
                    sb.append("  physical ").append(phys).append(":\n");
                    describe(sb, "    " + phys, cm.getCameraCharacteristics(phys));
                }
                sb.append('\n');
            }
        } catch (CameraAccessException e) {
            sb.append("Camera access failed: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private static void describe(StringBuilder sb, String id, CameraCharacteristics c) {
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        sb.append("Camera ").append(id).append(" (")
                .append(facing == null ? "?" : facing == CameraMetadata.LENS_FACING_BACK ? "back"
                        : facing == CameraMetadata.LENS_FACING_FRONT ? "front" : "external").append(")\n");
        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        sb.append("  hardware level: ").append(levelName(level)).append('\n');

        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        sb.append("  capabilities:");
        if (caps != null) for (int cap : caps) sb.append(' ').append(capName(cap));
        sb.append('\n');

        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        sb.append("  focal lengths: ").append(Arrays.toString(focal)).append(" mm\n");
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        sb.append("  min focus: ").append(minFocus == null ? "?" : minFocus == 0 ? "fixed focus"
                : String.format("%.1f diopters (%.1f cm)", minFocus, 100f / minFocus)).append('\n');
        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (iso != null) sb.append("  ISO: ").append(iso).append('\n');
        if (exp != null) sb.append(String.format("  shutter: %.6f to %.3f s%n", exp.getLower() / 1e9, exp.getUpper() / 1e9));

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
            if (jpeg != null && jpeg.length > 0) {
                Size max = Arrays.stream(jpeg).max((a, b) -> Long.compare((long) a.getWidth() * a.getHeight(),
                        (long) b.getWidth() * b.getHeight())).get();
                sb.append("  max JPEG: ").append(max).append('\n');
            }
        }
        Size maxRes = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
        if (maxRes != null) sb.append("  max-resolution sensor mode: ").append(maxRes).append('\n');
        Integer ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        sb.append("  timestamps: ").append(ts != null && ts == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                ? "realtime (video trim is accurate)" : "unknown (video trim may drift)").append('\n');
    }

    private static String levelName(Integer level) {
        if (level == null) return "?";
        switch (level) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return String.valueOf(level);
        }
    }

    private static String capName(int cap) {
        switch (cap) {
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: return "MANUAL_SENSOR";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: return "MANUAL_POST_PROCESSING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW: return "RAW";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: return "BURST";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: return "LOGICAL_MULTI_CAMERA";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR: return "ULTRA_HIGH_RESOLUTION";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT: return "10-BIT";
            default: return "#" + cap;
        }
    }
}
