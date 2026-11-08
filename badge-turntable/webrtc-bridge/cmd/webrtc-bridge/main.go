// Command webrtc-bridge is the app's WebRTC transport as a desktop program,
// for the mock phone (remote-mock/mock_phone.py):
//
//	webrtc-bridge -udp 8738 -upstream 127.0.0.1:8737 -cert mock-bridge-cert.pem
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"strings"

	"github.com/hamster/badge-turntable/webrtc-bridge/relay"
)

func main() {
	udp := flag.Int("udp", 8738, "UDP port")
	upstream := flag.String("upstream", "127.0.0.1:8737", "the HTTP API to relay to, host:port")
	cert := flag.String("cert", "bridge-cert.pem", "certificate and key (PEM), made on the first run")
	bind := flag.String("bind", "", "comma-separated IPv4 addresses to listen on (default: all)")
	flag.Parse()

	var addrs []string
	for _, a := range strings.Split(*bind, ",") {
		if a = strings.TrimSpace(a); a != "" {
			addrs = append(addrs, a)
		}
	}
	s, err := relay.Start(relay.Config{Bind: addrs, Port: *udp, Upstream: *upstream, CertPath: *cert})
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("READY fingerprint %s", s.Fingerprint())
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	<-sig
	s.Stop()
}
