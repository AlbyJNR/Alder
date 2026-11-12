package dev.alder

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Sources fetched ahead of Play. Resting on a title on Home, or opening its page, asks the stream add-ons for its
 * sources in the background; the sources page and the preview then find them here and start at once instead of
 * waiting on every add-on. Kept for a few minutes only (debrid links are made for the moment), one fetch per title
 * at a time, in memory only.
 */
object Sources {
    private class Entry(val at: Long, val answers: Map<String, Result<Addon.Streams>>) // add-on id → its answer

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2) // ahead-of-time work stays light beside Home's own fetches

    private fun key(authKey: String, type: String, videoId: String) = "${authKey.hashCode()}/$type/$videoId"

    /** What [addon] answered for this video lately, or null to ask it now. */
    fun saved(authKey: String, type: String, videoId: String, addon: Addon): Result<Addon.Streams>? =
        cache[key(authKey, type, videoId)]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.answers?.get(addon.id)

    /** Keeps one add-on's fresh answer next to the others already kept for this video. */
    fun keep(authKey: String, type: String, videoId: String, addon: Addon, answer: Result<Addon.Streams>) {
        val k = key(authKey, type, videoId)
        val old = cache[k]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.answers.orEmpty()
        cache[k] = Entry(System.currentTimeMillis(), old + (addon.id to answer))
    }

    /**
     * Every add-on's answer for this video, kept ones first and the rest fetched together. Blocks: run it off the
     * main thread. Add-ons that fail answer with their failure, so a caller can say why a list is empty.
     */
    fun all(c: Context, authKey: String, type: String, videoId: String): Map<String, Result<Addon.Streams>> {
        val addons = streamAddons(c, authKey, type, videoId)
        val missing = addons.filter { saved(authKey, type, videoId, it) == null }
        missing.parallelStream().forEach { a -> keep(authKey, type, videoId, a, runCatching { a.streams(type, videoId) }) }
        return addons.associate { a -> a.id to (saved(authKey, type, videoId, a) ?: Result.failure(IllegalStateException("no answer"))) }
    }

    /**
     * The answers once the fetch ahead for this video has finished: waits for it (to [AWAIT_MS]) and reads what it
     * kept, so asking costs no second round of requests. Blocks: its own thread, never the main one.
     */
    fun awaited(c: Context, authKey: String, type: String, videoId: String): Map<String, Result<Addon.Streams>>? {
        val k = key(authKey, type, videoId)
        val until = System.currentTimeMillis() + AWAIT_MS
        while (k in inFlight && System.currentTimeMillis() < until) Thread.sleep(250)
        if (k in inFlight || cache[k]?.let { System.currentTimeMillis() - it.at < TTL } != true) return null
        return all(c, authKey, type, videoId)
    }

    private const val AWAIT_MS = 40_000L

    /** Fetches ahead, once, in the background; nothing happens when the sources are already kept or being fetched. */
    fun prefetch(c: Context, authKey: String, type: String, videoId: String) {
        val k = key(authKey, type, videoId)
        if (cache[k]?.let { System.currentTimeMillis() - it.at < TTL } == true || !inFlight.add(k)) return
        val app = c.applicationContext
        pool.execute {
            try { all(app, authKey, type, videoId) }
            catch (e: Throwable) { android.util.Log.w("CAS", "sources ahead for $videoId failed: $e") }
            finally { inFlight.remove(k) }
        }
    }

    private const val TTL = 8 * 60_000L
}
