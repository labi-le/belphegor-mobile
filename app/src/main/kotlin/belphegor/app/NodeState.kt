package belphegor.app

import belphegor.mobile.Node

/**
 * Process-wide handle to the running belphegor node. [BelphegorService] publishes
 * it on start and clears it on stop; [MainActivity] reads it to render live status
 * (connected peers, listen address, transport) without binding to the service.
 */
object NodeState {
    @Volatile
    var node: Node? = null

    val running: Boolean get() = node != null

    /** JSON status snapshot from the core, or null when no node is running. */
    fun statusJson(): String? = node?.let { runCatching { it.statusJSON() }.getOrNull() }
}
