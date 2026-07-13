// Package mobile is the gomobile-bound facade over the belphegor core. It
// exposes a headless clipboard-sync node that a host application (Android via
// `gomobile bind`, or iOS) drives across the language boundary.
//
// The exported surface is intentionally limited to gomobile-compatible types
// (string, int, bool, []byte, error, exported structs and interfaces):
//
//	cfg := mobile.NewConfig()
//	cfg.Secret = "shared-key"      // must match the desktop --secret
//	cfg.FileSavePath = ctx.getCacheDir().getAbsolutePath()
//	cfg.DeviceName = "Pixel 8"
//	node, err := mobile.Start(cfg, handler)   // handler implements Handler
//	...
//	node.PushClipboard("text/plain", bytes)   // phone copied something
//	node.Stop()
//
// Everything below the facade — QUIC/TLS transport, the protobuf wire codec,
// discovery and the peer state machine — is the same code the desktop binary
// runs, so an Android build is wire-compatible with desktop peers by
// construction.
package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync/atomic"
	"time"

	"github.com/labi-le/belphegor/internal/channel"
	"github.com/labi-le/belphegor/internal/node"
	"github.com/labi-le/belphegor/internal/security"
	"github.com/labi-le/belphegor/internal/store"
	"github.com/labi-le/belphegor/internal/transport"
	"github.com/labi-le/belphegor/internal/transport/quic"
	"github.com/labi-le/belphegor/internal/transport/tcp"
	"github.com/labi-le/belphegor/internal/types/domain"
	"github.com/labi-le/belphegor/pkg/clipboard/android"
	"github.com/labi-le/belphegor/pkg/clipboard/eventful"
	"github.com/labi-le/belphegor/pkg/id"
	"github.com/rs/zerolog"
)

// Handler receives clipboard payloads arriving FROM the mesh so the host can
// place them on the device clipboard. Under gomobile this becomes a Java/Kotlin
// interface the app implements. mimeType is a short label ("text", "image",
// "path").
type Handler interface {
	HandleClipboard(mimeType string, data []byte)
}

// LogSink receives formatted log lines so the host can display them (e.g. an
// in-app log panel). Implemented on the host side; may be nil.
type LogSink interface {
	Log(line string)
}

type sinkWriter struct{ sink LogSink }

func (w sinkWriter) Write(p []byte) (int, error) {
	if w.sink != nil {
		w.sink.Log(string(p))
	}
	return len(p), nil
}

// Config holds the node knobs. Fields are set from the host before Start.
// All fields use gomobile-safe types.
type Config struct {
	// Secret is the shared key that gates the mesh; it MUST match the desktop
	// `--secret`. Empty means an open network (anyone on the LAN may connect).
	Secret string
	// Port to listen on; <= 0 picks a random high port (7000-7999).
	Port int
	// DeviceName is the human-friendly node name shown to peers.
	DeviceName string
	// FileSavePath is a writable directory for received files (e.g. the app
	// cache dir). It is created if missing.
	FileSavePath string
	// Transport is "quic" (default) or "tcp".
	Transport string
	// Discover enables LAN auto-discovery (requires a held MulticastLock on the
	// host to actually receive discovery packets over Wi-Fi).
	Discover bool
	// MaxPeers caps discovered/connected peers; <= 0 uses the default.
	MaxPeers int
	// Verbose raises the log level to trace.
	Verbose bool
	// NodeID is a stable 1..1023 mesh slot the host supplies because Android's
	// SELinux denies the core's net.Interfaces() MAC lookup (it would fall back
	// to a constant 1, colliding every phone). <= 0 keeps the core's own id.
	NodeID int
	// AllowCopyFiles lets the node receive and announce file clipboard items
	// (not just text/images). Default true.
	AllowCopyFiles bool
	// MaxFileSizeBytes caps a single received payload in bytes; <= 0 uses the
	// mobile default (16 MiB). Hard-capped at 256 MiB regardless of the host.
	MaxFileSizeBytes int64
	// MaxClipboardFiles caps how many files one copy may announce; <= 0 uses
	// the core default (15).
	MaxClipboardFiles int
	// DiscoverDelaySec is the LAN discovery scan interval in seconds; <= 0 uses
	// the default (30). Duration isn't gomobile-safe, hence seconds.
	DiscoverDelaySec int
	// KeepAliveSec is the peer keep-alive interval in seconds; <= 0 uses the
	// default (60).
	KeepAliveSec int
}

// NewConfig returns a Config with the same defaults the desktop uses.
func NewConfig() *Config {
	def := node.DefaultOptions()
	return &Config{
		Port:              def.ListenPort,
		Transport:         def.Transport.String(),
		Discover:          def.Discovering.Enable,
		MaxPeers:          def.MaxPeers,
		AllowCopyFiles:    def.Clip.AllowCopyFiles,
		MaxFileSizeBytes:  16 << 20,
		MaxClipboardFiles: def.Clip.MaxClipboardFiles,
		DiscoverDelaySec:  int(def.Discovering.Delay / time.Second),
		KeepAliveSec:      int(def.KeepAlive / time.Second),
	}
}

// Node is a running belphegor peer.
type Node struct {
	nd     *node.Node
	clip   *android.Clipboard
	ctx    context.Context
	cancel context.CancelFunc
	cfg    *Config
	// alive is false once the node's run goroutine exits (clean stop or crash),
	// so the host can detect a dead node instead of a stale "running" state.
	alive atomic.Bool
	// discovered holds LAN peers heard via multicast discovery, offered to the
	// host as one-tap "add peer" candidates (see DiscoveredJSON).
	discovered *discoveredStore
}

// writerBridge adapts the host Handler to the android.Writer contract.
type writerBridge struct {
	h Handler
}

func (w writerBridge) Write(mimeType string, data []byte) error {
	if w.h != nil {
		w.h.HandleClipboard(mimeType, data)
	}
	return nil
}

// Start builds and launches a node. It returns immediately; the node runs in
// background goroutines until Stop is called. handler may be nil (send-only).
func Start(cfg *Config, handler Handler, logs LogSink) (*Node, error) {
	if cfg == nil {
		cfg = NewConfig()
	}

	// The core seeds its node id (and the snowflake event-id generator) from the
	// first NIC's MAC via net.Interfaces(); SELinux denies that to an
	// untrusted_app, so it falls back to a constant 1 and every phone collides.
	// Override it from the host before any id is minted.
	if cfg.NodeID > 0 {
		id.MyID = int64(cfg.NodeID)
	}

	// quic-go's ECN probing trips "sendmsg: invalid argument" on Android; the
	// upstream-recommended workaround is to disable it before any QUIC socket
	// is opened. See https://github.com/quic-go/quic-go/issues/4178.
	_ = os.Setenv("QUIC_GO_DISABLE_ECN", "true")

	logger := newLogger(cfg.Verbose, logs)

	fileStore, err := store.NewFileStore(cfg.FileSavePath, logger)
	if err != nil {
		return nil, fmt.Errorf("mobile.Start: file store: %w", err)
	}

	opts := node.DefaultOptions()
	opts.Secret = cfg.Secret
	if cfg.Port > 0 {
		opts.ListenPort = cfg.Port
	}
	if cfg.MaxPeers > 0 {
		opts.MaxPeers = cfg.MaxPeers
		opts.Discovering.MaxPeers = cfg.MaxPeers
	}
	opts.Discovering.Enable = cfg.Discover
	opts.Transport = transportMode(cfg.Transport)
	opts.Logger = logger
	opts.Notifier = nopNotifier{}
	opts.Store = fileStore
	opts.FileSavePath = cfg.FileSavePath
	meta := domain.SelfMetaData()
	meta.ID = domain.NodeID(id.MyID) // SelfMetaData cached the stale init-time id
	if cfg.DeviceName != "" {
		meta.Name = cfg.DeviceName
	}
	opts.Metadata = meta
	opts.Clip.AllowCopyFiles = cfg.AllowCopyFiles
	if cfg.MaxClipboardFiles > 0 {
		opts.Clip.MaxClipboardFiles = cfg.MaxClipboardFiles
	}
	if cfg.DiscoverDelaySec > 0 {
		opts.Discovering.Delay = time.Duration(cfg.DiscoverDelaySec) * time.Second
	}
	if cfg.KeepAliveSec > 0 {
		opts.KeepAlive = time.Duration(cfg.KeepAliveSec) * time.Second
	}
	// Clamp the max payload on a phone. The desktop default (512 MiB) lets a
	// single peer OOM the process; keep a hard ceiling even if the host asks
	// for more (in open-mesh mode the sender is unauthenticated).
	const mobileMaxFileSize = 256 << 20 // 256 MiB hard ceiling
	wantSize := cfg.MaxFileSizeBytes
	if wantSize <= 0 {
		wantSize = 16 << 20 // mobile default
	}
	if wantSize > mobileMaxFileSize {
		wantSize = mobileMaxFileSize
	}
	opts.Clip.MaxFileSize = eventful.MaxFileSize(wantSize)
	opts = opts.Validated()

	tlsConfig, err := security.MakeTLSConfig(opts.Secret, logger)
	if err != nil {
		return nil, fmt.Errorf("mobile.Start: tls: %w", err)
	}

	clip := android.New(logger, opts.Clip, writerBridge{h: handler})

	var tr transport.Transport
	if opts.Transport == node.TransportTCP {
		tr = tcp.New(tlsConfig, opts.KeepAlive)
	} else {
		tr = quic.New(tlsConfig, opts.KeepAlive)
	}

	nd := node.New(tr, clip, new(node.Storage), channel.New(opts.MaxPeers), opts)

	ctx, cancel := context.WithCancel(context.Background())
	n := &Node{nd: nd, clip: clip, ctx: ctx, cancel: cancel, cfg: cfg}
	n.alive.Store(true)
	n.discovered = newDiscoveredStore()

	if opts.Discovering.Enable {
		// Netlink-free LAN discovery (see discover.go): joins the multicast group
		// on INADDR_ANY under the Wi-Fi MulticastLock the service holds, so it
		// works inside the Android app sandbox with no root / sepolicy.
		disc := newMulticastDiscoverer(opts.Discovering.Delay, logger)
		rec := &discoveryRecorder{inner: nd, store: n.discovered, selfID: n.nd.Metadata().ID.Int64(), logger: logger}
		go func() {
			defer recoverGoroutine(logger, "discovery")
			disc.Discover(ctx, rec)
		}()
	}

	go func() {
		// The node lives here for its whole life. Mark it dead when Start returns
		// (clean stop or crash); recoverGoroutine keeps a panic (e.g. from the
		// accept loop) from taking the whole process down.
		defer func() { n.alive.Store(false) }()
		defer recoverGoroutine(logger, "node")
		if startErr := nd.Start(ctx); startErr != nil {
			logger.Error().Err(startErr).Msg("node stopped")
		}
	}()

	return n, nil
}

// PushClipboard feeds a locally-originated clipboard change into the mesh.
// mimeHint is the platform content-type (e.g. Android ClipDescription:
// "text/plain", "image/png"), or "" to classify by content.
func (n *Node) PushClipboard(mimeHint string, data []byte) {
	n.clip.PushLocalCopy(mimeHint, data)
}

// StatusJSON returns a JSON snapshot of the node for the host UI: this device,
// the bound listen address, transport/discovery knobs, and the connected
// peers. Shape:
//
//	{"self":{"id":..,"name":..,"arch":..},"listen":"[::]:7423",
//	 "transport":"quic","discover":false,
//	 "peers":[{"id":..,"name":..,"arch":..,"addr":"ip:port"}]}
func (n *Node) StatusJSON() string {
	type dev struct {
		ID   int64  `json:"id"`
		Name string `json:"name"`
		Arch string `json:"arch"`
		Addr string `json:"addr,omitempty"`
	}
	self := n.nd.Metadata()
	peers := n.nd.Peers()
	ps := make([]dev, 0, len(peers))
	for _, p := range peers {
		ps = append(ps, dev{ID: p.ID, Name: p.Name, Arch: p.Arch, Addr: p.Addr})
	}
	listen := "auto"
	if n.cfg.Port > 0 {
		listen = fmt.Sprintf(":%d", n.cfg.Port)
	}
	snap := struct {
		Self      dev    `json:"self"`
		Listen    string `json:"listen"`
		Transport string `json:"transport"`
		Discover  bool   `json:"discover"`
		Peers     []dev  `json:"peers"`
	}{
		Self:      dev{ID: self.ID.Int64(), Name: self.Name, Arch: self.Arch},
		Listen:    listen,
		Transport: n.cfg.Transport,
		Discover:  n.cfg.Discover,
		Peers:     ps,
	}
	b, err := json.Marshal(snap)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// Connect dials a known peer at "ip:port" (e.g. a desktop node not found via
// discovery).
func (n *Node) Connect(addr string) error {
	if err := n.nd.ConnectTo(n.ctx, addr); err != nil {
		return fmt.Errorf("mobile.Connect: %w", err)
	}
	return nil
}

// Stop tears the node down and releases its resources.
func (n *Node) Stop() error {
	n.cancel()
	if err := n.nd.Close(); err != nil {
		return fmt.Errorf("mobile.Stop: %w", err)
	}
	return nil
}

// Running reports whether the node's run goroutine is still alive. It flips to
// false when the core node stops or panics, so the host can react (e.g. tear
// the service down) instead of showing a stale "running" state.
func (n *Node) Running() bool { return n.alive.Load() }

// DiscoveredJSON returns LAN peers heard via discovery as a JSON array
// [{"name":..,"addr":"ip:port"}], newest first, for the host's add-peer UI.
// Empty array when discovery is off or nothing has been heard yet.
func (n *Node) DiscoveredJSON() string {
	if n.discovered == nil {
		return "[]"
	}
	return n.discovered.jsonList()
}

// recoverGoroutine keeps a panic in a background goroutine from taking down the
// whole app. Defer it DIRECTLY (defer recoverGoroutine(...)) so its recover()
// runs in the deferred frame.
func recoverGoroutine(logger zerolog.Logger, where string) {
	if r := recover(); r != nil {
		logger.Error().Interface("panic", r).Str("goroutine", where).Msg("recovered from panic")
	}
}

func transportMode(s string) node.Transport {
	if node.Transport(s) == node.TransportTCP {
		return node.TransportTCP
	}
	return node.TransportQUIC
}

func newLogger(verbose bool, sink LogSink) zerolog.Logger {
	lvl := zerolog.InfoLevel
	if verbose {
		lvl = zerolog.TraceLevel
	}
	// Go stdout/stderr is routed to logcat under gomobile; also mirror to the
	// host LogSink (in-app log panel) when provided.
	var out io.Writer = os.Stderr
	if sink != nil {
		out = io.MultiWriter(os.Stderr, sinkWriter{sink})
	}
	cw := zerolog.ConsoleWriter{Out: out, NoColor: true, TimeFormat: "15:04:05"}
	return zerolog.New(cw).Level(lvl).With().Timestamp().Logger()
}

// nopNotifier satisfies notification.Notifier without a desktop notification
// backend. Host-side notifications can be surfaced through Handler later.
type nopNotifier struct{}

func (nopNotifier) Notify(string, ...any) {}
