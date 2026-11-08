package relay

import (
	"io"
	"net"
	"strings"
	"sync"

	"github.com/pion/stun/v3"
)

type pkt struct {
	data []byte
	addr net.Addr
}

// sniffConn sits between a UDP socket and Pion's mux. It shows each STUN
// binding request's ufrags to onCheck, which can hold the packet back while a
// connection is made for a new ufrag (the mux would drop it), and replay it
// once the mux knows the ufrag, which saves waiting for the browser to
// retransmit.
type sniffConn struct {
	net.PacketConn
	onCheck func(sc *sniffConn, local, remote string, first pkt) bool // true: held
	in      chan pkt

	closeOnce sync.Once
	done      chan struct{}
}

func newSniffConn(c net.PacketConn, onCheck func(*sniffConn, string, string, pkt) bool) *sniffConn {
	sc := &sniffConn{PacketConn: c, onCheck: onCheck, in: make(chan pkt, 256), done: make(chan struct{})}
	go sc.readLoop()
	return sc
}

func (sc *sniffConn) readLoop() {
	defer close(sc.in)
	for {
		buf := make([]byte, 1500)
		n, addr, err := sc.PacketConn.ReadFrom(buf)
		if err != nil {
			return
		}
		p := pkt{buf[:n], addr}
		if sc.check(p) {
			continue
		}
		select {
		case sc.in <- p:
		case <-sc.done:
			return
		}
	}
}

func (sc *sniffConn) check(p pkt) bool {
	if !stun.IsMessage(p.data) {
		return false
	}
	m := &stun.Message{Raw: append([]byte(nil), p.data...)}
	if m.Decode() != nil || m.Type != stun.BindingRequest {
		return false
	}
	var u stun.Username
	if u.GetFrom(m) != nil {
		return false
	}
	local, remote, ok := strings.Cut(u.String(), ":")
	return ok && sc.onCheck(sc, local, remote, p)
}

func (sc *sniffConn) replay(p pkt) {
	defer func() { _ = recover() }() // in is closed once the socket is
	select {
	case sc.in <- p:
	case <-sc.done:
	}
}

func (sc *sniffConn) ReadFrom(b []byte) (int, net.Addr, error) {
	p, ok := <-sc.in
	if !ok {
		return 0, nil, io.EOF // what the mux takes as a quiet end; net.ErrClosed it logs as an error
	}
	return copy(b, p.data), p.addr, nil
}

func (sc *sniffConn) Close() error {
	var err error
	sc.closeOnce.Do(func() {
		close(sc.done)
		err = sc.PacketConn.Close()
	})
	return err
}
