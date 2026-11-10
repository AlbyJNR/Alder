package dev.alder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Posters and backgrounds: downloaded off the main thread, scaled down to the size shown, kept in memory. */
/** What share of the poster store and of the art store stays at this onTrimMemory [level]; null: nothing to give up. */
internal fun trimShares(level: Int): Pair<Float, Float>? = when {
    level >= 40 -> 0f to 0f // TRIM_MEMORY_BACKGROUND and worse: next to go
    level >= 20 -> 0.5f to 0f // TRIM_MEMORY_UI_HIDDEN: nothing of ours is on the screen
    level >= 10 -> 0.5f to 0.5f // TRIM_MEMORY_RUNNING_LOW, RUNNING_CRITICAL: on the screen, the device short
    else -> null
}

object Images {
    // Hardware bitmaps live in graphics memory, not the Java heap; the heap limit (192 MB on the test TV) only scales
    // the budget to the device, so switching pages shows them from memory. Full-screen art (8 MB a picture at 1080p) has a store of its own: sharing one, a few arts pushed out every poster,
    // so a page seen a moment ago drew blank tiles again. Posters: a quarter (48 MB here, about three pages); art: a
    // sixth (32 MB, the art on screen, the one fading out and the neighbours fetched ahead).
    private fun store(budget: Long) = object : LruCache<String, Bitmap>(budget.toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val cards = store(Runtime.getRuntime().maxMemory() / 4)
    private val screens = store(Runtime.getRuntime().maxMemory() / 6)
    private fun cache(widthPx: Int) = if (widthPx > 1000) screens else cards

    /**
     * Android asks for memory back ([level] is onTrimMemory's): in use but short, both stores give up half; with CAS
     * off the screen, the full-screen art goes and the posters halve; in the background and under pressure, all of
     * it goes. Pictures come back from the disk cache when they are next shown. Held to nothing, a 1 GB Fire TV
     * Stick ended CAS in the background instead.
     */
    fun trim(level: Int) {
        val (posters, art) = trimShares(level) ?: return
        cards.trimToSize((cards.maxSize() * posters).toInt())
        screens.trimToSize((screens.maxSize() * art).toInt())
    }
    // Home asks only for posters near the screen and lets far ones go, so this pool and cache stay small.
    private val pool = newestFirst(8)
    // The picture on screen right now (the full-screen art, a logo) skips the queue of cards loading behind it.
    private val urgentPool = newestFirst(2)
    // Art fetched ahead for where the remote may go next never holds up the cards on screen.
    private val aheadPool = newestFirst(2)
    private val tmdb = Regex("""(image\.tmdb\.org/t/p/)(original|w1280)/""")
    private val metahub = Regex("""(images\.metahub\.space/(?:poster|background)/)(small|medium)/""")

    /**
     * The latest request first: after racing along a row, the cards the remote stopped on load before the ones it
     * passed (which mostly turn out stale and are skipped).
     */
    fun newestFirst(threads: Int) = ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
        object : LinkedBlockingDeque<Runnable>() { override fun offer(e: Runnable) = offerFirst(e) },
        // Decoding is background work: the four slow cores go to the main thread and RenderThread first.
        { r -> Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() } })
    private val main = Handler(Looper.getMainLooper())

    // Logos straight from MetaHub (Stremio's image server) by IMDb id, so a card's logo doesn't wait for its details.
    fun logoFor(id: String) = id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/logo/medium/${it.substringBefore(':')}/img" }
    /** A poster from MetaHub by IMDb id: the fallback when an add-on's own poster host (rated posters, a rate-limited CDN) won't answer. */
    fun posterFor(id: String) = id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/${it.substringBefore(':')}/img" }

    /**
     * Shows [url] in [into]; [done] runs once it's showing (right away when cached), for fading it in. When [url] is
     * missing or won't load, [orElse] is shown instead (an episode without a working still gets the series' art), or
     * else [failed] runs.
     */
    fun load(
        url: String?, into: ImageView, widthPx: Int, orElse: String? = null, urgent: Boolean = false, look: Look? = null,
        failed: (() -> Unit)? = null, done: (() -> Unit)? = null,
    ) {
        if (url == null) {
            if (orElse != null) load(orElse, into, widthPx, look = look, failed = failed, done = done)
            else { into.tag = null; into.setImageDrawable(null); failed?.invoke() }
            return
        }
        into.tag = url
        into.setImageDrawable(null)
        val key = key(url, widthPx, look)
        cache(widthPx).get(key)?.let { into.setImageBitmap(it); done?.invoke(); return }
        (if (urgent) urgentPool else pool).execute {
            if (into.tag != url) return@execute // the view moved on (scrolled away, another title) while this waited
            val bitmap = bitmap(url, widthPx, look)
            if (bitmap == null) {
                main.post { if (into.tag == url) { if (orElse != null) load(orElse, into, widthPx, look = look, failed = failed, done = done) else failed?.invoke() } }
                return@execute
            }
            main.post { if (into.tag == url) { into.setImageBitmap(bitmap); done?.invoke() } }
        }
    }

    /**
     * Fetches [url] into the cache ahead of time, so the next card's art is ready the moment it's focused. [urgent]
     * for the picture about to fill the screen (Home's first title at launch).
     */
    fun prefetch(url: String?, widthPx: Int, urgent: Boolean = false, look: Look? = null) {
        if (cache(widthPx).get(key(url ?: return, widthPx, look)) != null) return
        (if (urgent) urgentPool else aheadPool).execute { bitmap(url, widthPx, look) }
    }

    /**
     * Something painted into a picture once, as it's decoded, instead of drawn over it every frame: [paint] gets the
     * picture as decoded (software, [widthPx] wide) and returns what to show. [name] keeps its copies apart from plain ones.
     */
    class Look(val name: String, val paint: (Bitmap) -> Bitmap)

    /** An episode still with nothing to give away: shrunk to a few dozen pixels and blurred, then stretched smooth. */
    val blurred = Look("blur") { frost(it, 28) }

    /** A title's logo cropped to its ink: the clear margins some carry made one sit low and another to the right. */
    val trimmed = Look("trim") { trimClear(it) }

    private fun trimClear(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var top = h; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) for (x in 0 until w) if (px[y * w + x] ushr 24 > 24) {
            if (y < top) top = y
            if (y > bottom) bottom = y
            if (x < left) left = x
            if (x > right) right = x
        }
        if (bottom < 0 || (top == 0 && left == 0 && bottom == h - 1 && right == w - 1)) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1).also { if (it !== src) src.recycle() }
    }

    private val inFlight = ConcurrentHashMap<String, FutureTask<Bitmap?>>()

    /**
     * The picture at this size: from memory, or downloaded and decoded once however many ask at the same time (a
     * prefetch and then the view it was for wait on the same work instead of doing it twice).
     */
    private fun bitmap(url: String, widthPx: Int, look: Look? = null): Bitmap? {
        val key = key(url, widthPx, look)
        cache(widthPx).get(key)?.let { return it }
        val mine = FutureTask {
            runCatching {
                val bytes = Http.bytes(sized(url, widthPx))
                if (look == null) decode(bytes, widthPx) else decode(bytes, widthPx, Bitmap.Config.ARGB_8888)?.let(look.paint)
            }.getOrNull()?.also { cache(widthPx).put(key, it) }
        }
        val task = inFlight.putIfAbsent(key, mine) ?: mine
        if (task === mine) try { mine.run() } finally { inFlight.remove(key) }
        return runCatching { task.get() }.getOrNull()
    }

    fun cached(url: String, widthPx: Int, look: Look? = null) = cache(widthPx).get(key(url, widthPx, look)) != null

    /**
     * [src] frosted for glass, as Plume does it: shrunk to [w] px wide and box-blurred twice. Works on every Android
     * version (RenderEffect needs 12), costs one pass per picture instead of a GPU blur every frame, and the tiny
     * result scales up smooth. Call off the main thread.
     */
    fun frost(src: Bitmap, w: Int = 120): Bitmap {
        val h = maxOf(1, w * src.height / src.width)
        // Hardware bitmaps can't be read back; shrink in two steps, or the result gets grainy.
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val mid = Bitmap.createScaledBitmap(soft, maxOf(w, soft.width / 4), maxOf(h, soft.height / 4), true)
        if (soft !== src && soft !== mid) soft.recycle()
        val small = Bitmap.createScaledBitmap(mid, w, h, true)
        if (mid !== small) mid.recycle()
        var a = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        var b = IntArray(a.size)
        val r = 2
        repeat(2) {
            for (horizontal in listOf(true, false)) {
                val n = if (horizontal) w else h
                val lines = if (horizontal) h else w
                val step = if (horizontal) 1 else w
                val lineStep = if (horizontal) w else 1
                for (l in 0 until lines) for (i in 0 until n) {
                    var red = 0; var green = 0; var blue = 0
                    for (k in -r..r) {
                        val p = a[l * lineStep + (i + k).coerceIn(0, n - 1) * step]
                        red += p shr 16 and 255; green += p shr 8 and 255; blue += p and 255
                    }
                    val c = 2 * r + 1
                    b[l * lineStep + i * step] = (255 shl 24) or (red / c shl 16) or (green / c shl 8) or (blue / c)
                }
                val t = a; a = b; b = t
            }
        }
        val out = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true).also { small.recycle() }
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * MetaHub (Stremio's image server) serves small, medium and large copies, and Stremio's catalogs and library point
     * at the small poster (about 150 px) and medium background (about 1000 px): soft on a TV once stretched. The large
     * copy is asked for instead, and the decode below scales it to the size shown.
     */
    fun sharp(url: String) = url.replace(metahub, "$1large/")

    /** The 16:9 art MetaHub keeps for an IMDb title, for places that have no art of their own (Watch Next). */
    fun backgroundFor(id: String) = id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/background/large/${it.substringBefore(':')}/img" }

    // TMDB serves every picture in a few widths: a card needs the 780 px copy, not the 4K original (a tenth of the bytes).
    fun sized(url: String, widthPx: Int): String {
        val size = if (widthPx <= 780) "w780" else if (widthPx <= 1280) "w1280" else return sharp(url)
        return sharp(url).replace(tmdb, "$1$size/")
    }

    // One copy per size: a card's small copy must never stand in for the full-screen art (it would be blurry).
    private fun key(url: String, widthPx: Int, look: Look? = null) = "$url#$widthPx" + (look?.name?.let { "#$it" } ?: "")

    private fun decode(bytes: ByteArray, widthPx: Int, config: Bitmap.Config = Bitmap.Config.HARDWARE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        // Then scaled to exactly the width shown: a card's poster halved from 780 px was still 390 px for a 248 px card,
        // two and a half times the memory.
        val sampled = bounds.outWidth / sample
        // Decoded straight into GPU memory, here in the background. Otherwise the upload happens in the frame that first
        // shows the picture, and that frame drops: the only jank left when moving along a row.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
            if (sampled > widthPx) { inScaled = true; inDensity = sampled; inTargetDensity = widthPx }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
