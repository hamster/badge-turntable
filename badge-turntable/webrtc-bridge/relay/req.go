package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pion/webrtc/v4"
)

const (
	bodyChunk = 64 * 1024 // body messages are at most this
	highWater = 1 << 20   // stop sending while more than this is buffered…
	lowWater  = 256 * 1024
	maxBody   = 16 * 1024 // request bodies, as over HTTP
	firstMsg  = 10 * time.Second
)

// relayedHeaders are the only response headers passed to the editor.
var relayedHeaders = []string{"content-type", "content-length", "content-disposition", "x-shot-filename"}

// pairedHeader is set by the app on a response to a request whose token is
// valid. It's how the bridge learns that a connection is authenticated,
// without knowing the tokens itself. It isn't passed on.
const pairedHeader = "X-Paired"

// request is a req channel's one message from the editor.
type request struct {
	Method string          `json:"method"`
	Path   string          `json:"path"`
	Token  *string         `json:"token"`
	Body   json.RawMessage `json:"body"`
}

func newUpstreamClient() *http.Client {
	tr := &http.Transport{
		Proxy:               nil, // straight to the app on loopback, whatever the environment says
		DialContext:         (&net.Dialer{Timeout: 3 * time.Second}).DialContext,
		DisableCompression:  true, // pass bodies and their lengths through untouched
		MaxConnsPerHost:     8,    // the app's server has 24 connection slots in all; more requests wait here
		MaxIdleConnsPerHost: 8,
		IdleConnTimeout:     20 * time.Second, // under the app's 30 s, so a reused connection isn't one it just closed
	}
	return &http.Client{
		Transport:     tr,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// apiURL checks the editor's path and builds the upstream URL from it. Only
// the path and query come from the editor: the scheme and host are fixed, so a
// path can't send the request anywhere but the app's own /api/.
func apiURL(upstream, path string) (*url.URL, error) {
	if path == "" || path[0] != '/' {
		return nil, errors.New("path must start with /api/")
	}
	for _, r := range path {
		if r < 0x21 || r == 0x7f || r == '\\' || r == '#' {
			return nil, errors.New("path has a character that isn't allowed")
		}
	}
	u, err := url.ParseRequestURI(path)
	if err != nil {
		return nil, errors.New("path isn't a valid request path")
	}
	if u.Scheme != "" || u.Host != "" || u.User != nil || u.Opaque != "" {
		return nil, errors.New("path must start with /api/")
	}
	if !strings.HasPrefix(u.Path, "/api/") || !strings.HasPrefix(u.EscapedPath(), "/api/") {
		return nil, errors.New("path must start with /api/")
	}
	for _, seg := range strings.Split(u.Path, "/") {
		if seg == ".." || seg == "." {
			return nil, errors.New("path can't have . or .. segments")
		}
	}
	return &url.URL{Scheme: "http", Host: upstream, Path: u.Path, RawPath: u.RawPath, RawQuery: u.RawQuery}, nil
}

// reqChan is one req channel: one request, one response.
type reqChan struct {
	c      *conn
	dc     *webrtc.DataChannel
	ctx    context.Context
	cancel context.CancelFunc
	low    chan struct{}
}

func (c *conn) serveReq(dc *webrtc.DataChannel) {
	ctx, cancel := context.WithCancel(context.Background())
	r := &reqChan{c: c, dc: dc, ctx: ctx, cancel: cancel, low: make(chan struct{}, 1)}
	dc.SetBufferedAmountLowThreshold(lowWater)
	dc.OnBufferedAmountLow(func() {
		select {
		case r.low <- struct{}{}:
		default:
		}
	})
	dc.OnClose(cancel) // cancelling stops the upstream request, and the app stops sending
	var once sync.Once
	got := make(chan struct{})
	dc.OnMessage(func(m webrtc.DataChannelMessage) {
		once.Do(func() {
			close(got)
			go r.serve(m)
		})
	})
	go func() {
		select {
		case <-got:
		case <-ctx.Done():
		case <-time.After(firstMsg):
			dc.Close()
		}
	}()
}

func (r *reqChan) serve(m webrtc.DataChannelMessage) {
	defer r.cancel()
	if !m.IsString {
		r.fail(400, "bad_request", "The request must be a JSON text message.")
		return
	}
	var q request
	if err := json.Unmarshal(m.Data, &q); err != nil {
		r.fail(400, "bad_request", "The request isn't valid JSON.")
		return
	}
	switch q.Method {
	case "GET", "POST", "DELETE":
	default:
		r.fail(405, "method_not_allowed", "Use GET, POST or DELETE.")
		return
	}
	u, err := apiURL(r.c.s.cfg.Upstream, q.Path)
	if err != nil {
		r.fail(400, "bad_request", "Bad path: "+err.Error()+".")
		return
	}
	var body io.Reader
	hasBody := len(q.Body) > 0 && string(bytes.TrimSpace(q.Body)) != "null"
	if hasBody {
		if len(q.Body) > maxBody {
			r.fail(400, "bad_request", "Request body too large.")
			return
		}
		body = bytes.NewReader(q.Body)
	}
	hreq, err := http.NewRequestWithContext(r.ctx, q.Method, u.String(), body)
	if err != nil {
		r.fail(400, "bad_request", "Bad request: "+err.Error())
		return
	}
	if hasBody {
		hreq.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	if q.Token != nil {
		if strings.ContainsAny(*q.Token, "\r\n") {
			r.fail(400, "bad_request", "Bad token.")
			return
		}
		hreq.Header.Set("Authorization", "Bearer "+*q.Token)
	}
	resp, err := r.c.s.client.Do(hreq)
	if err != nil {
		if r.ctx.Err() != nil {
			return // the editor closed the channel
		}
		r.fail(502, "bad_gateway", "The phone's API didn't answer: "+err.Error())
		return
	}
	defer resp.Body.Close()

	if q.Token != nil && resp.Header.Get(pairedHeader) == "true" && !r.c.s.markAuthed(r.c) {
		return // over the limit: the connection is being closed
	}
	headers := map[string]string{}
	for _, h := range relayedHeaders {
		if v := resp.Header.Get(h); v != "" {
			headers[h] = v
		}
	}
	if _, ok := headers["content-length"]; !ok && resp.ContentLength >= 0 && resp.StatusCode != http.StatusNoContent {
		headers["content-length"] = strconv.FormatInt(resp.ContentLength, 10)
	}
	if !r.sendJSON(map[string]any{"type": "head", "status": resp.StatusCode, "headers": headers}) {
		return
	}
	n, err := r.copyBody(resp.Body, resp.ContentLength)
	if err != nil {
		// Cut short (the editor went away, or the app's stream broke): no end
		// marker, so the editor can tell.
		r.dc.Close()
		return
	}
	if r.sendJSON(map[string]any{"type": "end", "bytes": n}) {
		r.flushAndClose()
	}
}

// fail answers with an error head, the usual error body, and the end marker.
func (r *reqChan) fail(status int, code, message string) {
	b, _ := json.Marshal(map[string]any{"error": code, "message": message})
	head := map[string]any{"type": "head", "status": status, "headers": map[string]string{
		"content-type":   "application/json; charset=utf-8",
		"content-length": strconv.Itoa(len(b)),
	}}
	if r.sendJSON(head) && r.send(b) && r.sendJSON(map[string]any{"type": "end", "bytes": len(b)}) {
		r.flushAndClose()
	}
}

// copyBody sends the body in messages of up to bodyChunk bytes. It fails if
// the body ends early (want is its length, or -1 if unknown).
func (r *reqChan) copyBody(body io.Reader, want int64) (int64, error) {
	buf := make([]byte, bodyChunk)
	var total int64
	for {
		n := 0
		var err error
		for n < len(buf) && err == nil {
			var m int
			m, err = body.Read(buf[n:])
			n += m
		}
		if n > 0 {
			if !r.send(buf[:n]) {
				return total, context.Canceled
			}
			total += int64(n)
		}
		if err == io.EOF {
			if want >= 0 && total != want {
				return total, io.ErrUnexpectedEOF
			}
			return total, nil
		}
		if err != nil {
			return total, err
		}
	}
}

// send waits while too much is buffered, then sends one binary message.
func (r *reqChan) send(b []byte) bool {
	for r.dc.BufferedAmount() > highWater {
		select {
		case <-r.low:
		case <-r.ctx.Done():
			return false
		case <-time.After(time.Second):
		}
	}
	if r.ctx.Err() != nil {
		return false
	}
	return r.dc.Send(b) == nil
}

func (r *reqChan) sendJSON(v any) bool {
	b, err := json.Marshal(v)
	if err != nil {
		panic(fmt.Sprint("relay: marshal: ", err))
	}
	if r.ctx.Err() != nil {
		return false
	}
	return r.dc.SendText(string(b)) == nil
}

// flushAndClose closes the channel once what's buffered has gone.
func (r *reqChan) flushAndClose() {
	deadline := time.Now().Add(30 * time.Second)
	for r.dc.BufferedAmount() > 0 && r.ctx.Err() == nil && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	r.dc.Close()
}
