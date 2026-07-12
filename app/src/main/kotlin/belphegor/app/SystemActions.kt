package belphegor.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * System-integration actions kept out of [MainActivity]: installing/uninstalling
 * the bundled LSPosed module and requesting a battery-optimization exemption.
 * The APK copy runs off the main thread; the install intent is fired back on it.
 */

private const val UNLOCK_PKG = "belphegor.background"

fun AppCompatActivity.isUnlockInstalled(): Boolean =
    runCatching { packageManager.getPackageInfo(UNLOCK_PKG, 0); true }.getOrDefault(false)

fun AppCompatActivity.isBatteryExempt(): Boolean {
    val pm = getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(packageName)
}

// Ask the OS to exempt us from battery optimization so the sync service survives
// Doze / aggressive OEM killers. On OnePlus/ColorOS the user may still need to
// enable "auto-launch" in the OEM battery settings.
@SuppressLint("BatteryLife")
fun AppCompatActivity.requestBatteryExemption() {
    if (isBatteryExempt()) {
        toast(getString(R.string.bg_battery_ok_hint))
        return
    }
    val ok = runCatching {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }.isSuccess
    if (!ok) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}

fun AppCompatActivity.uninstallUnlockModule() {
    runCatching {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$UNLOCK_PKG")))
    }.onFailure {
        Log.w("Belphegor", "uninstall failed", it)
        toast(getString(R.string.unlock_uninstall_failed, it.message ?: ""))
    }
}

// Copies the bundled module APK out of assets (off the main thread) and fires a
// package-install intent. The module is an LSPosed module; installing it is step
// one, the user still enables it in LSPosed.
fun AppCompatActivity.installUnlockModule() {
    if (isUnlockInstalled()) return
    if (!packageManager.canRequestPackageInstalls()) {
        toast(getString(R.string.unlock_need_permission))
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        return
    }
    Thread {
        val staged = runCatching {
            val dir = File(cacheDir, "apks").apply { mkdirs() }
            val apk = File(dir, "background-clipboard.apk")
            assets.open("background-clipboard.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(this, "$packageName.files", apk)
        }
        runOnUiThread {
            staged.onSuccess { uri ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }.onFailure { toast(getString(R.string.unlock_install_failed, it.message ?: "")) }
            }.onFailure { toast(getString(R.string.unlock_install_failed, it.message ?: "")) }
        }
    }.start()
}

// Downloads the release APK (off the main thread, with a progress dialog) and
// fires the same package-install intent as the bundled module. The installer
// only completes if the release APK is signed with the key the installed app
// already carries — a stock Android self-update constraint, not a bug here.
fun AppCompatActivity.downloadAndInstallUpdate(update: Updater.Update) {
    if (!packageManager.canRequestPackageInstalls()) {
        toast(getString(R.string.unlock_need_permission))
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        return
    }
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
    }
    val label = TextView(this).apply { text = getString(R.string.update_downloading, 0) }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(20)
        setPadding(p, dp(16), p, 0)
        addView(label)
        addView(bar)
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.update_available_title, update.version))
        .setView(box)
        .setCancelable(false)
        .create()
    dialog.show()
    Thread {
        val result = runCatching {
            Updater.download(this, update) { pct ->
                runOnUiThread {
                    bar.isIndeterminate = false
                    bar.progress = pct
                    label.text = getString(R.string.update_downloading, pct)
                }
            }
        }
        runOnUiThread {
            dialog.dismiss()
            result.onSuccess { apk ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(
                                FileProvider.getUriForFile(this, "$packageName.files", apk),
                                "application/vnd.android.package-archive",
                            )
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }.onFailure { toast(getString(R.string.update_download_failed, it.message ?: "")) }
            }.onFailure {
                Log.w("Belphegor", "update download failed", it)
                toast(getString(R.string.update_download_failed, it.message ?: ""))
            }
        }
    }.start()
}
