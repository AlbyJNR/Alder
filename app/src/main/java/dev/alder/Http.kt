package dev.alder

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Plain HTTP for Stremio's API, addons and images, on OkHttp: one pool of open connections shared by every thread
 * (Android's own client kept five per process), HTTP/2 where a host speaks it (one connection carries a title page's
 * burst of add-on requests side by side), and a disk cache for pictures. Every call blocks, so run them off the main thread.
 */
object Http {
    private const val DEFAULT_TIMEOUT = 20_000
    private const val PICTURE_CACHE = 250L * 1024 * 1024

    @Volatile private var client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES)) // pictures load many at a time from a few hosts
        .build()

    /** Set once at start: the disk cache for pictures lives under [cacheDir]. */
    fun init(cacheDir: File) {
        if (client.cache != null) return
        client = client.newBuilder().cache(Cache(File(cacheDir, "okhttp"), PICTURE_CACHE)).build()
        // The cache the platform client kept before 1.17.0: its pictures are fetched again once, into the new one.
        Thread({ runCatching { File(cacheDir, "http").deleteRecursively() } }, "old-cache").apply { isDaemon = true }.start()
    }

    /**
     * Answers kept on disk (title details, catalogs, the library, the addon list), so a screen can draw from last time
     * at once and refresh behind it, as Netflix and NuvioTV do. Set once at start; null keeps nothing.
     */
    var saved: File? = null
        set(dir) {
            field = dir
            dir?.mkdirs()
            // Off the main thread: listing and stat-ing hundreds of saved answers on TV flash stalled the first screen.
            if (dir != null) Thread({ runCatching { trim() } }, "answers-trim").apply { isDaemon = true }.start()
        }

    /** Set once at start with the app's version. */
    var userAgent = "CAS"

    fun get(url: String, keep: Boolean = false, timeoutMs: Int = 20_000): String = text(url, null, keep, timeoutMs)

    fun post(url: String, json: String, keep: Boolean = false, timeoutMs: Int = 20_000, headers: Map<String, String> = emptyMap()): String =
        text(url, json, keep, timeoutMs, headers)

    fun bytes(url: String): ByteArray = request(url, null, cache = true).second

    private fun text(url: String, json: String?, keep: Boolean, timeoutMs: Int = 20_000, headers: Map<String, String> = emptyMap()): String {
        val (code, body) = request(url, json, cache = false, timeoutMs, headers)
        val text = String(body, Charsets.UTF_8)
        // Stremio's API answers errors as JSON (read by the caller); anything else failed is an error page: say the code.
        if (code >= 400 && !text.trimStart().startsWith("{")) throw IOException("HTTP $code")
        return text.also { if (keep && code < 400) keep(url, json, it) }
    }

    /** The last answer kept for this request, or null. Never touches the network. */
    fun savedAnswer(url: String, json: String? = null): String? =
        file(url, json)?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** Keeps [body] as the answer to this request, for [savedAnswer] next time. */
    fun keep(url: String, json: String?, body: String) {
        val f = file(url, json) ?: return
        runCatching { File(f.path + ".tmp").apply { writeText(body) }.renameTo(f) }
    }

    /**
     * Forgets every kept answer. Used when a profile is removed: its library, addon list and personal addons' answers
     * (their URLs carry tokens) aren't told apart by name, so all go, and the other profiles fetch theirs again once.
     */
    fun forget() {
        saved?.listFiles()?.forEach { it.delete() }
    }

    // Named by a hash of the request: urls carry addon settings and the body an auth key, so neither is a file name.
    private fun file(url: String, json: String?): File? {
        val dir = saved ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest((url + "\n" + (json ?: "")).toByteArray())
        return File(dir, hash.joinToString("") { "%02x".format(it) })
    }

    // ponytail: one pass at start, oldest first, down to 60 MB; an index would only matter for far bigger caches.
    private fun trim() {
        val files = saved?.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 60L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    private val jsonType = "application/json".toMediaType()

    // [cache]: pictures only. A response cache once kept an addon's 404 and, revalidated with a 304, served it forever
    // (AIOMetadata's movie search); answers are never stored here and have their own copy in [saved].
    private fun request(url: String, json: String?, cache: Boolean, timeoutMs: Int = DEFAULT_TIMEOUT, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val request = Request.Builder().url(url)
            // Not Android's "Dalvik/2.1.0": some add-ons behind Cloudflare answer that with a challenge page.
            .header("User-Agent", userAgent)
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                if (!cache) cacheControl(CacheControl.Builder().noStore().build())
                if (json != null) post(json.toRequestBody(jsonType))
            }.build()
        // A longer or shorter wait shares the pool and cache; only the timeouts differ.
        val with = if (timeoutMs == DEFAULT_TIMEOUT) client else client.newBuilder()
            .connectTimeout(minOf(10_000, timeoutMs).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        // Stremio's API puts its JSON error in the body of a failed response too, so the body is read either way.
        return with.newCall(request).execute().use { r -> r.code to (r.body?.bytes() ?: throw IOException("HTTP ${r.code}")) }
    }
}
