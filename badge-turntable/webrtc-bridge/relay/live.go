package relay

import (
	"encoding/json"
	"errors"
	"net"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"
)

const (
	maxFrame       = 256 * 1024 // a preview frame bigger than one message can be is skipped
	frameDropAbove = 256 * 1024 // a frame is dropped while more than this is buffered
	liveInbox      = 64         // editor messages waiting for the WebSocket; more closes the channel
)

// liveChan relays a live channel to the app's /api/live WebSocket, message
// for message. The WebSocket is opened when the channel opens, so the app's
// own 5 s auth timeout (4408) covers a channel that never authenticates.
type liveChan struct {
	c  *conn
	dc *webrtc.DataChannel

	in       chan webrtc.DataChannelMessage
	done     chan struct{}
	doneOnce sync.Once

	closeOnce sync.Once // the close message goes out once

	mu        sync.Mutex
	ws        *websocket.Conn
	byUs      bool // the WebSocket was (or is to be) closed from this side
	skipNoted bool
}

func (c *conn) serveLive(dc *webrtc.DataChannel) {
	l := &liveChan{c: c, dc: dc, in: make(chan webrtc.DataChannelMessage, liveInbox), done: make(chan struct{})}
	if !c.addLive(l) {
		dc.Close()
		return
	}
	dc.OnMessage(func(m webrtc.DataChannelMessage) {
		select {
		case l.in <- m:
		default:
			c.s.logf("webrtc: %s: live: the editor sent too much, closing", c)
			go dc.Close()
		}
	})
	dc.OnClose(func() {
		c.removeLive(l)
		l.doneOnce.Do(func() { close(l.done) })
		l.closeUpstream()
	})
	dc.OnOpen(func() { go l.run() })
}

func (l *liveChan) run() {
	d := websocket.Dialer{
		NetDialContext:   (&net.Dialer{Timeout: 3 * time.Second}).DialContext,
		HandshakeTimeout: 5 * time.Second,
	}
	ws, _, err := d.Dial("ws://"+l.c.s.cfg.Upstream+"/api/live", nil)
	if err != nil {
		l.c.s.logf("webrtc: %s: live: %v", l.c, err)
		l.sendClose(1011, "The phone's API didn't answer")
		l.flush(500 * time.Millisecond)
		l.dc.Close()
		return
	}
	ws.SetReadLimit(4 << 20)
	l.mu.Lock()
	if l.byUs {
		l.mu.Unlock()
		ws.Close()
		return
	}
	l.ws = ws
	l.mu.Unlock()
	go l.toUpstream(ws)
	l.fromUpstream(ws)
}

// toUpstream forwards the editor's messages, starting with its auth.
func (l *liveChan) toUpstream(ws *websocket.Conn) {
	for {
		select {
		case m := <-l.in:
			mt := websocket.BinaryMessage
			if m.IsString {
				mt = websocket.TextMessage
			}
			ws.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if err := ws.WriteMessage(mt, m.Data); err != nil {
				return // the read side sees the socket fail
			}
		case <-l.done:
			return
		}
	}
}

// fromUpstream forwards the app's messages. Text is never dropped; a preview
// frame is skipped if it can't fit in one message, and dropped while the
// channel is backed up (the WebSocket's rule of at most one frame waiting).
func (l *liveChan) fromUpstream(ws *websocket.Conn) {
	for {
		mt, data, err := ws.ReadMessage()
		if err != nil {
			l.mu.Lock()
			byUs := l.byUs
			l.mu.Unlock()
			if byUs {
				return
			}
			code, reason := 1006, "The phone's API closed the connection"
			var ce *websocket.CloseError
			if errors.As(err, &ce) {
				code, reason = ce.Code, ce.Text
			}
			l.sendClose(code, reason)
			l.flush(500 * time.Millisecond)
			l.dc.Close()
			return
		}
		switch mt {
		case websocket.TextMessage:
			if !l.c.isAuthed() && isHello(data) && !l.c.s.markAuthed(l.c) {
				return // over the limit: the connection is being closed with 4409
			}
			if l.dc.SendText(string(data)) != nil {
				return
			}
		case websocket.BinaryMessage:
			if len(data) > maxFrame {
				l.mu.Lock()
				first := !l.skipNoted
				l.skipNoted = true
				l.mu.Unlock()
				if first {
					l.c.s.logf("webrtc: %s: live: skipping preview frames over %d bytes (this one is %d)", l.c, maxFrame, len(data))
				}
				continue
			}
			if l.dc.BufferedAmount() > frameDropAbove {
				continue
			}
			if l.dc.Send(data) != nil {
				return
			}
		}
	}
}

func isHello(data []byte) bool {
	var m struct {
		Type string `json:"type"`
	}
	return len(data) < 4096 && json.Unmarshal(data, &m) == nil && m.Type == "hello"
}

// sendClose sends {"type":"close","code":…,"reason":…}, once per channel: a
// data channel has no close codes of its own.
func (l *liveChan) sendClose(code int, reason string) {
	l.closeOnce.Do(func() {
		b, _ := json.Marshal(map[string]any{"type": "close", "code": code, "reason": reason})
		_ = l.dc.SendText(string(b))
	})
}

func (l *liveChan) flush(max time.Duration) {
	deadline := time.Now().Add(max)
	for l.dc.BufferedAmount() > 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
}

// closeUpstream closes the WebSocket from this side (the channel or the
// connection went away).
func (l *liveChan) closeUpstream() {
	l.mu.Lock()
	if l.byUs {
		l.mu.Unlock()
		return
	}
	l.byUs = true
	ws := l.ws
	l.mu.Unlock()
	if ws != nil {
		_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""),
			time.Now().Add(100*time.Millisecond))
		ws.Close()
	}
}
