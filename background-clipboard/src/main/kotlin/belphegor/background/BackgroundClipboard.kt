package belphegor.background

import android.app.AppOpsManager
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

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
 * The access hook skips the guard's package check, so the notice hook must
 * independently check package ownership before suppressing a toast.
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

        val notificationMethod = clazz.declaredMethods.firstOrNull(::isSupportedNotificationMethod)
        if (notificationMethod != null) {
            XposedBridge.hookMethod(notificationMethod, suppressTargetReadNotice)
            log("hooked clipboard read notification")
        } else {
            log("supported clipboard read notification method not found; notices unchanged")
        }
    }

    private fun isSupportedNotificationMethod(method: Method): Boolean {
        if (method.name != READ_NOTIFICATION_METHOD || method.returnType != Void.TYPE) return false
        val types = method.parameterTypes
        return types.size == 5 &&
            types[0] == String::class.java &&
            types[1] == Integer.TYPE &&
            types[2] == Integer.TYPE &&
            types[3].name == "$CLIPBOARD_SERVICE\$Clipboard" &&
            types[3].declaringClass == method.declaringClass &&
            types[4] == Integer.TYPE
    }

    private val suppressTargetReadNotice = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.size != 5 || param.args[0] != TARGET_PACKAGE) return
            val uid = param.args[1] as? Int ?: return
            val packageOwnedByUid = runCatching {
                val appOps = XposedHelpers.getObjectField(param.thisObject, "mAppOps") as? AppOpsManager
                    ?: return@runCatching false
                appOps.checkPackage(uid, TARGET_PACKAGE)
                true
            }.getOrDefault(false)
            if (packageOwnedByUid) param.result = null
        }
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
        const val READ_NOTIFICATION_METHOD = "showAccessNotificationLocked"
        const val TARGET_PACKAGE = "belphegor.app"
        const val TAG = "belphegor-bg"
        val GUARD_METHODS = arrayOf("clipboardAccessAllowed")
    }
}
