package belphegor.background

import android.os.Binder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module that neutralises the Android 10+ background-clipboard guard,
 * but ONLY for the belphegor app. Runs inside system_server (package
 * "android").
 *
 * The guard (historically ClipboardService.clipboardAccessAllowed) decides
 * whether a caller may touch the clipboard based on focus / default-IME. Its
 * parameter list changed across Android versions, so instead of pinning one
 * signature we hook every overload by name and force-allow when the calling
 * package argument is ours. If a future Android renames the method, add the new
 * name to GUARD_METHODS.
 */
class BackgroundClipboard : IXposedHookLoadPackage {

    @Volatile
    private var cachedAppId = INVALID

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ANDROID) return

        val clazz = XposedHelpers.findClassIfExists(CLIPBOARD_SERVICE, lpparam.classLoader) ?: run {
            XposedBridge.log("belphegor-background: $CLIPBOARD_SERVICE not found")
            return
        }

        var hooked = 0
        for (name in GUARD_METHODS) {
            hooked += XposedBridge.hookAllMethods(clazz, name, allowForTarget).size
        }
        XposedBridge.log("belphegor-background: hooked $hooked clipboard guard method(s)")
    }

    // Force-allow the clipboard guard ONLY when the REAL binder caller is the
    // belphegor app (matched by appId). Matching on a String argument alone let
    // ANY local app pass callingPackage="belphegor.app" and have system_server
    // force-allow its own background clipboard access -- a device-wide privacy
    // bypass. Fails closed: if belphegor's uid can't be resolved we never override.
    private val allowForTarget = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val appId = belphegorAppId()
            if (appId != INVALID && Binder.getCallingUid() % PER_USER_RANGE == appId) {
                param.result = true
            }
        }
    }

    /** belphegor's appId (uid within a user), resolved once from system_server. */
    private fun belphegorAppId(): Int {
        if (cachedAppId != INVALID) return cachedAppId
        cachedAppId = try {
            val at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread",
            )
            val ctx = XposedHelpers.callMethod(at, "getSystemContext") as android.content.Context
            ctx.packageManager.getPackageUid(TARGET_PACKAGE, 0) % PER_USER_RANGE
        } catch (t: Throwable) {
            XposedBridge.log("belphegor-background: uid resolve failed: $t")
            INVALID
        }
        return cachedAppId
    }

    private companion object {
        const val ANDROID = "android"
        const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        const val TARGET_PACKAGE = "belphegor.app"
        const val PER_USER_RANGE = 100000
        const val INVALID = -1
        val GUARD_METHODS = arrayOf("clipboardAccessAllowed")
    }
}
