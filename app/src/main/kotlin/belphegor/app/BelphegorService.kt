package belphegor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
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
        startNode()
        connectPeers()
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
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setColor(getColor(R.color.accent))
            .setOngoing(true)
            .build()
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
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
                }
            }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun onDestroy() {
        runCatching { bridge.unregister() }
        runCatching { node?.stop() }
        node = null
        NodeState.node = null
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
        private const val WATCHDOG_MS = 30_000L
    }
}
