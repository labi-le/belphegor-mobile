package belphegor.app

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

/**
 * Drawer-based control surface. Four sections: Dashboard (state + Start/Stop),
 * Nodes (connected peers + saved peers, add with the + button), Settings and
 * Logs. Dashboard/Nodes poll [NodeState] so peers update live.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private lateinit var nav: NavigationView
    private lateinit var navHeaderView: View

    private lateinit var secret: EditText
    private lateinit var deviceName: EditText
    private lateinit var port: EditText
    private lateinit var maxPeers: EditText
    private lateinit var useTcp: SwitchMaterial
    private lateinit var discover: SwitchMaterial
    private lateinit var verbose: SwitchMaterial
    private lateinit var autostart: SwitchMaterial
    private lateinit var checkUpdates: SwitchMaterial
    private lateinit var unlockBtn: MaterialButton
    private lateinit var batteryBtn: MaterialButton

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var selfText: TextView
    private lateinit var metaText: TextView
    private lateinit var summaryText: TextView
    private lateinit var fab: ExtendedFloatingActionButton

    private lateinit var peersHeader: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var savedBox: LinearLayout

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val sections = HashMap<Int, View>()
    private var currentSection = ID_DASH

    // Last status JSON rendered; the poll skips identical ticks so an idle node
    // costs no JNI marshal / JSON parse / view rebuild.
    private var lastStatusJson: String? = null
    private var statusRendered = false

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, POLL_MS)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { drawer.closeDrawer(Gravity.START) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        sections[ID_DASH] = dashboardSection()
        sections[ID_NODES] = nodesSection()
        sections[ID_SETTINGS] = settingsSection()
        sections[ID_LOGS] = logsSection()

        toolbar = MaterialToolbar(this).apply { title = getString(R.string.sec_dashboard) }
        content = FrameLayout(this)
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }

        navHeaderView = navHeader()
        nav = NavigationView(this).apply {
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
            addHeaderView(navHeaderView)
            menu.add(0, ID_DASH, 0, getString(R.string.sec_dashboard)).isCheckable = true
            menu.add(0, ID_NODES, 1, getString(R.string.sec_nodes)).isCheckable = true
            menu.add(0, ID_SETTINGS, 2, getString(R.string.sec_settings)).isCheckable = true
            menu.add(0, ID_LOGS, 3, getString(R.string.sec_logs)).isCheckable = true
            setNavigationItemSelectedListener { item ->
                select(item.itemId)
                drawer.closeDrawer(Gravity.START)
                true
            }
        }

        drawer = DrawerLayout(this).apply {
            addView(main, DrawerLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(nav, DrawerLayout.LayoutParams(dp(300), MATCH_PARENT).apply { gravity = Gravity.START })
        }
        setContentView(drawer)
        setSupportActionBar(toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, bars.top, 0, 0)
            content.setPadding(0, 0, 0, bars.bottom)
            navHeaderView.setPadding(dp(20), bars.top + dp(24), dp(20), dp(20))
            insets
        }

        ActionBarDrawerToggle(this, drawer, toolbar, R.string.nav_open, R.string.nav_close).also {
            drawer.addDrawerListener(it)
            it.syncState()
        }

        // Back closes the drawer when open; otherwise the platform default
        // (finish) runs. Replaces the deprecated onBackPressed override.
        onBackPressedDispatcher.addCallback(this, backCallback)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { backCallback.isEnabled = true }
            override fun onDrawerClosed(drawerView: View) { backCallback.isEnabled = false }
        })

        select(ID_DASH)
        refreshStatus()

        if (prefs.autostart && !NodeState.running) startNode(announce = false)
        if (savedInstanceState == null && prefs.checkUpdates) checkUpdatesInBackground(silent = true)
    }

    // A rightward horizontal swipe from anywhere opens the drawer (not just the
    // edge). Tracked in dispatchTouchEvent so it works over scroll views/buttons.
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeHandled = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::drawer.isInitialized) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeDownX = ev.x
                    swipeDownY = ev.y
                    swipeHandled = false
                }
                MotionEvent.ACTION_MOVE ->
                    if (!swipeHandled && !drawer.isDrawerOpen(Gravity.START)) {
                        val dx = ev.x - swipeDownX
                        val dy = ev.y - swipeDownY
                        if (dx > dp(56) && dx > 2 * kotlin.math.abs(dy)) {
                            swipeHandled = true
                            // Cancel whatever child started handling this gesture
                            // (a button press, text selection) so it does not get
                            // stuck without ever receiving an ACTION_UP.
                            val cancel = MotionEvent.obtain(ev)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            drawer.openDrawer(Gravity.START)
                        }
                    }
            }
            if (swipeHandled) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun select(id: Int) {
        val v = sections[id] ?: return
        currentSection = id
        (v.parent as? ViewGroup)?.removeView(v)
        content.removeAllViews()
        content.addView(v)
        toolbar.title = getString(
            when (id) {
                ID_NODES -> R.string.sec_nodes
                ID_SETTINGS -> R.string.sec_settings
                ID_LOGS -> R.string.sec_logs
                else -> R.string.sec_dashboard
            },
        )
        nav.setCheckedItem(id)
        // Only stream log updates while the Logs panel is on screen.
        LogStore.onChange = if (id == ID_LOGS) ({ renderLogs() }) else null
        if (id == ID_NODES) renderSavedPeers()
        if (id == ID_LOGS) renderLogs()
    }

    private fun navHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(48), dp(20), dp(20))
        addView(TextView(this@MainActivity).apply { text = getString(R.string.app_name); textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
        addView(dimText().apply { text = getString(R.string.app_tagline); setPadding(0, dp(2), 0, 0) })
    }

    private fun dashboardSection(): View {
        val statusCard = card {
            val title = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusDot = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) }
                background = oval(COLOR_OFF)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            statusText = TextView(this@MainActivity).apply { text = getString(R.string.status_stopped); textSize = 20f; setTypeface(typeface, Typeface.BOLD) }
            title.addView(statusDot)
            title.addView(statusText)
            addView(title)
            selfText = TextView(this@MainActivity).apply { textSize = 15f; setPadding(0, dp(10), 0, 0) }
            metaText = dimText().apply { setPadding(0, dp(3), 0, 0) }
            addView(selfText)
            addView(metaText)
        }
        val summaryCard = card {
            addView(sectionLabel(getString(R.string.connected_nodes)).apply { setPadding(0, 0, 0, 0) })
            summaryText = TextView(this@MainActivity).apply { text = getString(R.string.dash_placeholder); textSize = 34f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, 0) }
            addView(summaryText)
            addView(dimText().apply { text = getString(R.string.tap_view_nodes); textSize = 12f; setPadding(0, dp(4), 0, 0) })
        }
        summaryCard.setOnClickListener { select(ID_NODES) }
        fab = ExtendedFloatingActionButton(this).apply {
            text = getString(R.string.fab_start)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { if (NodeState.running) stopNode() else startNode() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(96))
            addView(statusCard)
            addView(summaryCard)
        }
        return FrameLayout(this).apply {
            addView(scroll(body), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fab, fabParams())
        }
    }

    private fun nodesSection(): View {
        val connectedCard = card {
            peersHeader = TextView(this@MainActivity).apply {
                text = getString(R.string.peers_connected)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            }
            addView(peersHeader)
            peersBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(peersBox)
        }
        val savedCard = card {
            addView(
                TextView(this@MainActivity).apply {
                    text = getString(R.string.saved_peers)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(6))
                },
            )
            savedBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(savedBox)
        }
        val addFab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            contentDescription = getString(R.string.add_node)
            setOnClickListener { showAddNodeDialog() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(96))
            addView(connectedCard)
            addView(savedCard)
        }
        return FrameLayout(this).apply {
            addView(scroll(body), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(addFab, fabParams())
        }
    }

    private fun settingsSection(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        root.addView(sectionLabel(getString(R.string.sec_identity)))
        root.addView(
            card {
                deviceName = field(getString(R.string.set_device_name), Build.MODEL ?: "Android", prefs.deviceName)
                secret = field(getString(R.string.set_secret), getString(R.string.set_secret_hint), prefs.secret)
            },
        )
        root.addView(sectionLabel(getString(R.string.sec_network)))
        root.addView(
            card {
                port = field(getString(R.string.set_port), getString(R.string.set_port_ex), prefs.port.takeIf { it > 0 }?.toString() ?: "", numeric = true)
                maxPeers = field(getString(R.string.set_max_peers), getString(R.string.set_max_peers_ex), prefs.maxPeers.takeIf { it > 0 }?.toString() ?: "", numeric = true)
                useTcp = switchRow(getString(R.string.set_tcp), getString(R.string.set_tcp_sub), prefs.transport == "tcp")
                discover = switchRow(getString(R.string.set_discover), getString(R.string.set_discover_sub), prefs.discover)
            },
        )
        root.addView(sectionLabel(getString(R.string.sec_behavior)))
        root.addView(
            card {
                autostart = switchRow(getString(R.string.set_autostart), getString(R.string.set_autostart_sub), prefs.autostart)
                verbose = switchRow(getString(R.string.set_verbose), null, prefs.verbose)
            },
        )
        root.addView(sectionLabel(getString(R.string.sec_updates)))
        root.addView(
            card {
                checkUpdates = switchRow(getString(R.string.set_check_updates), getString(R.string.set_check_updates_sub), prefs.checkUpdates)
                addView(dimText().apply { text = getString(R.string.update_current, Updater.currentVersion(this@MainActivity)); textSize = 12f; setPadding(0, dp(12), 0, 0) })
                addView(outlinedButton(getString(R.string.update_check_now)) { save(); checkUpdatesInBackground(silent = false) }, fullWidth(dp(10)))
            },
        )
        root.addView(sectionLabel(getString(R.string.unlock_section)))
        root.addView(
            card {
                batteryBtn = outlinedButton(getString(R.string.bg_battery)) { requestBatteryExemption() }
                addView(batteryBtn, fullWidth(dp(10)))
                unlockBtn = outlinedButton(getString(R.string.unlock_install)) { toggleUnlockModule() }
                addView(unlockBtn, fullWidth(dp(10)))
            },
        )
        root.addView(filledButton(getString(R.string.action_save)) { save(); toast(getString(R.string.toast_saved)) }, fullWidth(dp(4)))
        return scroll(root)
    }

    private fun logsSection(): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            addView(
                TextView(this@MainActivity).apply { text = getString(R.string.node_logs); textSize = 16f; setTypeface(typeface, Typeface.BOLD) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            addView(outlinedButton(getString(R.string.action_clear)) { LogStore.clear() })
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun showAddNodeDialog() {
        val til = TextInputLayout(
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox),
            null,
        ).apply { hint = getString(R.string.add_node_hint); placeholderText = getString(R.string.add_node_placeholder) }
        val input = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        til.addView(input)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20); setPadding(p, dp(8), p, 0)
            addView(til)
        }
        // Build without an auto-dismissing positive button so an invalid address
        // shows an inline error and keeps the dialog open.
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_node)
            .setMessage(R.string.add_node_msg)
            .setView(content)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        // One-tap add for peers heard via LAN discovery, besides manual ip:port.
        val saved = prefs.peerList().toSet()
        for ((name, addr) in discoveredPeers()) {
            if (addr in saved) continue
            if (content.childCount == 1) {
                content.addView(sectionLabel(getString(R.string.add_node_discovered)).apply { setPadding(0, dp(14), 0, 0) })
            }
            content.addView(
                outlinedButton(peerLabel(name, addr)) { addPeer(addr); dialog.dismiss() },
                fullWidth(dp(6)),
            )
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val addr = input.text.toString().trim()
                if (isValidPeer(addr)) {
                    til.error = null
                    addPeer(addr)
                    dialog.dismiss()
                } else {
                    til.error = getString(R.string.add_node_invalid)
                }
            }
        }
        dialog.show()
    }

    /** LAN discovery results from the node: (name, "ip:port"), newest first. */
    private fun discoveredPeers(): List<Pair<String, String>> {
        val json = NodeState.node?.let { runCatching { it.discoveredJSON() }.getOrNull() } ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val addr = o.optString("addr")
            if (addr.isEmpty()) null else o.optString("name") to addr
        }
    }

    private fun peerLabel(name: String, addr: String): String =
        if (name.isBlank()) addr else "$name  \u00b7  $addr"

    /** Accept host:port where port is 1..65535 and host is non-blank. */
    private fun isValidPeer(addr: String): Boolean {
        val i = addr.lastIndexOf(':')
        if (i <= 0 || i == addr.length - 1) return false
        val port = addr.substring(i + 1).toIntOrNull() ?: return false
        return addr.substring(0, i).isNotBlank() && port in 1..65535
    }

    private fun addPeer(addr: String) {
        if (addr.isEmpty()) return
        val list = prefs.peerList().toMutableList()
        if (!list.contains(addr)) {
            list.add(addr)
            prefs.peers = list.joinToString("\n")
        }
        if (NodeState.running) {
            launchService(
                Intent(this, BelphegorService::class.java)
                    .setAction(BelphegorService.ACTION_CONNECT)
                    .putExtra(BelphegorService.EXTRA_ADDR, addr),
            )
        }
        toast(getString(R.string.toast_added, addr))
        renderSavedPeers()
    }

    private fun removePeer(addr: String) {
        prefs.peers = prefs.peerList().filter { it != addr }.joinToString("\n")
        toast(getString(R.string.toast_removed, addr))
        renderSavedPeers()
    }

    private fun renderSavedPeers() {
        if (!::savedBox.isInitialized) return
        savedBox.removeAllViews()
        val list = prefs.peerList()
        if (list.isEmpty()) {
            savedBox.addView(dimText().apply { text = getString(R.string.saved_peers_none) })
            return
        }
        for (addr in list) {
            savedBox.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(TextView(this@MainActivity).apply { text = addr; textSize = 15f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(outlinedButton(getString(R.string.action_remove)) { removePeer(addr) })
                },
            )
        }
    }

    private fun refreshStatus() {
        val json = NodeState.statusJson()
        // Skip identical ticks: an idle node re-emits the same snapshot, so there
        // is no need to re-marshal, re-parse, or rebuild any views.
        if (statusRendered && json == lastStatusJson) return
        statusRendered = true
        lastStatusJson = json

        if (json == null) {
            statusDot.background = oval(COLOR_OFF)
            statusText.text = getString(R.string.status_stopped)
            selfText.text = prefs.deviceName.ifBlank { Build.MODEL ?: "Android" }
            metaText.text = getString(R.string.status_offline_hint)
            summaryText.text = getString(R.string.dash_placeholder)
            fab.text = getString(R.string.fab_start)
            fab.backgroundTintList = ColorStateList.valueOf(COLOR_FAB_GO)
            peersHeader.text = getString(R.string.peers_connected)
            peersBox.removeAllViews()
            peersBox.addView(dimText().apply { text = getString(R.string.peers_service_off) })
            return
        }
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val self = o.optJSONObject("self")
        val name = self?.optString("name").orEmpty().ifEmpty { getString(R.string.status_this_device) }
        val id = self?.optLong("id") ?: 0L
        val listen = o.optString("listen").ifEmpty { getString(R.string.status_binding) }
        val transport = o.optString("transport").ifEmpty { "quic" }
        val disc = o.optBoolean("discover")
        val arr = o.optJSONArray("peers")
        val n = arr?.length() ?: 0

        statusDot.background = oval(COLOR_ON)
        statusText.text = getString(R.string.status_running)
        selfText.text = getString(R.string.self_line, name, id)
        metaText.text = buildString {
            append(getString(R.string.meta_line, listen, transport))
            if (disc) {
                append("  ")
                append(getString(R.string.meta_discovery))
            }
        }
        summaryText.text = "$n"
        fab.text = getString(R.string.fab_stop)
        fab.backgroundTintList = ColorStateList.valueOf(COLOR_STOP)

        peersHeader.text = getString(R.string.peers_connected_n, n)
        peersBox.removeAllViews()
        if (n == 0) {
            peersBox.addView(dimText().apply { text = getString(R.string.peers_none) })
        } else {
            for (i in 0 until n) {
                val p = arr!!.getJSONObject(i)
                peersBox.addView(peerRow(p.optString("name"), p.optString("arch"), p.optString("addr"), p.optLong("id")))
            }
        }
    }

    private fun peerRow(name: String, arch: String, addr: String, id: Long): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(
                View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(12) }
                    background = oval(COLOR_ON)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = name.ifEmpty { getString(R.string.peer_unknown) }
                            textSize = 16f
                            setTypeface(typeface, Typeface.BOLD)
                        },
                    )
                    addView(dimText().apply { text = getString(R.string.peer_meta, addr, arch, id); textSize = 12f })
                },
            )
        }

    private fun startNode(announce: Boolean = true) {
        save()
        launchService(Intent(this, BelphegorService::class.java))
        ensureNotificationPermission()
        if (announce) toast(getString(R.string.toast_starting))
    }

    private fun stopNode() {
        stopService(Intent(this, BelphegorService::class.java))
        NodeState.node = null
        toast(getString(R.string.toast_stopped))
        refreshStatus()
    }

    private fun launchService(intent: Intent) {
        if (intent.action == BelphegorService.ACTION_CONNECT) {
            super.startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUnlockButton()
        refreshBatteryButton()
        if (currentSection == ID_LOGS) {
            LogStore.onChange = { renderLogs() }
            renderLogs()
        }
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        LogStore.onChange = null
        ui.removeCallbacks(poll)
    }

    private fun renderLogs() {
        if (!::logView.isInitialized) return
        logView.text = LogStore.snapshot()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun save() {
        prefs.secret = secret.text.toString().trim()
        prefs.deviceName = deviceName.text.toString().trim()
        prefs.port = port.text.toString().trim().toIntOrNull() ?: 0
        prefs.maxPeers = maxPeers.text.toString().trim().toIntOrNull() ?: 0
        prefs.transport = if (useTcp.isChecked) "tcp" else "quic"
        prefs.discover = discover.isChecked
        prefs.verbose = verbose.isChecked
        prefs.autostart = autostart.isChecked
        prefs.checkUpdates = checkUpdates.isChecked
    }

    private fun refreshUnlockButton() {
        if (!::unlockBtn.isInitialized) return
        unlockBtn.text = getString(if (isUnlockInstalled()) R.string.unlock_uninstall else R.string.unlock_install)
    }

    private fun toggleUnlockModule() {
        if (isUnlockInstalled()) uninstallUnlockModule() else installUnlockModule()
    }

    private fun refreshBatteryButton() {
        if (!::batteryBtn.isInitialized) return
        batteryBtn.text = getString(if (isBatteryExempt()) R.string.bg_battery_ok else R.string.bg_battery)
    }

    private fun checkUpdatesInBackground(silent: Boolean) {
        if (!silent) toast(getString(R.string.update_checking))
        Thread {
            val res = Updater.check(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                res.onSuccess { up ->
                    if (up != null) showUpdateDialog(up)
                    else if (!silent) toast(getString(R.string.update_up_to_date))
                }.onFailure { e ->
                    Log.w("Belphegor", "update check failed", e)
                    if (!silent) toast(getString(R.string.update_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun showUpdateDialog(up: Updater.Update) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, up.version))
            .setMessage(up.notes.ifBlank { getString(R.string.dash_placeholder) })
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstallUpdate(up) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private companion object {
        const val ID_DASH = 1
        const val ID_NODES = 2
        const val ID_SETTINGS = 3
        const val ID_LOGS = 4
        const val POLL_MS = 1500L
        const val COLOR_ON = 0xFF4CAF50.toInt()
        const val COLOR_OFF = 0xFF9E9E9E.toInt()
        const val COLOR_FAB_GO = 0xFF2E7D32.toInt()
        const val COLOR_STOP = 0xFFC62828.toInt()
    }
}
