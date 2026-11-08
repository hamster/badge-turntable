package relay

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/ice/v4"
	"github.com/pion/webrtc/v4"
)

const goodToken = "0123456789abcdef0123456789abcdef"

// fakeAPI stands in for the app's HTTP server.
type fakeAPI struct {
	srv  *httptest.Server
	big  []byte
	mu   sync.Mutex
	seen []string // request URIs, to check what the bridge let through
	live chan *websocket.Conn
}

func newFakeAPI(t *testing.T) *fakeAPI {
	f := &fakeAPI{big: make([]byte, 5<<20+123), live: make(chan *websocket.Conn, 16)}
	for i := range f.big {
		f.big[i] = byte(i * 7)
	}
	authed := func(w http.ResponseWriter, r *http.Request) bool {
		if r.Header.Get("Authorization") != "Bearer "+goodToken {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.WriteHeader(401)
			io.WriteString(w, `{"error":"unauthorized","message":"Not paired."}`)
			return false
		}
		w.Header().Set("X-Paired", "true")
		return true
	}
	up := websocket.Upgrader{}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.seen = append(f.seen, r.Method+" "+r.RequestURI)
		f.mu.Unlock()
		switch r.URL.Path {
		case "/api/info":
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			io.WriteString(w, `{"api":1,"name":"badge-turntable"}`)
		case "/api/status":
			if authed(w, r) {
				w.Header().Set("Content-Type", "application/json; charset=utf-8")
				w.Header().Set("X-Secret", "not relayed")
				io.WriteString(w, `{"ok":true}`)
			}
		case "/api/echo":
			if authed(w, r) {
				b, _ := io.ReadAll(r.Body)
				w.Header().Set("Content-Type", r.Header.Get("Content-Type"))
				w.Write(b)
			}
		case "/api/big":
			if authed(w, r) {
				w.Header().Set("Content-Type", "video/mp4")
				w.Header().Set("Content-Length", strconv.Itoa(len(f.big)))
				w.Header().Set("Content-Disposition", `attachment; filename="spin.mp4"`)
				w.Header().Set("X-Shot-Filename", "spin.mp4")
				w.Write(f.big)
			}
		case "/api/short":
			if authed(w, r) {
				w.Header().Set("Content-Length", "1000")
				w.WriteHeader(200)
				w.Write(make([]byte, 500))
				w.(http.Flusher).Flush()
				c, _, _ := w.(http.Hijacker).Hijack()
				c.Close()
			}
		case "/api/gone":
			if authed(w, r) {
				w.WriteHeader(204)
			}
		case "/api/live":
			ws, err := up.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			go f.serveLive(ws)
		default:
			http.NotFound(w, r)
		}
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

// serveLive is a small /api/live: auth, hello, status, then echoes preview
// messages and sends a normal and an oversized frame.
func (f *fakeAPI) serveLive(ws *websocket.Conn) {
	defer ws.Close()
	ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, b, err := ws.ReadMessage()
	if err != nil {
		ws.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(4408, "No auth message"))
		return
	}
	var m struct{ Type, Token string }
	json.Unmarshal(b, &m)
	if m.Type != "auth" || m.Token != goodToken {
		ws.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(4401, "Not paired"))
		return
	}
	f.live <- ws
	ws.WriteMessage(websocket.TextMessage, []byte(`{"type":"hello","api":1}`))
	ws.WriteMessage(websocket.TextMessage, []byte(`{"type":"status"}`))
	ws.SetReadDeadline(time.Time{})
	for {
		mt, b, err := ws.ReadMessage()
		if err != nil {
			return
		}
		if mt == websocket.TextMessage && strings.Contains(string(b), `"preview"`) {
			ws.WriteMessage(websocket.BinaryMessage, bytes.Repeat([]byte{1}, 300*1024)) // too big: skipped
			ws.WriteMessage(websocket.BinaryMessage, bytes.Repeat([]byte{2}, 40*1024))
			ws.WriteMessage(websocket.TextMessage, []byte(`{"type":"ack"}`))
		}
	}
}

func (f *fakeAPI) saw(sub string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, s := range f.seen {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

func startRelay(t *testing.T, f *fakeAPI) *Server {
	s, err := Start(Config{
		Bind:     []string{"127.0.0.1"},
		Port:     freeUDPPort(t),
		Upstream: strings.TrimPrefix(f.srv.URL, "http://"),
		CertPath: t.TempDir() + "/cert.pem",
		Logf:     t.Logf,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Stop)
	return s
}

// editor is a Pion client that connects the way the editor does: it makes up
// the ufrag and writes the phone's answer itself.
type editor struct {
	pc   *webrtc.PeerConnection
	live *webrtc.DataChannel
	msgs chan webrtc.DataChannelMessage
}

func connect(t *testing.T, s *Server, fingerprint string) (*editor, error) {
	se := webrtc.SettingEngine{}
	se.SetIncludeLoopbackCandidate(true)
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	pc, err := webrtc.NewAPI(webrtc.WithSettingEngine(se)).NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	e := &editor{pc: pc, msgs: make(chan webrtc.DataChannelMessage, 64)}
	e.live, _ = pc.CreateDataChannel("live", nil)
	opened := make(chan struct{})
	e.live.OnOpen(func() { close(opened) })
	e.live.OnMessage(func(m webrtc.DataChannelMessage) { e.msgs <- m })
	offer, _ := pc.CreateOffer(nil)
	if err := pc.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	u := fmt.Sprintf("%032x", time.Now().UnixNano())
	port := s.cfg.Port
	answer := strings.Join([]string{
		"v=0", "o=- 1 1 IN IP4 0.0.0.0", "s=-", "t=0 0", "a=group:BUNDLE 0", "a=ice-lite",
		fmt.Sprintf("m=application %d UDP/DTLS/SCTP webrtc-datachannel", port), "c=IN IP4 0.0.0.0", "a=mid:0",
		"a=ice-ufrag:" + u, "a=ice-pwd:" + u,
		"a=fingerprint:" + fingerprint, "a=setup:passive",
		"a=sctp-port:5000", "a=max-message-size:262144",
		fmt.Sprintf("a=candidate:1 1 udp 2130706431 127.0.0.1 %d typ host", port), "a=end-of-candidates", "",
	}, "\r\n")
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: answer}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-opened:
		return e, nil
	case <-time.After(5 * time.Second):
		return nil, fmt.Errorf("live channel didn't open")
	}
}

func (e *editor) next(t *testing.T) webrtc.DataChannelMessage {
	t.Helper()
	select {
	case m := <-e.msgs:
		return m
	case <-time.After(5 * time.Second):
		t.Fatal("no live message")
		return webrtc.DataChannelMessage{}
	}
}

func (e *editor) nextJSON(t *testing.T) map[string]any {
	t.Helper()
	for {
		m := e.next(t)
		if !m.IsString {
			continue
		}
		var v map[string]any
		if err := json.Unmarshal(m.Data, &v); err != nil {
			t.Fatal(err)
		}
		return v
	}
}

type reply struct {
	head  map[string]any
	body  []byte
	end   map[string]any // nil: the channel closed without one
	extra int            // messages after end
}

func (r reply) status() int { return int(r.head["status"].(float64)) }

func (e *editor) req(t *testing.T, method, path string, token any, body any) reply {
	t.Helper()
	ch, err := e.pc.CreateDataChannel("req", nil)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var r reply
	done := make(chan struct{})
	var once sync.Once
	ch.OnOpen(func() {
		b, _ := json.Marshal(map[string]any{"method": method, "path": path, "token": token, "body": body})
		ch.SendText(string(b))
	})
	ch.OnMessage(func(m webrtc.DataChannelMessage) {
		mu.Lock()
		defer mu.Unlock()
		if r.end != nil {
			r.extra++
			return
		}
		if !m.IsString {
			r.body = append(r.body, m.Data...)
			return
		}
		var v map[string]any
		json.Unmarshal(m.Data, &v)
		if v["type"] == "head" {
			r.head = v
		} else if v["type"] == "end" {
			r.end = v
		}
	})
	ch.OnClose(func() { once.Do(func() { close(done) }) })
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		t.Fatalf("%s %s: no reply", method, path)
	}
	mu.Lock()
	defer mu.Unlock()
	return r
}

func TestRequestsAndLive(t *testing.T) {
	f := newFakeAPI(t)
	s := startRelay(t, f)
	e, err := connect(t, s, s.Fingerprint())
	if err != nil {
		t.Fatal(err)
	}

	r := e.req(t, "GET", "/api/info", nil, nil)
	if r.status() != 200 || string(r.body) != `{"api":1,"name":"badge-turntable"}` || r.end == nil {
		t.Fatalf("info: %+v %q", r.head, r.body)
	}
	if r.end["bytes"].(float64) != float64(len(r.body)) {
		t.Fatalf("end bytes %v, body %d", r.end["bytes"], len(r.body))
	}

	r = e.req(t, "GET", "/api/status", "nope", nil)
	if r.status() != 401 || !strings.Contains(string(r.body), "unauthorized") {
		t.Fatalf("bad token: %+v %q", r.head, r.body)
	}

	r = e.req(t, "GET", "/api/status", goodToken, nil)
	h := r.head["headers"].(map[string]any)
	if r.status() != 200 || h["content-type"] != "application/json; charset=utf-8" || h["x-secret"] != nil || h["x-paired"] != nil {
		t.Fatalf("status: %+v", r.head)
	}

	r = e.req(t, "POST", "/api/echo", goodToken, map[string]any{"by": 90})
	if r.status() != 200 || string(r.body) != `{"by":90}` || r.head["headers"].(map[string]any)["content-type"] != "application/json; charset=utf-8" {
		t.Fatalf("echo: %+v %q", r.head, r.body)
	}

	r = e.req(t, "DELETE", "/api/gone", goodToken, nil)
	if r.status() != 204 || len(r.body) != 0 || r.end == nil {
		t.Fatalf("204: %+v", r)
	}

	t0 := time.Now()
	r = e.req(t, "GET", "/api/big", goodToken, nil)
	h = r.head["headers"].(map[string]any)
	if r.status() != 200 || sha256.Sum256(r.body) != sha256.Sum256(f.big) || r.end == nil || r.extra != 0 {
		t.Fatalf("big: status %v, %d bytes of %d, end %v", r.status(), len(r.body), len(f.big), r.end)
	}
	if h["content-length"] != strconv.Itoa(len(f.big)) || h["x-shot-filename"] != "spin.mp4" || h["content-disposition"] != `attachment; filename="spin.mp4"` {
		t.Fatalf("big headers: %+v", h)
	}
	t.Logf("%d bytes in %v", len(r.body), time.Since(t0))

	r = e.req(t, "GET", "/api/short", goodToken, nil)
	if r.end != nil || len(r.body) > 500 {
		t.Fatalf("truncated body was reported complete: %+v, %d bytes", r.end, len(r.body))
	}

	// live: auth first, then hello and status, as over the WebSocket.
	e.live.SendText(`{"type":"auth","token":"` + goodToken + `"}`)
	if m := e.nextJSON(t); m["type"] != "hello" {
		t.Fatalf("want hello, got %v", m)
	}
	if m := e.nextJSON(t); m["type"] != "status" {
		t.Fatalf("want status, got %v", m)
	}
	e.live.SendText(`{"type":"preview","fps":8}`)
	m := e.next(t)
	if m.IsString || len(m.Data) != 40*1024 || m.Data[0] != 2 {
		t.Fatalf("want the 40 KB frame (the 300 KB one skipped), got string=%v len=%d", m.IsString, len(m.Data))
	}
	if v := e.nextJSON(t); v["type"] != "ack" {
		t.Fatalf("want ack, got %v", v)
	}

	// Stopping sends close 1001 on live.
	s.Stop()
	if v := e.nextJSON(t); v["type"] != "close" || v["code"].(float64) != 1001 {
		t.Fatalf("want close 1001, got %v", v)
	}
}

func TestLiveCloseCodePassesThrough(t *testing.T) {
	f := newFakeAPI(t)
	s := startRelay(t, f)
	e, err := connect(t, s, s.Fingerprint())
	if err != nil {
		t.Fatal(err)
	}
	e.live.SendText(`{"type":"auth","token":"wrong"}`)
	if v := e.nextJSON(t); v["type"] != "close" || v["code"].(float64) != 4401 || v["reason"] != "Not paired" {
		t.Fatalf("want close 4401, got %v", v)
	}
}

func TestPathsOutsideTheAPIAreRefused(t *testing.T) {
	f := newFakeAPI(t)
	s := startRelay(t, f)
	e, err := connect(t, s, s.Fingerprint())
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"@evil.example/api/x", "http://evil.example/api/x", "//evil.example/api/x",
		"/api/../x", "/other", "/api/x\r\nHost: evil", "api/x"} {
		r := e.req(t, "GET", p, goodToken, nil)
		if r.status() != 400 || r.end == nil {
			t.Errorf("%q: %+v", p, r.head)
		}
	}
	for _, m := range []string{"PUT", "HEAD", "CONNECT", "get"} {
		if r := e.req(t, m, "/api/info", nil, nil); r.status() != 405 {
			t.Errorf("%s: %+v", m, r.head)
		}
	}
	if f.saw("evil") || f.saw("/other") || f.saw("..") {
		t.Fatalf("a refused request reached the API: %v", f.seen)
	}
}

func TestAPIURL(t *testing.T) {
	ok := map[string]string{
		"/api/info":                          "http://up:1/api/info",
		"/api/shots/s-12?variant=thumb":      "http://up:1/api/shots/s-12?variant=thumb",
		"/api/sessions/a/media/front%20.jpg": "http://up:1/api/sessions/a/media/front%20.jpg",
	}
	for in, want := range ok {
		u, err := apiURL("up:1", in)
		if err != nil || u.String() != want {
			t.Errorf("%q: %v %v", in, u, err)
		}
	}
	for _, in := range []string{"", "/", "/api", "/apix/y", "@x/api/", "/api/a b", "/api/#x", `/api\x`, "/api/./x", "/%61pi/x"} {
		if u, err := apiURL("up:1", in); err == nil {
			t.Errorf("%q: accepted as %v", in, u)
		}
	}
}

func TestWrongFingerprintIsRefused(t *testing.T) {
	f := newFakeAPI(t)
	s := startRelay(t, f)
	wrong := "sha-256 " + strings.TrimSuffix(strings.Repeat("AB:", 32), ":")
	if _, err := connect(t, s, wrong); err == nil {
		t.Fatal("connected with the wrong fingerprint")
	}
}

func TestUnauthenticatedConnectionIsClosed(t *testing.T) {
	old := AuthWithin
	AuthWithin = 1500 * time.Millisecond
	defer func() { AuthWithin = old }()
	f := newFakeAPI(t)
	s := startRelay(t, f)
	e, err := connect(t, s, s.Fingerprint())
	if err != nil {
		t.Fatal(err)
	}
	e.req(t, "GET", "/api/info", nil, nil) // no token: doesn't count
	closed := make(chan struct{})
	var once sync.Once
	e.pc.OnConnectionStateChange(func(st webrtc.PeerConnectionState) {
		if st == webrtc.PeerConnectionStateClosed || st == webrtc.PeerConnectionStateFailed || st == webrtc.PeerConnectionStateDisconnected {
			once.Do(func() { close(closed) })
		}
	})
	select {
	case <-closed:
	case <-time.After(10 * time.Second):
		t.Fatal("an unauthenticated connection stayed open")
	}
	s.mu.Lock()
	n := len(s.conns)
	s.mu.Unlock()
	if n != 0 {
		t.Fatalf("%d connections left", n)
	}
}

func TestAuthenticatedConnectionStays(t *testing.T) {
	old := AuthWithin
	AuthWithin = 1500 * time.Millisecond
	defer func() { AuthWithin = old }()
	f := newFakeAPI(t)
	s := startRelay(t, f)
	e, err := connect(t, s, s.Fingerprint())
	if err != nil {
		t.Fatal(err)
	}
	if r := e.req(t, "GET", "/api/status", goodToken, nil); r.status() != 200 {
		t.Fatal(r.head)
	}
	time.Sleep(2500 * time.Millisecond)
	if r := e.req(t, "GET", "/api/status", goodToken, nil); r.status() != 200 {
		t.Fatal(r.head)
	}
}

func TestAtMostFourAuthenticated(t *testing.T) {
	f := newFakeAPI(t)
	s := startRelay(t, f)
	var eds []*editor
	for i := 0; i < MaxAuthed+1; i++ {
		e, err := connect(t, s, s.Fingerprint())
		if err != nil {
			t.Fatal(err)
		}
		e.live.SendText(`{"type":"auth","token":"` + goodToken + `"}`)
		eds = append(eds, e)
		if i < MaxAuthed {
			if m := e.nextJSON(t); m["type"] != "hello" {
				t.Fatalf("%d: want hello, got %v", i, m)
			}
		}
	}
	if v := eds[MaxAuthed].nextJSON(t); v["type"] != "close" || v["code"].(float64) != 4409 {
		t.Fatalf("5th: want close 4409, got %v", v)
	}
}

func TestUnauthenticatedLimit(t *testing.T) {
	s := &Server{conns: map[string]*conn{}, logf: t.Logf}
	for i := 0; i < MaxUnauthed; i++ {
		u := fmt.Sprintf("%032x", i)
		s.conns[u] = &conn{s: s, ufrag: u}
	}
	if s.onCheck(nil, fmt.Sprintf("%032x", 99), "abcd", pkt{}) {
		t.Fatal("a check beyond the limit started a connection")
	}
	if s.onCheck(nil, "NOT-OURS", "abcd", pkt{}) || s.onCheck(nil, fmt.Sprintf("%032x", 98), "a\r\nb", pkt{}) {
		t.Fatal("a bad ufrag was accepted")
	}
}

func TestCertificateIsKept(t *testing.T) {
	path := t.TempDir() + "/c.pem"
	c1, err := loadOrMakeCert(path, t.Logf)
	if err != nil {
		t.Fatal(err)
	}
	c2, err := loadOrMakeCert(path, t.Logf)
	if err != nil {
		t.Fatal(err)
	}
	f1, _ := fingerprintOf(c1)
	f2, _ := fingerprintOf(c2)
	if f1 != f2 || !strings.HasPrefix(f1, "sha-256 ") || len(f1) != len("sha-256 ")+32*3-1 || strings.ToUpper(f1[8:]) != f1[8:] {
		t.Fatalf("%q vs %q", f1, f2)
	}
}
