package org.snurkle.badgeturntable.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.util.Collections;

public class WebRtcTransportTest {
    @Test
    public void udpPortFollowsTheHttpPort() {
        assertEquals(8738, WebRtcTransport.portFor(8737));
        assertEquals(65534, WebRtcTransport.portFor(65535));
    }

    @Test
    public void noWifiAddressMeansNoTransport() {
        assertNull(WebRtcTransport.start(Collections.emptyList(), 8737, new File("unused.pem")));
    }

    @Test
    public void noBridgeMeansNoTransport() {
        // The JVM tests don't have the gomobile AAR's native library: start says so with null,
        // rather than throwing, and the app goes on with plain HTTP.
        assertNull(WebRtcTransport.start(Collections.singletonList("192.168.1.5"), 8737, new File("unused.pem")));
    }
}
