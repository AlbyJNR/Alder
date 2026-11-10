package dev.alder

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// The day's shape and its formats, made once: a guide of eighty episodes asked for each of them eighty times.
// SimpleDateFormat is not thread-safe; these are read on the main thread only (the guide, the watch block).
private val ISO_DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private val DAY_IN = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = UTC }
private val DAY_OUT = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = UTC }
private val DAY_YEAR_OUT = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = UTC }

internal fun episodeDate(value: String?): String? {
    val day = value?.take(10)?.takeIf { it.matches(ISO_DAY) } ?: return null
    val parsed = runCatching { DAY_IN.parse(day) }.getOrNull() ?: return null
    return DAY_YEAR_OUT.format(parsed)
}

/**
 * "Airs 7 Nov" for an episode released after [now] (both ISO text in UTC, as Stremio and the add-ons write them), with
 * the year when it isn't this one; null once it has aired, or without a date. Text compares as time in this format.
 */
internal fun airs(released: String?, now: String = Library.now(), word: String = "Airs"): String? {
    val day = released?.take(10)?.takeIf { it.matches(ISO_DAY) } ?: return null
    if (released <= now) return null
    val parsed = runCatching { DAY_IN.parse(day) }.getOrNull() ?: return null
    return "$word " + (if (day.take(4) == now.take(4)) DAY_OUT else DAY_YEAR_OUT).format(parsed)
}

// A title stays in place over its art; episodes and related titles open over the same scene.
/** The word before the episode on a series' page: "Continue" part-way through one, "Up next" past the first, none at the start or for a film. */
internal fun whereLead(series: Boolean, started: Boolean, pastFirst: Boolean): String? = when {
    !series -> null
    started -> "Continue"
    pastFirst -> "Up next"
    else -> null
}

class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }
    private val listedTrailer by lazy { intent.getStringExtra("trailer") } // IMDb's video id from the IMDb page: Trailer plays this one
    private val listedRelease by lazy { intent.getStringExtra("released") } // IMDb's release date, when the add-on has none
    private val original by lazy { intent.getStringExtra("original") } // the title in its own language, from the Foreign page's rows
    private val origin by lazy { intent.getStringExtra("origin") } // "Italy · Italian", the same way

    private lateinit var key: String
    override val backdrop get() = heroArt
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonStrip: LinearLayout
    private var seasonButtons = emptyMap<Int, TextView>()
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewFacts: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var similarOverlay: FrameLayout
    private var similarFirst: View? = null
    private lateinit var listButton: TextView
    private lateinit var playButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var infoOverlay: FrameLayout
    private var aboutView: TextView? = null // the plot, in the right column: gives up a line when the column runs long
    private var guideOpen = false
    private var similarOpen = false
    private var infoOpen = false
    private var selectedSeason = 1
    private var episodeRows = emptyList<View>()
    private var meta: Meta? = null
    private var item: JSONObject? = null // the title's library item: progress and whether it's in My List
    private var itemChangedHere = false
    private var similarRequested = false
    private var certificate: String? = null // the age rating, from the add-on or IMDb
    private var certificateRequested = false
    private var tomatoes: RtScores? = null // Rotten Tomatoes' meters and consensus, when it has the title
    private var tomatoesRequested = false
    private var criticsSlot: LinearLayout? = null // the critics' block, refilled where it is when Rotten Tomatoes answers
    private var sideColumn: View? = null // the information blocks down the right: Down moves within them before the episodes
    private var plotFull: String? = null // the whole description; the plot shows what fits, cut at a sentence
    private var factRow: LinearLayout? = null // the year · genre line and the scores under it: refilled where they are as a rating or RT arrives
    // One column for the labels (Critics, Cast, Director), as wide as the widest: the words after them start at one point.
    private val smallLine by lazy { text("", SMALL).lineHeight } // one small line's height: an empty scores line keeps it
    private var watchState: String? = null // what the watch block drew from the library item: the same again redraws nothing
    private var leadControl: View? = null // Play, or Trailer for a title not out yet
    private var lastKeyAt = 0L // the remote's last press: the hero is never rebuilt under a moving remote
    private var tickers = emptyMap<String, () -> Unit>() // each episode row's words, redrawn when a tick changes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false }
        root = FrameLayout(this).apply {
            clipChildren = false
            // The art shades into the page's dark from the left, as Netflix's title page does: the column of words down
            // the left reads over any picture, and the art's right side stays bright. A lighter shade up from the foot
            // keeps the last lines and the "▾ More like this" hint clear.
            addView(View(this@DetailsActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xEB08090B.toInt(), 0xD908090B.toInt(), 0xB308090B.toInt(), 0x6608090B, 0x0008090B))
            }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 90 / 100, -1))
            addView(View(this@DetailsActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xD908090B.toInt(), 0x6608090B, 0x0008090B))
            }, FrameLayout.LayoutParams(-1, resources.displayMetrics.heightPixels / 2, Gravity.BOTTOM))
            addView(hero, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@DetailsActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                textSize = SMALL
                setTextColor(TEXT_2)
                typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
        }
        setWindowed(root, heroArt)

        (initialArt ?: poster)?.let { Images.load(it, heroArt, artWidth, urgent = true) { heroArt.alpha = 1f; refrost() } }

        fun show(m: Meta) {
            // The fresh details are the saved ones again, the usual case: nothing to redraw, no guide to rebuild.
            if (meta?.stamp == m.stamp) return
            val first = meta == null
            meta = m
            val art = m.background ?: initialArt ?: poster
            if (heroArt.tag != art) Images.load(art, heroArt, artWidth, urgent = true) {
                heroArt.animate().alpha(1f).setDuration(250).start()
                refrost()
            }
            // Fresh details can land while the episode guide is open (Down pressed within the first second): the
            // guide is rebuilt, so it reopens on the same episode rather than leaving a hidden title behind.
            val wasGuide = guideOpen
            val episodeTag = currentFocus?.takeIf { it in episodeRows }?.tag
            certificate = m.certification ?: certificate
            // The first details build the page at once; fresh ones that differ rebuild it once the remote rests.
            if (first) showHero(m) else whenStill { showHero(meta) }
            m.imdbId?.let { id -> background({ imdbTrailerStreams(id) }) {} } // ready before the button is pressed
            // The age rating, from IMDb when the add-on has none; the facts line is redrawn once when it comes.
            if (certificate == null && !certificateRequested && m.imdbId != null && Settings.titlePage(this, SettingsActivity.TITLE_RATING)) {
                certificateRequested = true
                background({ imdbCertificate(m.imdbId) }) { r ->
                    val found = r.getOrNull() ?: return@background
                    certificate = found
                    refreshFacts()
                }
            }
            // Rotten Tomatoes' two meters and the critics' consensus, by the title's name and year; drawn in once they come.
            if (!tomatoesRequested && m.name.isNotBlank() && Settings.titlePage(this, SettingsActivity.TITLE_TOMATOES)) {
                tomatoesRequested = true
                val year = m.year?.take(4)?.toIntOrNull()
                background({ rtScores(m.name, year, type == "series") }) { r ->
                    tomatoes = r.getOrNull() ?: return@background
                    refreshFacts()
                }
            }
            if (type == "series" && m.videos.isNotEmpty()) {
                buildGuide(m.videos)
                if (wasGuide) { showGuide(); episodeRows.firstOrNull { it.tag == episodeTag }?.requestFocus() }
            }
            if (type == "movie" && !similarRequested) { similarRequested = true; loadSimilar() }
        }
        background({
            // The library Home holds in memory first: parsing the saved library (megabytes) again for one item held the
            // first block back, and the block was then built once more when the item came.
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to
                (Stremio.knownLibrary(key) ?: Stremio.savedLibrary(key))?.firstOrNull { it.optString("_id") == metaId }
        }) { result ->
            result.getOrNull()?.let { (saved, libraryItem) ->
                if (!itemChangedHere && item == null) item = libraryItem
                if (meta == null) saved?.let(::show)
            }
        }
        background({ Stremio.addons(key).meta(type, metaId, together = true) }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null) show(fresh)
            else if (meta == null) showHero(null)
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            if (!itemChangedHere) {
                item = result.getOrNull() ?: item
                // Only a library item that changes the watch block (a resume point, My List) redraws it; the usual
                // answer repeats what the saved copy already showed.
                meta?.let { m -> if (watchKey(m) != watchState) whenStill { showHero(meta) } }
            }
        }
    }

    // ---------- title and watch window ----------

    private var keepFocus: String? = null // the hero control the remote was on before a rebuild, by its words
    private var actionRow1: View? = null // Play and the small controls

    private fun showHero(m: Meta?) {
        keepFocus = currentFocus?.takeIf { isInside(it, hero) }?.let { it.contentDescription?.toString() ?: (it as? TextView)?.text?.toString() }
        hero.removeAllViews()
        aboutView = null
        criticsSlot = null
        sideColumn = null
        val title = text(m?.name ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
            // The box is a fixed height: at large font sizes a two-line name shrinks to fit instead of clipping.
            setAutoSizeTextTypeUniformWithConfiguration(20, SERIF_HERO.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val titleArea = FrameLayout(this).apply {
            addView(title, FrameLayout.LayoutParams(-1, -1))
            if (m != null) {
                val logo = ImageView(this@DetailsActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_START
                    visibility = View.GONE
                }
                addView(logo, FrameLayout.LayoutParams(dp(LOGO_W), -1))
                Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(LOGO_W), urgent = true, look = Images.trimmed, failed = {
                    title.visibility = View.VISIBLE; logo.visibility = View.GONE
                }) {
                    title.visibility = View.GONE; logo.visibility = View.VISIBLE
                }
            }
        }
        val stack = column(Gravity.START).apply { addView(titleArea, LinearLayout.LayoutParams(-1, dp(TITLE_H))) }
        if (m == null) {
            val error = failed("Couldn't load this title's details.") { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(2).requestFocus() }
        } else {
            // Laid out as the Apple TV app sets a title: down the left, four things with air between them (the name;
            // its facts on one line and its scores on the next; Play and its controls on one line; the plot), and down
            // the right the information in frosted blocks (the critics in their words, the cast, the director), each
            // its own thing. The facts and the critics' block are filled apart from the rest, so a late number (the
            // age rating, Rotten Tomatoes) refills them where they are instead of rebuilding the controls under the remote.
            // The title in its own language under the English one ("Gomorra"), when the rows knew it and it differs.
            val own = original?.takeIf { it.isNotBlank() && !it.equals(m.name, true) }
            if (own != null) stack.addView(text(own, SMALL, color = TEXT_2, serif = true).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(COLUMN_W), -2).apply { topMargin = dp(2) })
            val facts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fillFacts(this, m) }
            factRow = facts
            stack.addView(facts, LinearLayout.LayoutParams(dp(COLUMN_W), -2).apply { topMargin = dp(if (own != null) 12 else 16) })
            addWatch(m, stack)
            plotFull = m.description?.takeIf { it.isNotBlank() }
            aboutView = plotFull?.let { full ->
                text(full, BODY, color = TEXT).onArt().apply { maxLines = ABOUT_LINES; ellipsize = TextUtils.TruncateAt.END }
                    .also { about -> stack.addView(about, LinearLayout.LayoutParams(dp(COLUMN_W), -2).apply { topMargin = dp(28) }); fitPlot(about) }
            }
            // Both columns keep above the hint line at the foot: at a large font size the plot, or the critics' line,
            // gives up lines.
            val floor = resources.displayMetrics.heightPixels - dp(BAND_BOTTOM)
            stack.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
                val about = aboutView ?: return@addOnLayoutChangeListener
                if (bottom > floor && about.maxLines > 1) about.post { about.maxLines -= 1; fitPlot(about) }
            }
            val side = column(Gravity.START).apply { clipChildren = false; clipToPadding = false }
            criticsSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fillCritics(this) }
            side.addView(criticsSlot, LinearLayout.LayoutParams(-1, -2))
            if (m.cast.isNotEmpty() && Settings.titlePage(this, SettingsActivity.TITLE_CAST))
                side.addView(block("Cast", *m.cast.take(CAST_NAMES).map(::person).toTypedArray()), LinearLayout.LayoutParams(-1, -2))
            if (m.director.isNotEmpty() && type == "movie" && Settings.titlePage(this, SettingsActivity.TITLE_DIRECTOR))
                side.addView(block(if (m.director.size > 1) "Directors" else "Director", *m.director.take(2).map(::person).toTypedArray()),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(BLOCK_GAP) })
            // The writers under the director, a film's only (a series has a room of them): each a name to search, as the rest.
            if (m.writer.isNotEmpty() && type == "movie" && Settings.titlePage(this, SettingsActivity.TITLE_DIRECTOR))
                side.addView(block(if (m.writer.size > 1) "Writers" else "Writer", *m.writer.take(2).map(::person).toTypedArray()),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(BLOCK_GAP) })
            sideColumn = side
            hero.addView(side, FrameLayout.LayoutParams(dp(BLOCK_W), -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(PAD); topMargin = dp(BAND_TOP)
            })
            side.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
                val said = criticsSlot?.findViewWithTag<TextView>(SAID) ?: return@addOnLayoutChangeListener
                if (bottom > floor && said.maxLines > 1) said.post { said.maxLines -= 1 }
            }
        }
        hero.addView(stack, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(PAD); topMargin = dp(BAND_TOP)
        })
        addMoreHint()
        if (m != null && !guideOpen && !infoOpen && !similarOpen) {
            // The remote goes straight back to the control it was on (Trailer, a cast name, ⓘ), at its focused size:
            // no frame without focus for a press to fall into, and no second growth. The post is the fallback for a
            // view the window would not focus before its first layout.
            val back = keepFocus?.let { keyed(hero, it) } ?: leadControl ?: playButton
            back.requestFocus()
            back.settleFocus()
            hero.post { if (!guideOpen && !infoOpen && !similarOpen && currentFocus?.let { isInside(it, hero) } != true) back.requestFocus() }
        }
    }

    /** "▾ Episodes" or "▾ More like this" under the block, once there is something below; added in place when it comes. */
    private fun addMoreHint() {
        hero.findViewWithTag<View>(MORE_HINT)?.let(hero::removeView)
        val hasEpisodes = type == "series" && meta?.videos?.isNotEmpty() == true
        if (!hasEpisodes && !::similarOverlay.isInitialized) return
        hero.addView(caps(if (hasEpisodes) "▾  Episodes" else "▾  More like this", TEXT_2).onArt().apply {
            tag = MORE_HINT
            isFocusable = true
            setOnClickListener { if (hasEpisodes) showGuide() else showSimilar() }
            rowFocus(rest = { null })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(32)
        })
    }

    /** The year, a genre, the length, then IMDb's number and Rotten Tomatoes' two: refilled as each arrives. */
    /**
     * The facts under the name, two rows always: the age rating's capsule and "2012 · Action · 2 h 23 min" on the
     * first, the scores (IMDb, Rotten Tomatoes) on the second. The second row is kept a line tall while empty, so a
     * late Rotten Tomatoes answer lands in space already its own and moves nothing the eye has read.
     */
    private fun fillFacts(block: LinearLayout, m: Meta) {
        block.removeAllViews()
        val length = if (type == "series") m.videos.map { it.season }.filter { it > 0 }.distinct().size
            .takeIf { it > 0 }?.let(::seasons) else m.runtime
        // Where it was made and in what language first, for a title that came from the Foreign page.
        val facts = listOfNotNull(origin?.takeIf { it.isNotBlank() }, m.year, m.genres.firstOrNull(), length).joinToString(DOT)
        val ratingOn = Settings.titlePage(this, SettingsActivity.TITLE_RATING)
        val tomatoesOn = Settings.titlePage(this, SettingsActivity.TITLE_TOMATOES)
        val cert = certificate?.takeIf { ratingOn }
        val meters = tomatoes?.let { rt -> listOfNotNull(rt.critics?.let { "C$it%" }, rt.audience?.let { "A$it%" }).joinToString(" ").ifEmpty { null } }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (cert != null) line.addView(capsule(cert), wrap().apply { marginEnd = dp(8) })
        line.addView(text(facts, SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        block.addView(line, LinearLayout.LayoutParams(-1, -2))
        if (m.rating != null || tomatoesOn) block.addView(scoresLine(m.rating, meters, lead = false).apply { minimumHeight = smallLine },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** The age rating before the year: a thin outlined capsule, quiet, the way a cinema listing prints it. */
    private fun capsule(cert: String) = text(cert, RATING_SP, bold = true, color = TEXT_2).apply {
        letterSpacing = 0.04f
        setPadding(dp(5), dp(2), dp(5), dp(2))
        background = GradientDrawable().apply { setStroke(dp(1), TEXT_2); cornerRadius = dp(4).toFloat() }
        alpha = RATING_ALPHA
    }

    /** The critics' consensus in their words, a block of its own; a series' is its newest season's, named in the label. */
    private fun fillCritics(slot: LinearLayout) {
        slot.removeAllViews()
        val rt = tomatoes?.takeIf { it.consensus != null && Settings.titlePage(this, SettingsActivity.TITLE_TOMATOES) }
        slot.visibility = if (rt == null) View.GONE else View.VISIBLE
        val line = rt?.consensus ?: return
        val said = text(line, SMALL, color = TEXT_2).apply { maxLines = CRITICS_LINES; ellipsize = TextUtils.TruncateAt.END; tag = SAID }
        slot.addView(block(rt.season?.let { "Critics · Season $it" } ?: "Critics", said), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(BLOCK_GAP) })
    }

    /** A name the remote can select, for the leads and the director alike: their other work, as a search. */
    private fun person(person: String) = text(person, SMALL, color = TEXT_2).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(BLOCK_W - 2 * BLOCK_PAD)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        contentDescription = "Films and shows with $person"
        rowFocus(6, rest = { null }) { has -> setTextColor(if (has) TEXT else TEXT_2) }
        if (Profiles.active(this@DetailsActivity)?.kids == true) isFocusable = false // a Kids profile stays on its own titles
        else setOnClickListener { startActivity(Intent(this@DetailsActivity, SearchActivity::class.java).putExtra("person", person)) }
        // Hugs its words, and starts them at the block's own margin (its padding is the focus rim's room).
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = -dp(8) }
    }

    /** The plot ends at a sentence where its lines run out, never mid-word with dots; measured once laid out. */
    private fun fitPlot(about: TextView) {
        val full = plotFull ?: return
        about.post {
            if (plotFull != full) return@post
            val layout = about.layout ?: return@post
            val last = minOf(layout.lineCount, about.maxLines) - 1
            if (last >= 0 && layout.getEllipsisCount(last) > 0) about.text = synopsisAtSentence(full, layout.getLineStart(last) + layout.getEllipsisStart(last))
        }
    }

    /** Down within the information blocks moves to the next name, not to the episodes, while there is one below. */
    private fun nextBelowInSide(): Boolean {
        val side = sideColumn ?: return false
        val from = currentFocus?.takeIf { isInside(it, side) } ?: return false
        return from.focusSearch(View.FOCUS_DOWN)?.let { isInside(it, side) } == true
    }

    /** A late fact (the age rating, Rotten Tomatoes): its rows refill where they are; the controls, and the remote on them, stay. */
    private fun refreshFacts() {
        val m = meta ?: return
        factRow?.let { fillFacts(it, m) }
        criticsSlot?.let(::fillCritics)
    }

    /** What the watch block draws from the library item; the same key again means the same block. */
    private fun watchKey(m: Meta): String {
        val target = playTarget(m)?.first ?: metaId
        val resumeAt = item?.let { Library.resumeAt(it, target) } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        return listOf(target, resumeAt, duration, Library.inList(item)).joinToString("|")
    }

    /** Runs [action] once the remote has rested a moment: a block rebuilt under a moving remote drops the press. */
    private fun whenStill(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        val since = android.os.SystemClock.uptimeMillis() - lastKeyAt
        if (since >= STILL_WAIT) action() else main.postDelayed({ whenStill(action) }, STILL_WAIT - since)
    }

    /** Where you are, then the main action and three small glass controls. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
        watchState = watchKey(m)
        val target = playTarget(m)
        // What Play would play: its sources asked for now, so Play (and Preview) start without the add-ons' wait.
        target?.first?.let { Sources.prefetch(this, key, type, it) }
        val episode = target?.second
        // Under a minute in is not started: a title opened and closed at 0:00 offers Play, not Resume.
        val resumeAt = (item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L).takeIf { it >= STARTED } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = if (resumeAt > 0 && duration > resumeAt) runtime("${(duration - resumeAt) / 60_000} min") + " left" else null
        // The next episode isn't out yet (Stremio queues it as soon as the last one ends): say when, and Play waits.
        val unaired = airs(episode?.released)
        // Not released yet (a coming-soon title): the day, and the trailer leads the line of buttons.
        val comingOut = if (item == null || resumeAt == 0L) airs(m.released ?: listedRelease, word = if (type == "movie") "In cinemas" else "From") else null
        val where = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, episode?.title?.takeIf { it.isNotBlank() }, remaining ?: unaired ?: comingOut).joinToString(DOT)
        val before = stack.childCount // the where-line and its bar, when they come, sit between the scores and the controls
        // A series always says which episode Play plays (Alan, 2026-11-10: nothing on the page said where you were
        // until an episode was part-watched): a warm word for why it is this one, then the episode.
        val lead = whereLead(episode != null, resumeAt > 0, episode?.let { it.season > 1 || it.episode > 1 } == true)
        if (where.isNotEmpty() && (episode != null || resumeAt > 0 || unaired != null || comingOut != null)) stack.addView(text("", SMALL, bold = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            this.text = if (lead == null) where else android.text.SpannableString("${lead.uppercase()}   $where").apply {
                setSpan(android.text.style.ForegroundColorSpan(TUNGSTEN), 0, lead.length, 0)
                setSpan(android.text.style.RelativeSizeSpan(CAPS / SMALL), 0, lead.length, 0)
            }
        }.marginTop(18))
        if (remaining != null) stack.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })

        // The white disc: already the brightest thing on the page at rest, so the eye lands on it before the remote does.
        playButton = disc(DISC, R.drawable.ic_play, if (resumeAt > 0) "Resume" else "Play").apply {
            setOnClickListener { (unaired ?: comingOut)?.let(::notAired) ?: openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { (unaired ?: comingOut)?.let(::notAired) ?: openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        // Start over, and the way off Continue Watching that was only behind a held OK on Home.
        val startOver = if (resumeAt > 0) iconButton("↺", "Start over") {
            choose(m.name, listOf("Start over", "Remove from Continue Watching", "Cancel")) { i ->
                when (i) { 0 -> openSources(target?.first ?: metaId, startOver = true); 1 -> removeFromContinue() }
            }
        } else null
        infoButton = iconButton("ⓘ", "More information") { showInfo() }
        listButton = iconButton(if (Library.inList(item)) "✓" else "+", "My List") { toggleList() }.apply {
            // Held: the viewer's own lists (Kids, AC…), the rows under Continue on Home.
            setOnLongClickListener { listsMenu(Lists.Entry(metaId, type, m.name, m.poster ?: poster)); true }
        }
        // A trailer file (IMDb's, or the add-on's own) plays in the box on every TV; a title with only a YouTube id
        // takes the YouTube route Settings chose. Shown whenever IMDb could have one: every IMDb-numbered title.
        val ytId = m.trailers.firstOrNull()
        val trailer = if (Settings.titlePage(this, SettingsActivity.TITLE_TRAILER))
            glassButton("Trailer") {
                showTrailerClip(m.name, { (listedTrailer?.let(::imdbVideoStreams) ?: emptyList()) + m.trailerFiles + (m.imdbId?.let(::imdbTrailerStreams) ?: emptyList()) }) {
                    if (ytId != null) showTrailer(ytId, m.name)
                    else choose("Trailer unavailable", listOf("OK"), "No trailer was found for this title.") {}
                }
            } else null
        val preview = if (Settings.titlePage(this, SettingsActivity.TITLE_PREVIEW) && Settings.everything(this))
            glassButton("Preview") { if (unaired != null) notAired(unaired) else preview(target?.first ?: metaId, m) } else null
        // One line of controls, as the Apple TV app sets them: Play's white disc first, always (a title not out yet
        // keeps the same order, and Play says when it is out), the worded glass controls beside it, then the small
        // round ones a step apart. The column is wide enough for all of them, so nothing is squeezed out of the line.
        actionRow1 = LinearLayout(this).apply {
            clipChildren = false
            gravity = Gravity.CENTER_VERTICAL // the glass controls and the orbs sit on the disc's centre line
            // The disc says what it does in a word beside it, where the line has the room (no Preview on it).
            val word = if (preview == null) text(if (resumeAt > 0) "Resume" else "Play", BODY, bold = true).onArt() else null
            addView(playButton, LinearLayout.LayoutParams(dp(DISC), dp(DISC)).apply { marginEnd = dp(if (word != null) 10 else 14) })
            word?.let { addView(it, wrap().apply { marginEnd = dp(18) }) }
            listOfNotNull(trailer, preview).forEach { addView(it, LinearLayout.LayoutParams(-2, dp(BUTTON_H)).apply { marginEnd = dp(8) }) }
            listOfNotNull(startOver, listButton, infoButton).forEachIndexed { i, orb ->
                addView(orb, LinearLayout.LayoutParams(dp(ORB), dp(ORB)).apply { marginStart = dp(if (i == 0) 6 else 10) })
            }
        }
        stack.addView(actionRow1, wrap().apply { topMargin = dp(if (stack.childCount > before) 16 else 28) })
        leadControl = playButton
    }

    private fun keyed(root: View, key: String): View? {
        if (root.isFocusable && (root.contentDescription?.toString() == key || (root as? TextView)?.text?.toString() == key)) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) keyed(root.getChildAt(i), key)?.let { return it }
        return null
    }

    /** A small frosted circle (ⓘ, +, ↺) under the disc, as Apple's artist page sets its secondary actions. */
    private fun iconButton(symbol: String, description: String, action: () -> Unit) = text(symbol, 18f).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        lightFocus(ORB / 2)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(ORB), dp(ORB))
    }

    /** A worded glass control beside Play, as tall as the icon buttons. */
    private fun glassButton(label: String, action: () -> Unit) = text(label, SMALL, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        lightFocus(10) // the same corners and height as Play beside it
        setOnClickListener { action() }
    }

    /** A minute of what Play would play, in the box: the same add-ons, filters and ranking as the sources page. */
    private fun preview(videoId: String, m: Meta) {
        val episode = m.videos.firstOrNull { it.id == videoId }?.let { "S${it.season} E${it.episode}" }
        showPreview(listOfNotNull(m.name, episode).joinToString("  ·  ")) {
            val tv = Device.of(this)
            val all = Sources.all(this, key, type, videoId).values.flatMap { it.getOrNull()?.playable.orEmpty() }
            rankSources(this, all.filter { it.isVideo && it.playableOn(tv) && wantedSource(this, it) }, videoId, metaId).take(3)
        }
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    /** Off Continue Watching, as held OK on Home does it: the resume point goes, the history and My List stay; back to where the viewer came from. */
    private fun removeFromContinue() {
        val entry = item ?: return
        itemChangedHere = true
        val offset = Library.hideFromContinueWatching(entry)
        Settings.setHidden(this, Settings.hidden(this).put(metaId, JSONObject().put("name", meta?.name ?: name).put("timeOffset", offset)))
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) { finish() }
    }

    private fun toggleList() {
        itemChangedHere = true
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        Library.setInList(entry, !Library.inList(item))
        item = entry
        listButton.text = if (Library.inList(entry)) "✓" else "+"
        listButton.contentDescription = if (Library.inList(entry)) "Remove from My List" else "Add to My List"
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    /** An episode that isn't out yet: a word on when, instead of a search for sources that can't exist. */
    /** An episode not aired yet ("Airs 7 Nov"), or a title not out yet ("In cinemas 6 Nov", "From 6 Nov"): when, and nothing to play. */
    private fun notAired(when_: String) {
        val title = if (when_.startsWith("Airs")) "Not aired yet" else "Not out yet"
        val what = if (when_.startsWith("Airs")) "This episode" else meta?.name ?: "This title"
        choose(title, listOf("OK"), "$what ${when_.replaceFirstChar { it.lowercase() }}.") {}
    }

    // ---------- episode ticks ----------

    /**
     * A change to the ticks (Stremio's watched bitfield): on the title's library item, made if it has none yet, saved
     * to Stremio so the phone and the web show the same ✓, and reflected on Home's NEW count and in the guide at once.
     */
    private fun tick(change: (JSONObject, List<Video>) -> Unit) {
        val m = meta ?: return
        itemChangedHere = true
        val entry = item ?: Library.newItem(metaId, type, m.name, m.poster ?: poster)
        change(entry, m.videos)
        entry.put("_mtime", Library.now())
        item = entry
        Profiles.active(this)?.id?.let { NewEpisodes.record(this, it, entry, m) }
        refreshTicks()
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    /** The guide's rows and the still redrawn for the ticks as they are now; the remote stays where it is. */
    private fun refreshTicks() {
        tickers.values.forEach { it() }
        (currentFocus?.tag as? String)?.let { id -> meta?.videos?.firstOrNull { it.id == id }?.let(::updatePreview) }
    }

    /** Held OK on an episode: the sources, or its tick, or everything before it ticked in one go. */
    private fun episodeMenu(video: Video, videos: List<Video>) {
        val watched = Library.watched(item, videos)
        val ticked = video.id in watched
        val before = videos.subList(0, videos.indexOf(video).coerceAtLeast(0)).filter { it.season > 0 && it.id !in watched }.map { it.id }
        val options = listOf("Every source", if (ticked) "Mark as unwatched" else "Mark as watched") +
            (if (before.isNotEmpty()) listOf("Mark previous episodes watched") else emptyList())
        choose("S${video.season} E${video.episode}" + video.title.takeIf { it.isNotBlank() }?.let { "$DOT$it" }.orEmpty(), options) { i ->
            when (i) {
                0 -> {
                    val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                    openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}", pick = true)
                }
                1 -> tick { entry, vs -> if (ticked) Library.markUnwatched(entry, vs, video.id) else Library.markWatched(entry, vs, video.id) }
                2 -> tick { entry, vs -> Library.markWatchedMany(entry, vs, before) }
            }
        }
    }

    /** Held OK on a season: the whole season ticked, or every tick in it taken off when all are on. */
    private fun seasonMenu(season: Int, videos: List<Video>) {
        val ids = videos.filter { it.season == season }.map { it.id }
        if (ids.isEmpty()) return
        val watched = Library.watched(item, videos)
        val all = ids.all { it in watched }
        val name = if (season == 0) "Specials" else "season $season"
        choose(if (season == 0) "Specials" else "Season $season", listOf(if (all) "Mark $name unwatched" else "Mark $name watched", "Cancel")) { i ->
            if (i == 0) tick { entry, vs -> if (all) Library.markUnwatchedMany(entry, vs, ids) else Library.markWatchedMany(entry, vs, ids) }
        }
    }

    /** Whether an episode's still is hidden: Settings asks, and it is neither watched nor the one in progress. */
    private fun blurStill(video: Video): Boolean {
        if (!Settings.blurThumbnails(this)) return false
        val state = item?.optJSONObject("state")
        val inProgress = state?.text("video_id") == video.id && (state?.optLong("timeOffset") ?: 0L) > 1
        return !inProgress && video.id !in Library.watched(item, meta?.videos.orEmpty())
    }

    // ---------- glass and episode guide ----------

    private fun glassPane() = FrameLayout(this).apply {
        background = glassOver(this, 14, shade = PANE_SHADE)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
    }

    private fun buildGuide(videos: List<Video>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val x = dp(PAD)
        val top = dp(128)
        val width = resources.displayMetrics.widthPixels - x * 2
        val height = resources.displayMetrics.heightPixels - top - dp(27)
        guide = FrameLayout(this).apply { visibility = View.GONE }
        val smallLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        guide.addView(smallLogo, FrameLayout.LayoutParams(dp(150), dp(42)).apply {
            leftMargin = x; topMargin = dp(27)
        })
        val fallback = text(meta?.name ?: name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        }
        guide.addView(fallback, FrameLayout.LayoutParams(dp(250), -2).apply {
            leftMargin = x; topMargin = dp(28)
        })
        Images.load(meta?.logo ?: Images.logoFor(metaId), smallLogo, dp(150), urgent = true, failed = {
            smallLogo.visibility = View.GONE; fallback.visibility = View.VISIBLE
        })

        seasonStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Room around the chips for the focused one to grow into: flush with the strip, its left edge was cut flat.
        val room = dp(8)
        guide.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(room, room, room, room)
            clipToPadding = false
            addView(seasonStrip)
        }, FrameLayout.LayoutParams(width + 2 * room, dp(32) + 2 * room).apply { leftMargin = x - room; topMargin = dp(88) - room })

        val pane = glassPane()
        guideRows = column(Gravity.START).apply { focusByLight() }
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The row past the panel's foot fades out instead of running into the edge.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(guideRows)
        }, FrameLayout.LayoutParams(dp(380), height - dp(28)).apply {
            leftMargin = dp(14); topMargin = dp(14)
        })
        val previewX = dp(414)
        val previewWidth = width - previewX - dp(14)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(BASE, 8)
            clipToOutline = true
        }
        previewProgress = FrameLayout(this)
        previewTitle = text("", SERIF_NAME, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        previewFacts = text("", SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        previewAbout = text("", SMALL, color = TEXT).onArt().apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        }
        // What the remote can do on an episode, under its still: the preview is a key away, the sources a held one.
        val hints = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(keyHint("▸", "Preview"))
            addView(keyHint("Hold OK", "Sources"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        }
        hints.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val floor = height - dp(24) - hints.measuredHeight - dp(12) // where the words must stop, above the hints
        // The still is 16:9 of the column where the pane is tall enough; on a pane too short for that and the words
        // (a 540 dp screen), it keeps the column's width and gives up height until the name, the facts and two lines
        // of the story fit above the hints.
        val words = dp(6 + 3 + 14 + 4 + 8) + text("", SERIF_NAME).lineHeight + smallLine * 3
        val stillH = minOf(previewWidth * 9 / 16, floor - dp(14) - words).coerceAtLeast(dp(120)) // never a sliver at a huge font
        // The still, its bar, the name, the facts and the words, each under the one before: at a larger font the
        // words move down rather than over each other.
        val previewColumn = column(Gravity.START).apply {
            addView(previewImage, LinearLayout.LayoutParams(previewWidth, stillH))
            addView(previewProgress, LinearLayout.LayoutParams(previewWidth - dp(20), dp(3)).apply { topMargin = dp(6); marginStart = dp(10) })
            addView(previewTitle, LinearLayout.LayoutParams(previewWidth, -2).apply { topMargin = dp(14) })
            addView(previewFacts, LinearLayout.LayoutParams(previewWidth, -2).apply { topMargin = dp(4) })
            addView(previewAbout, LinearLayout.LayoutParams(previewWidth, -2).apply { topMargin = dp(8) })
        }
        pane.addView(previewColumn, FrameLayout.LayoutParams(previewWidth, -2).apply { leftMargin = previewX; topMargin = dp(14) })
        pane.addView(hints, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = previewX; bottomMargin = dp(24) })
        // The column stops above the hints: a long description gives up a line rather than run into them.
        previewColumn.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            if (bottom > floor && previewAbout.maxLines > 1) previewAbout.post { previewAbout.maxLines -= 1 }
        }
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x; topMargin = top
        })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private var pendingSeason = 1
    private val seasonSwitch = Runnable {
        selectedSeason = pendingSeason
        fillGuide()
        seasonButtons[selectedSeason]?.requestFocus()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasons = videos.map { it.season }.distinct()
        seasonStrip.removeAllViews()
        seasonButtons = seasons.associateWith { season ->
            // Every chip says "Season N", open or not: a chip that grew its word when opened moved the whole strip.
            chip(if (season == 0) "Specials" else "Season $season", season == selectedSeason).apply {
                // As Home's pages: landing on a season shows it, after the beat that lets the remote skate past.
                // Keeps chip()'s white face and growth under the remote; this listener replaces the one chip() set.
                setOnFocusChangeListener { v, has ->
                    chipFocus(has); v.grow(has, CHIP_GROW).start()
                    main.removeCallbacks(seasonSwitch)
                    if (has && season != selectedSeason) { pendingSeason = season; main.postDelayed(seasonSwitch, SEASON_WAIT) }
                }
                setOnClickListener {
                    main.removeCallbacks(seasonSwitch)
                    if (season == selectedSeason) episodeRows.firstOrNull()?.requestFocus()
                    else { pendingSeason = season; seasonSwitch.run() }
                }
                // Held: the whole season ticked as watched, or unticked.
                setOnLongClickListener { seasonMenu(season, videos); true }
                seasonStrip.addView(this, LinearLayout.LayoutParams(-2, -2).apply {
                    if (seasonStrip.childCount > 0) marginStart = dp(8)
                })
            }
        }
        // A very long season (a soap, an anime): one chip jumps to a stretch of it, with no scroll of hundreds of rows.
        val inSeason = videos.count { it.season == selectedSeason }
        if (inSeason > LONG_SEASON) seasonStrip.addView(chip("Go to…").apply {
            setOnClickListener {
                val starts = (0 until inSeason step EPISODE_JUMP).toList()
                choose("Go to episode", starts.map { "${it + 1} to ${minOf(it + EPISODE_JUMP, inSeason)}" }) { i -> episodeRows.getOrNull(starts[i])?.requestFocus() }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        guideRows.removeAllViews()
        val currentId = playTarget(meta)?.first
        val tickers = mutableMapOf<String, () -> Unit>()
        episodeRows = videos.filter { it.season == selectedSeason }.map { video ->
            val row = FrameLayout(this).apply { tag = video.id }
            val number = text("%02d".format(video.episode), BODY, color = TEXT_3)
            row.addView(number, FrameLayout.LayoutParams(dp(32), -2, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(12)
            })
            val unaired = airs(video.released)
            val title = text(video.title.ifBlank { "Episode ${video.episode}" }, BODY).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(title, FrameLayout.LayoutParams(dp(225), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(46) })
            val status = text("", SMALL).apply { gravity = Gravity.END }
            row.addView(status, FrameLayout.LayoutParams(dp(98), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(12) })
            // The row's words follow the ticks: ✓ and a quiet name once watched, time left when started, the length
            // otherwise; an episode not out yet says when, and sits back.
            tickers[video.id] = {
                val ticked = video.id in Library.watched(item, videos)
                // The episode Play plays: a warm play mark where its number was, its name in bold, and why.
                val current = video.id == currentId && !ticked
                number.text = if (current) "▶" else "%02d".format(video.episode)
                number.setTextColor(if (current) TUNGSTEN else TEXT_3)
                title.typeface = android.graphics.Typeface.create(title.typeface, if (current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
                val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }?.optLong("duration") ?: 0L
                title.setTextColor(if (ticked) TEXT_2 else TEXT)
                status.text = when {
                    ticked -> "✓"
                    unaired != null -> unaired
                    resumeAt > 0 && duration > resumeAt -> "${(duration - resumeAt) / 60_000} min left"
                    current && (video.season > 1 || video.episode > 1) -> "Up next"
                    else -> meta?.runtime.orEmpty()
                }
                status.setTextColor(if (ticked || current) TUNGSTEN else TEXT_2)
                status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
                row.alpha = if (unaired != null && !ticked) AIRS_ALPHA else 1f
            }
            tickers.getValue(video.id)()
            row.rowFocus(9) { focused -> if (focused) updatePreview(video) }
            row.setOnClickListener {
                if (unaired != null) return@setOnClickListener notAired(unaired)
                val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}")
            }
            row.setOnLongClickListener { episodeMenu(video, videos); true }
            guideRows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
            row
        }
        this.tickers = tickers
        videos.firstOrNull { it.id == currentId && it.season == selectedSeason }?.let(::updatePreview)
            ?: videos.firstOrNull { it.season == selectedSeason }?.let(::updatePreview)
    }

    private fun updatePreview(video: Video) {
        // The still hides behind a blur until the episode is watched, when Settings asks; the art behind, blurred too.
        Images.load(video.thumbnail, previewImage, previewImage.layoutParams?.width?.takeIf { it > 0 } ?: dp(436), orElse = meta?.background,
            look = if (blurStill(video)) Images.blurred else null)
        previewTitle.text = video.title.ifBlank { "Episode ${video.episode}" }
        previewFacts.text = listOfNotNull("S${video.season} E${video.episode}", meta?.runtime,
            airs(video.released) ?: episodeDate(video.released)).joinToString(DOT)
        previewAbout.text = video.overview.orEmpty()
        previewAbout.visibility = if (video.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
        val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration),
                FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun showGuide() {
        if (!::guide.isInitialized || guideOpen) return
        guideOpen = true
        hero.visibility = View.INVISIBLE
        guide.visibility = View.VISIBLE
        guide.alpha = 0f
        guide.translationY = dp(28).toFloat()
        guide.animate().alpha(1f).translationY(0f).setDuration(260).start()
        // The film steps back behind the guide, so the chips and panel read over a bright backdrop.
        heroArt.animate().alpha(GUIDE_ART).setDuration(260).withEndAction { refrost() }.start()
        leftShade(false) // the episode list sits on glass: the art can run to the left edge as it does on the right
        val currentId = playTarget(meta)?.first
        (episodeRows.firstOrNull { it.tag == currentId } ?: episodeRows.firstOrNull())?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        heroArt.animate().alpha(1f).setDuration(220).withEndAction { refrost() }.start()
        leftShade(true)
        guide.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(220).withEndAction {
            guide.visibility = View.GONE
            hero.visibility = View.VISIBLE
            if (::playButton.isInitialized) playButton.requestFocus()
        }.start()
    }

    /**
     * The whole story, in the title page's place and layout: name, facts, then the description, which scrolls with the
     * remote when it's long. Back returns to the title; there's nothing else to press.
     */
    private fun showInfo() {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        hero.visibility = View.INVISIBLE
        infoOverlay = FrameLayout(this)
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xC9000808.toInt(), 0x99000808.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 3 / 5, -1))
        val facts = listOfNotNull(certificate?.takeIf { Settings.titlePage(this, SettingsActivity.TITLE_RATING) }, m.year, m.runtime,
            m.genres.take(2).joinToString(" / ").ifEmpty { null }, m.rating?.let { "★ $it" },
            tomatoes?.critics?.let { "Tomatometer $it%" + if (tomatoes?.criticsCertified == true) " (Certified Fresh)" else "" },
            tomatoes?.audience?.let { "Popcornmeter $it%" }).joinToString(DOT)
        val story = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = true // takes the remote, so Up and Down scroll a long story
            addView(column(Gravity.START).apply {
                m.description?.takeIf { it.isNotBlank() }?.let {
                    addView(text(it, BODY, color = TEXT).onArt())
                }
                tomatoes?.consensus?.let {
                    addView(caps(tomatoes?.season?.let { s -> "Critics consensus · Season $s" } ?: "Critics consensus", TEXT_3).onArt(), wrap().apply { topMargin = dp(18) })
                    addView(text(it, BODY).onArt(), wrap().apply { topMargin = dp(6) })
                }
                // Every name selects, as on the page itself: three to a line.
                fun names(label: String, people: List<String>) {
                    addView(caps(label, TEXT_3).onArt(), wrap().apply { topMargin = dp(18) })
                    people.chunked(3).forEachIndexed { i, line ->
                        addView(LinearLayout(this@DetailsActivity).apply {
                            clipChildren = false
                            line.forEach { addView(person(it).apply { maxWidth = dp(180) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }) }
                        }, wrap().apply { topMargin = dp(if (i == 0) 4 else 2); marginStart = -dp(8) })
                    }
                }
                if (m.cast.isNotEmpty()) names("Cast", m.cast.take(12))
                if (m.director.isNotEmpty()) names(if (m.director.size > 1) "Directors" else "Director", m.director.take(3))
                if (m.writer.isNotEmpty()) names(if (m.writer.size > 1) "Writers" else "Writer", m.writer.take(6))
            })
        }
        infoOverlay.addView(column(Gravity.START,
            text(m.name, SERIF_TITLE, bold = true, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            text(facts, SMALL, color = TEXT).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(6),
            caps("The story", TEXT_3).onArt().marginTop(SECTION_GAP),
        ).apply {
            addView(story, LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(8) })
        }, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(96)
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
        // It rises in as the guide and More like this do; the page's three overlays move alike.
        infoOverlay.alpha = 0f
        infoOverlay.translationY = dp(20).toFloat()
        infoOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        story.requestFocus()
    }

    private var previewFrom: View? = null // the episode row a preview was opened from

    override fun onTrailerClosed() {
        val from = previewFrom
        previewFrom = null
        if (guideOpen && from != null && from.isAttachedToWindow) from.requestFocus()
        else if (::playButton.isInitialized && !guideOpen && !infoOpen && !similarOpen) playButton.requestFocus()
    }

    private fun hideInfo() {
        infoOpen = false
        val overlay = infoOverlay // the one going out: a new one may be built before it has gone
        overlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
            root.removeView(overlay)
            if (infoOpen) return@withEndAction // opened again before this one had gone: the new one holds the page
            hero.visibility = View.VISIBLE
            infoButton.requestFocus()
        }.start()
    }

    private fun loadSimilar() {
        background({
            Stremio.addons(key).firstNotNullOfOrNull { addon ->
                runCatching { addon.similar(type, metaId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
        }) { result ->
            result.getOrNull()?.let { titles ->
                titles.asSequence().filter { it.id != metaId && it.type == type }
                    .distinctBy { it.id }.take(12).toList().takeIf { it.isNotEmpty() }
                    ?.let { showSimilar("More like this", it) }
            }
        }
    }

    /** Movies reveal one Home-sized poster row without moving the title off the screen. */
    private fun showSimilar(heading: String, titles: List<Meta>) {
        if (::similarOverlay.isInitialized) root.removeView(similarOverlay)
        similarOverlay = FrameLayout(this).apply { visibility = View.GONE }
        similarOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF708090B.toInt(), 0xA608090B.toInt(), 0x0008090B))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        content.addView(caps(heading).onArt(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        similarFirst = null
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                background = rounded(BASE, 6)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                clipToOutline = true
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                val fallback = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, dp(8), dp(8)); visibility = View.GONE
                }
                addView(fallback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                cardFocus(6)
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = {
                    fallback.visibility = View.VISIBLE
                })
            }
            if (similarFirst == null) similarFirst = card
            row.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, dp(POSTER_H + 8)))
        similarOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        root.addView(similarOverlay, FrameLayout.LayoutParams(-1, -1))
        if (hero.childCount > 0) addMoreHint() // the hint under the block; the block itself, and the remote on it, stay
    }

    private fun showSimilar() {
        if (!::similarOverlay.isInitialized || similarOpen) return
        similarOpen = true
        hero.visibility = View.INVISIBLE
        similarOverlay.visibility = View.VISIBLE
        similarOverlay.alpha = 0f
        similarOverlay.translationY = dp(20).toFloat()
        similarOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        similarFirst?.requestFocus()
    }

    private fun hideSimilar() {
        similarOpen = false
        similarOverlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
            similarOverlay.visibility = View.GONE
            hero.visibility = View.VISIBLE
            playButton.requestFocus()
        }.start()
    }

    // ---------- shared ----------

    private fun openSources(videoId: String, startOver: Boolean, next: String? = null, label: String? = null, pick: Boolean = false) {
        val m = meta
        val nextId = next ?: m?.videos?.let { vs -> vs.getOrNull(vs.indexOfFirst { it.id == videoId } + 1)?.id }
        val video = m?.videos?.firstOrNull { it.id == videoId }
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", nextId).putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background).putExtra("tint", tint) // the player's shades in this title's colour
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            .putExtra("origin", origin) // the title's language, for subtitles that follow the sound
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") }))
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        // What the player saved is known here already (Stremio.savingItem): the block takes it now, before the screen
        // shows. Stremio's answer, usually the same, then redraws nothing, and the controls stay where the remote left them.
        Stremio.savingItem(key, metaId)?.takeIf { s -> item?.let { s.optString("_mtime") > it.optString("_mtime") } ?: true }?.let { item = it }
        refreshWatch()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull() }) { result ->
            item = result.getOrNull() ?: item
            refreshWatch()
        }
    }

    /** The watch block and the guide drawn again only when the item changed what they show (a resume point, My List, a tick). */
    private fun refreshWatch() {
        if (infoOpen) { root.removeView(infoOverlay); infoOpen = false }
        hero.visibility = View.VISIBLE
        val m = meta ?: return
        if (watchKey(m) == watchState) return
        val wasGuide = guideOpen
        showHero(m)
        m.videos.takeIf { type == "series" && it.isNotEmpty() }?.let(::buildGuide)
        if (wasGuide) showGuide()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastKeyAt = event.eventTime
            if (guideOpen) {
                val focused = currentFocus
                if (focused in seasonButtons.values) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { episodeRows.firstOrNull()?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
                val at = episodeRows.indexOf(focused)
                if (at >= 0) when (event.keyCode) {
                    // Right, free in a list, previews the episode under the remote; the box hands the remote back here.
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val m = meta; val id = focused?.tag as? String
                        val out = m?.videos?.firstOrNull { it.id == id }?.let { airs(it.released) == null } ?: true // nothing to preview before it airs
                        if (m != null && id != null && out && Settings.titlePage(this, SettingsActivity.TITLE_PREVIEW)) { previewFrom = focused; preview(id, m) }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (at == 0) { seasonButtons[selectedSeason]?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at == episodeRows.lastIndex) return true
                }
            } else if (similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideSimilar()
                return true
            } else if (!infoOpen && !similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                currentFocus?.let { isInside(it, hero) } == true && !nextBelowInSide() &&
                (if (type == "series") ::guide.isInitialized else ::similarOverlay.isInitialized)) {
                if (type == "series") showGuide() else showSimilar()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            infoOpen -> hideInfo()
            guideOpen -> hideGuide()
            similarOpen -> hideSimilar()
            else -> super.onBackPressed()
        }
    }

    private companion object {
        const val LONG_SEASON = 40 // episodes in a season before Go to… shows
        const val EPISODE_JUMP = 25 // episodes in each stretch it offers
        const val PANE_SHADE = 0.55f // the episode panel's frost, darkened to the mock's smoked glass
        const val GUIDE_ART = 0.6f // the film's brightness behind the episode guide
        const val STARTED = 60_000L // ms watched before a title counts as started
        const val RATING_ALPHA = 0.9f // the age rating capsule: quiet in its outline, not in its letters
        const val RATING_SP = 12f // its letters: between the badges and the label step, as a cinema listing prints it
        const val AIRS_ALPHA = 0.65f // an episode not out yet sits back in the list
        const val ORB = 36 // dp, the small frosted circles under the disc
        const val SEASON_WAIT = 300L // ms on a season chip before its episodes show (Home's pages switch the same way)
        const val STILL_WAIT = 400L // ms since the remote's last press before the hero may be rebuilt under it
        const val MORE_HINT = "more-hint" // the tag of the "▾ Episodes" / "▾ More like this" line
        // The column down the left of the art, as Netflix's title page: the name from BAND_TOP, everything under it.
        const val BAND_TOP = 64 // dp from the top: the name top left, under the clock's line
        const val BAND_BOTTOM = 60 // dp kept at the foot for the "▾ More like this" line
        const val COLUMN_W = 480 // dp: the column down the left, clear of the blocks down the right
        const val ABOUT_LINES = 4 // the plot's most lines, at COLUMN_W's measure: a synopsis whole for most titles
        const val CRITICS_LINES = 4 // the critics' consensus, in its block
        const val CAST_NAMES = 4 // names in the cast block; the rest are on the ⓘ page
        const val SAID = "said" // the tag of the consensus line, for the block's fitting
    }
}
