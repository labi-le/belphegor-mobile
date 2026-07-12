package belphegor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the sync service on device boot when autostart is enabled. The service
 * declares a `specialUse` foreground type so this is permitted on Android 15+,
 * where `dataSync` foreground services may not be launched from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(context).autostart) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, BelphegorService::class.java))
        }.onFailure { Log.w("BootReceiver", "autostart on boot failed", it) }
    }
}
