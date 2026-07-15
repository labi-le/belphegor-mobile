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
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject

/**
 * Tab-bar control surface (Apple-HIG styling, DESIGN.md). Four tabs: Dashboard
 * (status card with the sync switch), Nodes (connected + saved peers, add via
 * the toolbar +), Settings (grouped forms, applied on change) and Logs.
 * Dashboard/Nodes poll [NodeState] so peers update live.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private lateinit var tabs: BottomNavigationView

    private lateinit var secret: EditText
    private lateinit var deviceName: EditText
    private lateinit var port: EditText
    private lateinit var maxPeers: EditText
    private lateinit var useTcp: MaterialSwitch
    private lateinit var discover: MaterialSwitch
    private lateinit var verbose: MaterialSwitch
    private lateinit var autostart: MaterialSwitch
    private lateinit var checkUpdates: MaterialSwitch
    private lateinit var discoverDelay: EditText
    private lateinit var keepAlive: EditText
    private lateinit var maxFileSize: EditText
    private lateinit var maxClipboardFiles: EditText
    private lateinit var allowFiles: MaterialSwitch
    private lateinit var sendClip: MaterialSwitch
    private lateinit var receiveClip: MaterialSwitch
    private lateinit var wifiOnly: MaterialSwitch
    private lateinit var bgDiscovery: MaterialSwitch
    private lateinit var appearanceValue: TextView
    private lateinit var unlockBtn: TextView
    private lateinit var batteryBtn: TextView

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var syncSwitch: MaterialSwitch
    private lateinit var selfText: TextView
    private lateinit var metaText: TextView
    private lateinit var summaryText: TextView

    private lateinit var peersHeader: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var discoveredHeader: TextView
    private lateinit var discoveredBox: LinearLayout
    private lateinit var discoveredCard: View
    private lateinit var savedBox: LinearLayout

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val sections = HashMap<Int, View>()
    private var currentSection = ID_DASH

    // Last status JSON rendered; the poll skips identical ticks so an idle node
    // costs no JNI marshal / JSON parse / view rebuild.
    private var lastStatusJson: String? = null
    private var statusRendered = false

    // While set (uptime deadline), a null status renders as "Starting…" instead
    // of flashing back to Stopped between the service launch and the node
    // coming up. Cleared by an explicit stop.
    private var startingUntil = 0L
    private var lastStarting = false
    private var lastPaused = false

    // Signature of the last rendered LAN-discovered list (Nodes tab): an
    // unchanged discovery result skips the view rebuild, like the status poll.
    private var lastDiscoveredSig: String? = null

    // Hairline under the toolbar, shown only while the active section is
    // scrolled (the HIG "scroll edge" cue); scrollers registered per section.
    private lateinit var toolbarEdge: View
    private val scrollers = HashMap<Int, ScrollView>()
    private var suppressSwitch = false

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(themeMode(Prefs(this).theme))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        sections[ID_DASH] = dashboardSection()
        sections[ID_NODES] = nodesSection()
        sections[ID_SETTINGS] = settingsSection()
        sections[ID_LOGS] = logsSection()

        toolbar = MaterialToolbar(this).apply {
            setTitleTextAppearance(this@MainActivity, R.style.TextAppearance_Belphegor_ToolbarTitle)
            isTitleCentered = true
            setOnMenuItemClickListener { onToolbarAction(it) }
        }
        content = FrameLayout(this)
        tabs = BottomNavigationView(this).apply {
            isItemActiveIndicatorEnabled = false
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            // Kill the M3 tonal-elevation overlay so the bar sits on the plain
            // canvas color with only the hairline above it (iOS tab bar).
            elevation = 0f
            setBackgroundColor(color(R.color.bg_canvas))
            // No press ripple on tabs — iOS tab bars have no touch animation,
            // just an instant color change (and M3 draws an unbounded ripple
            // once the active-indicator is disabled).
            itemRippleColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            menu.add(0, ID_DASH, 0, getString(R.string.sec_dashboard)).setIcon(R.drawable.ic_nav_dashboard)
            menu.add(0, ID_NODES, 1, getString(R.string.sec_nodes)).setIcon(R.drawable.ic_nav_nodes)
            menu.add(0, ID_SETTINGS, 2, getString(R.string.sec_settings)).setIcon(R.drawable.ic_nav_settings)
            menu.add(0, ID_LOGS, 3, getString(R.string.sec_logs)).setIcon(R.drawable.ic_nav_logs)
            setOnItemSelectedListener { item ->
                select(item.itemId)
                true
            }
        }
        toolbarEdge = View(this).apply {
            setBackgroundColor(color(R.color.separator))
            visibility = View.INVISIBLE
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(toolbarEdge, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(
                View(this@MainActivity).apply { setBackgroundColor(color(R.color.separator)) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1),
            )
            addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        for ((id, sv) in scrollers) {
            sv.setOnScrollChangeListener { _, _, y, _, _ ->
                if (currentSection == id) toolbarEdge.visibility = if (y > 0) View.VISIBLE else View.INVISIBLE
            }
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.setPadding(0, bars.top, 0, 0)
            // Enforced edge-to-edge on 35: give the content room to scroll
            // fields above the keyboard (tabs sit behind the IME anyway).
            content.setPadding(0, 0, 0, (ime.bottom - bars.bottom).coerceAtLeast(0))
            insets
        }

        select(savedInstanceState?.getInt(KEY_SECTION) ?: ID_DASH)
        refreshStatus()

        if (prefs.autostart && !NodeState.running) startNode()
        if (savedInstanceState == null && prefs.checkUpdates) checkUpdatesInBackground(silent = true)
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
                else -> R.string.app_name
            },
        )
        toolbar.menu.clear()
        when (id) {
            ID_NODES -> toolbar.menu.add(0, MENU_ADD, 0, R.string.add_node).apply {
                // Borderless accent glyph (iOS nav-bar "+"): systemBlue reads
                // clearly on both the white and black canvas, so no filled chip
                // (DESIGN.md §5).
                actionView = MaterialButton(
                    this@MainActivity,
                    null,
                    com.google.android.material.R.attr.materialIconButtonStyle,
                ).apply {
                    setIconResource(R.drawable.ic_add)
                    iconTint = ColorStateList.valueOf(color(R.color.accent))
                    contentDescription = getString(R.string.add_node)
                    setOnClickListener { showAddNodeDialog() }
                }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            ID_LOGS -> toolbar.menu.add(0, MENU_CLEAR, 0, R.string.action_clear)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        tabs.menu.findItem(id)?.isChecked = true
        toolbarEdge.visibility =
            if ((scrollers[id]?.scrollY ?: 0) > 0) View.VISIBLE else View.INVISIBLE
        // Only stream log updates while the Logs panel is on screen.
        LogStore.onChange = if (id == ID_LOGS) ({ renderLogs() }) else null
        if (id == ID_NODES) { renderSavedPeers(); renderDiscovered() }
        if (id == ID_LOGS) renderLogs()
    }

    private fun onToolbarAction(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ADD -> {
            showAddNodeDialog()
            true
        }
        MENU_CLEAR -> {
            LogStore.clear()
            true
        }
        else -> false
    }

    private fun dashboardSection(): View {
        val statusCard = card {
            val title = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            statusDot = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
                background = oval(color(R.color.ios_gray))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            statusText = TextView(this@MainActivity).apply {
                text = getString(R.string.status_stopped)
                textSize = 20f
                setTextColor(color(R.color.label))
                setTypeface(typeface, Typeface.BOLD)
            }
            syncSwitch = iosSwitch().apply {
                contentDescription = getString(R.string.sync_switch)
                setOnCheckedChangeListener { _, checked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    if (checked) {
                        if (!NodeState.running) startNode()
                    } else {
                        stopNode()
                    }
                }
            }
            title.addView(statusDot)
            title.addView(statusText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            title.addView(syncSwitch)
            addView(title)
            divider()
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(11), dp(16), dp(12))
            }
            selfText = TextView(this@MainActivity).apply { textSize = 17f; setTextColor(color(R.color.label)) }
            metaText = dimText().apply { setPadding(0, dp(3), 0, 0) }
            details.addView(selfText)
            details.addView(metaText)
            addView(details)
        }
        val summaryCard = card {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(sectionLabel(getString(R.string.connected_nodes)).apply { setPadding(0, 0, 0, 0) })
                summaryText = TextView(this@MainActivity).apply {
                    text = getString(R.string.dash_placeholder)
                    textSize = 28f
                    setTextColor(color(R.color.label))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                }
                addView(summaryText)
            }
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_chevron)
                    imageTintList = ColorStateList.valueOf(color(R.color.label_tertiary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            addView(row)
        }
        summaryCard.setOnClickListener { select(ID_NODES) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(statusCard)
            addView(summaryCard)
        }
        return scroll(body).also { scrollers[ID_DASH] = it }
    }

    private fun nodesSection(): View {
        peersHeader = sectionLabel(getString(R.string.peers_connected))
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        discoveredHeader = sectionLabel(getString(R.string.add_node_discovered))
        discoveredBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        discoveredCard = card { addView(discoveredBox) }
        savedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(peersHeader)
            addView(card { addView(peersBox) })
            addView(discoveredHeader)
            addView(discoveredCard)
            addView(sectionLabel(getString(R.string.saved_peers)))
            addView(card { addView(savedBox) })
        }
        return scroll(body).also { scrollers[ID_NODES] = it }
    }

    private fun settingsSection(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(sectionLabel(getString(R.string.sec_appearance)))
        root.addView(
            card {
                appearanceValue = navRow(getString(R.string.set_appearance), themeLabel(prefs.theme)) { showThemeDialog() }
            },
        )
        root.addView(sectionLabel(getString(R.string.sec_identity)))
        root.addView(
            card {
                deviceName = fieldRow(getString(R.string.set_device_name), Build.MODEL ?: "Android", prefs.deviceName)
                divider()
                secret = fieldRow(getString(R.string.set_secret), getString(R.string.set_secret_hint), prefs.secret)
            },
        )
        root.addView(footerLabel(getString(R.string.footer_secret)))
        root.addView(sectionLabel(getString(R.string.sec_network)))
        root.addView(
            card {
                port = fieldRow(getString(R.string.set_port), getString(R.string.set_port_ex), prefs.port.takeIf { it > 0 }?.toString() ?: "", numeric = true)
                divider()
                maxPeers = fieldRow(getString(R.string.set_max_peers), getString(R.string.set_max_peers_ex), prefs.maxPeers.takeIf { it > 0 }?.toString() ?: "", numeric = true)
                divider()
                discoverDelay = fieldRow(getString(R.string.set_discover_delay), getString(R.string.set_discover_delay_ex), prefs.discoverDelay.toString(), numeric = true)
                divider()
                keepAlive = fieldRow(getString(R.string.set_keep_alive), getString(R.string.set_keep_alive_ex), prefs.keepAlive.toString(), numeric = true)
                divider()
                useTcp = switchRow(getString(R.string.set_tcp), getString(R.string.set_tcp_sub), prefs.transport == "tcp")
                divider()
                discover = switchRow(getString(R.string.set_discover), getString(R.string.set_discover_sub), prefs.discover)
                divider()
                wifiOnly = switchRow(getString(R.string.set_wifi_only), getString(R.string.set_wifi_only_sub), prefs.wifiOnly)
            },
        )
        root.addView(footerLabel(getString(R.string.footer_apply)))
        root.addView(sectionLabel(getString(R.string.sec_clipboard)))
        root.addView(
            card {
                sendClip = switchRow(getString(R.string.set_send), getString(R.string.set_send_sub), prefs.sendEnabled)
                divider()
                receiveClip = switchRow(getString(R.string.set_receive), getString(R.string.set_receive_sub), prefs.receiveEnabled)
                divider()
                allowFiles = switchRow(getString(R.string.set_files), getString(R.string.set_files_sub), prefs.allowFiles)
                divider()
                maxFileSize = fieldRow(getString(R.string.set_max_file_size), getString(R.string.set_max_file_size_ex), prefs.maxFileSizeMiB.toString(), numeric = true)
                divider()
                maxClipboardFiles = fieldRow(getString(R.string.set_max_files), getString(R.string.set_max_files_ex), prefs.maxClipboardFiles.toString(), numeric = true)
            },
        )
        root.addView(footerLabel(getString(R.string.footer_clipboard)))
        root.addView(sectionLabel(getString(R.string.sec_behavior)))
        root.addView(
            card {
                autostart = switchRow(getString(R.string.set_autostart), getString(R.string.set_autostart_sub), prefs.autostart)
                divider()
                bgDiscovery = switchRow(getString(R.string.set_bg_discovery), getString(R.string.set_bg_discovery_sub), prefs.bgDiscovery)
                divider()
                verbose = switchRow(getString(R.string.set_verbose), null, prefs.verbose)
            },
        )
        root.addView(sectionLabel(getString(R.string.sec_updates)))
        root.addView(
            card {
                checkUpdates = switchRow(getString(R.string.set_check_updates), getString(R.string.set_check_updates_sub), prefs.checkUpdates)
                divider()
                buttonRow(getString(R.string.update_check_now)) { checkUpdatesInBackground(silent = false) }
            },
        )
        root.addView(footerLabel(getString(R.string.update_current, Updater.currentVersion(this))))
        root.addView(sectionLabel(getString(R.string.unlock_section)))
        root.addView(
            card {
                batteryBtn = buttonRow(getString(R.string.bg_battery)) { requestBatteryExemption() }
                divider()
                unlockBtn = buttonRow(getString(R.string.unlock_install)) { toggleUnlockModule() }
            },
        )
        root.addView(sectionLabel(getString(R.string.sec_peers)))
        root.addView(
            card {
                buttonRow(getString(R.string.peers_clear)) { clearSavedPeers() }.setTextColor(color(R.color.ios_red))
            },
        )
        root.addView(footerLabel(getString(R.string.footer_peers)))

        // iOS-style settings: no Save button, changes persist as they are made
        // (the running node picks them up on its next start).
        for (field in arrayOf(deviceName, secret, port, maxPeers, discoverDelay, keepAlive, maxFileSize, maxClipboardFiles)) {
            field.doAfterTextChanged { save() }
        }
        for (sw in arrayOf(useTcp, discover, wifiOnly, sendClip, receiveClip, allowFiles, autostart, bgDiscovery, verbose, checkUpdates)) {
            sw.setOnCheckedChangeListener { _, _ -> save() }
        }
        return scroll(root).also { scrollers[ID_SETTINGS] = it }
    }

    private fun themeMode(theme: String) = when (theme) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun themeLabel(theme: String) = getString(
        when (theme) {
            "light" -> R.string.appearance_light
            "dark" -> R.string.appearance_dark
            else -> R.string.appearance_system
        },
    )

    private fun showThemeDialog() {
        val keys = arrayOf("system", "light", "dark")
        val labels = keys.map { themeLabel(it) }.toTypedArray()
        val current = keys.indexOf(prefs.theme).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setCustomTitle(dialogTitle(getString(R.string.set_appearance)))
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.theme = keys[which]
                appearanceValue.text = themeLabel(prefs.theme)
                d.dismiss()
                AppCompatDelegate.setDefaultNightMode(themeMode(prefs.theme))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearSavedPeers() {
        MaterialAlertDialogBuilder(this)
            .setCustomTitle(dialogTitle(getString(R.string.peers_clear_confirm)))
            .setPositiveButton(R.string.action_clear) { _, _ ->
                prefs.peers = ""
                renderSavedPeers()
                toast(getString(R.string.peers_cleared))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun logsSection(): View {
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        scrollers[ID_LOGS] = logScroll
        return FrameLayout(this).apply {
            addView(
                capped(logScroll),
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER_HORIZONTAL),
            )
        }
    }

    private fun showAddNodeDialog() {
        // App field grammar (DESIGN.md §4): borderless input on a rounded
        // tertiary fill, error as a plain red footnote — no boxes, no
        // floating labels.
        val fieldBg = fieldSurface()
        val input = EditText(this).apply {
            hint = getString(R.string.add_node_placeholder)
            textSize = 17f
            isSingleLine = true
            background = fieldBg
            setTextColor(color(R.color.label))
            setHintTextColor(color(R.color.label_tertiary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val errorText = dimText().apply {
            setTextColor(color(R.color.ios_red))
            visibility = View.GONE
            setPaddingRelative(dp(4), dp(6), 0, 0)
        }
        fun setError(msg: String?) {
            if (msg == null) {
                errorText.visibility = View.GONE
                fieldBg.setStroke(0, 0)
            } else {
                errorText.text = msg
                errorText.visibility = View.VISIBLE
                fieldBg.setStroke(dp(1).coerceAtLeast(2), color(R.color.ios_red))
            }
        }
        input.doAfterTextChanged { setError(null) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24); setPadding(p, dp(8), p, 0)
            addView(input, fullWidth())
            addView(errorText, fullWidth())
        }
        // Build without an auto-dismissing positive button so an invalid address
        // shows an inline error and keeps the dialog open.
        val dialog = MaterialAlertDialogBuilder(this)
            .setCustomTitle(dialogTitle(getString(R.string.add_node)))
            .setMessage(R.string.add_node_msg)
            .setView(content)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val addr = input.text.toString().trim()
                if (isValidPeer(addr)) {
                    addPeer(addr)
                    dialog.dismiss()
                } else {
                    setError(getString(R.string.add_node_invalid))
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
        renderSavedPeers()
        renderDiscovered()
    }

    private fun removePeer(addr: String) {
        prefs.peers = prefs.peerList().filter { it != addr }.joinToString("\n")
        renderSavedPeers()
        renderDiscovered()
    }

    private fun emptyRow(text: String): TextView = dimText().apply {
        this.text = text
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun renderSavedPeers() {
        if (!::savedBox.isInitialized) return
        savedBox.removeAllViews()
        val list = prefs.peerList()
        if (list.isEmpty()) {
            savedBox.addView(emptyRow(getString(R.string.saved_peers_none)))
            return
        }
        for ((i, addr) in list.withIndex()) {
            if (i > 0) savedBox.divider()
            savedBox.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingRelative(dp(16), dp(2), dp(8), dp(2))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = addr
                            textSize = 17f
                            setTextColor(color(R.color.label))
                        },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                    )
                    addView(
                        MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                            setIconResource(R.drawable.ic_remove_circle)
                            iconTint = ColorStateList.valueOf(color(R.color.ios_red))
                            contentDescription = getString(R.string.peer_remove_desc, addr)
                            setOnClickListener { removePeer(addr) }
                        },
                    )
                },
            )
        }
    }

    private fun renderDiscovered() {
        if (!::discoveredBox.isInitialized || currentSection != ID_NODES) return
        val saved = prefs.peerList().toSet()
        val list = discoveredPeers().filter { (_, addr) -> addr !in saved }
        val sig = list.joinToString("|") { it.second }
        if (sig == lastDiscoveredSig) return
        lastDiscoveredSig = sig
        val visible = list.isNotEmpty()
        discoveredHeader.visibility = if (visible) View.VISIBLE else View.GONE
        discoveredCard.visibility = if (visible) View.VISIBLE else View.GONE
        discoveredBox.removeAllViews()
        for ((i, peer) in list.withIndex()) {
            if (i > 0) discoveredBox.divider()
            discoveredBox.addView(discoveredRow(peer.first, peer.second))
        }
    }

    /** "Found on your network" row: tap to save the discovered peer. */
    private fun discoveredRow(name: String, addr: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPaddingRelative(dp(16), dp(6), dp(12), dp(6))
            setBackgroundResource(resolveAttrRes(android.R.attr.selectableItemBackground))
            contentDescription = getString(R.string.add_node) + " \u00b7 " + peerLabel(name, addr)
            addView(
                TextView(this@MainActivity).apply {
                    text = peerLabel(name, addr)
                    textSize = 17f
                    setTextColor(color(R.color.label))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_add)
                    imageTintList = ColorStateList.valueOf(color(R.color.accent))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            setOnClickListener { addPeer(addr) }
        }

    private fun refreshStatus() {
        renderDiscovered()
        val json = NodeState.statusJson()
        val starting = json == null && SystemClock.uptimeMillis() < startingUntil
        val paused = json == null && NodeState.pausedForNetwork
        // Skip identical ticks: an idle node re-emits the same snapshot, so there
        // is no need to re-marshal, re-parse, or rebuild any views.
        if (statusRendered && json == lastStatusJson && starting == lastStarting && paused == lastPaused) return
        statusRendered = true
        lastStatusJson = json
        lastStarting = starting
        lastPaused = paused

        if (json == null) {
            statusDot.background = oval(color(if (paused || starting) R.color.ios_orange else R.color.ios_gray))
            statusText.text = getString(if (paused) R.string.status_paused else if (starting) R.string.status_starting else R.string.status_stopped)
            setSwitch(starting || paused)
            selfText.text = prefs.deviceName.ifBlank { Build.MODEL ?: "Android" }
            metaText.text = if (paused) getString(R.string.status_wifi_wait) else if (starting) "" else getString(R.string.status_offline_hint)
            summaryText.text = getString(R.string.dash_placeholder)
            peersHeader.text = getString(R.string.peers_connected)
            peersBox.removeAllViews()
            peersBox.addView(
                emptyRow(getString(if (paused) R.string.status_wifi_wait else if (starting) R.string.status_starting else R.string.peers_service_off)),
            )
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

        statusDot.background = oval(color(R.color.ios_green))
        statusText.text = getString(R.string.status_running)
        setSwitch(true)
        selfText.text = getString(R.string.self_line, name, id)
        metaText.text = buildString {
            append(getString(R.string.meta_line, listen, transport))
            if (disc) {
                append("  ")
                append(getString(R.string.meta_discovery))
            }
        }
        summaryText.text = "$n"

        peersHeader.text = getString(R.string.peers_connected_n, n)
        peersBox.removeAllViews()
        if (n == 0) {
            peersBox.addView(emptyRow(getString(R.string.peers_none)))
        } else {
            for (i in 0 until n) {
                val p = arr!!.getJSONObject(i)
                if (i > 0) peersBox.divider()
                peersBox.addView(peerRow(p.optString("name"), p.optString("arch"), p.optString("addr"), p.optLong("id")))
            }
        }
    }

    private fun setSwitch(checked: Boolean) {
        if (!::syncSwitch.isInitialized || syncSwitch.isChecked == checked) return
        suppressSwitch = true
        syncSwitch.isChecked = checked
        suppressSwitch = false
    }

    private fun peerRow(name: String, arch: String, addr: String, id: Long): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            addView(
                View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(12) }
                    background = oval(color(R.color.ios_green))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = name.ifEmpty { getString(R.string.peer_unknown) }
                            textSize = 17f
                            setTextColor(color(R.color.label))
                            setTypeface(typeface, Typeface.BOLD)
                        },
                    )
                    addView(dimText().apply { text = getString(R.string.peer_meta, addr, arch, id); setPadding(0, dp(1), 0, 0) })
                },
            )
        }

    private fun startNode() {
        save()
        launchService(Intent(this, BelphegorService::class.java))
        ensureNotificationPermission()
        startingUntil = SystemClock.uptimeMillis() + STARTING_GRACE_MS
        refreshStatus()
    }

    private fun stopNode() {
        stopService(Intent(this, BelphegorService::class.java))
        NodeState.node = null
        NodeState.pausedForNetwork = false
        startingUntil = 0L
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
        // Opening/returning to the app re-dials saved peers if the link dropped
        // while backgrounded (the core does not auto-reconnect on its own).
        if (NodeState.node != null) {
            launchService(Intent(this, BelphegorService::class.java).setAction(BelphegorService.ACTION_CONNECT))
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SECTION, currentSection)
    }

    private fun renderLogs() {
        if (!::logView.isInitialized) return
        val text = LogStore.snapshot()
        if (text.isEmpty()) {
            logView.text = getString(R.string.logs_empty)
            logView.setTextColor(color(R.color.label_secondary))
            return
        }
        logView.setTextColor(color(R.color.label))
        logView.text = text
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun save() {
        prefs.secret = secret.text.toString().trim()
        prefs.deviceName = deviceName.text.toString().trim()
        prefs.port = (port.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 65535)
        prefs.maxPeers = (maxPeers.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 1024)
        prefs.transport = if (useTcp.isChecked) "tcp" else "quic"
        prefs.discover = discover.isChecked
        prefs.verbose = verbose.isChecked
        prefs.autostart = autostart.isChecked
        prefs.checkUpdates = checkUpdates.isChecked
        prefs.discoverDelay = (discoverDelay.text.toString().trim().toIntOrNull() ?: 60).coerceAtLeast(1)
        prefs.keepAlive = (keepAlive.text.toString().trim().toIntOrNull() ?: 60).coerceAtLeast(1)
        prefs.maxFileSizeMiB = (maxFileSize.text.toString().trim().toIntOrNull() ?: 16).coerceIn(1, 256)
        prefs.maxClipboardFiles = (maxClipboardFiles.text.toString().trim().toIntOrNull() ?: 15).coerceIn(1, 1024)
        prefs.allowFiles = allowFiles.isChecked
        prefs.sendEnabled = sendClip.isChecked
        prefs.receiveEnabled = receiveClip.isChecked
        prefs.wifiOnly = wifiOnly.isChecked
        prefs.bgDiscovery = bgDiscovery.isChecked
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
            .setCustomTitle(dialogTitle(getString(R.string.update_available_title, up.version)))
            .setMessage(up.notes.ifBlank { getString(R.string.dash_placeholder) })
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstallUpdate(up) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private companion object {
        const val KEY_SECTION = "section"
        const val ID_DASH = 1
        const val ID_NODES = 2
        const val ID_SETTINGS = 3
        const val ID_LOGS = 4
        const val MENU_ADD = 100
        const val MENU_CLEAR = 101
        const val POLL_MS = 1500L
        const val STARTING_GRACE_MS = 6000L
    }
}
