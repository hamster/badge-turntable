package org.snurkle.badgeturntable.remote;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.snurkle.badgeturntable.jobs.Json;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * The Remote API's WebRTC transport (Remote-API.md, WebRTC transport): the Go bridge in
 * webrtc-bridge/, built with gomobile into app/libs/webrtc-bridge.aar. It relays the editor's
 * data channels to this app's own HTTP server on loopback, so the API code doesn't change.
 *
 * It's reached by reflection, so the app builds without the AAR, and runs where the bridge's
 * native library can't load (the AAR is arm64 only, so not on an x86 emulator). Then there's
 * no transport, Info and Pair say {@code webrtc: null}, and the editor uses plain HTTP.
 */
final class WebRtcTransport {
    private static final String TAG = "WebRtcTransport";
    private static final String BRIDGE = "org.snurkle.webrtc.bridge.Bridge";

    final int port;
    final String fingerprint;
    private final Class<?> bridge;

    private WebRtcTransport(Class<?> bridge, int port, String fingerprint) {
        this.bridge = bridge;
        this.port = port;
        this.fingerprint = fingerprint;
    }

    /** The UDP port: the HTTP port + 1 (Remote-API.md, Info). */
    static int portFor(int httpPort) {
        return httpPort < 65535 ? httpPort + 1 : httpPort - 1;
    }

    /**
     * Starts the bridge on the phone's Wi-Fi addresses only, as the HTTP server accepts only
     * LAN connections, so it can't be reached over mobile data.
     *
     * @return null if there's no Wi-Fi address, no bridge, or it couldn't start
     */
    @Nullable
    static WebRtcTransport start(List<String> wifiAddresses, int httpPort, File certFile) {
        if (wifiAddresses.isEmpty()) {
            Log.w(TAG, "no Wi-Fi address, so no WebRTC transport");
            return null;
        }
        int udpPort = portFor(httpPort);
        Class<?> b;
        try {
            b = Class.forName(BRIDGE);
        } catch (Throwable t) {  // not built in, or its native library didn't load
            Log.w(TAG, "no WebRTC bridge in this build: " + t);
            return null;
        }
        try {
            b.getMethod("start", String.class, long.class, String.class, String.class)
                    .invoke(null, String.join(",", wifiAddresses), (long) udpPort,
                            "127.0.0.1:" + httpPort, certFile.getAbsolutePath());
            String fp = (String) b.getMethod("fingerprint").invoke(null);
            if (fp == null || fp.isEmpty()) throw new IllegalStateException("no fingerprint");
            Log.i(TAG, "WebRTC on " + wifiAddresses + " UDP " + udpPort + ", " + fp);
            return new WebRtcTransport(b, udpPort, fp);
        } catch (InvocationTargetException e) {
            Log.e(TAG, "WebRTC didn't start: " + e.getCause(), e.getCause());
        } catch (Throwable t) {
            Log.e(TAG, "WebRTC didn't start", t);
        }
        return null;
    }

    /**
     * Closes every connection (live channels that are still open get close 1001 first) and the
     * UDP socket. Returns within about a quarter of a second.
     */
    void stop() {
        try {
            bridge.getMethod("stop").invoke(null);
        } catch (Throwable t) {
            Log.w(TAG, "stopping WebRTC failed", t);
        }
    }

    /** {@code webrtc} in Info and Pair. */
    JSONObject toJson() {
        return Json.obj("port", port, "fingerprint", fingerprint);
    }
}
