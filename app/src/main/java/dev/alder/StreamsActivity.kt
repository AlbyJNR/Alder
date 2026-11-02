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

internal fun readyStreams(answers: Array<List<Stream>?>, allowGaps: Boolean): List<Stream> =
    (if (allowGaps) answers.asList() else answers.takeWhile { it != null }).flatMap { it.orEmpty() }

class StreamsActivity : Screen() {
    private val pick by lazy { intent.getBooleanExtra("pick", false) || Settings.alwaysList(this) }

    private lateinit var art: ImageView
    override val backdrop get() = art
    // Audio language chips above the list: All, then each language the sources name. A pick lasts while the page is open.
    private lateinit var languageStrip: HorizontalScrollView
    private var audio: String? = null
    private var audioOpened = false // the first list opens on the profile's language (Dubbed releases: My language first)

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
                .joinToString(" · "), SMALL, bold = true, color = TEXT_2).onArt())
            // The mock's 23 sp: the screen title's size crowded the count.
            addView(text(where.drop(1).joinToString(" · ").ifBlank { name }, NAME_SIZE, serif = true).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, wrap().apply { topMargin = dp(2) })
        }
        val count = text("", SMALL, color = TEXT_3).onArt().apply { gravity = Gravity.END }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brand, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(16) })
            addView(episodeInfo, LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, LinearLayout.LayoutParams(dp(90), -2))
        }
        val cols = LinearLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(4))
            addView(caps("Quality", TEXT_3).apply { maxLines = 1 }, LinearLayout.LayoutParams(dp(QUALITY_W), -2))
            addView(caps("Release", TEXT_3), LinearLayout.LayoutParams(0, -2, 1f))
            addView(caps("Size", TEXT_3).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(72), -2))
        }
        languageStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; visibility = View.GONE
        }
        val rows = column(Gravity.START)
        val status = text(intent.getStringExtra("message") ?: "Finding sources…", BODY, color = TEXT_2).onArt()
        rows.addView(status, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12); topMargin = dp(12) })
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
            addView(text("OK", 9f, bold = true, color = BASE).apply {
                background = rounded(TUNGSTEN, 3); includeFontPadding = false; setPadding(dp(4), dp(1), dp(4), dp(1))
            })
            val preset = presetName()
            val line = "  Play this source  ·  Best match follows your stream settings: $preset."
            addView(text("", SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                // The preset in full-strength bold, as the mock reads it.
                text = android.text.SpannableString(line).apply {
                    val at = line.lastIndexOf(preset)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + preset.length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(TEXT), at, at + preset.length, 0)
                }
            })
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
                    intArrayOf(0xB308090B.toInt(), 0x7308090B, 0x5908090B))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = glassOver(this, 14, 0.18f)
                addView(header, LinearLayout.LayoutParams(-1, dp(58)))
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
            val addons = result.getOrElse { status.text = "Couldn't load sources. Check your connection and try again."; return@background }
            if (addons.isEmpty()) { status.text = "No stream add-ons are connected. Add one in Stremio to find sources."; return@background }
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
                if (shown.isEmpty()) {
                    rows.addView(text("No playable sources found.", BODY, color = TEXT_2).onArt().apply {
                        setPadding(dp(12), dp(12), 0, 0)
                    })
                    val unplayable = answers.sumOf { a -> a.orEmpty().count { it.isVideo && !it.playableOn(tv) } }
                    emptyReasons(addons.map { it.name }, failures, torrents, unplayable).forEach { line ->
                        rows.addView(text(line, SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), dp(8), dp(12), 0) },
                            LinearLayout.LayoutParams(-1, -2))
                    }
                } else {
                    val best = eligible.firstOrNull { it in shown }
                    shown.forEach { stream -> rows.addView(streamRow(stream, providers[stream].orEmpty(), stream === best),
                        LinearLayout.LayoutParams(-1, dp(ROW_H)).apply { bottomMargin = dp(2) }) }
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
                startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", JSONArray(best.take(TRY).map { it.toJson() }).toString()))
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
                val kept = Sources.saved(key, type, videoId, addon)
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

    private fun streamRow(s: Stream, addon: String, best: Boolean): View {
        val quality = text(if (s.height > 0) "${s.height}p" else "Auto", BODY, bold = true).onArt()
        val detail = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (best) detail.addView(text("BEST MATCH", 9f, bold = true, color = BASE).apply {
            letterSpacing = 0.04f
            background = rounded(TUNGSTEN, 3)
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }, wrap().apply { marginEnd = dp(7) })
        detail.addView(text(sourceSummary(s), BODY, bold = true, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(if (best) 145 else 170)
        }, wrap().apply { marginEnd = dp(6) })
        // The debrid service first ("RD ⚡": cached on Real-Debrid, starts at once), then the release's own tags.
        (listOfNotNull(s.debrid?.tag) + s.tags.filter { it !in listOf("BluRay", "WEB") }.take(if (best) 1 else 2)).forEach { tag ->
            detail.addView(text(tag.uppercase(), 9f, bold = true, color = TUNGSTEN).apply {
                letterSpacing = 0.03f
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = dp(3).toFloat(); setStroke(dp(1), 0x80FFFFFF.toInt())
                }
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }, wrap().apply { marginEnd = dp(5) })
        }
        val provider = s.title.lineSequence().firstOrNull().orEmpty().substringBefore('(')
            .replace(Regex("^\\[[^]]+]\\s*"), "").trim().takeIf { it.isNotBlank() && !it.matches(Regex("\\d+p")) } ?: addon
        detail.addView(text(provider, SMALL, color = TEXT_3).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(quality, LinearLayout.LayoutParams(dp(QUALITY_W), -2))
            addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(sourceSize(s.size), BODY, bold = true).onArt().apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(72), -2))
            rowFocus(8) { focused ->
                alpha = if (!focused && s.height in 1..719) 0.48f else 1f
                elevation = if (focused) dp(5).toFloat() else 0f
            }
            alpha = if (s.height in 1..719) 0.48f else 1f
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
        const val TRY = 3
        const val NAME_SIZE = 23f // sp, the title or episode name in the header
        const val QUALITY_W = 76 // dp, wide enough for "QUALITY" in spaced capitals
        const val ROW_H = 38 // dp a source row: eight fit above the help line, as on the mock
    }
}

internal fun sourceSize(size: Long): String = when {
    size <= 0 -> "—"
    size >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", size / 1_000_000_000.0)
    else -> "${(size / 1_000_000.0).toInt()} MB"
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
        Regex("\\bav1\\b").containsMatchIn(words) -> "AV1"
        Regex("hevc|[xh].?265").containsMatchIn(words) -> "HEVC"
        Regex("avc|[xh].?264").containsMatchIn(words) -> "AVC"
        else -> null
    }
    val audio = when {
        "atmos" in words -> "Atmos"
        Regex("\\b(dd|ac3|eac3|e-ac3)[ ._-]?5[ ._-]?1\\b").containsMatchIn(words) -> "DD 5.1"
        Regex("\\b5[ ._-]?1\\b").containsMatchIn(words) -> "5.1"
        Regex("\\b2[ ._-]?0\\b|stereo").containsMatchIn(words) -> "2.0"
        else -> null
    }
    return listOfNotNull(release, codec, audio).joinToString(" · ")
}
