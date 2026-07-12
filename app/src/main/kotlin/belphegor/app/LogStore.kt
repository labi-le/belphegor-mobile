package belphegor.app

import android.os.Handler
import android.os.Looper

/**
 * Process-wide ring buffer of belphegor log lines. The service feeds it (via the
 * gomobile LogSink) and MainActivity renders it in the in-app log panel.
 */
object LogStore {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Called (on the main thread) whenever the buffer changes. */
    @Volatile
    var onChange: (() -> Unit)? = null

    @Synchronized
    fun add(chunk: String) {
        for (raw in chunk.split('\n')) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        onChange?.let { main.post(it) }
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        onChange?.let { main.post(it) }
    }
}
