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
    }
}
