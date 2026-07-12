package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/labi-le/belphegor/internal/discovering"
	"github.com/labi-le/belphegor/internal/protocol"
	"github.com/labi-le/belphegor/internal/types/domain"
	"github.com/rs/zerolog"
)

// discoveredTTL bounds how long a silent peer stays in the "found on the LAN"
// list the host offers as add-peer candidates.
const discoveredTTL = 2 * time.Minute

// discovered is one LAN node heard via multicast discovery: its friendly name
// and the DIALABLE "ip:port" (the peer's advertised listen port, not the
// ephemeral connection source), so the host can save and redial it.
type discovered struct {
	Name string    `json:"name"`
	Addr string    `json:"addr"`
	seen time.Time
}

// discoveredStore keeps recent discovery results for the host UI. It is written
// from the discovery goroutine and read from the UI thread, so it is guarded by
// a mutex and self-expires stale entries.
type discoveredStore struct {
	mu    sync.Mutex
	peers map[string]discovered // key: addr
}

func newDiscoveredStore() *discoveredStore {
	return &discoveredStore{peers: make(map[string]discovered)}
}

func (s *discoveredStore) put(name, addr string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peers[addr] = discovered{Name: name, Addr: addr, seen: time.Now()}
}

// jsonList returns fresh results as a JSON array, newest first, dropping stale
// entries. Shape: [{"name":..,"addr":"ip:port"}].
func (s *discoveredStore) jsonList() string {
	s.mu.Lock()
	now := time.Now()
	out := make([]discovered, 0, len(s.peers))
	for k, p := range s.peers {
		if now.Sub(p.seen) > discoveredTTL {
			delete(s.peers, k)
			continue
		}
		out = append(out, p)
	}
	s.mu.Unlock()

	sort.Slice(out, func(i, j int) bool { return out[i].seen.After(out[j].seen) })
	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// discoveryRecorder wraps the node's discovery Connector so every hit is also
// recorded for the host UI, then delegated to the real node (which dials it).
// It decodes the greet exactly as node.PeerDiscovered does to recover the
// peer's advertised listen port and friendly name.
type discoveryRecorder struct {
	inner  discovering.Connector
	store  *discoveredStore
	selfID int64
	logger zerolog.Logger
}

func (r *discoveryRecorder) DiscoveryPayload() []byte { return r.inner.DiscoveryPayload() }

func (r *discoveryRecorder) PeerDiscovered(ctx context.Context, peerIP net.IP, payload []byte) {
	if greet, err := protocol.DecodeExpect[domain.EventHandshake](bytes.NewReader(payload)); err == nil {
		md := greet.Payload.MetaData
		if md.ID.Int64() != r.selfID && greet.Payload.Port != 0 {
			addr := net.JoinHostPort(peerIP.String(), strconv.Itoa(int(greet.Payload.Port)))
			r.store.put(md.Name, addr)
		}
	}
	r.inner.PeerDiscovered(ctx, peerIP, payload)
}
