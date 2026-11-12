package dev.alder

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import org.json.JSONArray
import java.util.Locale

// Finds the sources for one video. Normally it picks one and hands the player a short list to fall back on, so Play
// is one click; with "pick" (held OK on Play, or after three sources failed) it lists them all.
/** The add-ons that give streams for [videoId]: a lender's shared ones first, then the profile's own. Blocks. */
internal fun streamAddons(c: Context, key: String, type: String, videoId: String): List<Addon> {
    val own = Stremio.addons(key).filter { it.serves("stream", type, videoId) }
    val household = Profiles.streamLenders(c).flatMap { lender ->
        runCatching {
            (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey))
                .filter { it.shareable && it.serves("stream", type, videoId) }
        }.getOrDefault(emptyList())
    }.distinctBy { it.id }
    return household + own.filterNot { mine -> household.any { it.id == mine.id } }
}

/** Settings' limits on a source: height, size, HDR, and never a cinema recording. [maxHeight] when the player asks for less. */
internal fun wantedSource(c: Context, s: Stream, maxHeight: Int = 0): Boolean {
    val limit = maxHeight.takeIf { it > 0 } ?: Settings.maxHeight(c)
    val maxBytes = Settings.maxSizeGb(c) * 1_073_741_824L
    return (limit == 0 || s.height == 0 || s.height <= limit) && (maxBytes == 0L || s.size == 0L || s.size <= maxBytes) &&
        (Settings.hdr(c) || !s.isHdr) && !s.cam
}

/** Sources best first, as Play orders them: the file or release group that played last, then fit for this TV, then Settings' tastes. */
internal fun rankSources(c: Context, streams: List<Stream>, videoId: String, metaId: String?, maxHeight: Int = 0): List<Stream> {
    val prefs = c.getSharedPreferences("sources", Context.MODE_PRIVATE)
    val file = prefs.getString("file:$videoId", null)
    val binge = prefs.getString("binge:$metaId", null)
    val tv = Device.of(c)
    val prefer = sourcePreference(c)
    val lower = maxHeight > 0 // the player asked for less: the tallest that's left
    return streams.sortedWith(compareByDescending<Stream> { if (lower) it.height else 0 }
        .thenByDescending { file != null && it.filename == file }
        .thenByDescending { binge != null && it.bingeGroup == binge }
        .thenBy { it.awkward }
        .thenBy { it.dolbyVisionOnly && !tv.dolbyVision } // may show no picture (or purple and green) here: last
        .thenByDescending { it.fit(tv) }
        .thenByDescending(prefer))
}

/** Settings' stream preferences as a score, higher first. At "No preference" with TV speakers, every source scores 0. */
internal fun sourcePreference(c: Context): (Stream) -> Int {
    val release = Settings.release(c)
    val hdr = Settings.hdrPref(c)
    val dub = Settings.dub(c)
    val language = Settings.audioLanguage(c)
    val receiver = Settings.passthrough(c)
    return { s ->
        when (release) {
            "remux" -> if (s.remux) 3 else if (s.bluray) 1 else 0
            "encode" -> if (s.remux) -2 else if (s.bluray || s.web) 2 else 0
            "web" -> if (s.web && !s.remux) 2 else if (s.remux) -2 else 0
            else -> 0
        } + when (hdr) {
            "dv" -> if (s.dolbyVision) 2 else if (s.hdr) 1 else 0
            "hdr10" -> if (s.hdr) 2 else 0
            else -> 0
        } + when (dub) {
            "original" -> if (s.dubbed) -2 else 0
            "mine" -> if (s.mentions(language)) 2 else if (s.multiAudio) 1 else 0
            else -> 0
        } + if (receiver && s.bigAudio) 1 else 0 // a receiver gets Atmos and lossless tracks; TV speakers don't
    }
}

/** Settings' tastes in a source, read once for a list: what [sourceReasons] weighs a source against. */
internal class Tastes(val limit: Int, val maxGb: Int, val hdrOn: Boolean, val release: String, val hdrPref: String,
                      val dub: String, val language: String, val receiver: Boolean) {
    companion object {
        /** As Settings stand; [maxHeight] when the player asked for less than Settings allow. */
        fun of(c: Context, maxHeight: Int = 0) = Tastes(maxHeight.takeIf { it > 0 } ?: Settings.maxHeight(c), Settings.maxSizeGb(c),
            Settings.hdr(c), Settings.release(c), Settings.hdrPref(c), Settings.dub(c), Settings.audioLanguage(c), Settings.passthrough(c))
    }
}

/**
 * Why a source stands where it does, in a few words each, in the order the ranking weighs them: what set it aside (a
 * limit in Settings, a cinema recording), what lifted it (played last time, the release the last episode used, cached
 * on the debrid service), what holds it back on this TV, and the tastes in Settings it meets or misses. Empty when
 * nothing tells it apart from the add-on's own order. Pure, so the tests can read it.
 */
internal fun sourceReasons(s: Stream, tv: Device, t: Tastes, playedLast: Boolean, sameRelease: Boolean): List<String> {
    val out = mutableListOf<String>()
    if (t.limit > 0 && s.height > t.limit) out += "above your ${t.limit}p limit"
    if (t.maxGb > 0 && s.size > t.maxGb * 1_073_741_824L) out += "over your ${t.maxGb} GB limit"
    if (!t.hdrOn && s.isHdr) out += "HDR is off in Settings"
    if (s.cam) out += "cinema recording"
    if (playedLast) out += "played last time"
    if (sameRelease) out += "the release the last episode used"
    s.debrid?.let { out += if (it.cached == true) "cached on ${it.name}" else "through ${it.name}" }
    if ("3D" in s.tags) out += "3D"
    if ("Hard subs" in s.tags) out += "subtitles burnt in"
    if (s.dolbyVisionOnly && !tv.dolbyVision) out += "Dolby Vision this TV may not show"
    if (s.height > tv.height) out += "above this TV's ${tv.height}p"
    if (s.isHdr && !tv.hdr) out += "HDR on a screen without it"
    when (t.release) {
        "remux" -> if (s.remux) out += "a remux, as you prefer" else if (s.bluray) out += "a BluRay encode"
        "encode" -> if (s.remux) out += "a remux (you prefer encodes)" else if (s.bluray || s.web) out += "an encode, as you prefer"
        "web" -> if (s.web && !s.remux) out += "a WEB release, as you prefer" else if (s.remux) out += "a remux (you prefer WEB)"
    }
    when (t.hdrPref) {
        "dv" -> if (s.dolbyVision) out += "Dolby Vision, as you prefer" else if (s.hdr) out += "HDR"
        "hdr10" -> if (s.hdr) out += "HDR, as you prefer"
    }
    when (t.dub) {
        "original" -> if (s.dubbed) out += "dubbed (you prefer the original)"
        "mine" -> if (s.mentions(t.language)) out += "in your language" else if (s.multiAudio) out += "more than one language"
    }
    if (t.receiver && s.bigAudio) out += "lossless sound for your receiver"
    return out
}

internal fun readyStreams(answers: Array<List<Stream>?>, allowGaps: Boolean): List<Stream> =
    (if (allowGaps) answers.asList() else answers.takeWhile { it != null }).flatMap { it.orEmpty() }

class StreamsActivity : Screen() {
    private val pick by lazy { intent.getBooleanExtra("pick", false) || Settings.alwaysList(this) }

    private lateinit var art: ImageView
    override val backdrop get() = art
    private val room by lazy { intent.getIntExtra("tint", BASE) } // the title page's colour, carried on to the player
    // Audio language chips above the list: All, then each language the sources name. A pick lasts while the page is open.
    private lateinit var languageStrip: HorizontalScrollView
    private var audio: String? = null
    private var audioOpened = false // the first list opens on the profile's language (Dubbed releases: My language first)
    // The help line under the list: the preset until a source is under the remote, then why that source stands where it does.
    private lateinit var whyLine: android.widget.TextView
    private var helpDefault: CharSequence = ""
    private val tastes by lazy { Tastes.of(this, intent.getIntExtra("maxHeight", 0)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val type = intent.getStringExtra("type") ?: "movie"
        val videoId = intent.getStringExtra("videoId")!!
        val name = intent.getStringExtra("name") ?: ""
        val episode = intent.getStringExtra("episode") ?: intent.getStringExtra("label")?.removePrefix(name)?.trim(' ', '·')
        val where = episode?.split("·")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        val fallback = text(name, SERIF_NAME, serif = true).onArt().apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val brand = FrameLayout(this).apply {
            addView(fallback, FrameLayout.LayoutParams(-1, -1))
            addView(logo, FrameLayout.LayoutParams(-1, -1))
        }
        Images.load(intent.getStringExtra("logo") ?: Images.logoFor(intent.getStringExtra("id").orEmpty()),
            logo, dp(200), failed = { logo.visibility = View.GONE }) { fallback.visibility = View.GONE }
        val episodeInfo = column(Gravity.START).apply {
            addView(text(listOfNotNull(where.firstOrNull(), intent.getStringExtra("runtime"))
                .joinToString(DOT), SMALL, bold = true, color = TEXT_2).onArt())
            addView(text(where.drop(1).joinToString(DOT).ifBlank { name }, SERIF_TITLE, serif = true).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, wrap().apply { topMargin = dp(2) })
        }
        val count = text("", SMALL, color = TEXT_3).onArt().apply { gravity = Gravity.END }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brand, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(16) })
            addView(episodeInfo, LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, LinearLayout.LayoutParams(-2, -2))
        }
        val cols = LinearLayout(this).apply {
            visibility = View.GONE // headings over nothing: shown once a row is under them
            setPadding(dp(12), dp(8), dp(12), dp(4))
            addView(caps("Quality", TEXT_3).apply { maxLines = 1 }, LinearLayout.LayoutParams(dp(QUALITY_W), -2))
            addView(caps("Release", TEXT_3), LinearLayout.LayoutParams(0, -2, 1f))
            addView(caps("Size", TEXT_3).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(72), -2))
        }
        languageStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; visibility = View.GONE
        }
        val rows = column(Gravity.START).apply { focusByLight() }
        val status = text(intent.getStringExtra("message") ?: "Finding sources…", BODY, color = TEXT_2).onArt()
        val spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(TEXT)
        }
        rows.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(spinner, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12); topMargin = dp(12) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The row past the panel's foot fades out above the help line instead of being cut by it.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32))
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(rows)
        }
        val help = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.INVISIBLE // "Play this source" waits for a source to be listed
            // The key cap as keyHint draws it, so OK looks the same here as under the trailer box.
            addView(text("OK", BADGE, bold = true, color = BASE).apply {
                background = rounded(TEXT_2, 3); includeFontPadding = false; setPadding(dp(4), dp(1), dp(4), dp(1))
            })
            val preset = presetName()
            // The preset in full-strength bold, as the mock reads it, until a source is under the remote (whyLine).
            helpDefault = emphasised("  Play this source${DOT}Best match follows your stream settings: $preset.", preset)
            whyLine = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; text = helpDefault }
            addView(whyLine)
        }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val still = intent.getStringExtra("still")
        val backArt = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        Images.load(still ?: backArt, art, resources.displayMetrics.widthPixels, orElse = backArt.takeIf { still != null }) {
            art.animate().alpha(1f).setDuration(250).start()
            refrost()
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BASE)
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(withAlpha(room, 0xB3), withAlpha(room, 0x73), withAlpha(room, 0x59)))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = glassOver(this, 14, 0.18f)
                addView(header, LinearLayout.LayoutParams(-1, -2))
                addView(View(context).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6) })
                addView(languageStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                addView(cols, LinearLayout.LayoutParams(-1, -2))
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(help, LinearLayout.LayoutParams(-1, dp(34)))
            }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 62 / 100, -1).apply {
                setMargins(dp(PAD), dp(27), 0, dp(27))
            })
            addView(TextClock(this@StreamsActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = SMALL; setTextColor(TEXT_2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
        })
        val tv = Device.of(this)

        background({ streamAddons(this, key, type, videoId) }) { result ->
            val addons = result.getOrElse { spinner.visibility = View.GONE; status.text = "Couldn't load sources. Check your connection and try again."; return@background }
            if (addons.isEmpty()) { spinner.visibility = View.GONE; status.text = "No stream add-ons are connected. Add one in Stremio to find sources."; return@background }
            val answers = arrayOfNulls<List<Stream>>(addons.size)
            // Why an add-on gave nothing to play, so an empty list can say so instead of blaming the TV.
            val failures = arrayOfNulls<Throwable>(addons.size)
            val torrents = IntArray(addons.size)
            var started = false
            var listed = false
            var waitingForEarlier = false
            var providers = emptyMap<Stream, String>()
            var eligible = emptyList<Stream>()
            var ordered = emptyList<Stream>()
            /** The list in the chosen audio language; Best match is the first within Settings' limits that's in it. */
            fun drawRows(focusList: Boolean) {
                val shown = audio?.let { lang -> ordered.filter { speaks(it, lang) } } ?: ordered
                count.text = "${shown.size} " + if (shown.size == 1) "source" else "sources"
                rows.removeAllViews()
                cols.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
                help.visibility = if (shown.isEmpty()) View.INVISIBLE else View.VISIBLE
                if (shown.isEmpty()) {
                    rows.addView(text("No playable sources found.", BODY).onArt().apply {
                        setPadding(dp(12), dp(12), 0, 0)
                    })
                    val unplayable = answers.sumOf { a -> a.orEmpty().count { it.isVideo && !it.playableOn(tv) } }
                    emptyReasons(addons.map { it.name }, failures, torrents, unplayable).forEach { line ->
                        rows.addView(text(line, SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), dp(8), dp(12), 0) },
                            LinearLayout.LayoutParams(-1, -2))
                    }
                    // No reload of its own: the page opens again and asks the add-ons afresh, not the answers kept from this visit.
                    val retry = pill("Try again").apply { setOnClickListener {
                        startActivity(Intent(intent).putExtra("retry", true).apply { removeExtra("message") })
                        finish()
                    } }
                    rows.addView(retry, wrap().apply { setMargins(dp(12), dp(14), 0, dp(4)) })
                    retry.requestFocus()
                } else {
                    val best = eligible.firstOrNull { it in shown }
                    shown.forEach { stream -> rows.addView(streamRow(stream, providers[stream].orEmpty(), stream === best, whyLine(stream === best, why(stream, tv))),
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }) }
                    if (focusList) rows.getChildAt(0).requestFocus()
                }
            }
            fun showList() {
                listed = true
                val available = addons.flatMapIndexed { i, addon -> answers[i].orEmpty()
                    .filter { it.isVideo && it.playableOn(tv) }.map { it to addon.name } }
                providers = available.toMap()
                val ranked = rank(available.map { it.first }, videoId)
                eligible = ranked.filter(::wanted)
                ordered = eligible + ranked.filterNot(::wanted)
                showLanguages(ordered) { drawRows(focusList = false) }
                drawRows(focusList = true)
            }

            // Keep add-on order when earlier ones answer promptly; after 800 ms, a playable later answer can start.
            fun tryStart(allowGaps: Boolean = false) {
                if (started || pick || listed) return
                val ready = readyStreams(answers, allowGaps)
                val best = rank(ready.filter { it.isVideo && it.playableOn(tv) && wanted(it) }, videoId)
                if (best.isEmpty() && answers.any { it == null }) return
                if (best.isEmpty()) return showList()
                started = true
                // The player says why this one once its picture is up: a word on its place, then what lifted it.
                val first = best.first()
                val note = (listOfNotNull("Best match", "${first.height}p".takeIf { first.height > 0 }) + why(first, tv).take(2)).joinToString(DOT)
                startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", JSONArray(best.take(TRY).map { it.toJson() }).toString()).putExtra("whyNote", note))
                finish()
            }

            fun answered(i: Int, addon: Addon, streams: Result<Addon.Streams>) {
                    streams.exceptionOrNull()?.let { e ->
                        failures[i] = e
                        android.util.Log.w("CAS", "streams from ${addon.name} failed", e)
                    }
                    torrents[i] = streams.getOrNull()?.torrents ?: 0
                    answers[i] = streams.getOrNull()?.playable.orEmpty()
                    if (answers.all { it != null }) {
                        if (pick || listed) showList() else tryStart()
                    } else if (!pick) {
                        tryStart()
                        if (!started && !waitingForEarlier && readyStreams(answers, true).any { it.isVideo && it.playableOn(tv) && wanted(it) }) {
                            waitingForEarlier = true
                            main.postDelayed({ tryStart(allowGaps = true) }, 800)
                        }
                    }
            }
            // Answers fetched ahead (resting on the title on Home, or its page) are in already: no wait on those add-ons.
            addons.forEachIndexed { i, addon ->
                val kept = if (intent.getBooleanExtra("retry", false)) null else Sources.saved(key, type, videoId, addon)
                if (kept != null) main.post { if (!isDestroyed) answered(i, addon, kept) }
                else background({ runCatching { addon.streams(type, videoId) }.also { Sources.keep(key, type, videoId, addon, it) }.getOrThrow() }) { answered(i, addon, it) }
            }
        }
    }

    /**
     * All, then each audio language the sources name, the profile's audio language and English first; hidden when the
     * sources name only one. Sources that name no language count as English, as most English releases name none.
     */
    private fun showLanguages(streams: List<Stream>, redraw: () -> Unit) {
        val unnamed = streams.any { it.audioLanguages.isEmpty() && !it.multiAudio }
        val mine = Settings.audioLanguage(this)
        val codes = (streams.flatMap { it.audioLanguages } + listOfNotNull("en".takeIf { unnamed })).distinct()
            .sortedWith(compareBy({ it != mine }, { it != "en" }, ::languageName))
        if (!audioOpened) {
            audioOpened = true
            if (Settings.dub(this) == "mine" && mine in codes) audio = mine
        }
        if (audio !in codes) audio = null
        languageStrip.removeAllViews()
        if (codes.size < 2) { languageStrip.visibility = View.GONE; audio = null; return }
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(2), dp(2), dp(2), dp(2)) }
        val chips = mutableMapOf<String?, android.widget.TextView>()
        fun label(code: String?) = code?.let(::languageName) ?: "All languages"
        (listOf<String?>(null) + codes).forEach { code ->
            val control = chip(label(code), code == audio).apply {
                setOnClickListener {
                    audio = code
                    chips.forEach { (value, chip) -> chip.select(label(value), value == code) }
                    redraw()
                }
            }
            chips[code] = control
            line.addView(control, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(6) })
        }
        languageStrip.addView(line)
        languageStrip.visibility = View.VISIBLE
    }

    /** In [language] by its names: it names that language, it's multi or dual audio, or (English) it names none. */
    private fun speaks(s: Stream, language: String) =
        language in s.audioLanguages || s.multiAudio || (language == "en" && s.audioLanguages.isEmpty())

    private fun languageName(code: String) =
        Locale(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }.replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /**
     * What went wrong when nothing is playable: add-ons that failed (and how), add-ons that only found torrents (they
     * need a debrid service), and sources in a format this TV can't decode. Plain words, one line each.
     */
    private fun emptyReasons(names: List<String>, failures: Array<Throwable?>, torrents: IntArray, unplayable: Int): List<String> {
        val failed = names.indices.filter { failures[it] != null }.map { "${names[it]} (${reason(failures[it]!!)})" }
        val torrentOnly = names.indices.filter { failures[it] == null && torrents[it] > 0 }.map { names[it] }
        return listOfNotNull(
            failed.takeIf { it.isNotEmpty() }?.let {
                "Couldn't get sources from ${it.joinToString(", ")}. Check the add-on in Stremio, or try again later."
            },
            torrentOnly.takeIf { it.isNotEmpty() }?.let {
                "${it.joinToString(", ")} found only torrents. CAS plays them through a debrid service: connect one in the add-on's settings."
            },
            unplayable.takeIf { it > 0 }?.let { "$it ${if (it == 1) "source needs" else "sources need"} a video format this TV can't decode." },
        ).ifEmpty { listOf("Your add-ons have no sources for this title.") }
    }

    private fun reason(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.UnknownHostException -> "address not found"
        is java.net.ConnectException -> "connection refused"
        is javax.net.ssl.SSLException -> "secure connection failed"
        is org.json.JSONException -> "not a source list"
        else -> e.message?.takeIf { it.length <= 40 } ?: "error"
    }

    /**
     * The order to try sources in: when the player asked for a lower quality, the tallest that's left first; then the
     * file played last time for this video (debrid links expire, so the link is fresh but the release is the same), then
     * the release the previous episode used (AIOStreams' bingeGroup), then the addon's own order, with releases above the
     * screen's resolution or HDR on a screen without it moved down, 3D and hard-coded subtitles last, and then Settings'
     * stream preferences. The sort is stable, so ties keep the addon's order.
     */
    private fun rank(streams: List<Stream>, videoId: String) =
        rankSources(this, streams, videoId, intent.getStringExtra("id"), intent.getIntExtra("maxHeight", 0))


    /** Within Settings' limits (quality, file size, HDR), and never a cam or screener. Unknown sizes and resolutions pass. */
    private fun wanted(s: Stream) = wantedSource(this, s, intent.getIntExtra("maxHeight", 0))

    /** [line] with [strong] in full-strength bold. */
    private fun emphasised(line: String, strong: String): CharSequence = android.text.SpannableString(line).apply {
        val at = line.lastIndexOf(strong)
        if (at < 0) return@apply
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + strong.length, 0)
        setSpan(android.text.style.ForegroundColorSpan(TEXT), at, at + strong.length, 0)
    }

    /**
     * The help line for the source under the remote: "Best match  ·  played last time  ·  cached on Real-Debrid", or
     * for any other source what lifts or holds it ("above your 1080p limit"). Nothing told apart reads as the add-on's order.
     */
    private fun whyLine(best: Boolean, reasons: List<String>): CharSequence {
        val parts = listOfNotNull("Best match".takeIf { best }) + reasons.ifEmpty { listOf(if (best) "the add-on's first" else "the add-on's order") }
        val line = "  " + parts.joinToString(DOT).replaceFirstChar { it.uppercase() }
        return if (best) emphasised(line, "Best match") else line
    }

    /** [sourceReasons] for [s], against this title's history and Settings as they stand. */
    private fun why(s: Stream, tv: Device): List<String> {
        val prefs = getSharedPreferences("sources", Context.MODE_PRIVATE)
        val file = prefs.getString("file:${intent.getStringExtra("videoId")}", null)
        val binge = prefs.getString("binge:${intent.getStringExtra("id")}", null)
        return sourceReasons(s, tv, tastes, file != null && s.filename == file, binge != null && s.bingeGroup == binge)
    }

    private fun streamRow(s: Stream, addon: String, best: Boolean, why: CharSequence): View {
        val quality = text(if (s.height > 0) "${s.height}p" else "Auto", BODY, bold = true).onArt()
        val detail = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (best) detail.addView(text("BEST MATCH", BADGE, bold = true, color = BASE).apply {
            letterSpacing = 0.04f
            background = rounded(TUNGSTEN, 3)
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }, wrap().apply { marginEnd = dp(7) })
        detail.addView(text(sourceSummary(s), BODY, bold = true, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(if (best) 145 else 170)
        }, wrap().apply { marginEnd = dp(6) })
        // The debrid service first ("RD ⚡": cached on Real-Debrid, starts at once), then the release's own tags.
        (listOfNotNull(s.debrid?.tag) + s.tags.filter { it !in listOf("BluRay", "WEB") }.take(if (best) 1 else 2)).forEach { tag ->
            detail.addView(text(tag.uppercase(), BADGE, bold = true, color = TUNGSTEN).apply {
                letterSpacing = 0.03f
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = dp(3).toFloat(); setStroke(dp(1), ink(0x80))
                }
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }, wrap().apply { marginEnd = dp(5) })
        }
        val provider = s.title.lineSequence().firstOrNull().orEmpty().substringBefore('(')
            .replace(LEADING_TAG, "").trim().takeIf { it.isNotBlank() && !it.matches(HEIGHT_ONLY) } ?: addon
        detail.addView(text(provider, SMALL, color = TEXT_3).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_H)
            setPadding(dp(12), 0, dp(12), 0)
            addView(quality, LinearLayout.LayoutParams(dp(QUALITY_W), -2))
            addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(sourceSize(s.size), BODY, bold = true).onArt().apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(72), -2))
            rowFocus(8) { focused ->
                alpha = if (!focused && s.height in 1..719) SD_DIM else 1f
                elevation = if (focused) dp(5).toFloat() else 0f
                if (focused) whyLine.text = why
            }
            alpha = if (s.height in 1..719) SD_DIM else 1f
            setOnClickListener {
                startActivity(Intent(this@StreamsActivity, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", JSONArray(listOf(s.toJson())).toString()).putExtra("picked", true))
            }
        }
    }

    private fun presetName(): String {
        val release = Settings.release(this)
        val hdr = Settings.hdrPref(this)
        val height = Settings.maxHeight(this)
        val size = Settings.maxSizeGb(this)
        return when {
            release == "" && hdr == "" && height == 0 && size == 0 -> "Add-on's order"
            release == "remux" && hdr == "dv" && height == 0 && size == 0 -> "Best picture"
            release == "encode" && hdr == "" && height == 0 && size == 20 -> "Balanced"
            release == "web" && hdr == "" && height == 1080 && size == 10 -> "Fast start"
            else -> "Custom"
        }
    }

    private companion object {
        const val TRY = 5
        const val QUALITY_W = 76 // dp, wide enough for "QUALITY" in spaced capitals
        const val ROW_H = 44 // dp a source row's least height; it grows with the TV's font size
        const val SD_DIM = 0.6f // a source below 720p at rest; focus by light dims it again, so lower read as gone
        val LEADING_TAG = Regex("^\\[[^]]+]\\s*") // "[RD+] Torrentio" reads as the add-on's name alone
        val HEIGHT_ONLY = Regex("\\d+p")
    }
}

internal fun sourceSize(size: Long): String = when {
    size <= 0 -> "—"
    size >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", size / 1_000_000_000.0)
    else -> "${(size / 1_000_000.0).toInt()} MB"
}

// The source list's marks, compiled once rather than once per row drawn.
private object Summary {
    val av1 = Regex("\\bav1\\b")
    val hevc = Regex("hevc|[xh].?265")
    val avc = Regex("avc|[xh].?264")
    val dd51 = Regex("\\b(dd|ac3|eac3|e-ac3)[ ._-]?5[ ._-]?1\\b")
    val five1 = Regex("\\b5[ ._-]?1\\b")
    val stereo = Regex("\\b2[ ._-]?0\\b|stereo")
}

internal fun sourceSummary(s: Stream): String {
    val words = "${s.filename} ${s.title} ${s.description}".lowercase()
    val release = when {
        s.remux || s.bluray -> "BluRay"
        s.web -> "WEB"
        "hdtv" in words -> "HDTV"
        "sdtv" in words -> "SDTV"
        else -> "Source"
    }
    val codec = when {
        Summary.av1.containsMatchIn(words) -> "AV1"
        Summary.hevc.containsMatchIn(words) -> "HEVC"
        Summary.avc.containsMatchIn(words) -> "AVC"
        else -> null
    }
    val audio = when {
        "atmos" in words -> "Atmos"
        Summary.dd51.containsMatchIn(words) -> "DD 5.1"
        Summary.five1.containsMatchIn(words) -> "5.1"
        Summary.stereo.containsMatchIn(words) -> "2.0"
        else -> null
    }
    return listOfNotNull(release, codec, audio).joinToString(" · ")
}
