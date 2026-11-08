// Package relay is the phone's side of the Remote API's WebRTC transport
// (Remote-API.md, WebRTC transport): an ICE-lite, DTLS-server WebRTC endpoint
// with no signalling server, which relays each data channel to the app's own
// HTTP API on loopback. A "req" channel is one HTTP request; a "live" channel
// is the /api/live WebSocket, message for message.
//
// The editor makes up the ICE ufrag (32 lower-case hex characters, password
// the same) and writes the phone's SDP answer itself, with the certificate
// fingerprint it pinned at pairing. The first STUN check for a new ufrag
// creates a PeerConnection for it.
package relay

import (
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pion/ice/v4"
	"github.com/pion/webrtc/v4"
)

// Limits (Remote-API.md, The phone's side).
const (
	// MaxUnauthed is how many connections may be set up or connected without
	// having shown a valid token. Checks for new ufrags beyond it are ignored.
	MaxUnauthed = 8
	// MaxAuthed is how many authenticated connections may be open, as for the
	// WebSocket. One that authenticates beyond it is closed (live gets 4409).
	MaxAuthed = 4
)

// AuthWithin is how long a connection has to show a valid token, counted from
// its first check. Connecting, opening a channel and authenticating all have
// to happen in that time. (A variable only so tests can shorten it.)
var AuthWithin = 10 * time.Second

// Config says where to listen and where the app's HTTP API is.
type Config struct {
	// Bind lists the IPv4 addresses to listen on. Empty means every address
	// (0.0.0.0), which is for the desktop; on the phone, give the Wi-Fi
	// addresses only, so the transport isn't reachable over mobile data.
	Bind []string
	// Port is the UDP port, shared by every connection.
	Port int
	// Upstream is the app's HTTP API as host:port, normally 127.0.0.1:<port>.
	Upstream string
	// CertPath is the PEM file holding the certificate and its key. It's made
	// on the first start.
	CertPath string
	// Logf logs; nil means the standard logger.
	Logf func(format string, args ...any)
}

// Server is a running WebRTC transport.
type Server struct {
	cfg         Config
	cert        webrtc.Certificate
	fingerprint string
	socks       []*sniffConn
	mux         ice.UDPMux
	client      *http.Client
	logf        func(format string, args ...any)

	mu     sync.Mutex
	conns  map[string]*conn
	closed bool
}

var (
	localUfrag  = regexp.MustCompile(`^[0-9a-f]{32}$`)
	remoteUfrag = regexp.MustCompile(`^[A-Za-z0-9+/]{4,256}$`) // ice-char, so it can't break the SDP
)

// Start listens and returns the running server.
func Start(cfg Config) (*Server, error) {
	if cfg.Port <= 0 || cfg.Port > 65535 {
		return nil, fmt.Errorf("bad UDP port %d", cfg.Port)
	}
	if _, _, err := net.SplitHostPort(cfg.Upstream); err != nil {
		return nil, fmt.Errorf("bad upstream %q: %w", cfg.Upstream, err)
	}
	logf := cfg.Logf
	if logf == nil {
		logf = log.Printf
	}
	cert, err := loadOrMakeCert(cfg.CertPath, logf)
	if err != nil {
		return nil, err
	}
	fp, err := fingerprintOf(cert)
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg:         cfg,
		cert:        cert,
		fingerprint: fp,
		client:      newUpstreamClient(),
		logf:        logf,
		conns:       map[string]*conn{},
	}
	binds := cfg.Bind
	if len(binds) == 0 {
		binds = []string{"0.0.0.0"}
	}
	var muxes []ice.UDPMux
	for _, b := range binds {
		ip := net.ParseIP(strings.TrimSpace(b))
		if ip == nil || ip.To4() == nil {
			s.closeSockets()
			return nil, fmt.Errorf("bad bind address %q (IPv4 only)", b)
		}
		pc, err := net.ListenPacket("udp4", net.JoinHostPort(ip.String(), strconv.Itoa(cfg.Port)))
		if err != nil {
			s.closeSockets()
			return nil, fmt.Errorf("can't listen on UDP %s:%d: %w", ip, cfg.Port, err)
		}
		sc := newSniffConn(pc, s.onCheck)
		s.socks = append(s.socks, sc)
		muxes = append(muxes, webrtc.NewICEUDPMux(nil, sc))
	}
	if len(muxes) == 1 {
		s.mux = muxes[0]
	} else {
		s.mux = ice.NewMultiUDPMuxDefault(muxes...)
	}
	logf("webrtc: listening on UDP %s port %d, upstream %s, fingerprint %s", strings.Join(binds, ","), cfg.Port, cfg.Upstream, fp)
	return s, nil
}

// Fingerprint is the certificate's fingerprint as Info and Pair give it:
// "sha-256 " and 32 upper-case hex bytes separated by colons.
func (s *Server) Fingerprint() string { return s.fingerprint }

// Stop sends close 1001 on every live channel that's still open, gives those
// messages a moment to go out, then closes every connection and the sockets.
// It returns within about a quarter of a second.
func (s *Server) Stop() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	var all []*conn
	for _, c := range s.conns {
		all = append(all, c)
	}
	s.conns = map[string]*conn{}
	s.mu.Unlock()

	var lives []*liveChan
	for _, c := range all {
		lives = append(lives, c.liveChans()...)
	}
	for _, l := range lives {
		l.sendClose(1001, "The phone stopped remote control")
	}
	deadline := time.Now().Add(200 * time.Millisecond)
	for _, l := range lives {
		for l.dc.BufferedAmount() > 0 && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
	}
	for _, c := range all {
		c.close()
	}
	s.closeSockets()
	s.client.CloseIdleConnections()
	s.logf("webrtc: stopped")
}

func (s *Server) closeSockets() {
	for _, sc := range s.socks {
		sc.Close()
	}
}

// onCheck sees each STUN binding request before the mux. A check for a new
// ufrag that follows the rule, within the limits, is held back while its
// connection is made, then replayed. Everything else passes through.
func (s *Server) onCheck(sc *sniffConn, local, remote string, first pkt) bool {
	if !localUfrag.MatchString(local) || !remoteUfrag.MatchString(remote) {
		return false
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return false
	}
	if _, ok := s.conns[local]; ok {
		s.mu.Unlock()
		return false
	}
	if s.countLocked(false) >= MaxUnauthed {
		s.mu.Unlock()
		return false // ignored: the mux drops checks for a ufrag it doesn't know
	}
	c := &conn{s: s, ufrag: local, from: first.addr.String(), lives: map[*liveChan]struct{}{}}
	s.conns[local] = c
	s.mu.Unlock()
	go c.setUp(sc, remote, first)
	time.AfterFunc(AuthWithin, func() {
		s.mu.Lock()
		stale := s.conns[c.ufrag] == c && !c.authed // not already dropped (a failed handshake, say)
		s.mu.Unlock()
		if stale {
			s.logf("webrtc: %s: no valid token within %v, closing", c, AuthWithin)
			s.drop(c)
		}
	})
	return true
}

// countLocked counts the connections that are (or aren't) authenticated.
func (s *Server) countLocked(authed bool) int {
	n := 0
	for _, c := range s.conns {
		if c.authed == authed {
			n++
		}
	}
	return n
}

// markAuthed records that the connection showed a valid token. It returns
// false, and closes the connection, if that would make more than MaxAuthed.
func (s *Server) markAuthed(c *conn) bool {
	s.mu.Lock()
	if c.authed {
		s.mu.Unlock()
		return true
	}
	if s.conns[c.ufrag] != c {
		s.mu.Unlock()
		return false // already dropped
	}
	if s.countLocked(true) >= MaxAuthed {
		s.mu.Unlock()
		s.logf("webrtc: %s: too many clients, closing", c)
		go c.closeWith(4409, "Too many clients")
		return false
	}
	c.authed = true
	s.mu.Unlock()
	return true
}

// drop forgets a connection and closes it.
func (s *Server) drop(c *conn) {
	s.mu.Lock()
	if s.conns[c.ufrag] == c {
		delete(s.conns, c.ufrag)
	}
	s.mu.Unlock()
	c.close()
}

// conn is one PeerConnection: one editor tab.
type conn struct {
	s     *Server
	ufrag string
	from  string

	authed bool // guarded by s.mu

	mu     sync.Mutex
	pc     *webrtc.PeerConnection
	lives  map[*liveChan]struct{}
	closed bool
}

func (c *conn) String() string { return c.ufrag[:6] + "... from " + c.from }

func (c *conn) isAuthed() bool {
	c.s.mu.Lock()
	defer c.s.mu.Unlock()
	return c.authed
}

func (c *conn) setUp(sc *sniffConn, remoteUfrag string, first pkt) {
	s := c.s
	se := webrtc.SettingEngine{}
	se.SetLite(true)
	se.SetICEUDPMux(s.mux)
	se.SetICECredentials(c.ufrag, c.ufrag)
	se.DisableCertificateFingerprintVerification(true) // the token authenticates the editor
	if err := se.SetAnsweringDTLSRole(webrtc.DTLSRoleServer); err != nil {
		s.logf("webrtc: %s: %v", c, err)
		s.drop(c)
		return
	}
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetIncludeLoopbackCandidate(true)                    // the desktop bridge is reached on 127.0.0.1
	se.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled) // the app's own responder owns .local
	se.SetICETimeouts(5*time.Second, 10*time.Second, 2*time.Second)
	api := webrtc.NewAPI(webrtc.WithSettingEngine(se))
	pc, err := api.NewPeerConnection(webrtc.Configuration{Certificates: []webrtc.Certificate{s.cert}})
	if err != nil {
		s.logf("webrtc: %s: NewPeerConnection: %v", c, err)
		s.drop(c)
		return
	}
	c.mu.Lock()
	closed := c.closed
	c.pc = pc
	c.mu.Unlock()
	if closed {
		pc.Close()
		return
	}
	pc.OnConnectionStateChange(func(st webrtc.PeerConnectionState) {
		switch st {
		case webrtc.PeerConnectionStateConnected:
			s.logf("webrtc: %s: connected", c)
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			s.drop(c)
		}
	})
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		switch dc.Label() {
		case "req":
			c.serveReq(dc)
		case "live":
			c.serveLive(dc)
		default:
			dc.Close()
		}
	})

	// The editor's offer, as far as this side needs it: its ufrag (from the
	// check), a placeholder password (an ICE-lite agent never sends checks)
	// and a placeholder fingerprint (not verified).
	offer := strings.Join([]string{
		"v=0", "o=- 1 1 IN IP4 0.0.0.0", "s=-", "t=0 0", "a=group:BUNDLE 0",
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 0.0.0.0", "a=mid:0",
		"a=ice-ufrag:" + remoteUfrag, "a=ice-pwd:" + strings.Repeat("x", 24),
		"a=fingerprint:sha-256 " + strings.Repeat("00:", 31) + "00", "a=setup:active",
		"a=sctp-port:5000", "a=max-message-size:262144", "",
	}, "\r\n")
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer}); err != nil {
		s.logf("webrtc: %s: SetRemoteDescription: %v", c, err)
		s.drop(c)
		return
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	answer, err := pc.CreateAnswer(nil)
	if err == nil {
		err = pc.SetLocalDescription(answer)
	}
	if err != nil {
		s.logf("webrtc: %s: answer: %v", c, err)
		s.drop(c)
		return
	}
	<-gathered
	sc.replay(first) // the mux knows the ufrag now
}

func (c *conn) addLive(l *liveChan) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return false
	}
	c.lives[l] = struct{}{}
	return true
}

func (c *conn) removeLive(l *liveChan) {
	c.mu.Lock()
	delete(c.lives, l)
	c.mu.Unlock()
}

func (c *conn) liveChans() []*liveChan {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]*liveChan, 0, len(c.lives))
	for l := range c.lives {
		out = append(out, l)
	}
	return out
}

// closeWith sends a close message on each live channel, waits briefly for it
// to go out, then drops the connection.
func (c *conn) closeWith(code int, reason string) {
	lives := c.liveChans()
	for _, l := range lives {
		l.sendClose(code, reason)
	}
	deadline := time.Now().Add(500 * time.Millisecond)
	for _, l := range lives {
		for l.dc.BufferedAmount() > 0 && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
	}
	c.s.drop(c)
}

func (c *conn) close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	pc := c.pc
	lives := make([]*liveChan, 0, len(c.lives))
	for l := range c.lives {
		lives = append(lives, l)
	}
	c.mu.Unlock()
	for _, l := range lives {
		l.closeUpstream()
	}
	if pc != nil {
		if err := pc.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
			c.s.logf("webrtc: %s: close: %v", c, err)
		}
	}
}
