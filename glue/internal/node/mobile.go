package node

// This file is overlaid into a checkout of the belphegor core by the mobile
// build (scripts/setup-core.sh). It lives in package node so it can read the
// unexported peer storage, exposing a snapshot for the gomobile facade WITHOUT
// modifying any upstream core file.

import (
	"github.com/labi-le/belphegor/internal/peer"
	"github.com/labi-le/belphegor/internal/types/domain"
)

// PeerInfo is a snapshot of one connected peer, safe to hand to a host UI
// (e.g. across the gomobile boundary).
type PeerInfo struct {
	ID   int64
	Name string
	Arch string
	Addr string
}

// Peers returns a snapshot of the currently connected peers.
func (n *Node) Peers() []PeerInfo {
	out := make([]PeerInfo, 0, n.peers.Len())
	n.peers.Tap(func(id domain.NodeID, p *peer.Peer) bool {
		md := p.MetaData()
		out = append(out, PeerInfo{
			ID:   id.Int64(),
			Name: md.Name,
			Arch: md.Arch,
			Addr: p.Conn().RemoteAddr().String(),
		})
		return true
	})
	return out
}
