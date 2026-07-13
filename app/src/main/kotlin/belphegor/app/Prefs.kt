package belphegor.app

import android.content.Context

/** All node knobs, persisted in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("belphegor", Context.MODE_PRIVATE)

    var secret: String
        get() = sp.getString(KEY_SECRET, "") ?: ""
        set(v) = sp.edit().putString(KEY_SECRET, v).apply()

    var deviceName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    /** 0 => random high port chosen by the core. */
    var port: Int
        get() = sp.getInt(KEY_PORT, 0)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    /** 0 => core default. */
    var maxPeers: Int
        get() = sp.getInt(KEY_MAX_PEERS, 0)
        set(v) = sp.edit().putInt(KEY_MAX_PEERS, v).apply()

    /** "quic" (default) or "tcp". */
    var transport: String
        get() = sp.getString(KEY_TRANSPORT, "quic") ?: "quic"
        set(v) = sp.edit().putString(KEY_TRANSPORT, v).apply()

    var discover: Boolean
        get() = sp.getBoolean(KEY_DISCOVER, false) // netlink-free multicast (glue/mobile/discover.go); off by default
        set(v) = sp.edit().putBoolean(KEY_DISCOVER, v).apply()

    var verbose: Boolean
        get() = sp.getBoolean(KEY_VERBOSE, false)
        set(v) = sp.edit().putBoolean(KEY_VERBOSE, v).apply()

    /** Start the node automatically on app launch and on device boot. */
    var autostart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    /** Check GitHub releases for a newer version when the app opens. */
    var checkUpdates: Boolean
        get() = sp.getBoolean(KEY_CHECK_UPDATES, true)
        set(v) = sp.edit().putBoolean(KEY_CHECK_UPDATES, v).apply()

    /** Receive/announce file clipboard items, not just text/images. */
    var allowFiles: Boolean
        get() = sp.getBoolean(KEY_ALLOW_FILES, true)
        set(v) = sp.edit().putBoolean(KEY_ALLOW_FILES, v).apply()

    /** Max received payload size in MiB (the core hard-caps at 256). */
    var maxFileSizeMiB: Int
        get() = sp.getInt(KEY_MAX_FILE_SIZE, 16)
        set(v) = sp.edit().putInt(KEY_MAX_FILE_SIZE, v).apply()

    /** Max files announced in a single copy. */
    var maxClipboardFiles: Int
        get() = sp.getInt(KEY_MAX_FILES, 15)
        set(v) = sp.edit().putInt(KEY_MAX_FILES, v).apply()

    /** LAN discovery scan interval, seconds. */
    var discoverDelay: Int
        get() = sp.getInt(KEY_DISCOVER_DELAY, 30)
        set(v) = sp.edit().putInt(KEY_DISCOVER_DELAY, v).apply()

    /** Peer keep-alive interval, seconds. */
    var keepAlive: Int
        get() = sp.getInt(KEY_KEEP_ALIVE, 60)
        set(v) = sp.edit().putInt(KEY_KEEP_ALIVE, v).apply()

    /** Share what you copy on this device to the mesh. */
    var sendEnabled: Boolean
        get() = sp.getBoolean(KEY_SEND, true)
        set(v) = sp.edit().putBoolean(KEY_SEND, v).apply()

    /** Apply clipboard received from peers to this device. */
    var receiveEnabled: Boolean
        get() = sp.getBoolean(KEY_RECEIVE, true)
        set(v) = sp.edit().putBoolean(KEY_RECEIVE, v).apply()

    /** Pause sync while on a metered (mobile-data) network. */
    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    /** Keep LAN discovery (multicast lock) alive with the screen off. */
    var bgDiscovery: Boolean
        get() = sp.getBoolean(KEY_BG_DISCOVERY, false)
        set(v) = sp.edit().putBoolean(KEY_BG_DISCOVERY, v).apply()

    /** UI theme: "system" (default), "light", or "dark". */
    var theme: String
        get() = sp.getString(KEY_THEME, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    /** Newline-separated "ip:port" peers to dial on start / on demand. */
    var peers: String
        get() = sp.getString(KEY_PEERS, "") ?: ""
        set(v) = sp.edit().putString(KEY_PEERS, v).apply()

    /** Parsed, non-blank, trimmed peer list. */
    fun peerList(): List<String> =
        peers.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Stable per-install mesh node id (a 0..1023 slot). The core normally
     * derives this from the NIC MAC via net.Interfaces(), but Android's SELinux
     * denies that netlink call to an untrusted_app, so it falls back to a
     * hardcoded 1 and every phone collides. We generate a random slot once and
     * feed it to the core via BELPHEGOR_NODE_ID (see App). Avoids 0/1.
     */
    val nodeId: Int
        get() {
            var v = sp.getInt(KEY_NODE_ID, 0)
            if (v == 0) {
                v = 2 + java.security.SecureRandom().nextInt(1022) // 2..1023
                sp.edit().putInt(KEY_NODE_ID, v).apply()
            }
            return v
        }

    private companion object {
        const val KEY_SECRET = "secret"
        const val KEY_NAME = "device_name"
        const val KEY_PORT = "port"
        const val KEY_MAX_PEERS = "max_peers"
        const val KEY_TRANSPORT = "transport"
        const val KEY_DISCOVER = "discover"
        const val KEY_VERBOSE = "verbose"
        const val KEY_PEERS = "peers"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_CHECK_UPDATES = "check_updates"
        const val KEY_NODE_ID = "node_id"
        const val KEY_ALLOW_FILES = "allow_files"
        const val KEY_MAX_FILE_SIZE = "max_file_size_mib"
        const val KEY_MAX_FILES = "max_clipboard_files"
        const val KEY_DISCOVER_DELAY = "discover_delay"
        const val KEY_KEEP_ALIVE = "keep_alive"
        const val KEY_SEND = "send_enabled"
        const val KEY_RECEIVE = "receive_enabled"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_BG_DISCOVERY = "bg_discovery"
        const val KEY_THEME = "theme"
    }
}
