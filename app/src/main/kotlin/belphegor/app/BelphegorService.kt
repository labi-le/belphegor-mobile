package belphegor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import belphegor.mobile.Config
import belphegor.mobile.LogSink
import belphegor.mobile.Mobile
import belphegor.mobile.Node
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Foreground service owning the belphegor QUIC node for the process lifetime.
 * The node runs in Go goroutines inside the AAR; this service keeps the process
 * alive, holds the Wi-Fi multicast lock, bridges the clipboard, and dials the
 * peers configured in settings.
 */
class BelphegorService : Service() {

    private lateinit var bridge: ClipboardBridge
    @Volatile private var node: Node? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val dialer: ExecutorService = Executors.newSingleThreadExecutor()
    private var watchdog: ScheduledExecutorService? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(android.content.ClipboardManager::class.java)!!
        bridge = ClipboardBridge(applicationContext, cm)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONNECT) {
            val addr = intent.getStringExtra(EXTRA_ADDR)
            if (addr != null) connectPeer(addr) else connectPeers()
            return START_STICKY
        }
        startAsForeground()
        if (Prefs(this).discover) enableDiscoveryLock()
        registerNetworkWatch()
        evaluateRun()
        return START_STICKY
    }

    private fun startNode() {
        if (node != null) return
        val prefs = Prefs(this)
        val cfg: Config = Mobile.newConfig().apply {
            secret = prefs.secret
            port = prefs.port.toLong() // Go int is a Java long across gomobile
            maxPeers = prefs.maxPeers.toLong()
            discover = prefs.discover
            transport = prefs.transport
            verbose = prefs.verbose
            deviceName = prefs.deviceName.ifBlank { Build.MODEL ?: "Android" }
            fileSavePath = cacheDir.absolutePath
            nodeID = prefs.nodeId.toLong()
            allowCopyFiles = prefs.allowFiles
            maxFileSizeBytes = prefs.maxFileSizeMiB.toLong() * 1024L * 1024L
            maxClipboardFiles = prefs.maxClipboardFiles.toLong()
            discoverDelaySec = prefs.discoverDelay.toLong()
            keepAliveSec = prefs.keepAlive.toLong()
        }
        try {
            val n = Mobile.start(cfg, bridge.handler, LogSink { line -> LogStore.add(line) })
            node = n
            bridge.node = n
            NodeState.node = n
            bridge.register()
            Log.i(TAG, "node started (transport=${prefs.transport}, discover=${prefs.discover})")
            LogStore.add("[app] node started, transport=${prefs.transport}")
            startWatchdog()
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start node", t)
            LogStore.add("[app] failed to start node: ${t.message}")
            stopSelf()
        }
    }

    private fun connectPeers() {
        // Re-dial saved peers only when no peer is live: the core does not
        // re-dial dropped outgoing connections itself, and a duplicate dial
        // makes it close the healthy one as "stale". Safe to call repeatedly
        // (watchdog, network change, app resume) to recover a dropped link.
        if (node == null || connectedPeerCount() > 0) return
        for (addr in Prefs(this).peerList()) connectPeer(addr)
    }

    private fun connectedPeerCount(): Int {
        val n = node ?: return 0
        val json = runCatching { n.statusJSON() }.getOrNull() ?: return 0
        return runCatching { JSONObject(json).optJSONArray("peers")?.length() ?: 0 }.getOrElse { 0 }
    }

    private fun connectPeer(addr: String) {
        val n = node ?: return
        dialer.execute {
            runCatching { n.connect(addr) }
                .onSuccess { Log.i(TAG, "connect $addr ok"); LogStore.add("[app] connect $addr ok") }
                .onFailure { Log.w(TAG, "connect $addr failed", it); LogStore.add("[app] connect $addr failed: ${it.message}") }
        }
    }

    /**
     * LAN discovery must RECEIVE multicast, which needs a Wi-Fi MulticastLock.
     * A held lock disables the chip's multicast filtering, so the CPU is woken
     * for every multicast/broadcast frame on the network (mDNS, SSDP, ARP,
     * other apps' discovery) for as long as it is held -- the dominant Wi-Fi
     * battery cost of this service, and it is paid even with the screen off.
     * Discovery only matters while the user is actually looking at the app to
     * add a peer, so gate the lock on screen state: hold it while the screen is
     * on, drop it when the screen goes off. Unicast QUIC/TCP sync to already-
     * known peers does NOT need the lock, so background sync keeps working; only
     * new-peer LAN discovery pauses while the screen is off.
     */
    private fun enableDiscoveryLock() {
        if (multicastLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("belphegor").apply { setReferenceCounted(false) }
        }
        if (Prefs(this).bgDiscovery) {
            // Hold the lock for the whole service lifetime: LAN discovery keeps
            // working with the screen off (extra battery, opt-in).
            acquireMulticastLock()
            return
        }
        if (getSystemService(PowerManager::class.java)?.isInteractive != false) acquireMulticastLock()
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> acquireMulticastLock()
                        Intent.ACTION_SCREEN_OFF -> releaseMulticastLock()
                    }
                }
            }
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
            )
        }
    }

    private fun acquireMulticastLock() {
        multicastLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification(getString(R.string.notif_text))
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setColor(getColor(R.color.accent))
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Whether the current default network is allowed under the Wi-Fi-only pref. */
    private fun onAllowedNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun registerNetworkWatch() {
        if (connectivityCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { evaluateRun() } }
            override fun onLost(network: Network) { main.post { evaluateRun() } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { evaluateRun() } }
        }
        connectivityCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    /**
     * Runs or pauses the node per the Wi-Fi-only policy. Called on start and on
     * every default-network change; when paused the FGS stays alive so sync
     * resumes automatically once an allowed network returns.
     */
    private fun evaluateRun() {
        val allowed = !Prefs(this).wifiOnly || onAllowedNetwork()
        if (allowed) {
            NodeState.pausedForNetwork = false
            if (node == null) startNode()
            connectPeers()
            updateNotification(getString(R.string.notif_text))
        } else {
            if (node != null) {
                runCatching { node?.stop() }
                node = null
                bridge.node = null
                NodeState.node = null
            }
            NodeState.pausedForNetwork = true
            updateNotification(getString(R.string.notif_waiting_wifi))
            LogStore.add("[app] paused: Wi-Fi only, waiting for Wi-Fi")
        }
    }

    /**
     * Watches the Go node's liveness. If it dies on its own (crash, transport
     * failure), tear the service down so the FGS notification and process state
     * reflect reality instead of a stale "running".
     */
    private fun startWatchdog() {
        watchdog?.shutdownNow()
        watchdog = Executors.newSingleThreadScheduledExecutor().also { wd ->
            wd.scheduleWithFixedDelay({
                val n = node ?: return@scheduleWithFixedDelay
                if (!n.running()) {
                    Log.w(TAG, "node stopped unexpectedly; shutting service down")
                    LogStore.add("[app] node stopped unexpectedly")
                    wd.shutdown()
                    stopSelf()
                    return@scheduleWithFixedDelay
                }
                // Recover a dropped link: the connection can die on doze / network
                // blips (and OEM background freezing), and the core does not
                // re-dial outgoing peers. connectPeers() no-ops while a peer is live.
                connectPeers()
            }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun onDestroy() {
        runCatching { bridge.unregister() }
        runCatching { node?.stop() }
        node = null
        NodeState.node = null
        NodeState.pausedForNetwork = false
        connectivityCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        connectivityCallback = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        releaseMulticastLock()
        multicastLock = null
        dialer.shutdownNow()
        watchdog?.shutdownNow()
        watchdog = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT = "belphegor.app.action.CONNECT"
        const val EXTRA_ADDR = "belphegor.app.extra.ADDR"
        private const val TAG = "BelphegorService"
        private const val CHANNEL = "clipboard-sync"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_MS = 15_000L
    }
}
