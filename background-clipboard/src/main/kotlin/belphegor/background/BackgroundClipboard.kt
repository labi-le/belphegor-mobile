package belphegor.background

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

    private val allowForTarget = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // Any String arg equal to our package => this call is on our behalf.
            if (param.args.any { it == TARGET_PACKAGE }) {
                param.result = true
            }
        }
    }

    private companion object {
        const val ANDROID = "android"
        const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        const val TARGET_PACKAGE = "belphegor.app"
        val GUARD_METHODS = arrayOf("clipboardAccessAllowed")
    }
}
