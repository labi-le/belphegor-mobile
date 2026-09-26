package belphegor.app

import android.app.KeyguardManager
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
    @Volatile private var unlockRecheck: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(android.content.ClipboardManager::class.java)!!
        bridge = ClipboardBridge(applicationContext, cm)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val addr = intent.getStringExtra(EXTRA_ADDR)
                if (addr != null) connectPeer(addr) else connectPeers()
                return START_STICKY
            }
            // A live service re-posts on the other channel; a dead one is never
            // promoted to foreground.
            ACTION_NOTIFICATION -> {
                refreshNotification()
                return START_STICKY
            }
        }
        startAsForeground()
        registerScreenWatch()
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
            if (prefs.discover) acquireMulticastLock()
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
        if (node == null || NodeState.peerCount() > 0) return
        for (addr in Prefs(this).peerList()) connectPeer(addr)
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
     * ACTION_SCREEN_ON/OFF/USER_PRESENT reach runtime-registered receivers only
     * -- a manifest component never sees them -- so the service itself has to
     * stay up to notice the wake-up and the unlock, even while its node is
     * paused. A parked service holds no node, and therefore no sockets, timers
     * or locks.
     */
    private fun registerScreenWatch() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                evaluateRun()
                if (intent?.action == Intent.ACTION_USER_PRESENT) scheduleUnlockRecheck()
            }
        }
        screenReceiver = receiver
        // RECEIVER_NOT_EXPORTED looks safer but silently drops USER_PRESENT on
        // API 33 (verified on device), and all three actions are protected
        // system broadcasts that no other app can send anyway.
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                // The unlock signal, and the only one a normal app can get:
                // KeyguardManager's own listener needs a permission the
                // platform keeps for itself.
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    /** The unlock broadcast can land before the keyguard state settles, and a
     *  park taken from that stale sample would never lift: nothing else
     *  re-evaluates until the next screen or network event. */
    private fun scheduleUnlockRecheck() {
        unlockRecheck?.let(main::removeCallbacks)
        unlockRecheck = Runnable { evaluateRun() }.also { main.postDelayed(it, UNLOCK_RECHECK_MS) }
    }

    /**
     * LAN discovery must RECEIVE multicast, which needs a Wi-Fi MulticastLock.
     * A held lock disables the chip's multicast filtering, so the CPU is woken
     * for every multicast/broadcast frame on the network (mDNS, SSDP, ARP,
     * other apps' discovery) for as long as it is held -- the dominant Wi-Fi
     * battery cost of this service. It is therefore tied to the node's own
     * lifetime: no node (screen off, or waiting for Wi-Fi) means no lock.
     * Unicast QUIC/TCP sync to known peers never needs it.
     */
    private fun acquireMulticastLock() {
        val lock = multicastLock ?: run {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("belphegor")
                .apply { setReferenceCounted(false) }
                .also { multicastLock = it }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * Created on demand, never both up front: a channel's importance is fixed at
     * creation, and an unused blocked channel is one tap away from being locked
     * to visible, after which the app can never lower it again.
     */
    private fun channelFor(hidden: Boolean): String {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = if (hidden) {
            NotificationChannel(CHANNEL_HIDDEN, getString(R.string.channel_name_hidden), NotificationManager.IMPORTANCE_NONE)
        } else {
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        }
        nm.createNotificationChannel(channel)
        return channel.id
    }

    private fun startAsForeground() {
        val notification = buildNotification(statusText())
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun refreshNotification() {
        runCatching {
            // The re-post to the blocked channel is dropped and an app cannot
            // cancel its own FGS notification, so an already visible one has to
            // be removed first. The poke only arrives while the activity is
            // visible, so the API 31+ background-FGS-start limit does not apply.
            if (Prefs(this).hideNotification) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            startAsForeground()
        }.onFailure {
            Log.w(TAG, "failed to refresh notification", it)
            LogStore.add("[app] failed to refresh notification: ${it.message}")
        }
    }

    /** Notification text for the current run/pause state. */
    private fun statusText(): String = getString(
        when (NodeState.pause) {
            NodeState.Pause.SCREEN -> R.string.notif_paused_screen
            NodeState.Pause.NETWORK -> R.string.notif_waiting_wifi
            NodeState.Pause.UNLOCK -> R.string.notif_waiting_unlock
            null -> R.string.notif_text
        },
    )

    // Hidden posts to a channel the app blocks itself: the system drops the
    // notification while the service keeps its foreground state (see DESIGN.md).
    private fun buildNotification(text: String): android.app.Notification {
        val hidden = Prefs(this).hideNotification
        return NotificationCompat.Builder(this, channelFor(hidden))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setColor(getColor(R.color.accent))
            .setOngoing(true)
            .setVisibility(if (hidden) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

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

    /** A device with no lock screen never parks here. A swipe-only keyguard
     *  counts as locked, which is exactly the glance-at-the-clock case. */
    private fun isUnlocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.let { !it.isKeyguardLocked } ?: true

    /**
     * Runs or pauses the node per the screen, Wi-Fi-only and unlock policies.
     * Called on start, on screen on/off, on user-present and on every
     * default-network change; a paused node keeps the FGS alive so sync
     * resumes by itself once the reason clears.
     */
    private fun evaluateRun() {
        val prefs = Prefs(this)
        val asleep = prefs.pauseOnScreenOff &&
            getSystemService(PowerManager::class.java)?.isInteractive == false
        when {
            asleep -> pauseNode(NodeState.Pause.SCREEN, "screen off")
            prefs.pauseOnScreenOff && prefs.resumeOnUnlock && !isUnlocked() ->
                pauseNode(NodeState.Pause.UNLOCK, "waiting for unlock")
            prefs.wifiOnly && !onAllowedNetwork() ->
                pauseNode(NodeState.Pause.NETWORK, "Wi-Fi only, waiting for Wi-Fi")
            else -> {
                NodeState.pause = null
                if (node == null) startNode()
                connectPeers()
                updateNotification(statusText())
            }
        }
    }

    private fun pauseNode(reason: NodeState.Pause, why: String) {
        if (NodeState.pause == reason && node == null) return
        stopNode()
        NodeState.pause = reason
        updateNotification(statusText())
        LogStore.add("[app] paused: $why")
    }

    /** Drops the node and everything it costs, leaving the FGS parked. */
    private fun stopNode() {
        watchdog?.shutdownNow()
        watchdog = null
        releaseMulticastLock()
        runCatching { bridge.unregister() }
        bridge.node = null
        node?.let { runCatching { it.stop() } }
        node = null
        NodeState.node = null
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
        stopNode()
        bridge.shutdown()
        NodeState.pause = null
        connectivityCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        connectivityCallback = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        unlockRecheck?.let(main::removeCallbacks)
        unlockRecheck = null
        multicastLock = null
        dialer.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT = "belphegor.app.action.CONNECT"
        const val ACTION_NOTIFICATION = "belphegor.app.action.NOTIFICATION"
        const val ACTION_REEVALUATE = "belphegor.app.action.REEVALUATE"
        const val EXTRA_ADDR = "belphegor.app.extra.ADDR"
        private const val TAG = "BelphegorService"
        private const val CHANNEL = "clipboard-sync"
        private const val CHANNEL_HIDDEN = "clipboard-sync-hidden"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_MS = 15_000L
        private const val UNLOCK_RECHECK_MS = 1_000L
    }
}
