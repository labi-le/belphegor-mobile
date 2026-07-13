package belphegor.background

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module that neutralises the Android 10+ background-clipboard guard,
 * but ONLY for the belphegor app. Runs inside system_server (package
 * "android"), so it only takes effect after a reboot.
 *
 * The guard (ClipboardService.clipboardAccessAllowed) decides whether a caller
 * may touch the clipboard based on focus / default-IME. Its parameter list
 * changed across Android versions, so we hook every overload by name and
 * force-allow when the call is belphegor's.
 *
 * Identity is matched on the callingPackage the SYSTEM passes as a method
 * argument. The clipboard service validates callingPackage against the caller's
 * real uid BEFORE this guard runs (it rejects a package the caller does not
 * own), so another app cannot pass our name here. We deliberately do NOT resolve
 * our uid via PackageManager: on some ROMs (e.g. ColorOS) getPackageUid() throws
 * NameNotFoundException for our package from system_server, which made an earlier
 * version fail closed and leave background reads denied.
 */
class BackgroundClipboard : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ANDROID) return

        val clazz = XposedHelpers.findClassIfExists(CLIPBOARD_SERVICE, lpparam.classLoader) ?: run {
            log("$CLIPBOARD_SERVICE not found")
            return
        }

        var hooked = 0
        for (name in GUARD_METHODS) {
            hooked += XposedBridge.hookAllMethods(clazz, name, allowForTarget).size
        }
        log("hooked $hooked clipboard guard method(s)")
    }

    private val allowForTarget = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            for (a in param.args) {
                if (a is String && a == TARGET_PACKAGE) {
                    param.result = true
                    return
                }
            }
        }
    }

    /** Mirror to both the LSPosed log and logcat (logcat is adb-readable). */
    private fun log(m: String) {
        XposedBridge.log("belphegor-background: $m")
        Log.i(TAG, m)
    }

    private companion object {
        const val ANDROID = "android"
        const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        const val TARGET_PACKAGE = "belphegor.app"
        const val TAG = "belphegor-bg"
        val GUARD_METHODS = arrayOf("clipboardAccessAllowed")
    }
}
