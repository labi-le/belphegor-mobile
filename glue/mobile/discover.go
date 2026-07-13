package mobile

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/labi-le/belphegor/internal/discovering"
	"github.com/rs/zerolog"
	"golang.org/x/net/ipv4"
)

// Discoverer runs LAN peer discovery and reports finds to a connector. It is
// the seam that lets the mobile build swap the discovery mechanism: the desktop
// core uses schollz/peerdiscovery (which enumerates interfaces over a netlink
// route socket), but that netlink call is denied to a sandboxed Android
// untrusted_app. multicastDiscoverer below is a netlink-free implementation.
type Discoverer interface {
	// Discover blocks until ctx is done, broadcasting the connector's payload
	// and reporting peers it hears from.
	Discover(ctx context.Context, c discovering.Connector)
}

// SSDP defaults, matching schollz/peerdiscovery's defaults so an Android phone
// and a desktop belphegor (which uses that library) discover each other.
const (
	multicastAddr = "239.255.255.250"
	multicastPort = 9999
	multicastTTL  = 2
	// Rebuild the sockets periodically so multicast membership survives a Wi-Fi
	// change (INADDR_ANY silently drops the group when the interface changes),
	// and back off before retrying while the network is momentarily unavailable.
	rejoinEvery   = 45 * time.Second
	rejoinBackoff = 3 * time.Second
)

// multicastDiscoverer does LAN discovery without ever touching a netlink route
// socket. It never calls net.Interfaces()/InterfaceByName (SELinux denies those
// to untrusted_app); it joins the multicast group on the kernel's default
// interface (INADDR_ANY) and relies on the Wi-Fi MulticastLock the foreground
// service holds while discovery is enabled. Wire-compatible with the desktop:
// raw payload datagrams to the same group and port.
type multicastDiscoverer struct {
	delay  time.Duration
	logger zerolog.Logger
}

var _ Discoverer = (*multicastDiscoverer)(nil)

func newMulticastDiscoverer(delay time.Duration, logger zerolog.Logger) *multicastDiscoverer {
	if delay <= 0 {
		delay = 30 * time.Second
	}
	return &multicastDiscoverer{delay: delay, logger: logger.With().Str("component", "discover-mobile").Logger()}
}

func (d *multicastDiscoverer) Discover(ctx context.Context, c discovering.Connector) {
	// Outer loop: (re)join the group and serve until the membership ends, then
	// rejoin. This recovers LAN discovery after a Wi-Fi change without ever
	// enumerating interfaces (still netlink-free).
	for ctx.Err() == nil {
		if !d.serve(ctx, c) {
			select {
			case <-ctx.Done():
				return
			case <-time.After(rejoinBackoff):
			}
		}
	}
}

// serve joins the multicast group, broadcasts the payload, and reports peers
// until the rejoin timer fires, a fatal read error occurs, or ctx is cancelled.
// It returns false only when socket setup failed (so the caller backs off).
func (d *multicastDiscoverer) serve(ctx context.Context, c discovering.Connector) bool {
	group := &net.UDPAddr{IP: net.ParseIP(multicastAddr), Port: multicastPort}

	// nil interface => join on INADDR_ANY; this path does NOT enumerate
	// interfaces (no netlink), unlike ListenMulticastUDP with a concrete ifi.
	recv, err := net.ListenMulticastUDP("udp4", nil, group)
	if err != nil {
		d.logger.Warn().Err(err).Msg("multicast listen failed; LAN discovery off, add peers manually")
		return false
	}
	defer recv.Close()

	send, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		d.logger.Warn().Err(err).Msg("multicast send socket failed; LAN discovery off, add peers manually")
		return false
	}
	defer send.Close()

	// Don't loop our own broadcasts back to the joined recv socket, and keep the
	// TTL tiny (LAN only). Both are plain setsockopts (no netlink); best-effort.
	if p := ipv4.NewPacketConn(send); p != nil {
		_ = p.SetMulticastLoopback(false)
		_ = p.SetMulticastTTL(multicastTTL)
	}

	// Broadcast under a sub-context so it stops the moment this membership ends.
	bctx, bcancel := context.WithCancel(ctx)
	defer bcancel()
	go d.broadcast(bctx, send, group, c)

	d.logger.Info().Str("group", group.String()).Msg("LAN discovery joined (netlink-free)")
	// Interrupt the blocking read the instant ctx is cancelled: a past deadline
	// unblocks ReadFromUDP without any periodic poll.
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			_ = recv.SetReadDeadline(time.Now())
		case <-done:
		}
	}()

	// One absolute deadline for the whole membership: the read blocks (no CPU,
	// no timer churn) until a datagram arrives or the rejoin instant passes,
	// instead of waking every couple of seconds to re-check. Absolute, so it
	// still fires the rejoin on schedule across any number of received packets.
	_ = recv.SetReadDeadline(time.Now().Add(rejoinEvery))

	buf := make([]byte, 65536)
	for {
		n, src, rErr := recv.ReadFromUDP(buf)
		if rErr != nil {
			if ctx.Err() != nil {
				return true
			}
			var ne net.Error
			if errors.As(rErr, &ne) && ne.Timeout() {
				return true // rejoin deadline reached: rebuild sockets to refresh membership
			}
			// A non-timeout error means the socket/interface went away (Wi-Fi
			// change); rejoin.
			d.logger.Trace().Err(rErr).Msg("multicast read error; rejoining")
			return true
		}
		if n == 0 || src == nil {
			continue
		}
		payload := make([]byte, n)
		copy(payload, buf[:n])
		d.deliver(ctx, c, src.IP, payload)
	}
}

// deliver hands one datagram to the connector, contained so a malformed packet
// from any LAN device can't panic the discovery loop.
func (d *multicastDiscoverer) deliver(ctx context.Context, c discovering.Connector, ip net.IP, payload []byte) {
	defer func() {
		if r := recover(); r != nil {
			d.logger.Warn().Interface("panic", r).Msg("peer-discovered handler panicked; dropping datagram")
		}
	}()
	c.PeerDiscovered(ctx, ip, payload)
}

func (d *multicastDiscoverer) broadcast(ctx context.Context, send *net.UDPConn, group *net.UDPAddr, c discovering.Connector) {
	defer func() {
		if r := recover(); r != nil {
			d.logger.Warn().Interface("panic", r).Msg("discovery broadcast panicked")
		}
	}()
	t := time.NewTicker(d.delay)
	defer t.Stop()
	for {
		if _, err := send.WriteToUDP(c.DiscoveryPayload(), group); err != nil {
			d.logger.Trace().Err(err).Msg("multicast broadcast failed")
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
