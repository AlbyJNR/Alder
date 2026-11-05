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

internal fun episodeDate(value: String?): String? {
    val day = value?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

/**
 * "Airs 7 Nov" for an episode released after [now] (both ISO text in UTC, as Stremio and the add-ons write them), with
 * the year when it isn't this one; null once it has aired, or without a date. Text compares as time in this format.
 */
internal fun airs(released: String?, now: String = Library.now(), word: String = "Airs"): String? {
    val day = released?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    if (released <= now) return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    val pattern = if (day.take(4) == now.take(4)) "d MMM" else "d MMM yyyy"
    return "$word " + SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

// A title stays in place over its art; episodes and related titles open over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }
    private val listedTrailer by lazy { intent.getStringExtra("trailer") } // IMDb's video id from the IMDb page: Trailer plays this one
    private val listedRelease by lazy { intent.getStringExtra("released") } // IMDb's release date, when the add-on has none

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
    private var castNames = emptyList<View>() // the leads on the title page; Down from one goes to Play, not the episodes
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
    private var tickers = emptyMap<String, () -> Unit>() // each episode row's words, redrawn when a tick changes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false }
        root = FrameLayout(this).apply {
            clipChildren = false
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
            showHero(m)
            m.imdbId?.let { id -> background({ imdbTrailerStreams(id) }) {} } // ready before the button is pressed
            // The age rating, from IMDb when the add-on has none; the facts line is redrawn once when it comes.
            if (certificate == null && !certificateRequested && m.imdbId != null && Settings.titlePage(this, SettingsActivity.TITLE_RATING)) {
                certificateRequested = true
                background({ imdbCertificate(m.imdbId) }) { r ->
                    val found = r.getOrNull() ?: return@background
                    certificate = found
                    if (!guideOpen && !infoOpen && !similarOpen) showHero(meta)
                }
            }
            // Rotten Tomatoes' two meters and the critics' consensus, by the title's name and year; drawn in once they come.
            if (!tomatoesRequested && m.name.isNotBlank() && Settings.titlePage(this, SettingsActivity.TITLE_TOMATOES)) {
                tomatoesRequested = true
                val year = m.year?.take(4)?.toIntOrNull()
                background({ rtScores(m.name, year, type == "series") }) { r ->
                    tomatoes = r.getOrNull() ?: return@background
                    if (!guideOpen && !infoOpen && !similarOpen) showHero(meta)
                }
            }
            if (type == "series" && m.videos.isNotEmpty()) {
                buildGuide(m.videos)
                if (wasGuide) { showGuide(); episodeRows.firstOrNull { it.tag == episodeTag }?.requestFocus() }
            }
            if (type == "movie" && !similarRequested) { similarRequested = true; loadSimilar() }
        }
        background({
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId }
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
                if (meta != null) showHero(meta)
            }
        }
    }

    // ---------- title and watch window ----------

    private var keepFocus: String? = null // the hero control the remote was on before a rebuild, by its words
    private var actionRow1: View? = null // Play and the small controls
    private var actionRow2: View? = null // Trailer, Preview, Recap: Down from Play lands here before the episodes

    private fun showHero(m: Meta?) {
        keepFocus = currentFocus?.takeIf { isInside(it, hero) }?.let { it.contentDescription?.toString() ?: (it as? TextView)?.text?.toString() }
        hero.removeAllViews()
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
                addView(logo, FrameLayout.LayoutParams(dp(260), -1))
                Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(260), urgent = true, failed = {
                    title.visibility = View.VISIBLE; logo.visibility = View.GONE
                }) {
                    title.visibility = View.GONE; logo.visibility = View.VISIBLE
                }
            }
        }
        val stack = column(Gravity.START).apply { addView(titleArea, LinearLayout.LayoutParams(-1, dp(75))) }
        if (m == null) {
            val error = failed("Couldn't load this title's details.") { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(2).requestFocus() }
        } else {
            val length = if (type == "series") m.videos.map { it.season }.filter { it > 0 }.distinct().size
                .takeIf { it > 0 }?.let(::seasons) else m.runtime
            val facts = listOfNotNull(m.year, m.genres.firstOrNull(), length).joinToString(DOT)
            val factRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            // The age rating before the year: a thin outlined capsule, quiet, the way a cinema listing prints it.
            val cert = certificate?.takeIf { Settings.titlePage(this, SettingsActivity.TITLE_RATING) }
            if (cert != null) factRow.addView(text(cert, 10.5f, bold = true, color = TEXT_2).apply {
                letterSpacing = 0.04f
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = GradientDrawable().apply { setStroke(dp(1), TEXT_2); cornerRadius = dp(4).toFloat() }
                alpha = RATING_ALPHA
            }, wrap().apply { marginEnd = dp(8) })
            factRow.addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            m.rating?.let { rating ->
                factRow.addView(text("·", SMALL, color = TEXT_2), wrap().apply { marginStart = dp(7); marginEnd = dp(7) })
                factRow.addView(text("IMDb", 9f, bold = true, color = BASE).apply {
                    setPadding(dp(4), dp(1), dp(4), dp(1)); background = rounded(TUNGSTEN, 3)
                })
                factRow.addView(text(rating, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(6) })
            }
            // Rotten Tomatoes after IMDb, the same way: its badge, then C for critics and A for audience, as percentages.
            tomatoes?.takeIf { it.critics != null || it.audience != null }?.let { rt ->
                factRow.addView(text("·", SMALL, color = TEXT_2), wrap().apply { marginStart = dp(7); marginEnd = dp(7) })
                factRow.addView(text("RT", 9f, bold = true, color = BASE).apply {
                    setPadding(dp(4), dp(1), dp(4), dp(1)); background = rounded(TUNGSTEN, 3)
                })
                val meters = listOfNotNull(rt.critics?.let { "C$it%" }, rt.audience?.let { "A$it%" }).joinToString(" ")
                factRow.addView(text(meters, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(6) })
            }
            stack.addView(factRow, wrap().apply { topMargin = dp(12) })
            m.description?.takeIf { it.isNotBlank() }?.let {
                stack.addView(text(it, BODY, color = TEXT).onArt().apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(8) })
            }
            // The critics' consensus, one line in their words under the plot: what the meters mean, in a sentence.
            tomatoes?.takeIf { it.consensus != null }?.let { rt ->
                val line = rt.consensus!!
                stack.addView(LinearLayout(this).apply {
                    gravity = Gravity.TOP
                    addView(caps(rt.season?.let { "Critics · S$it" } ?: "Critics", TEXT_3).onArt(), wrap().apply { marginEnd = dp(8); topMargin = dp(3) })
                    addView(text(line, SMALL, color = TEXT_2).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(8) })
            }
            // The leads, one quiet line, each a name the remote can select to find more of their work; the full
            // list stays behind the information button. Then the director, for films.
            castNames = emptyList()
            // A name the remote can select, for the leads and the director alike: their other work, as a search.
            fun person(person: String) = text(person, SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(150)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                contentDescription = "Films and shows with $person"
                rowFocus(6, rest = { null }) { has -> setTextColor(if (has) TEXT else TEXT_2) }
                if (Profiles.active(this@DetailsActivity)?.kids == true) isFocusable = false // a Kids profile stays on its own titles
                else setOnClickListener { startActivity(Intent(this@DetailsActivity, SearchActivity::class.java).putExtra("query", person)) }
            }
            if (m.cast.isNotEmpty() && Settings.titlePage(this, SettingsActivity.TITLE_CAST)) {
                val names = m.cast.take(3).map(::person)
                castNames = names
                stack.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    addView(caps("Cast", TEXT_3).onArt(), wrap().apply { marginEnd = dp(4) })
                    names.forEachIndexed { i, name ->
                        if (i > 0) addView(text("·", SMALL, color = TEXT_3).onArt())
                        addView(name, wrap())
                    }
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(8); marginStart = -dp(6) })
            }
            if (m.director.isNotEmpty() && type == "movie" && Settings.titlePage(this, SettingsActivity.TITLE_DIRECTOR)) {
                val directors = m.director.take(2).map(::person)
                castNames = castNames + directors // Down from a director's name goes to Play too
                stack.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    addView(caps(if (m.director.size > 1) "Directors" else "Director", TEXT_3).onArt(), wrap().apply { marginEnd = dp(4) })
                    directors.forEachIndexed { i, name ->
                        if (i > 0) addView(text("·", SMALL, color = TEXT_3).onArt())
                        addView(name, wrap())
                    }
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(4); marginStart = -dp(6) })
            }
            addWatch(m, stack)
        }
        hero.addView(stack, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(118)
        })
        val hasEpisodes = type == "series" && m?.videos?.isNotEmpty() == true
        if (hasEpisodes || ::similarOverlay.isInitialized) {
            hero.addView(caps(if (hasEpisodes) "▾  Episodes" else "▾  More like this", TEXT_2).onArt().apply {
                isFocusable = true
                setOnClickListener { if (hasEpisodes) showGuide() else showSimilar() }
                rowFocus(rest = { null })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(28)
            })
        }
    }

    /** Where you are, then the main action and three small glass controls. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
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
        if (where.isNotEmpty() && (resumeAt > 0 || unaired != null || comingOut != null)) stack.addView(text(where, SMALL, bold = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.marginTop(16))
        if (remaining != null) stack.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })

        // The white disc: already the brightest thing on the page at rest, so the eye lands on it before the remote does.
        playButton = disc(DISC, R.drawable.ic_play, if (resumeAt > 0) "Resume" else "Play").apply {
            setOnClickListener { if (unaired != null) notAired(unaired) else openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { if (unaired != null) notAired(unaired) else openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        val startOver = if (resumeAt > 0) iconButton("↺", "Start over") {
            openSources(target?.first ?: metaId, startOver = true)
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
        val preview = if (Settings.titlePage(this, SettingsActivity.TITLE_PREVIEW))
            glassButton("Preview") { if (unaired != null) notAired(unaired) else preview(target?.first ?: metaId, m) } else null
        val recap = if (type == "series" && Settings.titlePage(this, SettingsActivity.TITLE_RECAP) && Profiles.active(this)?.kids != true)
            glassButton("Recap") { openRecap(m, episode?.season ?: selectedSeason) } else null
        // Play and its small controls on one line; Trailer, Preview and Recap on a second, so none is ever squeezed
        // out of a line the text block's width can't hold (a series with a resume point had seven controls).
        // The worded actions on one line at one height (Play white, the others glass), the three small round
        // controls on a quieter line beneath: two kinds of thing, two lines, nothing bunched.
        val lead = if (comingOut != null && trailer != null) trailer else playButton
        actionRow1 = LinearLayout(this).apply {
            clipChildren = false
            gravity = Gravity.CENTER_VERTICAL // the worded glass controls sit on the disc's centre line
            if (lead === trailer) addView(trailer, LinearLayout.LayoutParams(-2, dp(BUTTON_H)).apply { marginEnd = dp(14) })
            addView(playButton, LinearLayout.LayoutParams(dp(DISC), dp(DISC)).apply { marginEnd = dp(14) })
            listOfNotNull(trailer.takeIf { lead !== trailer }, preview, recap).forEach { addView(it, LinearLayout.LayoutParams(-2, dp(BUTTON_H)).apply { marginEnd = dp(8) }) }
        }
        stack.addView(actionRow1, wrap().apply { topMargin = dp(16) })
        actionRow2 = LinearLayout(this).apply {
            clipChildren = false
            startOver?.let { addView(it, LinearLayout.LayoutParams(dp(ORB), dp(ORB)).apply { marginEnd = dp(10) }) }
            addView(listButton, LinearLayout.LayoutParams(dp(ORB), dp(ORB)).apply { marginEnd = dp(10) })
            addView(infoButton)
        }
        stack.addView(actionRow2, wrap().apply { topMargin = dp(12) })
        // The title's details arrive in two or three steps, each redrawing this block: the remote stays on the
        // control it was on (Trailer, a cast name, ⓘ) rather than snapping back to Play each time.
        playButton.post {
            if (guideOpen || infoOpen || similarOpen) return@post
            (keepFocus?.let { key -> keyed(hero, key) } ?: lead).requestFocus()
        }
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

    /**
     * A season's recap: Stremio's data has none, so this is a search in the TV's YouTube app ("<show> season N recap"),
     * where the show's own channel usually has one. YouTube's adverts there are YouTube's; CAS can't remove them.
     */
    private fun openRecap(m: Meta, season: Int) {
        val query = Uri.encode("${m.name} season ${season.coerceAtLeast(1)} recap")
        if (!openOutside(Uri.parse("https://www.youtube.com/results?search_query=$query")))
            choose("Recap unavailable", listOf("OK"), "This TV has no video app to search for a recap.") {}
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
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
    private fun notAired(when_: String) = choose(when_, listOf("OK"), "This episode has not aired yet.") {}

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
        choose(if (season == 0) "Specials" else "Season $season", listOf(if (all) "Mark $name unwatched" else "Mark $name watched", "Keep")) { i ->
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
        val largeText = resources.configuration.fontScale > 1.15f
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
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(226)).apply {
            leftMargin = previewX; topMargin = dp(14)
        })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth - dp(20), dp(3)).apply {
            leftMargin = previewX + dp(10); topMargin = dp(229)
        })
        previewTitle = text("", 22f, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(251)
        })
        previewFacts = text("", SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewFacts, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 285 else 280)
        })
        previewAbout = text("", SMALL, color = TEXT).onArt().apply {
            maxLines = if (largeText) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 313 else 304)
        })
        // What the remote can do on an episode, under its still: the preview is a key away, the sources a held one.
        pane.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(keyHint("▸", "Preview"))
            addView(keyHint("Hold OK", "Sources"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = previewX; bottomMargin = dp(16) })
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
            chip(if (season == selectedSeason) {
                if (season == 0) "Specials" else "Season $season"
            } else if (season == 0) "Specials" else season.toString(), season == selectedSeason).apply {
                // As Home's pages: landing on a season shows it, after the beat that lets the remote skate past.
                setOnFocusChangeListener { _, has ->
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
                seasonStrip.addView(this, LinearLayout.LayoutParams(-2, dp(24)).apply {
                    if (seasonStrip.childCount > 0) marginStart = dp(8)
                })
            }
        }
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
                val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
                val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }?.optLong("duration") ?: 0L
                title.setTextColor(if (ticked) TEXT_2 else TEXT)
                status.text = when {
                    ticked -> "✓"
                    unaired != null -> unaired
                    resumeAt > 0 && duration > resumeAt -> "${(duration - resumeAt) / 60_000} min left"
                    else -> meta?.runtime.orEmpty()
                }
                status.setTextColor(if (ticked) TUNGSTEN else TEXT_2)
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
            m.genres.take(2).joinToString(" / ").ifEmpty { null }, m.rating?.let { "IMDb $it" },
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
                    addView(caps(tomatoes?.season?.let { s -> "Critics consensus · Season $s" } ?: "Critics consensus", TEXT_2).onArt(), wrap().apply { topMargin = dp(18) })
                    addView(text(it, BODY).onArt(), wrap().apply { topMargin = dp(6) })
                }
                if (m.cast.isNotEmpty()) {
                    addView(caps("Cast", TEXT_2).onArt(), wrap().apply { topMargin = dp(18) })
                    addView(text(m.cast.take(12).joinToString("  ·  "), BODY).onArt(),
                        wrap().apply { topMargin = dp(6) })
                }
                if (m.director.isNotEmpty()) {
                    addView(caps(if (m.director.size > 1) "Directors" else "Director", TEXT_2).onArt(), wrap().apply { topMargin = dp(18) })
                    addView(text(m.director.take(3).joinToString("  ·  "), BODY).onArt(), wrap().apply { topMargin = dp(6) })
                }
            })
        }
        infoOverlay.addView(column(Gravity.START,
            text(m.name, SERIF_TITLE, bold = true, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            text(facts, SMALL, color = TEXT).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(6),
            text("The story", SMALL, bold = true, color = TEXT).onArt().marginTop(SECTION_GAP),
        ).apply {
            addView(story, LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(8) })
        }, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(96)
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
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
        root.removeView(infoOverlay)
        hero.visibility = View.VISIBLE
        infoButton.requestFocus()
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
        showHero(meta)
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
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") }))
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull() }) { result ->
            item = result.getOrNull() ?: item
            val wasGuide = guideOpen
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false }
            hero.visibility = View.VISIBLE
            showHero(meta)
            meta?.videos?.takeIf { type == "series" && it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && currentFocus in castNames) {
                if (::playButton.isInitialized) playButton.requestFocus()
                return true
            } else if (!infoOpen && !similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                currentFocus?.let { isInside(it, hero) } == true &&
                !(actionRow2 != null && currentFocus?.let { f -> actionRow1?.let { isInside(f, it) } } == true) &&
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
        const val PANE_SHADE = 0.55f // the episode panel's frost, darkened to the mock's smoked glass
        const val GUIDE_ART = 0.6f // the film's brightness behind the episode guide
        const val STARTED = 60_000L // ms watched before a title counts as started
        const val RATING_ALPHA = 0.65f // the age rating capsule: present, not loud
        const val AIRS_ALPHA = 0.65f // an episode not out yet sits back in the list
        const val ORB = 36 // dp, the small frosted circles under the disc
        const val SEASON_WAIT = 300L // ms on a season chip before its episodes show (Home's pages switch the same way)
    }
}
