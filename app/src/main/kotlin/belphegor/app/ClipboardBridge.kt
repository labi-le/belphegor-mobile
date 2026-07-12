package belphegor.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import belphegor.mobile.Handler
import belphegor.mobile.Node
import java.io.File

/**
 * Two-way bridge between the Android clipboard and the belphegor core.
 *
 *  - Phone copies something  -> onPrimaryClipChanged reads it, node.pushClipboard(...)
 *  - Peer sends clipboard     -> Handler.handleClipboard(...) writes it locally
 *
 * NOTE: on Android 10+ both reading (the listener firing) and writing
 * (setPrimaryClip) only work while the app is focused OR when the framework
 * clipboard guard is neutralised for us — see the :background-clipboard LSPosed
 * module. Without it, sync degrades to "works while the app is open".
 */
class ClipboardBridge(
    private val context: Context,
    private val cm: ClipboardManager,
) {
    /** Set once the node is up so the listener can push local copies. */
    @Volatile
    var node: Node? = null

    /** Sink for remote payloads. Registered with Mobile.start(cfg, handler). */
    val handler: Handler = Handler { mimeType, data -> writeLocal(mimeType, data) }

    /** Fires on every local clipboard change; forwards it to the mesh. */
    val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val n = node ?: return@OnPrimaryClipChangedListener
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

        val mime = clip.description?.getMimeType(0) ?: ""
        val item = clip.getItemAt(0)
        val bytes: ByteArray? = when {
            item.text != null -> item.text.toString().toByteArray(Charsets.UTF_8)
            item.uri != null -> runCatching {
                context.contentResolver.openInputStream(item.uri!!)?.use { it.readBytes() }
            }.getOrNull()
            else -> null
        }
        if (bytes != null && bytes.isNotEmpty()) {
            n.pushClipboard(mime, bytes)
        }
    }

    private fun writeLocal(mimeType: String, data: ByteArray) {
        val clip = when (mimeType) {
            "image" -> imageClip(data) ?: return
            else -> ClipData.newPlainText("belphegor", String(data, Charsets.UTF_8))
        }
        runCatching { cm.setPrimaryClip(clip) }
            .onFailure { Log.w(TAG, "setPrimaryClip failed (needs foreground or unlock module)", it) }
    }

    /**
     * Build a clip for a received image. Bytes are staged in the app's private
     * cache and exposed through the app FileProvider, so the image is never
     * published to the shared MediaStore / gallery where any app could read it.
     * The clipboard framework grants the pasting app temporary read access to
     * the content URI. Older cached images are pruned so only the current one
     * remains.
     */
    private fun imageClip(data: ByteArray): ClipData? = runCatching {
        val dir = File(context.cacheDir, "clip-images").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val f = File(dir, "clip-${System.currentTimeMillis()}.png").apply { writeBytes(data) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        ClipData.newUri(context.contentResolver, "belphegor", uri)
    }.getOrNull()

    fun register() = cm.addPrimaryClipChangedListener(listener)
    fun unregister() = cm.removePrimaryClipChangedListener(listener)

    private companion object {
        const val TAG = "ClipboardBridge"
    }
}
