// Package bridge is the app's handle on the WebRTC transport, bound to Java
// with gomobile (the class org.snurkle.webrtc.bridge.Bridge). The app calls
// Start after its HTTP server starts, and Stop after it has closed its
// WebSockets, so their 1001 close reaches the editor.
package bridge

import (
	"strings"
	"sync"

	"github.com/hamster/badge-turntable/webrtc-bridge/relay"
)

var (
	mu  sync.Mutex
	srv *relay.Server
)

// Start listens on UDP port udpPort on each IPv4 address in bind (a comma-
// separated list: the phone's Wi-Fi addresses), relaying to the app's HTTP API
// at upstream (127.0.0.1:<port>). The certificate is kept at certPath, and
// made on the first start. A running transport is stopped first.
func Start(bind string, udpPort int, upstream string, certPath string) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		srv.Stop()
		srv = nil
	}
	var addrs []string
	for _, a := range strings.Split(bind, ",") {
		if a = strings.TrimSpace(a); a != "" {
			addrs = append(addrs, a)
		}
	}
	s, err := relay.Start(relay.Config{Bind: addrs, Port: udpPort, Upstream: upstream, CertPath: certPath})
	if err != nil {
		return err
	}
	srv = s
	return nil
}

// Fingerprint is the certificate's "sha-256 AA:BB:…" fingerprint, or "" when
// the transport isn't running.
func Fingerprint() string {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return ""
	}
	return srv.Fingerprint()
}

// Stop closes every connection (live channels get close 1001 first) and the
// UDP sockets. It returns within about a quarter of a second.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		srv.Stop()
		srv = nil
	}
}
