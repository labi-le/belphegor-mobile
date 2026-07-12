package belphegor.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update via GitHub Releases. Pure logic, no Android views: fetch the
 * latest release, compare its tag to the installed versionName, resolve the APK
 * asset for this device's ABI, and download it.
 *
 * Unauthenticated: it reads the public releases API and downloads the asset over
 * browser_download_url, so no token is needed — the releases repo just has to be
 * public (GitHub ties release visibility to repo visibility). While the repo is
 * private the API 404s and the check quietly finds nothing.
 */
object Updater {
    const val OWNER = "labi-le"
    const val REPO = "belphegor-mobile"

    private const val LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val UA = "belphegor-android"
    private const val TIMEOUT_MS = 15_000
    private const val BUF = 64 * 1024

    data class Update(
        val version: String,
        val notes: String,
        val assetName: String,
        val downloadUrl: String,
    )

    /** Installed versionName, read via PackageManager (no BuildConfig needed). */
    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull()?.trim().orEmpty()

    /**
     * success(Update) — a newer release with an APK for this ABI exists;
     * success(null) — up to date, or no matching asset;
     * failure — network / parse error (a still-private repo 404s here).
     */
    fun check(ctx: Context): Result<Update?> = runCatching {
        val rel = JSONObject(get(LATEST))
        val version = rel.optString("tag_name").ifEmpty { rel.optString("name") }.removePrefix("v").trim()
        if (version.isEmpty() || compare(version, currentVersion(ctx)) <= 0) return@runCatching null
        val asset = pickAsset(rel.optJSONArray("assets")) ?: return@runCatching null
        Update(
            version = version,
            notes = rel.optString("body").trim(),
            assetName = asset.optString("name"),
            downloadUrl = asset.optString("browser_download_url"),
        )
    }

    /** Download the update APK into cacheDir/apks/, reporting 0..100 progress. */
    fun download(ctx: Context, u: Update, onProgress: (Int) -> Unit): File {
        val out = File(File(ctx.cacheDir, "apks").apply { mkdirs() }, "update.apk")
        val c = open(u.downloadUrl)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(BUF)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    /** The APK asset for this device's ABI, excluding the LSPosed module. */
    private fun pickAsset(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        val wantX86 = Build.SUPPORTED_ABIS.any { it == "x86_64" }
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name")
            if (!n.endsWith(".apk") || n.contains("background-clipboard")) continue
            if (n.contains("x86_64") == wantX86) return a
        }
        return null
    }

    /** Numeric dotted-version compare: >0 if a is newer than b, <0 older, 0 equal. */
    fun compare(a: String, b: String): Int {
        val pa = numbers(a)
        val pb = numbers(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun numbers(v: String): List<Int> =
        v.split('.', '-', '+', '_').mapNotNull { p -> p.takeWhile(Char::isDigit).toIntOrNull() }

    private fun get(url: String): String {
        val c = open(url).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (c.responseCode !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("HTTP ${c.responseCode}${if (body.isNotEmpty()) ": ${body.take(200)}" else ""}")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // browser_download_url 302-redirects to cloud storage; with no auth header to
    // leak, we just let HttpURLConnection follow the redirect.
    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
        }
}
