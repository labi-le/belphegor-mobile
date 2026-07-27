package belphegor.app

import belphegor.mobile.Node
import org.json.JSONObject

/**
 * Process-wide handle to the running belphegor node. [BelphegorService] publishes
 * it on start and clears it on stop; [MainActivity] reads it to render live status
 * (connected peers, listen address, transport) without binding to the service.
 */
object NodeState {
    @Volatile
    var node: Node? = null

    /** Why the node is intentionally down while the service stays alive, or null
     *  when it should be running — the dashboard shows "Paused", not "Stopped",
     *  and keeps the sync switch on. */
    @Volatile
    var pause: Pause? = null

    /** SCREEN: screen off under [Prefs.pauseOnScreenOff].
     *  NETWORK: Wi-Fi-only is on and the device is on mobile data. */
    enum class Pause { SCREEN, NETWORK }

    val running: Boolean get() = node != null || pause != null

    /** JSON status snapshot from the core, or null when no node is running. */
    fun statusJson(): String? = node?.let { runCatching { it.statusJSON() }.getOrNull() }

    /** Peers currently connected; 0 when the node is down or unreachable. */
    fun peerCount(): Int {
        val json = statusJson() ?: return 0
        return runCatching { JSONObject(json).optJSONArray("peers")?.length() ?: 0 }.getOrElse { 0 }
    }
}
